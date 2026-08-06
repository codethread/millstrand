// Command ralph drives a kanban epic through repeated headless agent runs.
//
// Each iteration hands the epic to a fresh agent process, streams what it does,
// and keeps the raw transcript on disk. The loop ends when the epic strand goes
// inactive, when the agent pulls its emergency brake, when runs keep failing,
// when the iteration cap is hit, or when the operator stops it.
//
// This binary is repo-local development tooling. It is a separate Go module on
// purpose: it ships with no Millstrand release and belongs to no spool.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"millstrand-ralph/internal/board"
	"millstrand-ralph/internal/harness"
	"millstrand-ralph/internal/loop"
	"millstrand-ralph/internal/ui"
)

func main() {
	code, err := run()
	if err != nil {
		fmt.Fprintln(os.Stderr, "ralph: "+err.Error())
	}
	os.Exit(code)
}

type options struct {
	harnessName     string
	model           string
	effort          string
	maxIterations   int
	failureLimit    int
	logDir          string
	workspace       string
	strandBin       string
	skipPermissions bool
	fullAuth        bool
	headless        bool
	poll            time.Duration
	pause           time.Duration
}

func run() (int, error) {
	// Environment defaults are validated before they reach a flag default: a
	// typo in RALPH_MAX_ITERATIONS must stop the loop, not quietly restore 30.
	maxIterations, err := envInt("RALPH_MAX_ITERATIONS", 30)
	if err != nil {
		return loop.ExitUsage, err
	}
	skipPermissions, err := envBool("RALPH_SKIP_PERMISSIONS", true)
	if err != nil {
		return loop.ExitUsage, err
	}

	opts := options{}
	fs := flag.NewFlagSet("ralph", flag.ContinueOnError)
	fs.Usage = func() {
		out := fs.Output()
		_, _ = fmt.Fprint(out, "usage: ralph [flags] <epic-id> [-- <extra harness args>]\n\n"+
			"Drive a kanban epic through repeated headless agent runs.\n\nflags:\n")
		fs.PrintDefaults()
		_, _ = fmt.Fprint(out, "\nenvironment: RALPH_HARNESS, RALPH_MODEL, RALPH_EFFORT, RALPH_MAX_ITERATIONS,\n"+
			"             RALPH_SKIP_PERMISSIONS, RALPH_LOG_DIR, MILLSTRAND_WORKSPACE\n"+
			"\nkeys: tab panes · enter expand · e run info · s soft stop · x hard stop · ? help · q stop/quit\n"+
			"\nexit codes: 0 clean stop, 1 failure, 2 usage, 3 agent brake, 130 hard stop\n")
	}
	fs.StringVar(&opts.harnessName, "harness", envOr("RALPH_HARNESS", "claude"),
		"agent harness to run: "+strings.Join(harness.Names(), "|"))
	fs.StringVar(&opts.model, "model", os.Getenv("RALPH_MODEL"), "model for each run (harness default when empty)")
	fs.StringVar(&opts.effort, "effort", os.Getenv("RALPH_EFFORT"), "reasoning effort (harness default when empty)")
	fs.IntVar(&opts.maxIterations, "max-iterations", maxIterations, "safety cap; 0 means unlimited")
	fs.IntVar(&opts.failureLimit, "failure-limit", 3, "stop after this many consecutive failed runs")
	fs.StringVar(&opts.logDir, "log-dir", os.Getenv("RALPH_LOG_DIR"), "transcript dir (default $TMPDIR/ralph/<epic>-<timestamp>)")
	fs.StringVar(&opts.workspace, "workspace", os.Getenv("MILLSTRAND_WORKSPACE"), "strand workspace dir (default: the repo's own)")
	fs.StringVar(&opts.strandBin, "strand", "", "strand binary (default: ./bin/strand beside this binary, else PATH)")
	fs.BoolVar(&opts.skipPermissions, "skip-permissions", skipPermissions,
		"bypass the harness's permission prompts (a headless run cannot answer them)")
	fs.BoolVar(&opts.fullAuth, "full-auth", false,
		"append an operator authority grant to the prompt (rebuild/restart mill and weavers, bump sibling spools)")
	fs.BoolVar(&opts.headless, "headless", false, "stream plain text instead of opening the TUI")
	fs.DurationVar(&opts.poll, "poll", 10*time.Second, "board refresh interval")
	fs.DurationVar(&opts.pause, "pause", 3*time.Second, "breather between iterations")

	if err := fs.Parse(os.Args[1:]); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return loop.ExitOK, nil
		}
		return loop.ExitUsage, nil
	}

	args := fs.Args()
	if len(args) < 1 {
		fs.Usage()
		return loop.ExitUsage, nil
	}
	epicID := args[0]
	rest := args[1:]

	var extra []string
	if len(rest) > 0 {
		if rest[0] != "--" {
			fs.Usage()
			return loop.ExitUsage, fmt.Errorf("unexpected argument %q; pass extra harness args after --", rest[0])
		}
		extra = rest[1:]
	}

	agent, err := harness.Lookup(opts.harnessName)
	if err != nil {
		return loop.ExitUsage, err
	}
	if _, err := exec.LookPath(agent.Binary()); err != nil {
		return loop.ExitError, fmt.Errorf("%s not found on PATH", agent.Binary())
	}
	settings, err := agent.Resolve(opts.model, opts.effort)
	if err != nil {
		return loop.ExitUsage, err
	}

	strandBin, err := resolveStrand(opts.strandBin)
	if err != nil {
		return loop.ExitError, err
	}
	client := board.Client{Bin: strandBin, Workspace: opts.workspace}

	ctx := context.Background()
	epic, err := client.Gate(ctx, epicID)
	if err != nil {
		return loop.ExitError, err
	}
	if epic.State != board.StateActive {
		fmt.Printf("ralph: epic %s (%s) is already %s; nothing to do\n", epic.ID, epic.Title, epic.State)
		return loop.ExitOK, nil
	}

	logDir := opts.logDir
	if logDir == "" {
		logDir = filepath.Join(tmpDir(), "ralph", fmt.Sprintf("%s-%s", epicID, time.Now().Format("20060102-150405")))
	}
	if err := os.MkdirAll(logDir, 0o755); err != nil {
		return loop.ExitError, err
	}

	engine := loop.New(loop.Config{
		Epic:            epicID,
		Harness:         agent,
		Settings:        settings,
		SkipPermissions: opts.skipPermissions,
		FullAuth:        opts.fullAuth,
		Extra:           extra,
		MaxIterations:   opts.maxIterations,
		FailureLimit:    opts.failureLimit,
		LogDir:          logDir,
		Board:           client,
		Pause:           opts.pause,
		PollInterval:    opts.poll,
	})

	session := ui.Session{
		Engine:        engine,
		Epic:          epic,
		HarnessName:   agent.Name(),
		Settings:      settings,
		MaxIterations: opts.maxIterations,
		FailureLimit:  opts.failureLimit,
		LogDir:        logDir,
		Workspace:     opts.workspace,
		SkipPerms:     opts.skipPermissions,
	}
	if opts.headless {
		return ui.RunHeadless(ctx, session), nil
	}
	return ui.Run(ctx, session)
}

// resolveStrand prefers the repo-local binary beside this one, so ralph built
// in a worktree talks to that worktree's strand rather than a global install.
func resolveStrand(explicit string) (string, error) {
	if explicit != "" {
		if _, err := os.Stat(explicit); err != nil {
			return "", fmt.Errorf("strand binary %s is not usable: %w", explicit, err)
		}
		return explicit, nil
	}
	if self, err := os.Executable(); err == nil {
		sibling := filepath.Join(filepath.Dir(self), "strand")
		if info, err := os.Stat(sibling); err == nil && !info.IsDir() {
			return sibling, nil
		}
	}
	found, err := exec.LookPath("strand")
	if err != nil {
		return "", errors.New("no strand binary beside ralph or on PATH (run make build)")
	}
	return found, nil
}

func tmpDir() string {
	if dir := os.Getenv("TMPDIR"); dir != "" {
		return dir
	}
	return "/tmp"
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int) (int, error) {
	v := os.Getenv(key)
	if v == "" {
		return fallback, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 {
		return 0, fmt.Errorf("%s must be a non-negative integer, got %q", key, v)
	}
	return n, nil
}

func envBool(key string, fallback bool) (bool, error) {
	switch v := os.Getenv(key); v {
	case "":
		return fallback, nil
	case "0", "false":
		return false, nil
	case "1", "true":
		return true, nil
	default:
		return false, fmt.Errorf("%s must be 0/1 or false/true, got %q", key, v)
	}
}
