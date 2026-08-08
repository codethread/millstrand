package client

import (
	"bufio"
	"bytes"
	"errors"
	"net"
	"strings"
	"testing"
)

func relay(t *testing.T, frames string) (string, string, int) {
	t.Helper()
	var out, er bytes.Buffer
	code, err := RelayResponse(bufio.NewReader(strings.NewReader(frames)), &out, &er, nil)
	if err != nil {
		// Relay errors are surfaced through stderr/exit code for the caller;
		// tests that expect a clean relay assert err==nil explicitly.
		t.Logf("relay returned err: %v", err)
	}
	return out.String(), er.String(), code
}

func TestRelaySingleSuccessPrintsResult(t *testing.T) {
	out, er, code := relay(t, `{"protocol_version":1,"request_id":"r1","ok":true,"result":{"id":"task-1"},"error":null}`+"\n")
	if code != 0 {
		t.Fatalf("expected exit 0, got %d (stderr=%q)", code, er)
	}
	if strings.TrimSpace(out) != `{"id":"task-1"}` {
		t.Fatalf("unexpected stdout: %q", out)
	}
	if er != "" {
		t.Fatalf("expected empty stderr, got %q", er)
	}
}

func TestRelaySingleSuccessDoesNotHTMLEscapeResult(t *testing.T) {
	out, er, code := relay(t, `{"protocol_version":1,"request_id":"r1","ok":true,"result":{"usage":"strand kanban <id>"},"error":null}`+"\n")
	if code != 0 {
		t.Fatalf("expected exit 0, got %d (stderr=%q)", code, er)
	}
	if strings.TrimSpace(out) != `{"usage":"strand kanban <id>"}` {
		t.Fatalf("stdout JSON should preserve angle brackets, got %q", out)
	}
	if strings.Contains(out, `\\u003c`) || strings.Contains(out, `\\u003e`) {
		t.Fatalf("stdout JSON must not HTML-escape angle brackets, got %q", out)
	}
}

func TestRelaySingleVerbatimPrintsRawText(t *testing.T) {
	// A default help transform's output rides back as a `verbatim` frame whose
	// result is a JSON string; the relay prints the decoded text raw, never as a
	// JSON-quoted string (DELTA-Dtf-002.CC1).
	frame := `{"protocol_version":1,"request_id":"r1","ok":true,"result":"RENDERED add: usage <id>","error":null,"verbatim":true}` + "\n"
	out, er, code := relay(t, frame)
	if code != 0 {
		t.Fatalf("expected exit 0, got %d (stderr=%q)", code, er)
	}
	if out != "RENDERED add: usage <id>\n" {
		t.Fatalf("verbatim result must relay raw text with a trailing newline, got %q", out)
	}
	if strings.Contains(out, `"`) {
		t.Fatalf("verbatim text must not be JSON-quoted, got %q", out)
	}
	if er != "" {
		t.Fatalf("expected empty stderr, got %q", er)
	}
}

func TestRelaySingleVerbatimPreservesExistingTrailingNewline(t *testing.T) {
	// The transform's string already ends in a newline; the relay must not double
	// it (byte-faithful, mirroring the stream relay).
	frame := `{"protocol_version":1,"request_id":"r1","ok":true,"result":"line one\nline two\n","error":null,"verbatim":true}` + "\n"
	out, _, code := relay(t, frame)
	if code != 0 {
		t.Fatalf("expected exit 0, got %d", code)
	}
	if out != "line one\nline two\n" {
		t.Fatalf("verbatim relay must preserve the string's own trailing newline, got %q", out)
	}
}

func TestRelaySingleNonVerbatimStringStaysJSON(t *testing.T) {
	// A normal op that legitimately returns a JSON string value keeps canonical
	// JSON relay: the verbatim path must not blanket-unquote every string result.
	out, _, code := relay(t, `{"protocol_version":1,"request_id":"r1","ok":true,"result":"plain","error":null}`+"\n")
	if code != 0 {
		t.Fatalf("expected exit 0, got %d", code)
	}
	if strings.TrimSpace(out) != `"plain"` {
		t.Fatalf("a non-verbatim string result must relay as JSON, got %q", out)
	}
}

func TestRelaySingleErrorGoesToStderrNonZero(t *testing.T) {
	frame := `{"protocol_version":1,"request_id":"r1","ok":false,"result":null,"error":{"type":"domain","code":"op/not-found","message":"Operation not found","details":{"available":["add","list"]}}}` + "\n"
	var out, er bytes.Buffer
	code, err := RelayResponse(bufio.NewReader(strings.NewReader(frame)), &out, &er, nil)
	if code == 0 {
		t.Fatalf("expected non-zero exit for error frame")
	}
	var responseErr *ResponseError
	if !errors.As(err, &responseErr) || responseErr.Code != "op/not-found" || responseErr.Details["available"] == nil {
		t.Fatalf("relay must preserve typed response error, got %v", err)
	}
	if out.Len() != 0 {
		t.Fatalf("error frame must not print to stdout, got %q", out.String())
	}
	if !strings.Contains(er.String(), "op/not-found") || !strings.Contains(er.String(), "available: add, list") {
		t.Fatalf("stderr missing error detail: %q", er.String())
	}
}

func TestRelayStreamRelaysLinesThenExitsBySuccess(t *testing.T) {
	frames := strings.Join([]string{
		`{"protocol_version":1,"request_id":"r1","stream":true}`,
		`{"i":0}`,
		`{"i":1}`,
		`{"protocol_version":1,"request_id":"r1","done":true,"success":true,"result":{"emitted":2}}`,
	}, "\n") + "\n"
	out, er, code := relay(t, frames)
	if code != 0 {
		t.Fatalf("expected exit 0, got %d (stderr=%q)", code, er)
	}
	if strings.TrimSpace(out) != "{\"i\":0}\n{\"i\":1}" {
		t.Fatalf("unexpected streamed stdout: %q", out)
	}
	if er != "" {
		t.Fatalf("expected empty stderr, got %q", er)
	}
}

func TestRelayStreamErrorTerminatorNonZero(t *testing.T) {
	frames := strings.Join([]string{
		`{"protocol_version":1,"request_id":"r1","stream":true}`,
		`{"i":0}`,
		`{"protocol_version":1,"request_id":"r1","done":true,"success":false,"error":{"type":"domain","code":"stream/failed","message":"stream failed","details":{}}}`,
	}, "\n") + "\n"
	out, er, code := relay(t, frames)
	if code == 0 {
		t.Fatalf("expected non-zero exit for error terminator")
	}
	if strings.TrimSpace(out) != `{"i":0}` {
		t.Fatalf("emitted lines before the error must still be relayed: %q", out)
	}
	if !strings.Contains(er, "stream/failed") {
		t.Fatalf("stderr missing terminator error: %q", er)
	}
}

// TestRelayStreamOverSocketFlushesIncrementally proves the relay reads and emits
// frames one at a time from a live socket rather than buffering the whole stream.
func TestRelayStreamOverSocketFlushesIncrementally(t *testing.T) {
	server, cliConn := net.Pipe()
	defer func() { _ = server.Close() }()
	defer func() { _ = cliConn.Close() }()

	go func() {
		w := bufio.NewWriter(server)
		lines := []string{
			`{"protocol_version":1,"request_id":"r1","stream":true}`,
			`{"i":0}`,
			`{"protocol_version":1,"request_id":"r1","done":true,"success":true,"result":null}`,
		}
		for _, l := range lines {
			_, _ = w.WriteString(l)
			_, _ = w.WriteString("\n")
			_ = w.Flush()
		}
		_ = server.Close()
	}()

	var out, er bytes.Buffer
	code, err := RelayResponse(bufio.NewReader(cliConn), &out, &er, nil)
	if err != nil {
		t.Fatalf("relay error: %v", err)
	}
	if code != 0 {
		t.Fatalf("expected exit 0, got %d", code)
	}
	if strings.TrimSpace(out.String()) != `{"i":0}` {
		t.Fatalf("unexpected socket stream stdout: %q", out.String())
	}
}

func TestRelayStreamTruncatedFailsNonZero(t *testing.T) {
	// Header + one line but no terminator: the relay must not report success.
	frames := `{"protocol_version":1,"request_id":"r1","stream":true}` + "\n" + `{"i":0}` + "\n"
	_, _, code := relay(t, frames)
	if code == 0 {
		t.Fatalf("truncated stream (no terminator) must exit non-zero")
	}
}
