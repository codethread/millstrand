package harness

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"
)

// Codex drives `codex exec --json`.
type Codex struct{}

// Name implements Harness.
func (Codex) Name() string { return "codex" }

// Binary implements Harness.
func (Codex) Binary() string { return "codex" }

// codexAliases are the ralph-facing model names, each fixing a Codex model and
// the reasoning effort it is normally run at.
var codexAliases = map[string]Settings{
	"sol-low":   {Model: "gpt-5.6-sol", Effort: "low"},
	"luna-low":  {Model: "gpt-5.6-luna", Effort: "low"},
	"luna-high": {Model: "gpt-5.6-luna", Effort: "high"},
}

// Resolve implements Harness. An alias fixes both fields; an explicit effort
// still wins over the alias's default. Any other model name passes through.
func (Codex) Resolve(model, effort string) (Settings, error) {
	if model == "" {
		model = "luna-high"
	}
	resolved, ok := codexAliases[model]
	if !ok {
		resolved = Settings{Model: model, Effort: "high"}
	}
	if effort != "" {
		resolved.Effort = effort
	}
	return resolved, nil
}

// Args implements Harness.
func (Codex) Args(spec RunSpec, settings Settings) []string {
	args := []string{
		"exec",
		"--json",
		"--skip-git-repo-check",
		"--model", settings.Model,
		"-c", fmt.Sprintf("model_reasoning_effort=%q", settings.Effort),
		"-c", "shell_environment_policy.inherit=all",
		"--output-last-message", spec.FinalPath(),
	}
	if spec.SkipPermissions {
		// A headless run cannot answer an approval prompt.
		args = append(args, "--dangerously-bypass-approvals-and-sandbox")
	}
	args = append(args, spec.Extra...)
	// The generated prompt opens with a `---` banner, which the CLI would read
	// as an option; `--` ends option parsing so it lands as the positional.
	return append(args, "--", spec.Prompt)
}

type codexLine struct {
	Type string `json:"type"`
	Item struct {
		Type    string `json:"type"`
		Text    string `json:"text"`
		Command string `json:"command"`
		Server  string `json:"server"`
		Tool    string `json:"tool"`
		Query   string `json:"query"`
	} `json:"item"`
	Usage struct {
		InputTokens       int `json:"input_tokens"`
		CachedInputTokens int `json:"cached_input_tokens"`
		OutputTokens      int `json:"output_tokens"`
	} `json:"usage"`
	Error struct {
		Message string `json:"message"`
	} `json:"error"`
}

// Decode implements Harness.
func (Codex) Decode(line []byte) []Event {
	var parsed codexLine
	if err := json.Unmarshal(line, &parsed); err != nil {
		return nil
	}
	now := time.Now()
	switch parsed.Type {
	case "item.completed":
		if parsed.Item.Type != "agent_message" || firstLine(parsed.Item.Text) == "" {
			return nil
		}
		return []Event{{
			Kind: KindText, At: now, Label: "agent",
			Text: clip(firstLine(parsed.Item.Text), 300), Detail: parsed.Item.Text,
		}}
	case "item.started":
		switch parsed.Item.Type {
		case "command_execution":
			return []Event{{
				Kind: KindTool, At: now, Label: "command",
				Text: clip(parsed.Item.Command, 300), Detail: parsed.Item.Command,
			}}
		case "mcp_tool_call":
			label := strings.TrimSpace(parsed.Item.Server + " " + parsed.Item.Tool)
			return []Event{{
				Kind: KindTool, At: now, Label: "mcp",
				Text: clip(or(label, "mcp_tool_call"), 300), Detail: string(line),
			}}
		case "web_search":
			return []Event{{
				Kind: KindTool, At: now, Label: "web_search",
				Text: clip(parsed.Item.Query, 300), Detail: string(line),
			}}
		}
		return nil
	case "turn.completed":
		stats := &Stats{
			InputTokens:  parsed.Usage.InputTokens,
			CachedTokens: parsed.Usage.CachedInputTokens,
			OutputTokens: parsed.Usage.OutputTokens,
		}
		return []Event{{
			Kind: KindResult, At: now, Label: "run",
			Text: fmt.Sprintf("finished | input %d | cached %d | output %d",
				stats.InputTokens, stats.CachedTokens, stats.OutputTokens),
			Stats: stats,
		}}
	case "turn.failed":
		return []Event{{
			Kind: KindError, At: now, Label: "run",
			Text:   "turn failed: " + clip(parsed.Error.Message, 300),
			Detail: parsed.Error.Message,
		}}
	}
	return nil
}

// FinalMessage implements Harness. Codex writes its final message to the file
// named by --output-last-message; a successful run without one is a broken
// contract rather than an empty reply.
func (Codex) FinalMessage(spec RunSpec, _ string) (string, error) {
	body, err := os.ReadFile(spec.FinalPath())
	if err != nil {
		return "", fmt.Errorf("codex wrote no readable final message at %s: %w", spec.FinalPath(), err)
	}
	if strings.TrimSpace(string(body)) == "" {
		return "", fmt.Errorf("codex wrote an empty final message at %s", spec.FinalPath())
	}
	return string(body), nil
}
