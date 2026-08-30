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
	for _, want := range []string{agentGuidanceMarker, agentGuidanceEndMarker, "mill prime millstrand", "strand --help"} {
		if !strings.Contains(got, want) {
			t.Fatalf("missing %q in %q", want, got)
		}
	}
}

func TestBootstrapSeedsCanonicalDepsAndOnlyIgnoresPersonalOverlays(t *testing.T) {
	directory := t.TempDir()
	world, err := bootstrapWorld(directory, filepath.Join(directory, "world"), "", false)
	if err != nil {
		t.Fatal(err)
	}
	deps, err := os.ReadFile(filepath.Join(world.ConfigDir, "deps.edn"))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"io.millstrand/batteries", "d0284a70b63be4ea6e050dc5a116b90550ec814e"} {
		if !strings.Contains(string(deps), want) {
			t.Fatalf("deps.edn missing %q: %s", want, deps)
		}
	}
	ignored, err := os.ReadFile(filepath.Join(world.ConfigDir, ".gitignore"))
	if err != nil {
		t.Fatal(err)
	}
	if got, want := string(ignored), "config.local.json\ndeps.local.edn\ninit.local.clj\n"; got != want {
		t.Fatalf("unexpected workspace ignore template: got %q want %q", got, want)
	}
	for _, name := range []string{"deps.local.edn", "init.local.clj"} {
		if _, err := os.Stat(filepath.Join(world.ConfigDir, name)); !os.IsNotExist(err) {
			t.Fatalf("bootstrap must not create %s: %v", name, err)
		}
	}
}

func TestBootstrapNeverOverwritesDependencyOrActivationOverlays(t *testing.T) {
	directory := t.TempDir()
	worldPath := filepath.Join(directory, "world")
	if err := os.MkdirAll(worldPath, 0o755); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"deps.edn", "deps.local.edn", "init.local.clj"} {
		if err := os.WriteFile(filepath.Join(worldPath, name), []byte(name+" sentinel\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := bootstrapWorld(directory, worldPath, "", false); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"deps.edn", "deps.local.edn", "init.local.clj"} {
		content, err := os.ReadFile(filepath.Join(worldPath, name))
		if err != nil || string(content) != name+" sentinel\n" {
			t.Fatalf("bootstrap overwrote %s: content=%q err=%v", name, content, err)
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
