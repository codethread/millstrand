package dispatch

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/errfmt"
)

// Every test here writes to a buffer, which resolves to plain mode on its own.
// A MILLSTRAND_ERROR_FORMAT inherited from the developer's shell would override that
// for this process and every binary it spawns, so it is cleared once up front.
func TestMain(m *testing.M) {
	_ = os.Unsetenv("MILLSTRAND_ERROR_FORMAT")
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
	t.Setenv("MILLSTRAND_ERROR_FORMAT", "colour")
	_, er, code := runDispatch("", "kanban", "card")
	if code != 1 {
		t.Fatalf("exit = %d, want 1", code)
	}
	if !strings.Contains(er, "MILLSTRAND_ERROR_FORMAT") || !strings.Contains(er, "json, plain, pretty") {
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
	t.Setenv("MILLSTRAND_ERROR_FORMAT", "colour")
	for _, arg := range []string{"--version", "--help"} {
		out, er, code := runDispatch("", arg)
		if code != 0 || out == "" {
			t.Fatalf("%s: exit %d stdout %q stderr %q", arg, code, out, er)
		}
	}
}

// decodeStderr asserts stderr is exactly one decodable envelope line.
func decodeStderr(t *testing.T, stderr string) map[string]any {
	t.Helper()
	if strings.Count(stderr, "\n") != 1 {
		t.Fatalf("JSON mode writes exactly one line, got %q", stderr)
	}
	var decoded map[string]any
	if err := json.Unmarshal([]byte(stderr), &decoded); err != nil {
		t.Fatalf("decoding %q: %v", stderr, err)
	}
	return decoded
}

// The two origins a dispatcher failure can have, told apart by the field a
// consumer branches on: a bad invocation is never worth retrying, an
// unreachable mill is (SPEC-002.C4c).
func TestDispatchRendersJSONForBothLocalOrigins(t *testing.T) {
	cases := []struct {
		name     string
		args     []string
		failWith error
		wantType string
		wantCode string
	}{
		{
			name:     "bad invocation",
			args:     []string{"--payload", "malformed", "kanban"},
			wantType: errfmt.TypeLocal,
			wantCode: errfmt.CodePayloadUnreadable,
		},
		{
			name:     "unreachable mill",
			args:     []string{"kanban", "card"},
			failWith: &client.TransportError{Err: errors.New("no running mill; start one with: mill start"), Code: errfmt.CodeMillUnreachable},
			wantType: errfmt.TypeTransport,
			wantCode: errfmt.CodeMillUnreachable,
		},
	}
	for _, c := range cases {
		var captured capture
		harness(t, &captured)
		failure := c.failWith
		sendInvoke = func(world client.MillWorldRequest, env map[string]any, stdout, stderr io.Writer) (int, error) {
			return 1, failure
		}
		t.Setenv("MILLSTRAND_ERROR_FORMAT", "json")
		_, er, code := runDispatch("", c.args...)
		if code != 1 {
			t.Fatalf("%s: exit = %d, want 1", c.name, code)
		}
		decoded := decodeStderr(t, er)
		if decoded["type"] != c.wantType || decoded["code"] != c.wantCode {
			t.Fatalf("%s: type/code = %v/%v, want %q/%q", c.name, decoded["type"], decoded["code"], c.wantType, c.wantCode)
		}
		if decoded["message"] == "" {
			t.Fatalf("%s: the message must survive: %q", c.name, er)
		}

		// The plain surface SPEC-002.C4 pins is untouched by the taxonomy: still
		// the bare line, with no type noun, code, or details= tail.
		t.Setenv("MILLSTRAND_ERROR_FORMAT", "plain")
		_, plain, _ := runDispatch("", c.args...)
		if want := "error: " + decoded["message"].(string) + "\n"; plain != want {
			t.Fatalf("%s: plain line = %q, want %q", c.name, plain, want)
		}
	}
}

// A weaver-origin envelope reaches the consumer through the relay, which renders
// it itself — the dispatcher must not add a second line to a stream whose whole
// promise is one envelope per failure.
func TestDispatchRendersOneJSONLineForARelayedEnvelope(t *testing.T) {
	var captured capture
	harness(t, &captured)
	envelope := &client.ResponseError{
		Type:    "domain",
		Code:    "op/not-found",
		Message: "Operation not found",
		Details: map[string]any{"available": []any{"add", "list"}},
	}
	sendInvoke = func(world client.MillWorldRequest, env map[string]any, stdout, stderr io.Writer) (int, error) {
		errfmt.Render(stderr, errfmt.Error{
			Type:    envelope.Type,
			Code:    envelope.Code,
			Message: envelope.Message,
			Details: envelope.Details,
		}, errfmt.ModeFor(stderr))
		return 1, envelope
	}
	t.Setenv("MILLSTRAND_ERROR_FORMAT", "json")
	_, er, code := runDispatch("", "no-such-op")
	if code != 1 {
		t.Fatalf("exit = %d, want 1", code)
	}
	decoded := decodeStderr(t, er)
	if decoded["type"] != "domain" || decoded["code"] != "op/not-found" {
		t.Fatalf("relayed envelope = %#v", decoded)
	}
	details, ok := decoded["details"].(map[string]any)
	if !ok || len(details["available"].([]any)) != 2 {
		t.Fatalf("the affordance must arrive structured: %#v", decoded["details"])
	}
}

func TestDispatchRendersPrettyWhenTheFormatIsForced(t *testing.T) {
	var captured capture
	harness(t, &captured)
	t.Setenv("MILLSTRAND_ERROR_FORMAT", "pretty")
	t.Setenv("NO_COLOR", "1")
	_, er, code := runDispatch("", "--payload", "malformed", "kanban")
	if code != 1 {
		t.Fatalf("exit = %d, want 1", code)
	}
	if !strings.HasPrefix(er, "  x ") {
		t.Fatalf("forced pretty must reach a local error: %q", er)
	}
}
