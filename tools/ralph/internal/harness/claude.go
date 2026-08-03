package harness

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Claude drives Claude Code's headless `--print` mode over its stream-json
// output format.
type Claude struct{}

// Name implements Harness.
func (Claude) Name() string { return "claude" }

// Binary implements Harness.
func (Claude) Binary() string { return "claude" }

// Resolve implements Harness. Claude takes model and effort names verbatim.
func (Claude) Resolve(model, effort string) (Settings, error) {
	if model == "" {
		model = "fable"
	}
	if effort == "" {
		effort = "high"
	}
	return Settings{Model: model, Effort: effort}, nil
}

// Args implements Harness.
func (Claude) Args(spec RunSpec, settings Settings) []string {
	args := []string{
		"--print",
		"--verbose",
		"--output-format", "stream-json",
		"--model", settings.Model,
		"--effort", settings.Effort,
	}
	if spec.SkipPermissions {
		// A headless run cannot answer a permission prompt, so it either skips
		// them or blocks forever.
		args = append(args, "--dangerously-skip-permissions")
	}
	args = append(args, spec.Extra...)
	// The generated prompt opens with a `---` banner, which the CLI would read
	// as an option; `--` ends option parsing so it lands as the positional.
	return append(args, "--", spec.Prompt)
}

type claudeLine struct {
	Type    string `json:"type"`
	Subtype string `json:"subtype"`
	Message struct {
		Content []struct {
			Type  string          `json:"type"`
			Text  string          `json:"text"`
			Name  string          `json:"name"`
			Input json.RawMessage `json:"input"`
		} `json:"content"`
	} `json:"message"`
	Result       string  `json:"result"`
	NumTurns     int     `json:"num_turns"`
	TotalCostUSD float64 `json:"total_cost_usd"`
	DurationMS   int64   `json:"duration_ms"`
	IsError      bool    `json:"is_error"`
}

// Decode implements Harness.
func (Claude) Decode(line []byte) []Event {
	var parsed claudeLine
	if err := json.Unmarshal(line, &parsed); err != nil {
		return nil
	}
	now := time.Now()
	switch parsed.Type {
	case "assistant":
		var events []Event
		for _, block := range parsed.Message.Content {
			switch block.Type {
			case "text":
				if firstLine(block.Text) == "" {
					continue
				}
				events = append(events, Event{
					Kind: KindText, At: now, Label: "agent",
					Text: clip(firstLine(block.Text), 300), Detail: block.Text,
				})
			case "tool_use":
				input := clip(string(block.Input), 300)
				events = append(events, Event{
					Kind: KindTool, At: now, Label: block.Name,
					Text: input, Detail: indentJSON(block.Input),
				})
			}
		}
		return events
	case "result":
		kind := KindResult
		if parsed.IsError {
			kind = KindError
		}
		stats := &Stats{
			Subtype:  parsed.Subtype,
			Turns:    parsed.NumTurns,
			CostUSD:  parsed.TotalCostUSD,
			Duration: time.Duration(parsed.DurationMS) * time.Millisecond,
		}
		return []Event{{
			Kind: kind, At: now, Label: "run",
			Text:   fmt.Sprintf("finished: %s | turns %d | $%.2f | %s", or(parsed.Subtype, "?"), parsed.NumTurns, parsed.TotalCostUSD, stats.Duration.Round(time.Second)),
			Detail: parsed.Result,
			Final:  true,
			Stats:  stats,
		}}
	}
	return nil
}

// FinalMessage implements Harness. Claude carries its final message inline in
// the closing result event, so a run that exited cleanly without one did not
// really finish — and the brake would be unreadable either way.
func (Claude) FinalMessage(_ RunSpec, streamFinal string) (string, error) {
	if strings.TrimSpace(streamFinal) == "" {
		return "", errors.New("claude exited cleanly but its stream carried no final result message")
	}
	return streamFinal, nil
}

func or(v, fallback string) string {
	if v == "" {
		return fallback
	}
	return v
}

func indentJSON(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var buf []byte
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return string(raw)
	}
	buf, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return string(raw)
	}
	return string(buf)
}
