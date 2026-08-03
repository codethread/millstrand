package harness

import (
	"slices"
	"strings"
	"testing"
	"time"
)

func TestClaudeDecode(t *testing.T) {
	cases := []struct {
		name  string
		line  string
		kinds []Kind
		texts []string
	}{
		{
			name:  "assistant text and tool call in one message",
			line:  `{"type":"assistant","message":{"content":[{"type":"text","text":"Claiming the card.\nThen decomposing."},{"type":"tool_use","name":"Bash","input":{"command":"strand kanban claim c1"}}]}}`,
			kinds: []Kind{KindText, KindTool},
			texts: []string{"Claiming the card.", `{"command":"strand kanban claim c1"}`},
		},
		{
			name:  "empty text blocks are dropped",
			line:  `{"type":"assistant","message":{"content":[{"type":"text","text":"   "}]}}`,
			kinds: nil,
		},
		{
			name:  "result carries stats",
			line:  `{"type":"result","subtype":"success","num_turns":7,"total_cost_usd":1.234,"duration_ms":90000,"result":"done"}`,
			kinds: []Kind{KindResult},
			texts: []string{"finished: success | turns 7 | $1.23 | 1m30s"},
		},
		{
			name:  "error result",
			line:  `{"type":"result","subtype":"error_during_execution","is_error":true,"result":"boom"}`,
			kinds: []Kind{KindError},
		},
		{
			name:  "unknown line types are left to the transcript",
			line:  `{"type":"system","subtype":"init"}`,
			kinds: nil,
		},
		{
			name:  "malformed JSON never panics",
			line:  `{"type":`,
			kinds: nil,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			events := Claude{}.Decode([]byte(tc.line))
			var kinds []Kind
			for _, e := range events {
				kinds = append(kinds, e.Kind)
			}
			if !slices.Equal(kinds, tc.kinds) {
				t.Fatalf("kinds = %v, want %v", kinds, tc.kinds)
			}
			for i, want := range tc.texts {
				if events[i].Text != want {
					t.Errorf("event %d text = %q, want %q", i, events[i].Text, want)
				}
			}
		})
	}
}

func TestClaudeResultFinalMessage(t *testing.T) {
	events := Claude{}.Decode([]byte(`{"type":"result","subtype":"success","result":"RALPH-STOP: cannot build"}`))
	if len(events) != 1 || !events[0].Final {
		t.Fatalf("the result event must be flagged as the final message, got %+v", events)
	}
	final, err := Claude{}.FinalMessage(RunSpec{}, events[0].Detail)
	if err != nil {
		t.Fatalf("FinalMessage: %v", err)
	}
	reason, pulled, err := Brake(final)
	if err != nil || !pulled || reason != "cannot build" {
		t.Fatalf("Brake(%q) = %q, %v, %v", final, reason, pulled, err)
	}
}

func TestClaudeResultStatsDuration(t *testing.T) {
	events := Claude{}.Decode([]byte(`{"type":"result","duration_ms":1500}`))
	if len(events) != 1 || events[0].Stats == nil {
		t.Fatal("a result event must carry stats")
	}
	if got := events[0].Stats.Duration; got != 1500*time.Millisecond {
		t.Errorf("duration = %s, want 1.5s", got)
	}
}

func TestClaudeArgs(t *testing.T) {
	settings, err := Claude{}.Resolve("", "")
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if settings.Model != "fable" || settings.Effort != "high" {
		t.Fatalf("defaults = %+v", settings)
	}

	spec := RunSpec{Prompt: "go", SkipPermissions: true, Extra: []string{"--add-dir", "/tmp"}}
	args := Claude{}.Args(spec, settings)
	joined := strings.Join(args, " ")
	for _, want := range []string{
		"--print", "--output-format stream-json", "--model fable", "--effort high",
		"--dangerously-skip-permissions", "--add-dir /tmp",
	} {
		if !strings.Contains(joined, want) {
			t.Errorf("args missing %q: %v", want, args)
		}
	}
	if args[len(args)-1] != "go" {
		t.Errorf("the prompt must be the final argument, got %q", args[len(args)-1])
	}
	// The generated prompt opens with a `---` banner the CLI would otherwise
	// reject as an unknown option.
	if args[len(args)-2] != "--" {
		t.Errorf("the prompt must follow a %q terminator, got %q", "--", args[len(args)-2])
	}

	kept := Claude{}.Args(RunSpec{Prompt: "go"}, settings)
	if slices.Contains(kept, "--dangerously-skip-permissions") {
		t.Error("permission bypass must be opt-in")
	}
}

func TestClaudeRefusesAnEmptyFinalMessage(t *testing.T) {
	// A clean exit with no closing result means the stream was truncated; the
	// brake would be unreadable, so the iteration cannot be called a success.
	if _, err := (Claude{}).FinalMessage(RunSpec{}, "  \n"); err == nil {
		t.Fatal("an empty final message must be an error")
	}
	if _, err := (Claude{}).FinalMessage(RunSpec{}, "all done"); err != nil {
		t.Fatalf("FinalMessage: %v", err)
	}
}
