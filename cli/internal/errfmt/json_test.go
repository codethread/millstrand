package errfmt

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

// decodeLine asserts the shape every JSON-mode consumer relies on: exactly one
// line, decodable, carrying all four fields.
func decodeLine(t *testing.T, e Error) map[string]any {
	t.Helper()
	var out bytes.Buffer
	Render(&out, e, JSON)
	line := out.String()
	if !strings.HasSuffix(line, "\n") || strings.Count(line, "\n") != 1 {
		t.Fatalf("JSON mode writes exactly one terminated line, got %q", line)
	}
	var decoded map[string]any
	if err := json.Unmarshal([]byte(line), &decoded); err != nil {
		t.Fatalf("decoding %q: %v", line, err)
	}
	for _, field := range []string{"type", "code", "message", "details"} {
		if _, ok := decoded[field]; !ok {
			t.Fatalf("%q is missing from %q", field, line)
		}
	}
	if len(decoded) != 4 {
		t.Fatalf("the envelope carries exactly four fields, got %q", line)
	}
	return decoded
}

func TestRenderJSONCarriesTheWeaverEnvelope(t *testing.T) {
	decoded := decodeLine(t, Error{
		Type:    "domain",
		Code:    "op/not-found",
		Message: `Unknown subcommand "list"`,
		Details: map[string]any{"available": []any{"add", "board"}, "token": "list"},
	})
	if decoded["type"] != "domain" || decoded["code"] != "op/not-found" {
		t.Fatalf("taxonomy fields lost: %#v", decoded)
	}
	if decoded["message"] != `Unknown subcommand "list"` {
		t.Fatalf("message must survive verbatim: %#v", decoded["message"])
	}
	details, ok := decoded["details"].(map[string]any)
	if !ok || details["token"] != "list" {
		t.Fatalf("details must arrive structured, got %#v", decoded["details"])
	}
	// The prose foldings plain mode performs are the string renderer's, not the
	// envelope's: a consumer reads `available` out of details itself.
	if strings.Contains(decoded["message"].(string), "available:") {
		t.Fatalf("JSON mode folds no prose into the message: %#v", decoded["message"])
	}
}

// A mill-forwarded failure already arrives as a structured envelope, so it
// renders through the same path with its own type and code intact.
func TestRenderJSONCarriesAForwardedMillEnvelope(t *testing.T) {
	decoded := decodeLine(t, Error{
		Type:    "transport",
		Code:    "mill/weaver-forward-failed",
		Message: "weaver refused the invoke",
		Details: map[string]any{"workspace": "/tmp/ws"},
	})
	if decoded["type"] != "transport" || decoded["code"] != "mill/weaver-forward-failed" {
		t.Fatalf("forwarded envelope changed shape: %#v", decoded)
	}
}

func TestRenderJSONDetailsAreAlwaysAnObject(t *testing.T) {
	decoded := decodeLine(t, LocalError(TypeLocal, CodeInvalidInvocation, errors.New("unknown flag: --nope"), nil))
	details, ok := decoded["details"].(map[string]any)
	if !ok || len(details) != 0 {
		t.Fatalf("an error with no details still carries an empty object, got %#v", decoded["details"])
	}
}

// A locally raised error has no envelope, so its type and code are the whole of
// what a consumer can branch on: `cli` never retryable, `transport` retryable
// once a mill is running (SPEC-002.C4c).
func TestRenderJSONTypesLocalErrorsOnTheRetryAxis(t *testing.T) {
	cases := []struct {
		name     string
		in       Error
		wantType string
		wantCode string
	}{
		{
			name:     "bad invocation",
			in:       LocalError(TypeLocal, CodePayloadUnreadable, errors.New("reading payload body: no such file"), []string{"add"}),
			wantType: TypeLocal,
			wantCode: CodePayloadUnreadable,
		},
		{
			name:     "unreachable mill",
			in:       LocalError(TypeTransport, CodeMillUnreachable, errors.New("no running mill; start one with: mill start"), []string{"kanban"}),
			wantType: TypeTransport,
			wantCode: CodeMillUnreachable,
		},
	}
	for _, c := range cases {
		decoded := decodeLine(t, c.in)
		if decoded["type"] != c.wantType || decoded["code"] != c.wantCode {
			t.Fatalf("%s: type/code = %v/%v, want %q/%q", c.name, decoded["type"], decoded["code"], c.wantType, c.wantCode)
		}
		// The plain-mode mirror: adding the taxonomy must not touch the line
		// SPEC-002.C4 pins for a locally raised error.
		var plain bytes.Buffer
		Render(&plain, c.in, Plain)
		if plain.String() != "error: "+c.in.Message+"\n" {
			t.Fatalf("%s: plain line = %q", c.name, plain.String())
		}
	}
}

// A synthesized code is for a decoder, not a reader: pretty says the same thing
// it always did.
func TestPrettyDropsTheCodeOfALocallyRaisedError(t *testing.T) {
	t.Setenv("NO_COLOR", "1")
	var out bytes.Buffer
	Render(&out, LocalError(TypeLocal, CodeInvalidInvocation, errors.New(`unknown command "nope"`), []string{"weaver"}), Pretty)
	if out.String() != `  x weaver: unknown command "nope"`+"\n" {
		t.Fatalf("pretty local headline = %q", out.String())
	}
}

// The remedy pointer is prose, and JSON mode carries none: one envelope line per
// failure is the whole promise.
func TestRenderRemedyIsSilentInJSONMode(t *testing.T) {
	var out bytes.Buffer
	RenderRemedy(&out, "mill --help", JSON)
	if out.Len() != 0 {
		t.Fatalf("JSON mode writes no remedy pointer, got %q", out.String())
	}
}

// A message carrying a newline would split the envelope in two if it were not
// escaped, and a line-oriented consumer would decode half an error.
func TestRenderJSONSurvivesANewlineInTheMessage(t *testing.T) {
	decoded := decodeLine(t, Error{Type: "domain", Code: "op/boom", Message: "first line\nsecond line"})
	if decoded["message"] != "first line\nsecond line" {
		t.Fatalf("message = %#v", decoded["message"])
	}
}

func TestRenderJSONIsByteFaithful(t *testing.T) {
	var out bytes.Buffer
	Render(&out, Error{
		Type:    "domain",
		Code:    "op/usage",
		Message: "invalid invocation",
		Details: map[string]any{"usage": "strand kanban <usage> & more"},
	}, JSON)
	if !strings.Contains(out.String(), `strand kanban <usage> & more`) {
		t.Fatalf("angle brackets and ampersands must survive unescaped, got %q", out.String())
	}
	if strings.HasPrefix(out.String(), "error:") || strings.Contains(out.String(), "details=") {
		t.Fatalf("JSON mode carries no prefix and no details= tail, got %q", out.String())
	}
}

// A details value the weaver stringified into something unencodable would
// otherwise cost the consumer its whole line.
func TestRenderJSONKeepsTheLineWhenDetailsWillNotEncode(t *testing.T) {
	decoded := decodeLine(t, Error{
		Type:    "domain",
		Code:    "op/failed",
		Message: "boom",
		Details: map[string]any{"cycle": make(chan int)},
	})
	details, ok := decoded["details"].(map[string]any)
	if !ok || details["errfmt/unrenderable-details"] == nil {
		t.Fatalf("the dropped details must be named, got %#v", decoded["details"])
	}
	if decoded["message"] != "boom" || decoded["code"] != "op/failed" {
		t.Fatalf("the rest of the envelope survives: %#v", decoded)
	}
}
