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

func TestQualityGraphKeepsFourteenChecksAndBuildDependencies(t *testing.T) {
	if len(qualityChecks) != 14 {
		t.Fatalf("quality graph has %d checks, want 14", len(qualityChecks))
	}
	if err := validateChecks(qualityChecks); err != nil {
		t.Fatal(err)
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
		{name: "heavy-a", argv: []string{"a"}, heavy: true},
		{name: "heavy-b", argv: []string{"b"}, heavy: true},
		{name: "heavy-c", argv: []string{"c"}, heavy: true},
		{name: "light", argv: []string{"light"}},
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
		{name: "failed", argv: []string{"failed"}},
		{name: "blocked", argv: []string{"blocked"}, deps: []string{"failed"}},
		{name: "independent", argv: []string{"independent"}},
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
	if _, err := printSummary(&summary, results); err != nil {
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
	if err := validateChecks([]check{{name: "a", argv: []string{"a"}, deps: []string{"missing"}}}); err == nil {
		t.Fatal("unknown dependency accepted")
	}
	cycle := []check{
		{name: "a", argv: []string{"a"}, deps: []string{"b"}},
		{name: "b", argv: []string{"b"}, deps: []string{"a"}},
	}
	if err := validateChecks(cycle); err == nil {
		t.Fatal("dependency cycle accepted")
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
