package dispatch

import (
	"io"
	"os"
	"strings"
	"testing"

	"skein-strand-cli/internal/client"
)

// Every test here writes to a buffer, which resolves to plain mode on its own.
// A SKEIN_ERROR_FORMAT inherited from the developer's shell would override that
// for this process and every binary it spawns, so it is cleared once up front.
func TestMain(m *testing.M) {
	_ = os.Unsetenv("SKEIN_ERROR_FORMAT")
	os.Exit(m.Run())
}

// The relay renders a decoded envelope itself, so the dispatcher must not
// render it a second time — the strand-side twin of the mill triple-print bug.
func TestDispatchRendersEachErrorExactlyOnce(t *testing.T) {
	cases := []struct {
		name    string
		relayed string
		err     error
	}{
		{
			name:    "envelope already rendered by the relay",
			relayed: "error: weaver domain error (op/boom): it broke\n",
			err:     &client.ResponseError{Type: "domain", Code: "op/boom", Message: "it broke", Details: map[string]any{}},
		},
		{
			name: "transport failure the relay never saw",
			err:  io.ErrUnexpectedEOF,
		},
	}
	for _, c := range cases {
		var captured capture
		harness(t, &captured)
		relayed, failure := c.relayed, c.err
		sendInvoke = func(world client.MillWorldRequest, env map[string]any, stdout, stderr io.Writer) (int, error) {
			_, _ = io.WriteString(stderr, relayed)
			return 1, failure
		}
		_, er, code := runDispatch("", "kanban", "card")
		if code == 0 {
			t.Fatalf("%s: expected a non-zero exit", c.name)
		}
		if got := strings.Count(er, "error:"); got != 1 {
			t.Fatalf("%s: stderr carries %d renderings, want exactly 1: %q", c.name, got, er)
		}
	}
}

func TestDispatchRejectsAnInvalidErrorFormat(t *testing.T) {
	var captured capture
	harness(t, &captured)
	t.Setenv("SKEIN_ERROR_FORMAT", "json")
	_, er, code := runDispatch("", "kanban", "card")
	if code != 1 {
		t.Fatalf("exit = %d, want 1", code)
	}
	if !strings.Contains(er, "SKEIN_ERROR_FORMAT") || !strings.Contains(er, "plain, pretty") {
		t.Fatalf("stderr must name the variable and the accepted set: %q", er)
	}
	if captured.called {
		t.Fatal("a bad format must fail before anything is dispatched")
	}
}

// SPEC-002.C34 promises --version and --help work with no weaver; a typo in an
// unrelated environment variable must not take them down either.
func TestLocalOnlyFlagsSurviveAnInvalidErrorFormat(t *testing.T) {
	var captured capture
	harness(t, &captured)
	t.Setenv("SKEIN_ERROR_FORMAT", "json")
	for _, arg := range []string{"--version", "--help"} {
		out, er, code := runDispatch("", arg)
		if code != 0 || out == "" {
			t.Fatalf("%s: exit %d stdout %q stderr %q", arg, code, out, er)
		}
	}
}

func TestDispatchRendersPrettyWhenTheFormatIsForced(t *testing.T) {
	var captured capture
	harness(t, &captured)
	t.Setenv("SKEIN_ERROR_FORMAT", "pretty")
	t.Setenv("NO_COLOR", "1")
	_, er, code := runDispatch("", "--payload", "malformed", "kanban")
	if code != 1 {
		t.Fatalf("exit = %d, want 1", code)
	}
	if !strings.HasPrefix(er, "  x ") {
		t.Fatalf("forced pretty must reach a local error: %q", er)
	}
}
