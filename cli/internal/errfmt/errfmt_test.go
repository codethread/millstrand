package errfmt

import (
	"bytes"
	"os"
	"strings"
	"testing"
)

// charDevice returns a writer that is a character device without needing a
// terminal, which is what mode detection actually looks for.
func charDevice(t *testing.T) *os.File {
	t.Helper()
	file, err := os.OpenFile(os.DevNull, os.O_WRONLY, 0)
	if err != nil {
		t.Fatalf("open %s: %v", os.DevNull, err)
	}
	t.Cleanup(func() { _ = file.Close() })
	return file
}

func TestResolveDetectsFromTheWriter(t *testing.T) {
	t.Setenv(FormatEnv, "")
	pipeRead, pipeWrite, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	t.Cleanup(func() { _ = pipeRead.Close(); _ = pipeWrite.Close() })

	cases := []struct {
		name   string
		writer interface{ Write([]byte) (int, error) }
		want   Mode
	}{
		{"buffer", &bytes.Buffer{}, Plain},
		{"pipe", pipeWrite, Plain},
		{"character device", charDevice(t), Pretty},
	}
	for _, c := range cases {
		mode, err := Resolve(c.writer)
		if err != nil {
			t.Fatalf("%s: unexpected error %v", c.name, err)
		}
		if mode != c.want {
			t.Fatalf("%s: mode = %q, want %q", c.name, mode, c.want)
		}
	}
}

func TestResolveHonoursTheEnvironmentOverrideBothWays(t *testing.T) {
	t.Setenv(FormatEnv, "pretty")
	if mode, err := Resolve(&bytes.Buffer{}); err != nil || mode != Pretty {
		t.Fatalf("pretty override on a buffer = (%q, %v)", mode, err)
	}
	t.Setenv(FormatEnv, "plain")
	if mode, err := Resolve(charDevice(t)); err != nil || mode != Plain {
		t.Fatalf("plain override on a terminal = (%q, %v)", mode, err)
	}
}

func TestResolveRejectsAnUnacceptedFormatLoudly(t *testing.T) {
	// json is card e4trm's to add; until it lands it is as invalid as a typo.
	for _, value := range []string{"json", "PLAIN", " pretty", "colour"} {
		t.Setenv(FormatEnv, value)
		mode, err := Resolve(&bytes.Buffer{})
		if err == nil {
			t.Fatalf("%q: expected a loud rejection", value)
		}
		if !strings.Contains(err.Error(), FormatEnv) || !strings.Contains(err.Error(), "plain, pretty") {
			t.Fatalf("%q: error must name the variable and the accepted set, got %q", value, err)
		}
		if mode != Plain {
			t.Fatalf("%q: a rejected override still resolves the detected mode, got %q", value, mode)
		}
	}
}

func TestQuotedDetailsFindsWhatTheMessageAlreadySays(t *testing.T) {
	quoted := QuotedDetails(`unknown subcommand "list"`, map[string]any{
		"token":     "list",
		"op":        "kanban",
		"path":      []any{},
		"empty":     "",
		"available": []any{"add", "list"},
	})
	if len(quoted) != 1 || quoted["token"] != "list" {
		t.Fatalf("quoted = %#v, want only the token", quoted)
	}
}

func TestFromErrorIsLocal(t *testing.T) {
	e := FromError(os.ErrNotExist, []string{"kanban"})
	if e.Type != TypeLocal || e.Message != os.ErrNotExist.Error() {
		t.Fatalf("FromError = %#v", e)
	}
	if e.Code != "" || e.Details != nil {
		t.Fatalf("a local error carries no envelope, got %#v", e)
	}
}
