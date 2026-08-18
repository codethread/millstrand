package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	defaultHeavyLimit = 2
	stopGrace         = 3 * time.Second
)

type check struct {
	name  string
	argv  []string
	heavy bool
	deps  []string
}

type result struct {
	check   check
	status  string
	elapsed time.Duration
	logPath string
	err     error
}

type executor func(context.Context, check, string) error

type runner struct {
	checks     []check
	heavyLimit int
	execute    executor
	out        io.Writer
	logDir     string
}

var qualityChecks = []check{
	{name: "clojure-test", argv: []string{"clojure", "-M:test"}, heavy: true},
	{name: "go-test", argv: []string{"make", "test-go"}, heavy: true},
	{name: "e2e", argv: []string{"make", "test-e2e"}, heavy: true},
	{name: "spool-suites", argv: []string{"make", "spool-suite-gate"}, heavy: true},
	{name: "format", argv: []string{"make", "fmt-check"}},
	{name: "lint", argv: []string{"make", "lint"}, heavy: true},
	{name: "reflection", argv: []string{"make", "reflect-check"}},
	{name: "ci-config", argv: []string{"make", "ci-config-check"}},
	{name: "identity", argv: []string{"make", "identity-check"}},
	{name: "build", argv: []string{"make", "build"}},
	{name: "acceptance-kanban", argv: []string{"test/shell/acceptance/millstrand-millhouse-kanban.sh"}, deps: []string{"build"}},
	{name: "acceptance-docs", argv: []string{"test/shell/acceptance/millstrand-docs.sh"}, deps: []string{"build"}},
	{name: "acceptance-neovim", argv: []string{"test/shell/acceptance/millstrand-neovim.sh"}, deps: []string{"build"}},
	{name: "docs", argv: []string{"make", "docs-check"}},
}

func main() {
	limit, err := heavyLimitFromEnv()
	if err != nil {
		fmt.Fprintln(os.Stderr, "land-quality:", err)
		os.Exit(2)
	}
	logDir, err := os.MkdirTemp("", "millstrand-land-quality-")
	if err != nil {
		fmt.Fprintln(os.Stderr, "land-quality: create log directory:", err)
		os.Exit(2)
	}

	ctx, cancel := context.WithCancel(context.Background())
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(signals)
	interrupted := make(chan os.Signal, 1)
	go func() {
		select {
		case received := <-signals:
			interrupted <- received
			cancel()
		case <-ctx.Done():
		}
	}()

	r := runner{checks: qualityChecks, heavyLimit: limit, execute: executeCommand, out: os.Stdout, logDir: logDir}
	results, runErr := r.run(ctx)
	cancel()
	failed, summaryErr := printSummary(os.Stdout, results)
	runErr = errors.Join(runErr, summaryErr)
	if failed == 0 && runErr == nil {
		if err := os.RemoveAll(logDir); err != nil {
			fmt.Fprintln(os.Stderr, "land-quality: remove logs:", err)
			os.Exit(1)
		}
		return
	}
	fmt.Fprintf(os.Stderr, "[land-quality] logs retained at %s\n", logDir)
	select {
	case received := <-interrupted:
		if received == os.Interrupt {
			os.Exit(130)
		}
		if received == syscall.SIGTERM {
			os.Exit(143)
		}
	default:
	}
	os.Exit(1)
}

func heavyLimitFromEnv() (int, error) {
	raw := os.Getenv("LAND_QUALITY_HEAVY_LIMIT")
	if raw == "" {
		return defaultHeavyLimit, nil
	}
	limit, err := strconv.Atoi(raw)
	if err != nil || limit < 1 {
		return 0, fmt.Errorf("LAND_QUALITY_HEAVY_LIMIT must be a positive integer, got %q", raw)
	}
	return limit, nil
}

func (r runner) run(ctx context.Context) ([]result, error) {
	if err := validateChecks(r.checks); err != nil {
		return nil, err
	}
	if r.heavyLimit < 1 {
		return nil, errors.New("heavy limit must be positive")
	}

	states := make(map[string]string, len(r.checks))
	for _, c := range r.checks {
		states[c.name] = "pending"
	}
	completed := make(map[string]result, len(r.checks))
	outcomes := make(chan result, len(r.checks))
	running, heavyRunning := 0, 0

	for len(completed) < len(r.checks) {
		progress := false
		for _, c := range r.checks {
			if states[c.name] != "pending" {
				continue
			}
			if dependency, failed := failedDependency(c, states); failed {
				states[c.name] = "blocked"
				completed[c.name] = result{
					check:  c,
					status: "BLOCKED",
					err:    fmt.Errorf("prerequisite %s %s", dependency, strings.ToLower(states[dependency])),
				}
				progress = true
				continue
			}
			if !dependenciesPassed(c, states) || c.heavy && heavyRunning >= r.heavyLimit {
				continue
			}
			if err := ctx.Err(); err != nil {
				states[c.name] = "blocked"
				completed[c.name] = result{check: c, status: "BLOCKED", err: err}
				progress = true
				continue
			}
			states[c.name] = "running"
			running++
			if c.heavy {
				heavyRunning++
			}
			progress = true
			if _, err := fmt.Fprintf(r.out, "[land-quality] START %-20s %s\n", c.name, strings.Join(c.argv, " ")); err != nil {
				return orderedResults(r.checks, completed), fmt.Errorf("write start status: %w", err)
			}
			go r.runOne(ctx, c, outcomes)
		}

		if running == 0 {
			if !progress && len(completed) < len(r.checks) {
				return orderedResults(r.checks, completed), errors.New("scheduler made no progress")
			}
			continue
		}

		outcome := <-outcomes
		running--
		if outcome.check.heavy {
			heavyRunning--
		}
		states[outcome.check.name] = strings.ToLower(outcome.status)
		completed[outcome.check.name] = outcome
		if _, err := fmt.Fprintf(r.out, "[land-quality] %-5s %-20s %s\n", outcome.status, outcome.check.name, formatDuration(outcome.elapsed)); err != nil {
			return orderedResults(r.checks, completed), fmt.Errorf("write completion status: %w", err)
		}
	}
	return orderedResults(r.checks, completed), ctx.Err()
}

func (r runner) runOne(ctx context.Context, c check, outcomes chan<- result) {
	started := time.Now()
	logPath := filepath.Join(r.logDir, c.name+".log")
	err := r.execute(ctx, c, logPath)
	status := "PASS"
	if err != nil {
		status = "FAIL"
	}
	outcomes <- result{check: c, status: status, elapsed: time.Since(started), logPath: logPath, err: err}
}

func executeCommand(ctx context.Context, c check, logPath string) error {
	log, err := os.Create(logPath)
	if err != nil {
		return err
	}

	cmd := exec.Command(c.argv[0], c.argv[1:]...)
	cmd.Stdout, cmd.Stderr = log, log
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		return errors.Join(err, log.Close())
	}
	done := make(chan struct{})
	watchDone := make(chan struct{})
	killErrors := make(chan error, 2)
	go func() {
		defer close(watchDone)
		select {
		case <-ctx.Done():
			if err := signalProcessGroup(cmd.Process.Pid, syscall.SIGTERM); err != nil {
				killErrors <- err
			}
			timer := time.NewTimer(stopGrace)
			defer timer.Stop()
			select {
			case <-done:
			case <-timer.C:
				if err := signalProcessGroup(cmd.Process.Pid, syscall.SIGKILL); err != nil {
					killErrors <- err
				}
			}
		case <-done:
		}
	}()
	waitErr := cmd.Wait()
	close(done)
	<-watchDone
	close(killErrors)
	var killErr error
	for err := range killErrors {
		killErr = errors.Join(killErr, err)
	}
	closeErr := log.Close()
	if waitErr != nil {
		return errors.Join(fmt.Errorf("%s: %w", strings.Join(c.argv, " "), waitErr), killErr, closeErr)
	}
	return errors.Join(killErr, closeErr)
}

func validateChecks(checks []check) error {
	known := make(map[string]bool, len(checks))
	for _, c := range checks {
		if c.name == "" || len(c.argv) == 0 {
			return fmt.Errorf("invalid check: %#v", c)
		}
		if known[c.name] {
			return fmt.Errorf("duplicate check %q", c.name)
		}
		known[c.name] = true
	}
	for _, c := range checks {
		for _, dep := range c.deps {
			if !known[dep] {
				return fmt.Errorf("check %q has unknown dependency %q", c.name, dep)
			}
		}
	}
	visiting, visited := map[string]bool{}, map[string]bool{}
	byName := make(map[string]check, len(checks))
	for _, c := range checks {
		byName[c.name] = c
	}
	var visit func(string) error
	visit = func(name string) error {
		if visiting[name] {
			return fmt.Errorf("dependency cycle at %q", name)
		}
		if visited[name] {
			return nil
		}
		visiting[name] = true
		for _, dep := range byName[name].deps {
			if err := visit(dep); err != nil {
				return err
			}
		}
		delete(visiting, name)
		visited[name] = true
		return nil
	}
	for _, c := range checks {
		if err := visit(c.name); err != nil {
			return err
		}
	}
	return nil
}

func dependenciesPassed(c check, states map[string]string) bool {
	for _, dep := range c.deps {
		if states[dep] != "pass" {
			return false
		}
	}
	return true
}

func failedDependency(c check, states map[string]string) (string, bool) {
	for _, dep := range c.deps {
		if states[dep] == "fail" || states[dep] == "blocked" {
			return dep, true
		}
	}
	return "", false
}

func signalProcessGroup(pid int, signal syscall.Signal) error {
	if err := syscall.Kill(-pid, signal); err != nil && !errors.Is(err, syscall.ESRCH) {
		return fmt.Errorf("signal process group %d with %s: %w", pid, signal, err)
	}
	return nil
}

func orderedResults(checks []check, completed map[string]result) []result {
	results := make([]result, 0, len(checks))
	for _, c := range checks {
		results = append(results, completed[c.name])
	}
	return results
}

func printSummary(out io.Writer, results []result) (int, error) {
	if _, err := fmt.Fprintln(out, "\n[land-quality] SUMMARY"); err != nil {
		return 0, err
	}
	passed, failed, blocked := 0, 0, 0
	for _, r := range results {
		line := fmt.Sprintf("[land-quality] %-7s %-20s %s", r.status, r.check.name, formatDuration(r.elapsed))
		switch r.status {
		case "PASS":
			passed++
		case "FAIL":
			failed++
			line += " log=" + r.logPath
		case "BLOCKED":
			blocked++
			line += " reason=" + r.err.Error()
		}
		if _, err := fmt.Fprintln(out, line); err != nil {
			return failed + blocked, err
		}
	}
	if _, err := fmt.Fprintf(out, "[land-quality] %d passed, %d failed, %d blocked\n", passed, failed, blocked); err != nil {
		return failed + blocked, err
	}
	for _, r := range results {
		if r.status != "FAIL" {
			continue
		}
		if _, err := fmt.Fprintf(out, "\n[land-quality] FAILURE %s (%s)\n", r.check.name, r.logPath); err != nil {
			return failed + blocked, err
		}
		if _, err := fmt.Fprintln(out, r.err); err != nil {
			return failed + blocked, err
		}
		content, err := os.ReadFile(r.logPath)
		if err != nil {
			return failed + blocked, fmt.Errorf("read failure log %s: %w", r.logPath, err)
		}
		if _, err := out.Write(content); err != nil {
			return failed + blocked, err
		}
		if len(content) > 0 && content[len(content)-1] != '\n' {
			if _, err := fmt.Fprintln(out); err != nil {
				return failed + blocked, err
			}
		}
	}
	return failed + blocked, nil
}

func formatDuration(d time.Duration) string {
	return fmt.Sprintf("%.1fs", d.Seconds())
}
