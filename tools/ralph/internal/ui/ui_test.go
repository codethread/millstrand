package ui

import (
	"strings"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"millstrand-ralph/internal/board"
	"millstrand-ralph/internal/harness"
	"millstrand-ralph/internal/loop"
)

func testModel(t *testing.T, width, height int) model {
	t.Helper()
	session := Session{
		Engine:        loop.New(loop.Config{Epic: "e1"}),
		Epic:          board.Strand{ID: "e1", Title: "Epic one", State: board.StateActive},
		HarnessName:   "claude",
		Settings:      harness.Settings{Model: "fable", Effort: "high"},
		MaxIterations: 30,
		LogDir:        "/tmp/ralph/e1",
		SkipPerms:     true,
	}
	m := newModel(session)
	updated, _ := m.Update(tea.WindowSizeMsg{Width: width, Height: height})
	return updated.(model)
}

func update(t *testing.T, m model, msgs ...tea.Msg) model {
	t.Helper()
	for _, msg := range msgs {
		next, _ := m.Update(msg)
		m = next.(model)
	}
	return m
}

// TestInterruptKeysOpenTheStopPrompt is the hard requirement: no keystroke may
// end a live agent run on its own.
func TestInterruptKeysOpenTheStopPrompt(t *testing.T) {
	for _, key := range []tea.KeyMsg{
		{Type: tea.KeyCtrlC},
		{Type: tea.KeyCtrlD},
		{Type: tea.KeyRunes, Runes: []rune{'q'}},
	} {
		t.Run(key.String(), func(t *testing.T) {
			m := testModel(t, 120, 40)
			next, cmd := m.Update(key)
			got := next.(model)
			if cmd != nil {
				t.Fatalf("%s produced a command (%v); it must not quit", key, cmd())
			}
			if got.confirm != confirmQuit {
				t.Fatalf("%s did not raise the stop prompt", key)
			}
			view := got.View()
			for _, want := range []string{"soft stop", "hard stop", "cancel"} {
				if !strings.Contains(view, want) {
					t.Errorf("stop prompt is missing %q:\n%s", want, view)
				}
			}
		})
	}
}

func TestStopPromptCancels(t *testing.T) {
	m := update(t, testModel(t, 120, 40),
		tea.KeyMsg{Type: tea.KeyCtrlC},
		tea.KeyMsg{Type: tea.KeyEsc},
	)
	if m.confirm != confirmNone {
		t.Fatal("esc must dismiss the stop prompt")
	}
	if m.session.Engine.SoftStopped() {
		t.Error("cancelling must not arm a stop")
	}
}

func TestSoftStopTogglesAndArmsTheEngine(t *testing.T) {
	m := update(t, testModel(t, 120, 40), tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'s'}})
	if !m.softArmed || !m.session.Engine.SoftStopped() {
		t.Fatal("s must arm a soft stop on the engine")
	}
	m = update(t, m, tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'s'}})
	if m.softArmed || m.session.Engine.SoftStopped() {
		t.Fatal("s again must disarm it")
	}
}

func TestHardStopNeedsConfirmation(t *testing.T) {
	m := update(t, testModel(t, 120, 40), tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'x'}})
	if m.confirm != confirmHard {
		t.Fatal("x must ask before killing the run")
	}
	if m.hardArmed {
		t.Fatal("x alone must not kill the run")
	}
	m = update(t, m, tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'x'}})
	if !m.hardArmed {
		t.Fatal("confirming must kill the run")
	}
}

func TestQuitAfterTheLoopFinishes(t *testing.T) {
	m := update(t, testModel(t, 120, 40), loop.OutcomeMsg{Outcome: loop.Outcome{
		Reason: loop.ReasonEpicInactive, Detail: "epic e1 is closed", ExitCode: loop.ExitOK,
	}})
	if !m.finished || m.outcome == nil {
		t.Fatal("the outcome must be recorded")
	}
	_, cmd := m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'q'}})
	if cmd == nil {
		t.Fatal("q must leave once the loop has stopped")
	}
	if _, ok := cmd().(tea.QuitMsg); !ok {
		t.Fatalf("q produced %T, want tea.QuitMsg", cmd())
	}
}

// TestLogPaneIsScopedToOneIteration covers both halves of the deal: a new
// iteration takes the pane over, and the iterations pane can point it back at
// an earlier run.
func TestLogPaneIsScopedToOneIteration(t *testing.T) {
	m := testModel(t, 120, 40)
	m = update(t, m,
		loop.IterationStarted{N: 1, Transcript: "/tmp/ralph/e1/iter-1.jsonl", At: time.Now()},
		loop.StreamMsg{N: 1, Event: harness.Event{Kind: harness.KindTool, Label: "Bash", Text: "first-run-command"}},
		loop.IterationFinished{N: 1, ExitCode: 0},
		loop.IterationStarted{N: 2, Transcript: "/tmp/ralph/e1/iter-2.jsonl", At: time.Now()},
		loop.StreamMsg{N: 2, Event: harness.Event{Kind: harness.KindTool, Label: "Bash", Text: "second-run-command"}},
	)

	view := m.View()
	if strings.Contains(view, "first-run-command") {
		t.Error("a new iteration must clear the log pane of the previous one")
	}
	if !strings.Contains(view, "second-run-command") {
		t.Errorf("the live iteration's events must be showing:\n%s", view)
	}

	// Walking up the iterations pane scopes the log to the selected run.
	m.focus = paneIters
	m = update(t, m, tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'k'}})
	if m.logView != 1 {
		t.Fatalf("log is scoped to iteration %d, want 1", m.logView)
	}
	view = m.View()
	if !strings.Contains(view, "first-run-command") {
		t.Errorf("selecting iteration 1 must show its own events:\n%s", view)
	}
	if !strings.Contains(view, "ITERATION 1") {
		t.Errorf("the pane title must name the iteration it is showing:\n%s", view)
	}

	// An iteration arriving while an earlier one is selected must not steal it.
	m = update(t, m, loop.IterationStarted{N: 3, Transcript: "/tmp/ralph/e1/iter-3.jsonl", At: time.Now()})
	if m.logView != 1 {
		t.Errorf("log jumped to iteration %d while iteration 1 was selected", m.logView)
	}

	// Returning to the newest row resumes following the live run.
	m = update(t, m, tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'G'}})
	if m.logView != 3 || !m.logFollow {
		t.Errorf("bottom of the iterations pane must follow the live run, got view %d follow %v",
			m.logView, m.logFollow)
	}
}

func TestRunInfoPopup(t *testing.T) {
	m := update(t, testModel(t, 120, 40), tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'e'}})
	if !m.showInfo {
		t.Fatal("e must open the run info popup")
	}
	view := m.View()
	for _, want := range []string{"run info", "/tmp/ralph/e1", "claude", "fable"} {
		if !strings.Contains(view, want) {
			t.Errorf("run info is missing %q:\n%s", want, view)
		}
	}

	m = update(t, m, tea.KeyMsg{Type: tea.KeyEsc})
	if m.showInfo {
		t.Error("esc must close the run info popup")
	}
}

// TestHeaderShowsTheConfiguredFailureLimit guards against the limit being
// reported as the default when the run was started with another one.
func TestHeaderShowsTheConfiguredFailureLimit(t *testing.T) {
	m := testModel(t, 120, 40)
	m.session.FailureLimit = 7
	if !strings.Contains(m.View(), "failures 0/7") {
		t.Errorf("header must report the run's own failure limit:\n%s", m.View())
	}
}

func TestViewFitsTheTerminal(t *testing.T) {
	sizes := []struct{ w, h int }{{80, 24}, {120, 40}, {200, 60}, {60, 20}}
	for _, size := range sizes {
		m := testModel(t, size.w, size.h)
		m = update(t, m,
			loop.SnapshotMsg{Snapshot: sampleSnapshot()},
			loop.IterationStarted{N: 1, Transcript: "/tmp/ralph/e1/iter-1.jsonl", At: time.Now()},
		)
		for range 40 {
			m = update(t, m, loop.StreamMsg{N: 1, Event: harness.Event{
				Kind: harness.KindTool, Label: "Bash",
				Text:   strings.Repeat("a long command line ", 20),
				Detail: "detail",
			}})
		}
		view := m.View()
		lines := strings.Split(view, "\n")
		if len(lines) > size.h {
			t.Errorf("%dx%d: view is %d lines, over the terminal height", size.w, size.h, len(lines))
		}
		for n, line := range lines {
			if w := lipgloss.Width(line); w > size.w {
				t.Errorf("%dx%d: line %d is %d cells wide: %q", size.w, size.h, n, w, line)
			}
		}
	}
}

func TestPreviewMovesAtWidthBreakpoint(t *testing.T) {
	for _, size := range []struct {
		name   string
		width  int
		bottom bool
	}{
		{name: "narrow", width: widePreviewMinWidth - 1, bottom: true},
		{name: "wide", width: widePreviewMinWidth, bottom: false},
	} {
		t.Run(size.name, func(t *testing.T) {
			m := update(t, testModel(t, size.width, 50),
				loop.SnapshotMsg{Snapshot: sampleSnapshot()},
				tea.KeyMsg{Type: tea.KeyShiftTab},
			)
			view := m.View()
			preview := lineContaining(view, "PREVIEW")
			iterations := lineContaining(view, "ITERATIONS")
			if preview < 0 || iterations < 0 {
				t.Fatalf("dashboard is missing preview or iterations:\n%s", view)
			}
			if got := preview > iterations; got != size.bottom {
				t.Errorf("preview below iterations = %v, want %v:\n%s", got, size.bottom, view)
			}
			for _, want := range []string{"Epic one", "features  1"} {
				if !strings.Contains(view, want) {
					t.Errorf("preview is missing selected board detail %q:\n%s", want, view)
				}
			}
		})
	}
}

func TestPreviewMirrorsEachFocusedPane(t *testing.T) {
	newPreviewModel := func() model {
		return update(t, testModel(t, 160, 50),
			loop.SnapshotMsg{Snapshot: sampleSnapshot()},
			loop.IterationStarted{N: 1, Transcript: "/logs/iter-1.jsonl", Prompt: "log detail", At: time.Now()},
		)
	}
	for _, tc := range []struct {
		name string
		key  tea.KeyMsg
		want string
	}{
		{name: "board", key: tea.KeyMsg{Type: tea.KeyShiftTab}, want: "features  1"},
		{name: "log", want: "log detail"},
		{name: "iterations", key: tea.KeyMsg{Type: tea.KeyTab}, want: "transcript /logs/iter-1.jsonl"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			m := newPreviewModel()
			if tc.key.Type != tea.KeyNull {
				m = update(t, m, tc.key)
			}
			if !strings.Contains(m.View(), tc.want) {
				t.Errorf("preview is missing selected detail %q:\n%s", tc.want, m.View())
			}
		})
	}
}

func TestPreviewTracksCursorAndEnterUsesTheSameDetail(t *testing.T) {
	m := update(t, testModel(t, 160, 50),
		loop.SnapshotMsg{Snapshot: sampleSnapshot()},
		tea.KeyMsg{Type: tea.KeyShiftTab},
		tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'j'}},
	)
	if !strings.Contains(m.View(), "lane      claimed") {
		t.Errorf("preview did not follow the board cursor:\n%s", m.View())
	}

	m = update(t, m, tea.KeyMsg{Type: tea.KeyEnter})
	for _, want := range []string{"DETAIL", "lane      claimed"} {
		if !strings.Contains(m.View(), want) {
			t.Errorf("Enter did not open the detail shown in the preview (%q):\n%s", want, m.View())
		}
	}
}

func lineContaining(view, want string) int {
	for i, line := range strings.Split(view, "\n") {
		if strings.Contains(line, want) {
			return i
		}
	}
	return -1
}

func TestBoardPaneShowsFeaturesTasksAndReady(t *testing.T) {
	m := update(t, testModel(t, 160, 50), loop.SnapshotMsg{Snapshot: sampleSnapshot()})
	var summaries []string
	for _, it := range m.panes[paneBoard].items {
		summaries = append(summaries, it.summary)
	}
	joined := strings.Join(summaries, "\n")
	for _, want := range []string{"Epic one", "f1", "Claimed feature", "t1", "Do the thing", "r1"} {
		if !strings.Contains(joined, want) {
			t.Errorf("board pane is missing %q:\n%s", want, joined)
		}
	}
}

func TestIterationHistoryRecordsEachRun(t *testing.T) {
	m := update(t, testModel(t, 160, 50),
		loop.IterationStarted{N: 1, Transcript: "/logs/iter-1.jsonl", At: time.Now()},
		loop.StreamMsg{N: 1, Event: harness.Event{Kind: harness.KindTool, Label: "Bash", Text: "git status"}},
		loop.StreamMsg{N: 1, Event: harness.Event{Kind: harness.KindText, Text: "Claimed the card"}},
		loop.IterationFinished{
			N: 1, ExitCode: 0, Duration: 90 * time.Second, Final: "all done",
			Stats: &harness.Stats{Turns: 4, CostUSD: 0.5},
		},
	)
	items := m.panes[paneIters].items
	if len(items) != 1 {
		t.Fatalf("iterations = %d, want 1", len(items))
	}
	if !strings.Contains(items[0].summary, "Claimed the card") {
		t.Errorf("row should summarise the last thing the agent said: %q", items[0].summary)
	}
	for _, want := range []string{"/logs/iter-1.jsonl", "tool calls 1", "all done", "$0.50"} {
		if !strings.Contains(items[0].detail, want) {
			t.Errorf("expanded iteration is missing %q:\n%s", want, items[0].detail)
		}
	}
}

func TestLogTailsUntilTheReaderScrollsUp(t *testing.T) {
	m := testModel(t, 120, 40)
	for range 10 {
		m = update(t, m, loop.NoticeMsg{Text: "tick"})
	}
	if got := m.panes[paneLog].cursor; got != 9 {
		t.Fatalf("cursor = %d, want the newest line", got)
	}
	m = update(t, m, tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{'k'}})
	m = update(t, m, loop.NoticeMsg{Text: "tick"})
	if got := m.panes[paneLog].cursor; got != 8 {
		t.Errorf("cursor = %d; scrolling up must stop the tail", got)
	}
}

func sampleSnapshot() board.Snapshot {
	return board.Snapshot{
		Epic: board.Strand{ID: "e1", Title: "Epic one", State: board.StateActive},
		Features: []board.Card{{
			ID: "f1", Title: "Claimed feature", Lane: "claimed", Priority: "p2",
			Owner: "opus", Branch: "f1-work", State: "active",
			Tasks: []board.Task{{ID: "t1", Title: "Do the thing", Status: "doing", State: "active"}},
			Ready: []board.Strand{{ID: "r1", Title: "Ready work", State: "active"}},
		}},
		TakenAt: time.Now(),
	}
}
