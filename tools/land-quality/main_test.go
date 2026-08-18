package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestQualityGraphKeepsThirteenChecksAndBuildDependencies(t *testing.T) {
	if len(qualityChecks) != 13 {
		t.Fatalf("quality graph has %d checks, want 13", len(qualityChecks))
	}
	if err := validateChecks(qualityChecks); err != nil {
		t.Fatal(err)
	}
	for _, c := range qualityChecks {
		if c.name == "spool-suites" || strings.Contains(strings.Join(c.argv, " "), "spool-suite-gate") {
			t.Fatalf("external spool check remains in graph: %#v", c)
		}
	}
	for _, name := range []string{"acceptance-kanban", "acceptance-docs", "acceptance-neovim"} {
		var found *check
		for i := range qualityChecks {
			if qualityChecks[i].name == name {
				found = &qualityChecks[i]
				break
			}
		}
		if found == nil || len(found.deps) != 1 || found.deps[0] != "build" {
			t.Fatalf("%s dependencies = %#v, want build", name, found)
		}
	}
}

func TestRunnerBoundsHeavyChecksAndOverlapsLightChecks(t *testing.T) {
	checks := []check{
		{name: "heavy-a", argv: []string{"a"}, heavy: true, baseline: time.Second, timeout: time.Minute},
		{name: "heavy-b", argv: []string{"b"}, heavy: true, baseline: time.Second, timeout: time.Minute},
		{name: "heavy-c", argv: []string{"c"}, heavy: true, baseline: time.Second, timeout: time.Minute},
		{name: "light", argv: []string{"light"}, baseline: time.Second, timeout: time.Minute},
	}
	var mu sync.Mutex
	runningHeavy, maxHeavy := 0, 0
	started := make(chan string, len(checks))
	release := make(chan struct{})
	exec := func(ctx context.Context, c check, _ string) error {
		mu.Lock()
		if c.heavy {
			runningHeavy++
			if runningHeavy > maxHeavy {
				maxHeavy = runningHeavy
			}
		}
		mu.Unlock()
		started <- c.name
		select {
		case <-release:
		case <-ctx.Done():
			return ctx.Err()
		}
		mu.Lock()
		if c.heavy {
			runningHeavy--
		}
		mu.Unlock()
		return nil
	}

	done := make(chan []result, 1)
	go func() {
		results, _ := (runner{checks: checks, heavyLimit: 2, execute: exec, out: io.Discard, logDir: t.TempDir()}).run(context.Background())
		done <- results
	}()

	seen := map[string]bool{}
	for len(seen) < 3 {
		seen[<-started] = true
	}
	if !seen["light"] {
		t.Fatalf("light check did not start beside heavy checks: %#v", seen)
	}
	mu.Lock()
	gotMax := maxHeavy
	mu.Unlock()
	if gotMax != 2 {
		t.Fatalf("maximum heavy concurrency = %d, want 2", gotMax)
	}
	close(release)
	results := <-done
	for _, result := range results {
		if result.status != "PASS" {
			t.Fatalf("%s status = %s", result.check.name, result.status)
		}
	}
}

func TestRunnerBlocksDependentsButFinishesIndependentChecks(t *testing.T) {
	checks := []check{
		{name: "failed", argv: []string{"failed"}, baseline: time.Second, timeout: time.Minute},
		{name: "blocked", argv: []string{"blocked"}, deps: []string{"failed"}, baseline: time.Second, timeout: time.Minute},
		{name: "independent", argv: []string{"independent"}, baseline: time.Second, timeout: time.Minute},
	}
	var mu sync.Mutex
	executed := map[string]bool{}
	exec := func(_ context.Context, c check, _ string) error {
		mu.Lock()
		executed[c.name] = true
		mu.Unlock()
		if c.name == "failed" {
			return errors.New("boom")
		}
		return nil
	}
	results, err := (runner{checks: checks, heavyLimit: 1, execute: exec, out: io.Discard, logDir: t.TempDir()}).run(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"FAIL", "BLOCKED", "PASS"}
	for i := range results {
		if results[i].status != want[i] {
			t.Fatalf("result %d status = %s, want %s", i, results[i].status, want[i])
		}
	}
	if got := results[1].err.Error(); got != "prerequisite failed fail" {
		t.Fatalf("blocked reason = %q", got)
	}
	results[0].logPath = t.TempDir() + "/failed.log"
	if err := os.WriteFile(results[0].logPath, []byte("boom\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	var summary bytes.Buffer
	if _, err := printSummary(&summary, results, time.Second); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(summary.String(), "reason=prerequisite failed fail") {
		t.Fatalf("summary omitted blocked reason:\n%s", summary.String())
	}
	if executed["blocked"] || !executed["independent"] {
		t.Fatalf("unexpected execution set: %#v", executed)
	}
}

func TestValidateChecksRejectsUnknownDependenciesAndCycles(t *testing.T) {
	if err := validateChecks([]check{{name: "a", argv: []string{"a"}, deps: []string{"missing"}, baseline: time.Second, timeout: time.Minute}}); err == nil {
		t.Fatal("unknown dependency accepted")
	}
	cycle := []check{
		{name: "a", argv: []string{"a"}, deps: []string{"b"}, baseline: time.Second, timeout: time.Minute},
		{name: "b", argv: []string{"b"}, deps: []string{"a"}, baseline: time.Second, timeout: time.Minute},
	}
	if err := validateChecks(cycle); err == nil {
		t.Fatal("dependency cycle accepted")
	}
	invalidBudget := []check{{name: "a", argv: []string{"a"}, baseline: time.Second, timeout: 1100 * time.Millisecond}}
	if err := validateChecks(invalidBudget); err == nil {
		t.Fatal("hard timeout at the warning budget was accepted")
	}
}

func TestRunnerWarnsAtMoreThanTenPercentGrowth(t *testing.T) {
	c := check{name: "slow", argv: []string{"slow"}, baseline: 10 * time.Second, timeout: time.Minute}
	status, err := classifyResult(c, 11*time.Second+time.Nanosecond, nil, nil)
	if err != nil || status != "WARN" {
		t.Fatalf("status = %s, err = %v; want WARN", status, err)
	}
	status, err = classifyResult(c, 11*time.Second, nil, nil)
	if err != nil || status != "PASS" {
		t.Fatalf("at-budget status = %s, err = %v; want PASS", status, err)
	}
	if got := growthBudget(10 * time.Second); got != 11*time.Second {
		t.Fatalf("10%% growth budget = %s", got)
	}
	var summary bytes.Buffer
	if _, err := printSummary(&summary, nil, growthBudget(overallBaseline)+time.Nanosecond); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(summary.String(), "WARN    total") {
		t.Fatalf("overall growth warning missing:\n%s", summary.String())
	}
}

func TestRunnerTimesOutCheckAndBlocksDependent(t *testing.T) {
	checks := []check{
		{name: "slow", argv: []string{"slow"}, baseline: time.Millisecond, timeout: 10 * time.Millisecond},
		{name: "dependent", argv: []string{"dependent"}, deps: []string{"slow"}, baseline: time.Second, timeout: time.Minute},
	}
	exec := func(ctx context.Context, _ check, _ string) error {
		<-ctx.Done()
		return ctx.Err()
	}
	results, err := (runner{checks: checks, heavyLimit: 1, execute: exec, out: io.Discard, logDir: t.TempDir()}).run(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if results[0].status != "TIMEOUT" || results[1].status != "BLOCKED" {
		t.Fatalf("statuses = %s, %s", results[0].status, results[1].status)
	}
	if !strings.Contains(results[1].err.Error(), "prerequisite slow timeout") {
		t.Fatalf("blocked reason = %q", results[1].err)
	}
}

func TestRunnerStopsAtOverallContextDeadline(t *testing.T) {
	c := check{name: "slow", argv: []string{"slow"}, baseline: time.Millisecond, timeout: time.Minute}
	exec := func(ctx context.Context, _ check, _ string) error {
		<-ctx.Done()
		return ctx.Err()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	results, err := (runner{checks: []check{c}, heavyLimit: 1, execute: exec, out: io.Discard, logDir: t.TempDir()}).run(ctx)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("run error = %v", err)
	}
	if results[0].status != "TIMEOUT" {
		t.Fatalf("status = %s, want TIMEOUT", results[0].status)
	}
}

func TestExecuteCommandStopsProcessGroupOnCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	started := time.Now()
	err := executeCommand(ctx, check{name: "cancel", argv: []string{"sh", "-c", "sleep 30 & wait"}}, t.TempDir()+"/cancel.log")
	if err == nil {
		t.Fatal("cancelled command passed")
	}
	if elapsed := time.Since(started); elapsed > stopGrace+time.Second {
		t.Fatalf("cancelled process group took %s to stop", elapsed)
	}
}

func TestHeavyLimitFromEnvFailsLoudly(t *testing.T) {
	t.Setenv("LAND_QUALITY_HEAVY_LIMIT", "0")
	if _, err := heavyLimitFromEnv(); err == nil {
		t.Fatal("zero heavy limit accepted")
	}
}
