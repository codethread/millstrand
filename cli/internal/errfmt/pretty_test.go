package errfmt

import (
	"bytes"
	"strings"
	"testing"
)

// pretty renders e with colour off and a fixed terminal width, so goldens read
// as layout rather than escape sequences.
func pretty(t *testing.T, e Error) string {
	t.Helper()
	t.Setenv("NO_COLOR", "1")
	t.Setenv("COLUMNS", "80")
	var out bytes.Buffer
	Render(&out, e, Pretty)
	return out.String()
}

func TestPrettyRendersTheExpandedLayout(t *testing.T) {
	got := pretty(t, Error{
		Type:    "domain",
		Code:    fallbackCode,
		Message: `Unknown subcommand "list"`,
		Command: []string{"kanban", "list"},
		Details: map[string]any{
			"available": []any{"about", "add", "board", "card", "claim", "finish", "label", "next", "note", "prime", "priority", "promote", "reopen", "review", "rework", "task"},
			"op":        "kanban",
			"path":      []any{},
			"reason":    "unknown-subcommand",
			"token":     "list",
			"try":       "strand help kanban",
		},
	})
	want := strings.Join([]string{
		`  x kanban: Unknown subcommand "list"`,
		``,
		`    available:`,
		`      about     add       board     card      claim     finish    label`,
		`      next      note      prime     priority  promote   reopen    review`,
		`      rework    task`,
		``,
		`    details:`,
		`      op      kanban`,
		`      path    []`,
		`      reason  unknown-subcommand`,
		``,
		`    try: strand help kanban`,
		``,
	}, "\n")
	if got != want {
		t.Fatalf("layout drifted:\n got:\n%s\nwant:\n%s", got, want)
	}
}

func TestPrettyHeadlineCarriesTheCodeOnlyWhenItSaysSomething(t *testing.T) {
	withCode := pretty(t, Error{Type: "domain", Code: "hook/failed", Message: "rejected", Command: []string{"kanban", "claim"}})
	if withCode != "  x kanban claim: rejected (hook/failed)\n" {
		t.Fatalf("headline = %q", withCode)
	}
	// The type noun is never spoken: "weaver domain error" tells nobody anything.
	if strings.Contains(withCode, "domain") {
		t.Fatalf("headline must not name the wire type: %q", withCode)
	}
	fallback := pretty(t, Error{Type: "domain", Code: fallbackCode, Message: "rejected", Command: []string{"kanban"}})
	if fallback != "  x kanban: rejected\n" {
		t.Fatalf("fallback code must be dropped, got %q", fallback)
	}
}

func TestPrettyDropsDetailsTheMessageAlreadyQuotes(t *testing.T) {
	got := pretty(t, Error{
		Type:    "domain",
		Message: `no card "zzz" on the board`,
		Command: []string{"kanban", "card"},
		Details: map[string]any{"card": "zzz", "lane": "pending"},
	})
	if strings.Contains(got, "card    zzz") {
		t.Fatalf("the quoted value must not repeat as a row:\n%s", got)
	}
	if !strings.Contains(got, "lane  pending") {
		t.Fatalf("unquoted rows must stay:\n%s", got)
	}
}

func TestPrettySortsRowsAndAcceptsUnknownKeys(t *testing.T) {
	got := pretty(t, Error{
		Type:    "domain",
		Message: "boom",
		Details: map[string]any{"zeta": 1, "alpha": true, "midway": map[string]any{"nested": "yes"}},
	})
	want := strings.Join([]string{
		`  x boom`,
		``,
		`    details:`,
		`      alpha   true`,
		`      midway  {"nested":"yes"}`,
		`      zeta    1`,
		``,
	}, "\n")
	if got != want {
		t.Fatalf("rows:\n got:\n%s\nwant:\n%s", got, want)
	}
}

func TestPrettyHasDefinedOutputForDegenerateErrors(t *testing.T) {
	cases := []struct {
		name string
		in   Error
		want string
	}{
		{"empty message", Error{Type: "transport", Code: "transport/server-error"}, "  x unknown error (transport/server-error)\n"},
		{"empty everything", Error{}, "  x unknown error\n"},
		{"unknown type", Error{Type: "quantum", Message: "strange"}, "  x strange\n"},
		{"empty details map", Error{Type: "domain", Message: "strange", Details: map[string]any{}}, "  x strange\n"},
		// An `available` with nothing in it earns no section, but it is still a
		// detail the weaver sent, so it says so rather than disappearing.
		{"empty available list", Error{Type: "domain", Message: "strange", Details: map[string]any{"available": []any{}}}, "  x strange\n\n    details:\n      available  []\n"},
	}
	for _, c := range cases {
		if got := pretty(t, c.in); got != c.want {
			t.Fatalf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}

// A well-known key whose value is the wrong shape must not be swallowed by the
// section that would have rendered it: the alternatives a failure lists are the
// most actionable thing it carries.
func TestPrettyKeepsMalformedWellKnownKeysAsRows(t *testing.T) {
	got := pretty(t, Error{
		Type:    "domain",
		Message: "unknown op",
		Details: map[string]any{"available": "add, list", "try": []any{"strand help"}},
	})
	want := strings.Join([]string{
		`  x unknown op`,
		``,
		`    details:`,
		`      available  add, list`,
		`      try        ["strand help"]`,
		``,
	}, "\n")
	if got != want {
		t.Fatalf("malformed well-known keys:\n got:\n%s\nwant:\n%s", got, want)
	}
}

// The weaver stringifies non-JSON-safe ex-data with pr-str, so braces, quotes,
// and newlines arrive inside ordinary detail values.
func TestPrettyIndentsMultiLineDetailValues(t *testing.T) {
	got := pretty(t, Error{
		Type:    "domain",
		Message: "spool failed",
		Details: map[string]any{"form": "{:a 1\n :b \"two\"}"},
	})
	want := strings.Join([]string{
		`  x spool failed`,
		``,
		`    details:`,
		`      form  {:a 1`,
		`             :b "two"}`,
		``,
	}, "\n")
	if got != want {
		t.Fatalf("multi-line value:\n got:\n%s\nwant:\n%s", got, want)
	}
}

func TestPrettyWrapsAvailableToTheTerminalWidth(t *testing.T) {
	t.Setenv("NO_COLOR", "1")
	t.Setenv("COLUMNS", "40")
	var out bytes.Buffer
	Render(&out, Error{
		Type:    "domain",
		Message: "unknown op",
		Details: map[string]any{"available": []any{"aaaa", "bbbb", "cccc", "dddd", "eeee", "ffff"}},
	}, Pretty)
	for _, line := range strings.Split(out.String(), "\n") {
		if len(line) > 40 {
			t.Fatalf("line exceeds the terminal width: %q", line)
		}
	}
	if !strings.Contains(out.String(), "aaaa  bbbb  cccc  dddd  eeee") {
		t.Fatalf("names should fill each row:\n%s", out.String())
	}
}

func TestPrettyColoursOnlyWhenNoColorIsUnset(t *testing.T) {
	t.Setenv("COLUMNS", "80")
	e := Error{Type: "domain", Code: "hook/failed", Message: "rejected", Command: []string{"kanban"}, Details: map[string]any{"try": "strand help kanban"}}

	t.Setenv("NO_COLOR", "")
	var coloured bytes.Buffer
	Render(&coloured, e, Pretty)
	if !strings.Contains(coloured.String(), ansiRed+"x"+ansiReset) || !strings.Contains(coloured.String(), ansiCyan) {
		t.Fatalf("expected colour at a terminal: %q", coloured.String())
	}

	t.Setenv("NO_COLOR", "1")
	var bare bytes.Buffer
	Render(&bare, e, Pretty)
	if strings.Contains(bare.String(), "\x1b[") {
		t.Fatalf("NO_COLOR must strip every escape: %q", bare.String())
	}
	// Layout is identical either way: NO_COLOR is colour, never shape.
	if stripANSI(coloured.String()) != bare.String() {
		t.Fatalf("NO_COLOR changed the layout:\n%q\n%q", stripANSI(coloured.String()), bare.String())
	}
}

func stripANSI(s string) string {
	for _, code := range []string{ansiReset, ansiRed, ansiBold, ansiDim, ansiCyan} {
		s = strings.ReplaceAll(s, code, "")
	}
	return s
}
