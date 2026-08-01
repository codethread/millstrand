package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/spf13/cobra"
)

// runMillFailure drives a real invocation to the same place main takes it: the
// command tree runs, and whatever it returns goes through the single failure
// writer. Cobra's own streams go to buffers so nothing it prints can be
// mistaken for what mill wrote.
func runMillFailure(t *testing.T, args ...string) string {
	t.Helper()
	original := millErrorOut
	t.Cleanup(func() { millErrorOut = original })
	var stderr bytes.Buffer
	millErrorOut = &stderr

	root := newMillCommand()
	root.SetArgs(args)
	root.SetOut(&bytes.Buffer{})
	root.SetErr(&bytes.Buffer{})
	cmd, err := root.ExecuteC()
	if err == nil {
		t.Fatalf("mill %s unexpectedly succeeded", strings.Join(args, " "))
	}
	writeMillCommandFailure(err, cmd)
	return stderr.String()
}

// renderings counts the error lines in out. Every assertion here checks it:
// the whole point of the root's silencing is that a failure is written once.
func renderings(out string) int {
	count := 0
	for _, line := range strings.Split(out, "\n") {
		if strings.HasPrefix(line, "error:") {
			count++
		}
	}
	return count
}

func TestMillUnknownCommandRendersOnceAndPointsAtHelp(t *testing.T) {
	got := runMillFailure(t, "nosuchcmd")
	want := "error: unknown command \"nosuchcmd\" for \"mill\"\ntry: mill --help\n"
	if got != want {
		t.Fatalf("unknown command output = %q, want %q", got, want)
	}
}

func TestMillUnknownCommandPointerFollowsTheChosenRendering(t *testing.T) {
	t.Setenv("SKEIN_ERROR_FORMAT", "pretty")
	t.Setenv("NO_COLOR", "1")
	got := runMillFailure(t, "nosuchcmd")
	want := "  x unknown command \"nosuchcmd\" for \"mill\"\n\n    try: mill --help\n"
	if got != want {
		t.Fatalf("pretty unknown command output = %q, want %q", got, want)
	}
}

func TestMillFlagErrorRendersOnceWithTheFailingCommandsUsage(t *testing.T) {
	got := runMillFailure(t, "weaver", "repl", "--nope")
	if renderings(got) != 1 {
		t.Fatalf("want exactly one rendering, got %q", got)
	}
	if !strings.HasPrefix(got, "error: unknown flag: --nope\n") {
		t.Fatalf("flag failure output = %q", got)
	}
	if !strings.Contains(got, "Usage:\n  mill weaver repl") {
		t.Fatalf("want the failing subcommand's usage, got %q", got)
	}
	if strings.Contains(got, "try:") {
		t.Fatalf("a known command needs no help pointer: %q", got)
	}
}

func TestMillArityErrorRendersOnceWithUsage(t *testing.T) {
	got := runMillFailure(t, "weaver", "status", "extra")
	if renderings(got) != 1 {
		t.Fatalf("want exactly one rendering, got %q", got)
	}
	if !strings.HasPrefix(got, "error: unknown command \"extra\" for \"mill weaver status\"\n") {
		t.Fatalf("arity failure output = %q", got)
	}
	if !strings.Contains(got, "Usage:\n  mill weaver status") {
		t.Fatalf("want the failing subcommand's usage, got %q", got)
	}
}

// A command that ran and failed gets the error and nothing else: the flag list
// says nothing about a weaver that is not there.
func TestMillRuntimeFailureRendersOnceWithNoGuidance(t *testing.T) {
	original := millErrorOut
	t.Cleanup(func() { millErrorOut = original })
	var stderr bytes.Buffer
	millErrorOut = &stderr

	root := newMillCommand()
	repl, _, err := root.Find([]string{"weaver", "repl"})
	if err != nil {
		t.Fatalf("find weaver repl: %v", err)
	}
	writeMillCommandFailure(errors.New("no running mill; start one with: mill start"), repl)

	if got := stderr.String(); got != "error: no running mill; start one with: mill start\n" {
		t.Fatalf("runtime failure output = %q", got)
	}
}

// The structured bin envelope is a machine contract (SPEC-002.C54/C55), so a
// bin failure raised by argument validation still emits the envelope alone.
func TestMillBinUsageFailureStaysAStructuredEnvelope(t *testing.T) {
	got := runMillFailure(t, "bin", "list", "extra")
	var envelope map[string]any
	if err := json.Unmarshal([]byte(got), &envelope); err != nil {
		t.Fatalf("bin failure is not a lone JSON envelope: %v (%q)", err, got)
	}
	if envelope["operation"] != "bin list" {
		t.Fatalf("envelope = %#v", envelope)
	}
	if strings.Contains(got, "Usage:") || strings.Contains(got, "try:") {
		t.Fatalf("machine envelope must carry no human guidance: %q", got)
	}
}

// The stamp has to survive whatever a validator already returns, or the bin
// envelope above would be unreachable through errors.As.
func TestUsageErrorUnwrapsToTheValidatorsOwnError(t *testing.T) {
	cause := newBinError("bin list", "", "bin/unknown", "no such bin", nil)
	stamped := error(&usageError{cmd: &cobra.Command{Use: "list"}, err: cause})
	var binErr *binError
	if !errors.As(stamped, &binErr) || binErr != cause {
		t.Fatalf("stamped error lost its cause: %v", stamped)
	}
	if stamped.Error() != cause.Error() {
		t.Fatalf("stamped error text = %q, want %q", stamped.Error(), cause.Error())
	}
}
