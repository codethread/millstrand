package main

import (
	"bytes"
	"errors"
	"os"
	"strings"
	"testing"
)

// Every test here writes to a buffer, which resolves to plain mode on its own.
// A SKEIN_ERROR_FORMAT inherited from the developer's shell would override that
// for this process and every binary it spawns, so it is cleared once up front.
func TestMain(m *testing.M) {
	_ = os.Unsetenv("SKEIN_ERROR_FORMAT")
	os.Exit(m.Run())
}

func captureMillError(t *testing.T, err error, command []string) string {
	t.Helper()
	original := millErrorOut
	t.Cleanup(func() { millErrorOut = original })
	var stderr bytes.Buffer
	millErrorOut = &stderr
	writeMillCommandError(err, command)
	return stderr.String()
}

func TestMillCommandErrorFallthroughIsTodaysPlainLine(t *testing.T) {
	got := captureMillError(t, errors.New("mill socket unreachable; start one with: mill start"), []string{"weaver", "repl"})
	if got != "error: mill socket unreachable; start one with: mill start\n" {
		t.Fatalf("plain fallthrough = %q", got)
	}
}

func TestMillCommandErrorFallthroughRendersPrettyWhenForced(t *testing.T) {
	t.Setenv("SKEIN_ERROR_FORMAT", "pretty")
	t.Setenv("NO_COLOR", "1")
	got := captureMillError(t, errors.New("no weaver is running"), []string{"weaver", "repl"})
	if got != "  x weaver repl: no weaver is running\n" {
		t.Fatalf("pretty fallthrough = %q", got)
	}
}

func TestMillRejectsAnInvalidErrorFormatBeforeRunningACommand(t *testing.T) {
	t.Setenv("SKEIN_ERROR_FORMAT", "json")
	cmd := newMillCommand()
	cmd.SetArgs([]string{"status"})
	err := cmd.Execute()
	if err == nil {
		t.Fatal("expected a loud rejection before mill status touched the socket")
	}
	if !strings.Contains(err.Error(), "SKEIN_ERROR_FORMAT") || !strings.Contains(err.Error(), "plain, pretty") {
		t.Fatalf("error must name the variable and the accepted set: %v", err)
	}
}

// Cobra serves --help without reaching a command's pre-run, which is what keeps
// a typo'd format from taking down the paths that need no weaver.
func TestMillHelpSurvivesAnInvalidErrorFormat(t *testing.T) {
	t.Setenv("SKEIN_ERROR_FORMAT", "json")
	cmd := newMillCommand()
	cmd.SetArgs([]string{"--help"})
	cmd.SetOut(&bytes.Buffer{})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("mill --help = %v", err)
	}
}

func TestMillCommandPathDropsTheBinaryName(t *testing.T) {
	root := newMillCommand()
	weaver, _, err := root.Find([]string{"weaver", "repl"})
	if err != nil {
		t.Fatalf("find weaver repl: %v", err)
	}
	if got := millCommandPath(weaver); strings.Join(got, " ") != "weaver repl" {
		t.Fatalf("command path = %#v", got)
	}
	if got := millCommandPath(nil); got != nil {
		t.Fatalf("no command means no headline prefix, got %#v", got)
	}
}
