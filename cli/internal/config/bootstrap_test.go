package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestEnsureAgentGuidanceAppendsToExisting(t *testing.T) {
	d := t.TempDir()
	path := filepath.Join(d, "AGENTS.md")
	if err := os.WriteFile(path, []byte("# Repo\n\nExisting prose.\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ensureAgentGuidance(d); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	got := string(b)
	if !strings.Contains(got, "Existing prose.") {
		t.Fatalf("existing prose was dropped: %q", got)
	}
	for _, want := range []string{agentGuidanceMarker, agentGuidanceEndMarker, "mill prime millstrand", "mill prime strand"} {
		if !strings.Contains(got, want) {
			t.Fatalf("missing %q in %q", want, got)
		}
	}
}

func TestEnsureAgentGuidanceIdempotent(t *testing.T) {
	d := t.TempDir()
	path := filepath.Join(d, "AGENTS.md")
	if err := os.WriteFile(path, []byte("# Repo\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		if err := ensureAgentGuidance(d); err != nil {
			t.Fatal(err)
		}
	}
	b, _ := os.ReadFile(path)
	if n := strings.Count(string(b), agentGuidanceMarker); n != 1 {
		t.Fatalf("expected marker exactly once, got %d", n)
	}
}

func TestEnsureAgentGuidanceCreatesWhenNoneExist(t *testing.T) {
	d := t.TempDir()
	if err := ensureAgentGuidance(d); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(d, "CLAUDE.md")); !os.IsNotExist(err) {
		t.Fatalf("CLAUDE.md should not be created, stat err=%v", err)
	}
	b, err := os.ReadFile(filepath.Join(d, "AGENTS.md"))
	if err != nil {
		t.Fatalf("AGENTS.md not created: %v", err)
	}
	if !strings.Contains(string(b), "mill prime millstrand") {
		t.Fatalf("created AGENTS.md missing guidance: %q", string(b))
	}
}

// CLAUDE.md commonly symlinks to AGENTS.md; injection must write once through the
// shared target and leave the symlink intact.
func TestEnsureAgentGuidanceSymlinkSafe(t *testing.T) {
	d := t.TempDir()
	agents := filepath.Join(d, "AGENTS.md")
	if err := os.WriteFile(agents, []byte("# Repo\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("AGENTS.md", filepath.Join(d, "CLAUDE.md")); err != nil {
		t.Fatal(err)
	}
	if err := ensureAgentGuidance(d); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(agents)
	if n := strings.Count(string(b), agentGuidanceMarker); n != 1 {
		t.Fatalf("expected marker exactly once through symlink, got %d", n)
	}
	fi, err := os.Lstat(filepath.Join(d, "CLAUDE.md"))
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode()&os.ModeSymlink == 0 {
		t.Fatal("CLAUDE.md symlink was replaced by a regular file")
	}
}
