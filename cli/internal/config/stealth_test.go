package config

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func initGitRepo(t *testing.T) string {
	t.Helper()
	repo := t.TempDir()
	cmd := exec.Command("git", "init", "-q", repo)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git init: %v\n%s", err, out)
	}
	return repo
}

func TestBootstrapStealthWorldCreatesAndReusesOwnedBlocks(t *testing.T) {
	repo := initGitRepo(t)
	world, report, err := BootstrapStealthWorld(repo)
	if err != nil {
		t.Fatal(err)
	}
	wantConfig, err := filepath.EvalSymlinks(filepath.Join(repo, ".millstrand"))
	if err != nil {
		t.Fatal(err)
	}
	if world.ConfigDir != wantConfig {
		t.Fatalf("config dir = %q", world.ConfigDir)
	}
	if report.GitExclude.Status != StealthStatusUpdated || report.ClaudeGuidance.Status != StealthStatusCreated || report.CodexGuidance.Status != StealthCodexManualRequired {
		t.Fatalf("unexpected first report: %#v", report)
	}
	if err := report.Validate(); err != nil {
		t.Fatal(err)
	}
	if got := report.CodexGuidance.SuggestedText; got != "This repository uses a local, gitignored .millstrand workspace. "+agentOrientation {
		t.Fatalf("unexpected Codex guidance: %q", got)
	}
	if got := string(mustRead(t, report.GitExclude.Path)); !strings.Contains(got, stealthExcludeBlock) {
		t.Fatalf("exclude missing owned block:\n%s", got)
	}
	if got := string(mustRead(t, report.ClaudeGuidance.Path)); got != agentGuidanceSection {
		t.Fatalf("unexpected Claude guidance:\n%s", got)
	}
	if _, err := os.Stat(filepath.Join(repo, "AGENTS.md")); !os.IsNotExist(err) {
		t.Fatalf("stealth init must not create AGENTS.md: %v", err)
	}

	_, second, err := BootstrapStealthWorld(repo)
	if err != nil {
		t.Fatal(err)
	}
	if second.GitExclude.Status != StealthStatusUnchanged || second.ClaudeGuidance.Status != StealthStatusUnchanged {
		t.Fatalf("repeat init not idempotent: %#v", second)
	}
	cmd := exec.Command("git", "status", "--short", "--untracked-files=all")
	cmd.Dir = repo
	if out, err := cmd.CombinedOutput(); err != nil || len(out) != 0 {
		t.Fatalf("stealth files visible to Git: err=%v out=%q", err, out)
	}
}

func TestBootstrapStealthWorldRejectsTrackedWorkspaceBeforeWrites(t *testing.T) {
	for _, workspace := range []string{DefaultWorkspace, WorkspaceAlias} {
		t.Run(workspace, func(t *testing.T) {
			repo := initGitRepo(t)
			tracked := filepath.Join(repo, workspace, "tracked.txt")
			if err := os.MkdirAll(filepath.Dir(tracked), 0o755); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(tracked, []byte("tracked\n"), 0o644); err != nil {
				t.Fatal(err)
			}
			gitAdd(t, repo, filepath.Join(workspace, "tracked.txt"))

			_, _, err := BootstrapStealthWorld(repo)
			var refusal *StealthRefusal
			if !errors.As(err, &refusal) || refusal.Target != StealthTargetTrackedMillstrand || refusal.State != StealthStateTracked {
				t.Fatalf("unexpected refusal: %#v err=%v", refusal, err)
			}
			if _, err := os.Stat(filepath.Join(repo, ".millstrand", "config.json")); !os.IsNotExist(err) {
				t.Fatalf("tracked refusal wrote bootstrap config: %v", err)
			}
			if _, err := os.Stat(filepath.Join(repo, "CLAUDE.local.md")); !os.IsNotExist(err) {
				t.Fatalf("tracked refusal wrote Claude guidance: %v", err)
			}
		})
	}
}

func TestBootstrapStealthWorldRejectsMalformedMarkerBeforeWrites(t *testing.T) {
	repo := initGitRepo(t)
	exclude := filepath.Join(repo, ".git", "info", "exclude")
	original := []byte("existing\n" + stealthExcludeStart + "\n")
	if err := os.WriteFile(exclude, original, 0o644); err != nil {
		t.Fatal(err)
	}

	_, _, err := BootstrapStealthWorld(repo)
	var refusal *StealthRefusal
	if !errors.As(err, &refusal) || refusal.Target != StealthTargetGitExclude || refusal.State != StealthStateStartOnly {
		t.Fatalf("unexpected refusal: %#v err=%v", refusal, err)
	}
	if got := mustRead(t, exclude); string(got) != string(original) {
		t.Fatalf("preflight changed malformed exclude: %q", got)
	}
	if _, err := os.Stat(filepath.Join(repo, ".millstrand")); !os.IsNotExist(err) {
		t.Fatalf("malformed preflight created .millstrand: %v", err)
	}
}

func TestBootstrapStealthWorldSkipsTrackedClaudeGuidance(t *testing.T) {
	repo := initGitRepo(t)
	path := filepath.Join(repo, "CLAUDE.local.md")
	original := []byte("tracked project guidance\n")
	if err := os.WriteFile(path, original, 0o644); err != nil {
		t.Fatal(err)
	}
	gitAdd(t, repo, "CLAUDE.local.md")

	_, report, err := BootstrapStealthWorld(repo)
	if err != nil {
		t.Fatal(err)
	}
	if report.ClaudeGuidance.Status != StealthStatusSkippedTracked {
		t.Fatalf("tracked Claude status = %q", report.ClaudeGuidance.Status)
	}
	if got := mustRead(t, path); string(got) != string(original) {
		t.Fatalf("tracked Claude guidance changed: %q", got)
	}
}

func TestBootstrapStealthWorldFromLinkedWorktreeUsesGitPrivateExclude(t *testing.T) {
	repo := initGitRepo(t)
	gitRun(t, repo, "-c", "user.name=Millstrand Test", "-c", "user.email=millstrand@example.test", "commit", "--allow-empty", "-qm", "root")
	linked := filepath.Join(t.TempDir(), "linked")
	gitRun(t, repo, "worktree", "add", "-qb", "linked-test", linked)

	world, report, err := BootstrapStealthWorld(linked)
	if err != nil {
		t.Fatal(err)
	}
	wantRoot, err := filepath.EvalSymlinks(repo)
	if err != nil {
		t.Fatal(err)
	}
	if world.ConfigDir != filepath.Join(wantRoot, ".millstrand") {
		t.Fatalf("linked worktree selected %q, want canonical %q", world.ConfigDir, filepath.Join(wantRoot, ".millstrand"))
	}
	wantExclude, err := gitPrivateExcludePath(repo)
	if err != nil {
		t.Fatal(err)
	}
	if report.GitExclude.Path != wantExclude {
		t.Fatalf("exclude path = %q, want %q", report.GitExclude.Path, wantExclude)
	}
}

func TestPreflightMarkedFileRejectsEveryAmbiguousState(t *testing.T) {
	spec := markerSpec{target: StealthTargetGitExclude, start: "START", end: "END", block: "START\nbody\nEND\n"}
	tests := []struct {
		name  string
		body  string
		state string
	}{
		{name: "start only", body: "START\n", state: StealthStateStartOnly},
		{name: "end only", body: "END\n", state: StealthStateEndOnly},
		{name: "duplicate start", body: "START\nSTART\nEND\n", state: StealthStateDuplicateStart},
		{name: "duplicate end", body: "START\nEND\nEND\n", state: StealthStateDuplicateEnd},
		{name: "reversed", body: "END\nSTART\n", state: StealthStateReversed},
		{name: "edited", body: "START\nchanged\nEND\n", state: StealthStateEdited},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "marked")
			if err := os.WriteFile(path, []byte(tt.body), 0o644); err != nil {
				t.Fatal(err)
			}
			_, err := preflightMarkedFile(path, spec)
			var refusal *StealthRefusal
			if !errors.As(err, &refusal) || refusal.State != tt.state || refusal.Path != path || refusal.Remediation == "" {
				t.Fatalf("state %q: refusal=%#v err=%v", tt.state, refusal, err)
			}
			details, err := refusal.Details()
			if err != nil || len(details) != 4 {
				t.Fatalf("state %q: invalid details=%#v err=%v", tt.state, details, err)
			}
		})
	}
}

func TestStealthRefusalDetailsRejectInvalidEnums(t *testing.T) {
	refusal := &StealthRefusal{Path: "/repo/.millstrand", Target: "unknown", State: StealthStateTracked, Remediation: "repair it"}
	if _, err := refusal.Details(); err == nil {
		t.Fatal("invalid refusal target was accepted")
	}
}

func mustRead(t *testing.T, path string) []byte {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func gitAdd(t *testing.T, repo, path string) {
	t.Helper()
	cmd := exec.Command("git", "add", "-f", "--", path)
	cmd.Dir = repo
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git add %s: %v\n%s", path, err, out)
	}
}

func gitRun(t *testing.T, repo string, args ...string) {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = repo
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git %s: %v\n%s", strings.Join(args, " "), err, out)
	}
}
