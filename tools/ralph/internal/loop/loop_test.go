package loop_test

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"millstrand-ralph/internal/board"
	"millstrand-ralph/internal/harness"
	"millstrand-ralph/internal/loop"
)

// scriptHarness runs a shell script in place of a real agent. It borrows
// Claude's stream decoder, so a test's fixture lines are ordinary stream-json.
type scriptHarness struct {
	harness.Claude
	bin string
}

func (h scriptHarness) Binary() string { return h.bin }

// Args hands the script its iteration number and the shared fixture directory;
// the prompt itself is not what these tests are about.
func (h scriptHarness) Args(spec harness.RunSpec, _ harness.Settings) []string {
	return []string{strconv.Itoa(spec.Iteration), spec.LogDir}
}

// world is one test's fake strand plus fake agent.
type world struct {
	t      *testing.T
	dir    string
	client board.Client
	agent  scriptHarness
	logDir string
}

const epicActive = `{"id":"e1","title":"Epic one","state":"active","attributes":{"kanban/type":"epic","kanban.label/ralph":"true"}}`

func newWorld(t *testing.T, agentScript string) *world {
	t.Helper()
	dir := t.TempDir()
	w := &world{t: t, dir: dir, logDir: filepath.Join(dir, "logs")}
	if err := os.MkdirAll(w.logDir, 0o755); err != nil {
		t.Fatal(err)
	}

	w.write("resp_show_e1.json", epicActive)
	w.write("resp_kanban_board.json", `{"claimed":[],"in_review":[],"pending":[],"refinement":[]}`)

	strandScript := fmt.Sprintf(`#!/bin/sh
while [ "$1" = "--workspace" ]; do shift 2; done
key="$(printf '%%s' "$*" | tr ' /' '__')"
dir=%q
if [ -f "$dir/override_$key.json" ]; then cat "$dir/override_$key.json"; exit 0; fi
if [ -f "$dir/resp_$key.json" ]; then cat "$dir/resp_$key.json"; exit 0; fi
echo "fake strand: unexpected call: $*" >&2
exit 1
`, dir)
	strandBin := filepath.Join(dir, "strand")
	if err := os.WriteFile(strandBin, []byte(strandScript), 0o700); err != nil {
		t.Fatal(err)
	}
	w.client = board.Client{Bin: strandBin}

	// $1 is the iteration, $2 the log dir, $DIR the fixture root.
	agentBin := filepath.Join(dir, "agent")
	body := "#!/bin/sh\nDIR=" + strconv.Quote(dir) + "\n" + agentScript
	if err := os.WriteFile(agentBin, []byte(body), 0o700); err != nil {
		t.Fatal(err)
	}
	w.agent = scriptHarness{bin: agentBin}
	return w
}

func (w *world) write(name, body string) {
	w.t.Helper()
	if err := os.WriteFile(filepath.Join(w.dir, name), []byte(body), 0o600); err != nil {
		w.t.Fatal(err)
	}
}

func (w *world) engine(cfg loop.Config) *loop.Engine {
	cfg.Epic = "e1"
	cfg.Harness = w.agent
	cfg.Board = w.client
	cfg.LogDir = w.logDir
	if cfg.Pause == 0 {
		cfg.Pause = time.Millisecond
	}
	if cfg.PollInterval == 0 {
		// Only the opening poll should run; most tests are not about refreshes.
		cfg.PollInterval = time.Hour
	}
	return loop.New(cfg)
}

// drive runs the engine to completion, calling watch for every message. watch
// is how a test arms a stop at a known point in the run.
func drive(t *testing.T, e *loop.Engine, watch func(loop.Msg)) (loop.Outcome, []loop.Msg) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	outcomes := make(chan loop.Outcome, 1)
	go func() { outcomes <- e.Run(ctx) }()

	var seen []loop.Msg
	for msg := range e.Msgs() {
		seen = append(seen, msg)
		if watch != nil {
			watch(msg)
		}
	}
	select {
	case out := <-outcomes:
		return out, seen
	case <-ctx.Done():
		t.Fatal("engine did not return after closing its message channel")
		return loop.Outcome{}, seen
	}
}

func TestEpicClosesEndsTheLoop(t *testing.T) {
	// The agent "closes the epic" the way a real one does: it changes what
	// strand reports, and the next gate check sees it.
	w := newWorld(t, `
cp "$DIR/closed.json" "$DIR/override_show_e1.json"
printf '%s\n' '{"type":"result","subtype":"success","result":"epic closed"}'
`)
	w.write("closed.json", `{"id":"e1","title":"Epic one","state":"closed","attributes":{"kanban/type":"epic","kanban.label/ralph":"true"}}`)

	out, _ := drive(t, w.engine(loop.Config{MaxIterations: 5}), nil)
	if out.Reason != loop.ReasonEpicInactive || out.ExitCode != loop.ExitOK {
		t.Fatalf("outcome = %+v", out)
	}
	if out.Iterations != 1 {
		t.Errorf("iterations = %d, want 1", out.Iterations)
	}
}

func TestBrakeStopsTheLoop(t *testing.T) {
	w := newWorld(t, `printf '%s\n' '{"type":"result","subtype":"success","result":"tried everything\nRALPH-STOP: the weaver is down"}'`)

	out, _ := drive(t, w.engine(loop.Config{MaxIterations: 5}), nil)
	if out.Reason != loop.ReasonBrake || out.ExitCode != loop.ExitBrake {
		t.Fatalf("outcome = %+v", out)
	}
	if out.Detail != "the weaver is down" {
		t.Errorf("detail = %q, want the brake reason", out.Detail)
	}
}

func TestConsecutiveFailuresStopTheLoop(t *testing.T) {
	w := newWorld(t, `printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"text","text":"starting"}]}}'
exit 7`)

	out, msgs := drive(t, w.engine(loop.Config{MaxIterations: 0}), nil)
	if out.Reason != loop.ReasonFailures || out.ExitCode != loop.ExitError {
		t.Fatalf("outcome = %+v", out)
	}
	if out.Iterations != 3 {
		t.Errorf("iterations = %d, want the default failure limit of 3", out.Iterations)
	}
	var codes []int
	for _, msg := range msgs {
		if fin, ok := msg.(loop.IterationFinished); ok {
			codes = append(codes, fin.ExitCode)
		}
	}
	if len(codes) != 3 || codes[0] != 7 {
		t.Errorf("iteration exit codes = %v, want three 7s", codes)
	}
}

func TestMaxIterationsStopsTheLoop(t *testing.T) {
	w := newWorld(t, `printf '%s\n' '{"type":"result","subtype":"success","result":"still working"}'`)

	out, _ := drive(t, w.engine(loop.Config{MaxIterations: 2}), nil)
	if out.Reason != loop.ReasonMaxIterations || out.ExitCode != loop.ExitError {
		t.Fatalf("outcome = %+v", out)
	}
	if out.Iterations != 2 {
		t.Errorf("iterations = %d, want 2", out.Iterations)
	}
}

func TestWithdrawnRalphLabelStopsTheLoop(t *testing.T) {
	w := newWorld(t, `
cp "$DIR/unlabelled.json" "$DIR/override_show_e1.json"
printf '%s\n' '{"type":"result","subtype":"success","result":"carrying on"}'
`)
	w.write("unlabelled.json", `{"id":"e1","title":"Epic one","state":"active","attributes":{"kanban/type":"epic"}}`)

	out, _ := drive(t, w.engine(loop.Config{MaxIterations: 5}), nil)
	if out.Reason != loop.ReasonGate || out.ExitCode != loop.ExitError {
		t.Fatalf("outcome = %+v", out)
	}
	if !strings.Contains(out.Detail, "kanban.label/ralph") {
		t.Errorf("detail = %q, want it to name the withdrawn label", out.Detail)
	}
}

func TestSoftStopFinishesTheIterationFirst(t *testing.T) {
	w := newWorld(t, `printf '%s\n' '{"type":"result","subtype":"success","result":"iteration done"}'`)
	e := w.engine(loop.Config{MaxIterations: 10})

	var finished bool
	out, _ := drive(t, e, func(msg loop.Msg) {
		switch msg.(type) {
		case loop.IterationStarted:
			// Arming mid-run must not interrupt the iteration in flight.
			e.SoftStop()
		case loop.IterationFinished:
			finished = true
		}
	})
	if out.Reason != loop.ReasonSoftStop || out.ExitCode != loop.ExitOK {
		t.Fatalf("outcome = %+v", out)
	}
	if !finished {
		t.Error("the running iteration must complete before a soft stop takes effect")
	}
	if out.Iterations != 1 {
		t.Errorf("iterations = %d, want 1", out.Iterations)
	}
}

func TestHardStopKillsTheRunningAgent(t *testing.T) {
	// The agent announces itself and then hangs; only a kill ends it.
	w := newWorld(t, `
printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"text","text":"working"}]}}'
while true; do sleep 1; done
`)
	e := w.engine(loop.Config{MaxIterations: 10})

	out, _ := drive(t, e, func(msg loop.Msg) {
		if _, ok := msg.(loop.StreamMsg); ok {
			e.HardStop()
		}
	})
	if out.Reason != loop.ReasonHardStop || out.ExitCode != loop.ExitStopped {
		t.Fatalf("outcome = %+v", out)
	}
}

// Board polling runs beside the iteration loop and must not disturb it. Run
// under -race, this also exercises the shutdown path where a poll is in flight
// as the loop ends; the engine joins the poller before closing its channel,
// since cancelling its context alone would leave it free to send one more
// snapshot into a closed channel.
func TestFrequentPollingDoesNotDisturbTheLoop(t *testing.T) {
	w := newWorld(t, `printf '%s\n' '{"type":"result","subtype":"success","result":"still working"}'`)
	out, _ := drive(t, w.engine(loop.Config{MaxIterations: 3, PollInterval: time.Millisecond}), nil)
	if out.Reason != loop.ReasonMaxIterations {
		t.Fatalf("outcome = %+v", out)
	}
}

func TestTranscriptKeepsEveryStreamLine(t *testing.T) {
	w := newWorld(t, `
printf '%s\n' '{"type":"system","subtype":"init","session_id":"abc"}'
printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"text","text":"hello"}]}}'
printf '%s\n' '{"type":"result","subtype":"success","result":"done"}'
`)

	out, _ := drive(t, w.engine(loop.Config{MaxIterations: 1}), nil)
	if out.Iterations != 1 {
		t.Fatalf("outcome = %+v", out)
	}
	body, err := os.ReadFile(filepath.Join(w.logDir, "iter-1.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(body)), "\n")
	if len(lines) != 3 {
		t.Fatalf("transcript has %d lines, want all 3 including the system line the UI ignores", len(lines))
	}
	if !strings.Contains(lines[0], `"session_id":"abc"`) {
		t.Errorf("first transcript line = %q", lines[0])
	}
}
