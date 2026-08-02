package harness

import (
	"strings"
	"testing"
)

func TestBrake(t *testing.T) {
	cases := []struct {
		name    string
		final   string
		reason  string
		pulled  bool
		wantErr bool
	}{
		{name: "no marker", final: "all done, epic still has work"},
		{
			name:   "final line",
			final:  "I cannot reach the weaver.\nRALPH-STOP: weaver is down",
			reason: "weaver is down",
			pulled: true,
		},
		{
			name:   "trailing blank lines still count",
			final:  "RALPH-STOP: no credentials\n\n  \n",
			reason: "no credentials",
			pulled: true,
		},
		{
			// The addendum tells the agent to end its reply with the marker, so
			// quoting it mid-answer must not stop the loop.
			name:  "quoted mid-message",
			final: "The brake is `RALPH-STOP: <reason>`.\nCarrying on with the next feature.",
		},
		{
			name:    "marker without a reason",
			final:   "RALPH-STOP:",
			pulled:  true,
			wantErr: true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			reason, pulled, err := Brake(tc.final)
			if (err != nil) != tc.wantErr {
				t.Fatalf("err = %v, want error: %v", err, tc.wantErr)
			}
			if pulled != tc.pulled {
				t.Errorf("pulled = %v, want %v", pulled, tc.pulled)
			}
			if !tc.wantErr && reason != tc.reason {
				t.Errorf("reason = %q, want %q", reason, tc.reason)
			}
		})
	}
}

func TestPromptCarriesLoopMechanics(t *testing.T) {
	got := Prompt("Work every feature.", "e1", "Epic one", 4)

	if !strings.HasPrefix(got, "Work every feature.") {
		t.Errorf("the user's prompt must come first, got:\n%s", got)
	}
	// Each of these is a loop exit or the orientation an iteration needs; the
	// addendum is worthless without them.
	for _, want := range []string{
		"iteration 4",
		`epic e1 ("Epic one")`,
		"strand kanban card e1",
		"strand kanban finish e1 --outcome done",
		"strand ready --query kanban-epic-pending --param epic=e1",
		"exactly ONE feature card",
		"RALPH-STOP: <one-line reason>",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("prompt is missing %q:\n%s", want, got)
		}
	}
}

func TestLookup(t *testing.T) {
	for _, name := range Names() {
		h, err := Lookup(name)
		if err != nil {
			t.Fatalf("Lookup(%q): %v", name, err)
		}
		if h.Name() != name {
			t.Errorf("Lookup(%q).Name() = %q", name, h.Name())
		}
	}
	if _, err := Lookup("gemini"); err == nil {
		t.Error("an unknown harness must be refused, not defaulted")
	}
}
