// Package ui renders a ralph run: a full-screen Bubble Tea dashboard, or a
// plain line-oriented stream for scripted use.
package ui

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	tea "github.com/charmbracelet/bubbletea"

	"millstrand-ralph/internal/board"
	"millstrand-ralph/internal/harness"
	"millstrand-ralph/internal/loop"
)

// Session is everything a renderer needs to describe the run it is showing.
type Session struct {
	Engine        *loop.Engine
	Epic          board.Strand
	HarnessName   string
	Settings      harness.Settings
	MaxIterations int
	FailureLimit  int
	LogDir        string
	Workspace     string
	SkipPerms     bool
}

// signalStopMsg arrives when the process is signalled from outside the
// terminal, so a SIGINT lands on the same stop prompt as ctrl-c.
type signalStopMsg struct{ hard bool }

// Run opens the dashboard and returns the loop's exit code.
func Run(ctx context.Context, s Session) (int, error) {
	engineCtx, cancelEngine := context.WithCancel(ctx)
	defer cancelEngine()

	outcomes := make(chan loop.Outcome, 1)
	go func() { outcomes <- s.Engine.Run(engineCtx) }()

	// Bubble Tea's own signal handling would quit the program on SIGINT, which
	// is exactly what must not happen to a live agent run.
	program := tea.NewProgram(newModel(s),
		tea.WithAltScreen(),
		tea.WithoutSignalHandler(),
		tea.WithContext(ctx),
	)

	go func() {
		for msg := range s.Engine.Msgs() {
			program.Send(msg)
		}
	}()

	signals := make(chan os.Signal, 2)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(signals)
	go func() {
		for sig := range signals {
			program.Send(signalStopMsg{hard: sig == syscall.SIGTERM})
		}
	}()

	final, err := program.Run()
	if err != nil {
		cancelEngine()
		return loop.ExitError, err
	}

	// The UI can only leave after the loop has stopped or been asked to; wait
	// for the engine's own word on the exit code.
	if m, ok := final.(model); ok && m.outcome != nil {
		return m.outcome.ExitCode, nil
	}
	cancelEngine()
	select {
	case out := <-outcomes:
		return out.ExitCode, nil
	case <-time.After(10 * time.Second):
		return loop.ExitError, nil
	}
}

// RunHeadless streams the same run as plain lines. The first interrupt arms a
// soft stop and the second kills the agent, so a stray ctrl-c never ends an
// iteration mid-flight.
func RunHeadless(ctx context.Context, s Session) int {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	signals := make(chan os.Signal, 2)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(signals)
	go func() {
		interrupted := false
		for sig := range signals {
			switch {
			case sig == syscall.SIGTERM || interrupted:
				fmt.Println("[ralph] hard stop: killing the agent run")
				s.Engine.HardStop()
			default:
				interrupted = true
				fmt.Println("[ralph] soft stop armed — the loop ends when this iteration finishes (interrupt again to kill it now)")
				s.Engine.SoftStop()
			}
		}
	}()

	go func() { s.Engine.Run(ctx) }() //nolint:errcheck // the outcome arrives on the message channel

	exit := loop.ExitError
	for msg := range s.Engine.Msgs() {
		switch msg := msg.(type) {
		case loop.IterationStarted:
			fmt.Printf("\n[ralph] iteration %d — epic %s (%s) | transcript: %s\n\n",
				msg.N, s.Epic.ID, s.Epic.Title, msg.Transcript)
		case loop.StreamMsg:
			switch msg.Event.Kind {
			case harness.KindText:
				fmt.Println(msg.Event.Detail)
			case harness.KindTool:
				fmt.Printf("⏺ %s %s\n", msg.Event.Label, msg.Event.Text)
			case harness.KindResult:
				fmt.Printf("\n[ralph] %s\n", msg.Event.Text)
			case harness.KindError:
				fmt.Fprintf(os.Stderr, "[ralph] %s\n", msg.Event.Text)
			}
		case loop.NoticeMsg:
			if msg.Error {
				fmt.Fprintf(os.Stderr, "[ralph] %s\n", msg.Text)
			} else {
				fmt.Printf("[ralph] %s\n", msg.Text)
			}
		case loop.IterationFinished:
			if msg.Err != nil {
				fmt.Fprintf(os.Stderr, "[ralph] iteration %d failed: %v\n", msg.N, msg.Err)
			}
		case loop.SnapshotMsg:
			if msg.Err != nil {
				fmt.Fprintf(os.Stderr, "[ralph] board poll failed: %v\n", msg.Err)
			}
		case loop.OutcomeMsg:
			exit = msg.Outcome.ExitCode
			out := os.Stdout
			if exit != loop.ExitOK {
				out = os.Stderr
			}
			_, _ = fmt.Fprintf(out, "\n[ralph] %s after %d iteration(s): %s\n",
				msg.Outcome.Reason, msg.Outcome.Iterations, msg.Outcome.Detail)
		}
	}
	return exit
}
