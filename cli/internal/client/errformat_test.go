package client

import (
	"bufio"
	"bytes"
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
