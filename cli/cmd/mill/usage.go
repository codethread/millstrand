package main

import (
	"errors"
	"io"

	"github.com/spf13/cobra"
	"skein-strand-cli/internal/errfmt"
)

// usageError marks a failure Cobra raised about the invocation itself — a flag
// it could not parse, or an argument count the command rejects — as opposed to
// a command that ran and failed. Cobra classes neither: flag failures arrive
// through FlagErrorFunc and Args validators return bare fmt.Errorf values, so
// mill stamps both here and reads the stamp back with errors.As.
type usageError struct {
	cmd *cobra.Command
	err error
}

func (e *usageError) Error() string { return e.err.Error() }

func (e *usageError) Unwrap() error { return e.err }

// stampUsageErrors marks every usage-shaped failure the tree can raise. Call it
// once the tree is complete: a command added afterwards keeps Cobra's unstamped
// error and simply gets no usage block. Children inherit the flag hook from the
// root, so only the argument validators need walking.
func stampUsageErrors(root *cobra.Command) {
	root.SetFlagErrorFunc(func(cmd *cobra.Command, err error) error {
		return &usageError{cmd: cmd, err: err}
	})
	stampArgsValidator(root)
}

func stampArgsValidator(command *cobra.Command) {
	for _, child := range command.Commands() {
		stampArgsValidator(child)
	}
	// A nil Args is Cobra's ArbitraryArgs, which never fails; there is nothing
	// to stamp.
	if command.Args == nil {
		return
	}
	validate := command.Args
	command.Args = func(cmd *cobra.Command, args []string) error {
		if err := validate(cmd, args); err != nil {
			return &usageError{cmd: cmd, err: err}
		}
		return nil
	}
}

// writeMillCommandFailure is the whole of what a failed mill invocation prints:
// one rendering, then the guidance the silenced root would otherwise have left
// to Cobra. An unknown command earns a pointer at --help, a malformed
// invocation earns the usage block, and a command that ran and failed earns
// neither — a flag list explains nothing about an unreachable weaver.
func writeMillCommandFailure(err error, cmd *cobra.Command) {
	writeMillCommandError(err, millCommandPath(cmd))
	// A bin failure is a machine contract (SPEC-002.C54/C55): the JSON envelope
	// is the entire output, with no human guidance after it.
	var binErr *binError
	if errors.As(err, &binErr) {
		return
	}
	var usageErr *usageError
	if errors.As(err, &usageErr) {
		// Cobra's own block, unindented in either mode: it is a usage rendering
		// rather than an error one.
		_, _ = io.WriteString(millErrorOut, usageErr.cmd.UsageString())
		return
	}
	// Cobra rejects an unknown command in Find, before the command it did find
	// could run — so an error carrying a command that cannot run is exactly the
	// case where the user typed a name that does not exist.
	if cmd != nil && !cmd.Runnable() {
		errfmt.RenderRemedy(millErrorOut, cmd.CommandPath()+" --help", errfmt.ModeFor(millErrorOut))
	}
}
