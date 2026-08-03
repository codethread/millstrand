package harness

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestCodexResolve(t *testing.T) {
	cases := []struct {
		model, effort string
		want          Settings
	}{
		{want: Settings{Model: "gpt-5.6-luna", Effort: "high"}},
		{model: "luna-high", want: Settings{Model: "gpt-5.6-luna", Effort: "high"}},
		{model: "luna-low", want: Settings{Model: "gpt-5.6-luna", Effort: "low"}},
		{model: "sol-low", want: Settings{Model: "gpt-5.6-sol", Effort: "low"}},
		// An explicit effort overrides the alias's own default.
		{model: "luna-low", effort: "high", want: Settings{Model: "gpt-5.6-luna", Effort: "high"}},
		// Any other name is a Codex model id, passed through untouched.
		{model: "o5-mini", want: Settings{Model: "o5-mini", Effort: "high"}},
	}
	for _, tc := range cases {
		t.Run(tc.model+"/"+tc.effort, func(t *testing.T) {
			got, err := Codex{}.Resolve(tc.model, tc.effort)
			if err != nil {
				t.Fatalf("Resolve: %v", err)
			}
			if got != tc.want {
				t.Errorf("Resolve(%q, %q) = %+v, want %+v", tc.model, tc.effort, got, tc.want)
			}
		})
	}
}

func TestCodexArgs(t *testing.T) {
	spec := RunSpec{Prompt: "go", Iteration: 2, LogDir: "/logs", SkipPermissions: true}
	args := Codex{}.Args(spec, Settings{Model: "gpt-5.6-luna", Effort: "low"})
	joined := strings.Join(args, " ")
	for _, want := range []string{
		"exec", "--json", "--skip-git-repo-check", "--model gpt-5.6-luna",
		`model_reasoning_effort="low"`, "shell_environment_policy.inherit=all",
		"--output-last-message /logs/iter-2-final.txt",
		"--dangerously-bypass-approvals-and-sandbox",
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
}

func TestCodexDecode(t *testing.T) {
	cases := []struct {
		name  string
		line  string
		kinds []Kind
		text  string
	}{
		{
			name:  "agent message",
			line:  `{"type":"item.completed","item":{"type":"agent_message","text":"Feature landed.\nNext up: docs."}}`,
			kinds: []Kind{KindText},
			text:  "Feature landed.",
		},
		{
			name:  "command execution",
			line:  `{"type":"item.started","item":{"type":"command_execution","command":"git status"}}`,
			kinds: []Kind{KindTool},
			text:  "git status",
		},
		{
			name:  "turn usage",
			line:  `{"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":4,"output_tokens":2}}`,
			kinds: []Kind{KindResult},
			text:  "finished | input 10 | cached 4 | output 2",
		},
		{
			name:  "turn failure",
			line:  `{"type":"turn.failed","error":{"message":"rate limited"}}`,
			kinds: []Kind{KindError},
			text:  "turn failed: rate limited",
		},
		{
			name:  "reasoning items stay in the transcript",
			line:  `{"type":"item.completed","item":{"type":"reasoning","text":"thinking"}}`,
			kinds: nil,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			events := Codex{}.Decode([]byte(tc.line))
			var kinds []Kind
			for _, e := range events {
				kinds = append(kinds, e.Kind)
			}
			if !slices.Equal(kinds, tc.kinds) {
				t.Fatalf("kinds = %v, want %v", kinds, tc.kinds)
			}
			if tc.text != "" && events[0].Text != tc.text {
				t.Errorf("text = %q, want %q", events[0].Text, tc.text)
			}
		})
	}
}

func TestCodexFinalMessage(t *testing.T) {
	dir := t.TempDir()
	spec := RunSpec{Iteration: 1, LogDir: dir}
	codex := Codex{}

	// A successful Codex run always writes this file; its absence is a broken
	// contract, not an empty reply.
	if _, err := codex.FinalMessage(spec, "ignored"); err == nil {
		t.Error("a missing final-message file must be an error")
	}

	path := filepath.Join(dir, "iter-1-final.txt")
	if err := os.WriteFile(path, []byte("   \n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := codex.FinalMessage(spec, ""); err == nil {
		t.Error("an empty final-message file must be an error")
	}

	if err := os.WriteFile(path, []byte("all good\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := codex.FinalMessage(spec, "")
	if err != nil {
		t.Fatalf("FinalMessage: %v", err)
	}
	if strings.TrimSpace(got) != "all good" {
		t.Errorf("final = %q", got)
	}
}
