// Package loop drives an epic through repeated headless agent runs.
//
// The engine only ever reads state: it re-checks the epic gate, starts one
// harness run per iteration, and streams what that run does. Every mutation —
// claiming cards, committing, closing the epic — happens inside the agent runs
// themselves. Callers observe progress on Msgs and stop the engine with
// SoftStop (finish the current iteration) or HardStop (kill the run now).
package loop

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"millstrand-ralph/internal/board"
	"millstrand-ralph/internal/harness"
)

// Process exit codes. They are the loop's contract with whoever runs it.
const (
	// ExitOK covers every clean finish: the epic went inactive, or the
	// operator asked for a soft stop and the iteration completed.
	ExitOK = 0
	// ExitError covers harness failures, exhausted iterations, and any
	// unexpected state.
	ExitError = 1
	// ExitUsage is invalid invocation.
	ExitUsage = 2
	// ExitBrake is the agent's own emergency brake.
	ExitBrake = 3
	// ExitStopped is an operator hard stop, mid-run.
	ExitStopped = 130
)

// Reason names why the loop ended.
type Reason string

// Loop stop reasons.
const (
	ReasonEpicInactive  Reason = "epic-inactive"
	ReasonMaxIterations Reason = "max-iterations"
	ReasonFailures      Reason = "consecutive-failures"
	ReasonBrake         Reason = "brake"
	ReasonGate          Reason = "gate"
	ReasonSoftStop      Reason = "soft-stop"
	ReasonHardStop      Reason = "hard-stop"
	ReasonError         Reason = "error"
)

// Outcome is the loop's final word.
type Outcome struct {
	Reason     Reason
	Detail     string
	Iterations int
	ExitCode   int
}

// Msg is anything the engine reports while running.
type Msg any

// SnapshotMsg carries a board poll, or the error that poll hit.
type SnapshotMsg struct {
	Snapshot board.Snapshot
	Err      error
}

// IterationStarted announces a new agent run.
type IterationStarted struct {
	N          int
	Transcript string
	At         time.Time
	Prompt     string
}

// StreamMsg is one normalised event from the running agent.
type StreamMsg struct {
	N     int
	Event harness.Event
}

// IterationFinished closes out an agent run.
type IterationFinished struct {
	N        int
	ExitCode int
	Duration time.Duration
	Stats    *harness.Stats
	Final    string
	Err      error
}

// NoticeMsg is ralph's own commentary about the loop.
type NoticeMsg struct {
	Text  string
	Error bool
}

// OutcomeMsg is the last message on the channel.
type OutcomeMsg struct {
	Outcome Outcome
}

// Config is everything the engine needs for a run.
type Config struct {
	Epic            string
	Harness         harness.Harness
	Settings        harness.Settings
	SkipPermissions bool
	FullAuth        bool
	Extra           []string
	MaxIterations   int
	FailureLimit    int
	LogDir          string
	Board           board.Client
	// Pause is the breather between iterations; it keeps a crash-looping
	// harness from hot-looping.
	Pause time.Duration
	// PollInterval is how often the board snapshot refreshes.
	PollInterval time.Duration
}

// Engine runs the loop.
type Engine struct {
	cfg  Config
	msgs chan Msg

	mu  sync.Mutex
	cmd *exec.Cmd

	soft      atomic.Bool
	hard      atomic.Bool
	hardCh    chan struct{}
	refreshCh chan struct{}
	once      sync.Once
}

// New builds an engine. Zero-valued knobs take the defaults the bash loops used.
func New(cfg Config) *Engine {
	if cfg.FailureLimit <= 0 {
		cfg.FailureLimit = 3
	}
	if cfg.Pause <= 0 {
		cfg.Pause = 3 * time.Second
	}
	if cfg.PollInterval <= 0 {
		cfg.PollInterval = 10 * time.Second
	}
	return &Engine{
		cfg:       cfg,
		msgs:      make(chan Msg, 2048),
		hardCh:    make(chan struct{}),
		refreshCh: make(chan struct{}, 1),
	}
}

// Refresh asks for an immediate board poll. Extra requests while one is already
// pending are dropped rather than queued.
func (e *Engine) Refresh() {
	select {
	case e.refreshCh <- struct{}{}:
	default:
	}
}

// Msgs is the engine's output stream. It closes when Run returns.
func (e *Engine) Msgs() <-chan Msg { return e.msgs }

// SoftStopped reports whether a soft stop is armed.
func (e *Engine) SoftStopped() bool { return e.soft.Load() }

// SoftStop asks the loop to exit once the current iteration finishes.
func (e *Engine) SoftStop() { e.soft.Store(true) }

// CancelSoftStop disarms a soft stop that has not taken effect yet.
func (e *Engine) CancelSoftStop() { e.soft.Store(false) }

// HardStop kills the running agent and ends the loop now.
func (e *Engine) HardStop() {
	if !e.hard.CompareAndSwap(false, true) {
		return
	}
	e.once.Do(func() { close(e.hardCh) })
	e.killChild()
}

func (e *Engine) killChild() {
	e.mu.Lock()
	cmd := e.cmd
	e.mu.Unlock()
	if cmd == nil || cmd.Process == nil {
		return
	}
	// The child runs in its own process group so the whole agent tree goes
	// down with it; killing the leader alone would orphan its tool processes.
	pgid := cmd.Process.Pid
	_ = syscall.Kill(-pgid, syscall.SIGTERM)
	go func() {
		time.Sleep(3 * time.Second)
		_ = syscall.Kill(-pgid, syscall.SIGKILL)
	}()
}

func (e *Engine) emit(m Msg) {
	select {
	case e.msgs <- m:
	default:
		// A stalled consumer must never wedge the loop; the transcript on
		// disk stays complete either way.
	}
}

// Run drives the loop until it stops, then returns the outcome. The message
// channel closes before Run returns.
func (e *Engine) Run(ctx context.Context) Outcome {
	pollCtx, stopPoll := context.WithCancel(ctx)
	var polling sync.WaitGroup
	polling.Add(1)
	go func() {
		defer polling.Done()
		e.poll(pollCtx)
	}()

	outcome := e.run(ctx)

	// The poller has to be joined before the channel closes: cancelling its
	// context alone leaves it free to send one last snapshot.
	stopPoll()
	polling.Wait()
	// The outcome is the one message a renderer cannot afford to lose, so it
	// blocks rather than being dropped when a slow consumer has filled the
	// buffer. Both renderers drain until the channel closes.
	e.msgs <- OutcomeMsg{Outcome: outcome}
	close(e.msgs)
	return outcome
}

func (e *Engine) poll(ctx context.Context) {
	tick := time.NewTicker(e.cfg.PollInterval)
	defer tick.Stop()
	for {
		snap, err := e.cfg.Board.Snapshot(ctx, e.cfg.Epic)
		if ctx.Err() != nil {
			return
		}
		e.emit(SnapshotMsg{Snapshot: snap, Err: err})
		select {
		case <-ctx.Done():
			return
		case <-e.refreshCh:
		case <-tick.C:
		}
	}
}

func (e *Engine) run(ctx context.Context) Outcome {
	iteration := 0
	failures := 0

	for {
		if ctx.Err() != nil {
			return Outcome{Reason: ReasonHardStop, Detail: "cancelled", Iterations: iteration, ExitCode: ExitStopped}
		}
		if e.hard.Load() {
			return Outcome{Reason: ReasonHardStop, Detail: "operator hard stop", Iterations: iteration, ExitCode: ExitStopped}
		}

		// Re-check the gate immediately before each model prompt: withdrawing
		// the ralph label is how a human stops the loop from outside.
		epic, err := e.cfg.Board.Gate(ctx, e.cfg.Epic)
		if err != nil {
			if errors.Is(err, board.ErrGate) {
				return Outcome{Reason: ReasonGate, Detail: err.Error(), Iterations: iteration, ExitCode: ExitError}
			}
			return Outcome{Reason: ReasonError, Detail: err.Error(), Iterations: iteration, ExitCode: ExitError}
		}
		if epic.State != board.StateActive {
			return Outcome{
				Reason:     ReasonEpicInactive,
				Detail:     fmt.Sprintf("epic %s is %s", e.cfg.Epic, epic.State),
				Iterations: iteration,
				ExitCode:   ExitOK,
			}
		}
		if e.soft.Load() {
			return Outcome{Reason: ReasonSoftStop, Detail: "operator soft stop", Iterations: iteration, ExitCode: ExitOK}
		}
		if e.cfg.MaxIterations != 0 && iteration >= e.cfg.MaxIterations {
			return Outcome{
				Reason:     ReasonMaxIterations,
				Detail:     fmt.Sprintf("hit --max-iterations=%d with the epic still active", e.cfg.MaxIterations),
				Iterations: iteration,
				ExitCode:   ExitError,
			}
		}

		iteration++
		res := e.iterate(ctx, iteration, epic)
		e.emit(IterationFinished{
			N: iteration, ExitCode: res.exitCode, Duration: res.duration,
			Stats: res.stats, Final: res.final, Err: res.err,
		})

		switch {
		case e.hard.Load():
			return Outcome{Reason: ReasonHardStop, Detail: "operator hard stop", Iterations: iteration, ExitCode: ExitStopped}
		case res.err != nil:
			return Outcome{Reason: ReasonError, Detail: res.err.Error(), Iterations: iteration, ExitCode: ExitError}
		case res.exitCode != 0:
			failures++
			e.emit(NoticeMsg{
				Text:  fmt.Sprintf("%s exited %d (failure %d/%d)", e.cfg.Harness.Name(), res.exitCode, failures, e.cfg.FailureLimit),
				Error: true,
			})
			if failures >= e.cfg.FailureLimit {
				return Outcome{
					Reason:     ReasonFailures,
					Detail:     fmt.Sprintf("%d consecutive failed runs", failures),
					Iterations: iteration,
					ExitCode:   ExitError,
				}
			}
		default:
			failures = 0
			reason, pulled, err := harness.Brake(res.final)
			if err != nil {
				return Outcome{Reason: ReasonError, Detail: err.Error(), Iterations: iteration, ExitCode: ExitError}
			}
			if pulled {
				return Outcome{Reason: ReasonBrake, Detail: reason, Iterations: iteration, ExitCode: ExitBrake}
			}
		}

		if !e.wait(ctx, e.cfg.Pause) {
			return Outcome{Reason: ReasonHardStop, Detail: "operator hard stop", Iterations: iteration, ExitCode: ExitStopped}
		}
	}
}

// wait sleeps between iterations, returning false if the loop was interrupted.
func (e *Engine) wait(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-e.hardCh:
		return false
	case <-ctx.Done():
		return false
	}
}

type iterResult struct {
	exitCode int
	duration time.Duration
	stats    *harness.Stats
	final    string
	err      error
}

func (e *Engine) iterate(ctx context.Context, n int, epic board.Strand) iterResult {
	spec := harness.RunSpec{
		Prompt:          harness.Prompt(epic.ID, epic.Title, n, e.cfg.FullAuth),
		Iteration:       n,
		LogDir:          e.cfg.LogDir,
		SkipPermissions: e.cfg.SkipPermissions,
		Extra:           e.cfg.Extra,
	}
	started := time.Now()
	e.emit(IterationStarted{N: n, Transcript: spec.TranscriptPath(), At: started, Prompt: spec.Prompt})

	transcript, err := os.Create(spec.TranscriptPath())
	if err != nil {
		return iterResult{err: fmt.Errorf("cannot open transcript: %w", err), duration: time.Since(started)}
	}
	defer func() { _ = transcript.Close() }()

	stderrPath := strings.TrimSuffix(spec.TranscriptPath(), ".jsonl") + ".stderr"
	stderr, err := os.Create(stderrPath)
	if err != nil {
		return iterResult{err: fmt.Errorf("cannot open stderr log: %w", err), duration: time.Since(started)}
	}
	defer func() { _ = stderr.Close() }()

	cmd := exec.Command(e.cfg.Harness.Binary(), e.cfg.Harness.Args(spec, e.cfg.Settings)...)
	// Own process group: a hard stop takes the agent's whole tool tree down,
	// and the agent never receives the terminal's own signals.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Stderr = stderr
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return iterResult{err: fmt.Errorf("cannot pipe %s stdout: %w", e.cfg.Harness.Name(), err), duration: time.Since(started)}
	}
	if err := cmd.Start(); err != nil {
		return iterResult{err: fmt.Errorf("cannot start %s: %w", e.cfg.Harness.Name(), err), duration: time.Since(started)}
	}

	e.mu.Lock()
	e.cmd = cmd
	e.mu.Unlock()
	// A hard stop raised between Start and here would have found no child.
	if e.hard.Load() {
		e.killChild()
	}
	reaped := make(chan struct{})
	defer close(reaped)
	go func() {
		select {
		case <-ctx.Done():
			e.killChild()
		case <-reaped:
		}
	}()

	var stats *harness.Stats
	var streamFinal string
	reader := bufio.NewReaderSize(stdout, 1<<20)
	for {
		line, err := readLine(reader)
		if len(line) > 0 {
			if _, werr := transcript.Write(append(line, '\n')); werr != nil {
				e.emit(NoticeMsg{Text: "transcript write failed: " + werr.Error(), Error: true})
			}
			for _, ev := range e.cfg.Harness.Decode(line) {
				if ev.Stats != nil {
					stats = ev.Stats
				}
				if ev.Final {
					streamFinal = ev.Detail
				}
				e.emit(StreamMsg{N: n, Event: ev})
			}
		}
		if err != nil {
			break
		}
	}

	waitErr := cmd.Wait()
	e.mu.Lock()
	e.cmd = nil
	e.mu.Unlock()

	res := iterResult{duration: time.Since(started), stats: stats}
	res.exitCode = exitCode(waitErr)
	if res.exitCode == 0 {
		final, err := e.cfg.Harness.FinalMessage(spec, streamFinal)
		if err != nil {
			res.err = err
			return res
		}
		res.final = final
	}
	return res
}

// readLine returns one line without its terminator, growing past the reader's
// buffer for the long JSON payloads a tool call can produce.
func readLine(r *bufio.Reader) ([]byte, error) {
	var buf []byte
	for {
		chunk, err := r.ReadSlice('\n')
		buf = append(buf, chunk...)
		if errors.Is(err, bufio.ErrBufferFull) {
			continue
		}
		return []byte(strings.TrimRight(string(buf), "\r\n")), err
	}
}

func exitCode(err error) int {
	if err == nil {
		return 0
	}
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		return exitErr.ExitCode()
	}
	if errors.Is(err, io.EOF) {
		return 0
	}
	return 1
}
