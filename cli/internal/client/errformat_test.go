package client

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"

	"skein-strand-cli/internal/errfmt"
)

// Every test here writes to a buffer, which resolves to plain mode on its own.
// A SKEIN_ERROR_FORMAT inherited from the developer's shell would override that
// for this process and every binary it spawns, so it is cleared once up front.
func TestMain(m *testing.M) {
	_ = os.Unsetenv("SKEIN_ERROR_FORMAT")
	os.Exit(m.Run())
}

// The relay hands the renderer the typed envelope and the command the user
// typed, so pretty mode has more than a flattened string to work with.
func TestRelayErrorRendersPrettyWithTheTypedFields(t *testing.T) {
	t.Setenv("SKEIN_ERROR_FORMAT", "pretty")
	t.Setenv("NO_COLOR", "1")
	t.Setenv("COLUMNS", "80")
	frame := `{"protocol_version":1,"request_id":"r1","ok":false,"result":null,"error":{"type":"domain","code":"domain/error","message":"Unknown subcommand \"list\"","details":{"available":["add","board"],"op":"kanban","token":"list"}}}` + "\n"
	var out, er bytes.Buffer
	if _, err := RelayResponse(bufio.NewReader(strings.NewReader(frame)), &out, &er, []string{"kanban", "list"}); err == nil {
		t.Fatal("expected the typed response error")
	}
	want := strings.Join([]string{
		`  x kanban: Unknown subcommand "list"`,
		``,
		`    available:`,
		`      add    board`,
		``,
		`    details:`,
		`      op  kanban`,
		``,
	}, "\n")
	if er.String() != want {
		t.Fatalf("pretty relay:\n got:\n%s\nwant:\n%s", er.String(), want)
	}
}

// The weaver-origin case JSON mode exists for: the envelope the weaver built
// reaches a CLI-over-CLI consumer as structure rather than as prose.
func TestRelayErrorRendersJSONWhenAsked(t *testing.T) {
	t.Setenv("SKEIN_ERROR_FORMAT", "json")
	frame := `{"protocol_version":1,"request_id":"r1","ok":false,"result":null,"error":{"type":"domain","code":"domain/error","message":"Unknown subcommand \"list\"","details":{"available":["add","board"],"op":"kanban","token":"list"}}}` + "\n"
	var out, er bytes.Buffer
	if _, err := RelayResponse(bufio.NewReader(strings.NewReader(frame)), &out, &er, []string{"kanban", "list"}); err == nil {
		t.Fatal("expected the typed response error")
	}
	var decoded struct {
		Type    string         `json:"type"`
		Code    string         `json:"code"`
		Message string         `json:"message"`
		Details map[string]any `json:"details"`
	}
	if err := json.Unmarshal(er.Bytes(), &decoded); err != nil {
		t.Fatalf("decoding %q: %v", er.String(), err)
	}
	if decoded.Type != "domain" || decoded.Code != "domain/error" {
		t.Fatalf("taxonomy lost in relay: %#v", decoded)
	}
	if decoded.Message != `Unknown subcommand "list"` || decoded.Details["op"] != "kanban" {
		t.Fatalf("envelope lost in relay: %#v", decoded)
	}
}

// SPEC-002.C4c splits the taxonomy from the origin: an unreachable mill is
// transport even though the bin raised it locally, and locally raised means the
// plain line stays bare.
func TestForRenderingClassifiesTheThreeOrigins(t *testing.T) {
	command := []string{"kanban", "board"}
	cases := []struct {
		name      string
		err       error
		wantType  string
		wantLocal bool
	}{
		{"decoded envelope", &ResponseError{Type: "domain", Code: "op/boom", Message: "it broke", Details: map[string]any{}}, "domain", false},
		{"unreachable mill", asTransport(errors.New("mill socket unreachable; start one with: mill start")), errfmt.TypeTransport, true},
		{"bad invocation", errors.New("malformed --payload (want name=path): x"), errfmt.TypeLocal, true},
	}
	for _, c := range cases {
		got := ForRendering(c.err, command)
		if got.Type != c.wantType || got.Local != c.wantLocal {
			t.Fatalf("%s: type = %q local = %v, want %q / %v", c.name, got.Type, got.Local, c.wantType, c.wantLocal)
		}
	}
}

// An error frame that will not decode has nothing typed to render, so the relay
// leaves it to the caller that renders everything the relay did not. Rendering
// it here as well put two lines on stderr — two envelopes in JSON mode, where
// exactly one is the whole contract.
func TestRelayLeavesAnUndecodableFrameToItsCaller(t *testing.T) {
	frame := `{"protocol_version":1,"request_id":"r1","ok":false,"result":null,"error":"boom"}` + "\n"
	var out, er bytes.Buffer
	code, err := RelayResponse(bufio.NewReader(strings.NewReader(frame)), &out, &er, nil)
	if code == 0 || err == nil {
		t.Fatalf("expected a non-zero exit and an error, got (%d, %v)", code, err)
	}
	if er.Len() != 0 {
		t.Fatalf("the relay renders nothing it could not decode, got %q", er.String())
	}
	var transportErr *TransportError
	if !errors.As(err, &transportErr) {
		t.Fatalf("a frame that will not decode is skew, so transport: %#v", err)
	}
	if transportErr.Code != errfmt.CodeWeaverResponseMalformed {
		t.Fatalf("code = %q", transportErr.Code)
	}
	// The undecodable error value is the most faithful thing left to say, so it
	// rides in the message rather than being dropped with the rendering.
	if !strings.Contains(err.Error(), `"boom"`) {
		t.Fatalf("the raw error value must survive in the message: %v", err)
	}
}

// Every failure InvokeThroughMill raises itself is a transport failure; only a
// decoded envelope passes through untouched.
func TestInvokeThroughMillMarksItsOwnFailuresTransport(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", t.TempDir())
	var out, er bytes.Buffer
	_, err := InvokeThroughMill(MillWorldRequest{}, map[string]any{"name": "kanban"}, &out, &er)
	var transportErr *TransportError
	if !errors.As(err, &transportErr) {
		t.Fatalf("no running mill must surface as transport, got %#v", err)
	}
	// The remedy is to start a mill, and the code says so rather than leaving a
	// JSON-mode consumer to guess which transport failure it hit.
	if transportErr.Code != errfmt.CodeMillUnreachable {
		t.Fatalf("code = %q", transportErr.Code)
	}
	if rendered := ForRendering(err, nil); rendered.Type != errfmt.TypeTransport || rendered.Code != errfmt.CodeMillUnreachable {
		t.Fatalf("the code must reach the renderer: %#v", rendered)
	}
}

// A decoded envelope with no message is not a shape any weaver frame promises,
// but it decodes cleanly, so it needs a rendering rather than a panic.
func TestRelayRendersAnEmptyMessageEnvelope(t *testing.T) {
	frame := `{"protocol_version":1,"request_id":"r1","ok":false,"result":null,"error":{"type":"transport","code":"transport/server-error","message":"","details":{}}}` + "\n"
	var out, er bytes.Buffer
	if _, err := RelayResponse(bufio.NewReader(strings.NewReader(frame)), &out, &er, nil); err == nil {
		t.Fatal("expected the typed response error")
	}
	if er.String() != "error: weaver transport error (transport/server-error): \n" {
		t.Fatalf("empty-message rendering = %q", er.String())
	}
}
