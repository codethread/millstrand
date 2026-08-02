// Package harness adapts headless agent CLIs to one interface: how to invoke
// them for a single iteration, how to read their JSON stream, and how to find
// the run's final message.
//
// The two dialects (Claude Code's stream-json and Codex's JSONL) normalise onto
// a common Event so the rest of ralph never branches on which agent is running.
package harness

import (
	"fmt"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Kind classifies a normalised stream event.
type Kind string

const (
	// KindText is assistant prose.
	KindText Kind = "text"
	// KindTool is a tool call the agent started.
	KindTool Kind = "tool"
	// KindResult closes a run and carries its usage stats.
	KindResult Kind = "result"
	// KindError is a harness-reported failure inside a run.
	KindError Kind = "error"
	// KindNotice is ralph's own commentary, never the agent's.
	KindNotice Kind = "notice"
)

// Stats are the closing numbers a run reports. Harnesses fill the fields they
// have; the UI shows what is non-zero.
type Stats struct {
	Subtype      string
	Turns        int
	CostUSD      float64
	Duration     time.Duration
	InputTokens  int
	CachedTokens int
	OutputTokens int
}

// Event is one line of agent activity, ready to render.
type Event struct {
	Kind Kind
	At   time.Time
	// Label is the short tag shown in the log gutter (a tool name, "agent").
	Label string
	// Text is the single-line summary.
	Text string
	// Detail is the full body, shown when the line is expanded.
	Detail string
	// Final marks this event as carrying the run's final assistant message.
	Final bool
	// Stats is set on KindResult events.
	Stats *Stats
}

// Settings are the resolved model and effort a harness will actually run with,
// after its own aliases are applied.
type Settings struct {
	Model  string
	Effort string
}

// RunSpec describes one iteration.
type RunSpec struct {
	Prompt          string
	Iteration       int
	LogDir          string
	SkipPermissions bool
	Extra           []string
}

// TranscriptPath is where this iteration's raw stream is kept.
func (s RunSpec) TranscriptPath() string {
	return filepath.Join(s.LogDir, fmt.Sprintf("iter-%d.jsonl", s.Iteration))
}

// FinalPath is where a harness that writes its final message to disk puts it.
func (s RunSpec) FinalPath() string {
	return filepath.Join(s.LogDir, fmt.Sprintf("iter-%d-final.txt", s.Iteration))
}

// Harness is one headless agent CLI.
type Harness interface {
	// Name is the harness's ralph-facing name (claude, codex).
	Name() string
	// Binary is the executable ralph looks for on PATH.
	Binary() string
	// Resolve applies the harness's model aliases and effort defaults. Empty
	// inputs mean "use the default"; an unusable pair is an error.
	Resolve(model, effort string) (Settings, error)
	// Args builds the argv (after Binary) for one iteration.
	Args(spec RunSpec, settings Settings) []string
	// Decode turns one raw stream line into zero or more events. Lines it does
	// not recognise yield nothing and survive only in the transcript.
	Decode(line []byte) []Event
	// FinalMessage returns the run's final assistant message. streamFinal is
	// whatever the decoded stream reported, for harnesses that carry it inline.
	FinalMessage(spec RunSpec, streamFinal string) (string, error)
}

// All returns the registered harnesses by name.
func All() map[string]Harness {
	return map[string]Harness{
		Claude{}.Name(): Claude{},
		Codex{}.Name():  Codex{},
	}
}

// Names lists the registered harness names, sorted.
func Names() []string {
	var out []string
	for name := range All() {
		out = append(out, name)
	}
	sort.Strings(out)
	return out
}

// Lookup finds a harness by name.
func Lookup(name string) (Harness, error) {
	h, ok := All()[name]
	if !ok {
		return nil, fmt.Errorf("unknown harness %q (known: %s)", name, strings.Join(Names(), ", "))
	}
	return h, nil
}

// BrakePrefix is the emergency-brake marker an agent ends its final message
// with to halt the loop.
const BrakePrefix = "RALPH-STOP:"

// Brake reads the emergency brake out of a final message. Only the last
// non-empty line counts, so an agent quoting the instruction mid-reply cannot
// stop the loop by accident. A marker with no reason is malformed and reported
// as such rather than silently ignored.
func Brake(final string) (reason string, pulled bool, err error) {
	var last string
	for line := range strings.SplitSeq(final, "\n") {
		if strings.TrimSpace(line) != "" {
			last = strings.TrimRight(line, "\r \t")
		}
	}
	if !strings.HasPrefix(last, BrakePrefix) {
		return "", false, nil
	}
	rest := strings.TrimSpace(strings.TrimPrefix(last, BrakePrefix))
	if rest == "" {
		return "", true, fmt.Errorf("malformed emergency brake %q; expected %s <reason>", last, BrakePrefix)
	}
	return rest, true, nil
}

// fullAuthGrant is the operator authority appended by --full-auth. It hands the
// agent permissions the repo docs otherwise reserve for the user, so it exists
// only as an explicit opt-in.
const fullAuthGrant = "Work with my authority: rebuild and restart mill/weaver CLIs etc. as you " +
	"need, including bumping sibling spools — this grant is the explicit user " +
	"sign-off the repo docs require for those steps. Verify such key steps with " +
	"guidance from the :oracle seat (`strand agent harnesses`). DO NOT tag v1 on " +
	"skein-src itself, but breaking changes are permitted at this pre-v1 stage."

// Prompt builds the generated per-iteration prompt. Work discipline belongs to
// the registered ralph-iterate workflow and the epic's feature cards; this
// prompt carries only the workflow pointer and the Go loop's stop mechanics.
func Prompt(epicID, epicTitle string, iteration int, fullAuth bool) string {
	var b strings.Builder
	b.WriteString("--- ralph harness (tools/ralph, iteration ")
	fmt.Fprintf(&b, "%d", iteration)
	b.WriteString(") ---\n")
	fmt.Fprintf(&b, "Run the registered `ralph-iterate` workflow for epic %s (%q).\n", epicID, epicTitle)
	fmt.Fprintf(&b, "Start it with `strand workflow start <run-id> --workflow ralph-iterate --params '{\"epic\":\"%s\"}'` and drive the workflow to its judgment point.\n", epicID)
	b.WriteString("The workflow and live strands carry the work discipline; this prompt carries the Go loop contract.\n")
	b.WriteString("CRITICAL: when every feature under the epic is complete, close the epic with\n")
	fmt.Fprintf(&b, "  `strand kanban finish %s --outcome done` — the loop runs forever otherwise.\n", epicID)
	b.WriteString("EMERGENCY BRAKE: if you hit a blocker you cannot work around, end your reply\n")
	fmt.Fprintf(&b, "  with a final line of exactly: %s <one-line reason>\n", BrakePrefix)
	b.WriteString("Use it only for hard blockers; it halts the loop immediately.\n")
	if fullAuth {
		b.WriteString("\n")
		b.WriteString(fullAuthGrant)
		b.WriteString("\n")
	}
	return b.String()
}

// clip shortens a one-line summary to n runes, marking the cut.
func clip(s string, n int) string {
	s = strings.TrimSpace(strings.ReplaceAll(s, "\n", " "))
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

// firstLine is the summary line for a multi-line body.
func firstLine(s string) string {
	trimmed := strings.TrimSpace(s)
	if idx := strings.IndexByte(trimmed, '\n'); idx >= 0 {
		return strings.TrimSpace(trimmed[:idx])
	}
	return trimmed
}
