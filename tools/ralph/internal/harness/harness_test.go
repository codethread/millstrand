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

func TestPromptCarriesWorkflowAndLoopMechanics(t *testing.T) {
	got := Prompt("e1", "Epic one", 4, false)

	if strings.Contains(got, "Work every feature.") {
		t.Errorf("generated prompt must not carry ad-hoc user text:\n%s", got)
	}
	// These are the workflow pointer and the Go-owned loop contract.
	for _, want := range []string{
		"iteration 4",
		"Do not delegate implementation work: you are already the mechanical agent. Reviews and council are still encouraged.",
		`epic e1 ("Epic one")`,
		"strand workflow start <run-id> --workflow ralph-iterate",
		`{"epic":"e1"}`,
		"strand kanban finish e1 --outcome done",
		"RALPH-STOP: <one-line reason>",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("prompt is missing %q:\n%s", want, got)
		}
	}
}

func TestPromptFullAuthIsExplicit(t *testing.T) {
	without := Prompt("e1", "Epic one", 1, false)
	with := Prompt("e1", "Epic one", 1, true)

	if strings.Contains(without, "Work with my authority") {
		t.Error("full-auth grant must not appear without explicit opt-in")
	}
	for _, want := range []string{"Work with my authority", "DO NOT tag v1 on millstrand-src itself"} {
		if !strings.Contains(with, want) {
			t.Errorf("full-auth prompt is missing %q:\n%s", want, with)
		}
	}
}

func TestPromptEscapesEpicIDInWorkflowParams(t *testing.T) {
	got := Prompt(`e"1\line`, "Epic one", 1, false)

	if !strings.Contains(got, `{"epic":"e\"1\\line"}`) {
		t.Errorf("workflow params must be valid JSON for special-character epic IDs:\n%s", got)
	}
}

func TestPromptShellQuotesEpicIDInWorkflowParams(t *testing.T) {
	got := Prompt("e'1", "Epic one", 1, false)

	if !strings.Contains(got, `--params '{"epic":"e'"'"'1"}'`) {
		t.Errorf("workflow params must shell-escape apostrophes in epic IDs:\n%s", got)
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
