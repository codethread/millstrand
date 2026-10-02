package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBootstrapDoesNotWriteOrChangeInstructionFiles(t *testing.T) {
	t.Run("existing guidance", func(t *testing.T) {
		repo := initGitRepo(t)
		agents := filepath.Join(repo, "AGENTS.md")
		claude := filepath.Join(repo, "CLAUDE.md")
		agentsOriginal := []byte("existing agent instructions\n")
		claudeOriginal := []byte("existing Claude instructions\n")
		if err := os.WriteFile(agents, agentsOriginal, 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(claude, claudeOriginal, 0o644); err != nil {
			t.Fatal(err)
		}

		world, err := BootstrapWorld(repo, "")
		if err != nil {
			t.Fatal(err)
		}
		root, err := filepath.EvalSymlinks(repo)
		if err != nil {
			t.Fatal(err)
		}
		if want := filepath.Join(root, ".millstrand"); world.ConfigDir != want {
			t.Fatalf("config dir = %q, want %q", world.ConfigDir, want)
		}
		assertFileBytes(t, agents, agentsOriginal)
		assertFileBytes(t, claude, claudeOriginal)

		if _, err := BootstrapWorld(repo, ""); err != nil {
			t.Fatal(err)
		}
		assertFileBytes(t, agents, agentsOriginal)
		assertFileBytes(t, claude, claudeOriginal)
	})

	t.Run("missing guidance", func(t *testing.T) {
		repo := initGitRepo(t)
		if _, err := BootstrapWorld(repo, ""); err != nil {
			t.Fatal(err)
		}
		for _, name := range []string{"AGENTS.md", "CLAUDE.md", "CLAUDE.local.md"} {
			if _, err := os.Stat(filepath.Join(repo, name)); !os.IsNotExist(err) {
				t.Fatalf("bootstrap wrote %s: %v", name, err)
			}
		}
	})

	t.Run("Claude symlink", func(t *testing.T) {
		repo := initGitRepo(t)
		agents := filepath.Join(repo, "AGENTS.md")
		claude := filepath.Join(repo, "CLAUDE.md")
		original := []byte("shared instructions\n")
		if err := os.WriteFile(agents, original, 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.Symlink("AGENTS.md", claude); err != nil {
			t.Fatal(err)
		}

		if _, err := BootstrapWorld(repo, ""); err != nil {
			t.Fatal(err)
		}
		assertFileBytes(t, agents, original)
		info, err := os.Lstat(claude)
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode()&os.ModeSymlink == 0 {
			t.Fatal("CLAUDE.md symlink was replaced")
		}
	})
}

func assertFileBytes(t *testing.T, path string, want []byte) {
	t.Helper()
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(want) {
		t.Fatalf("bootstrap changed %s: got %q want %q", path, got, want)
	}
}

func TestBootstrapSeedsCanonicalDepsAndOnlyIgnoresPersonalOverlays(t *testing.T) {
	directory := t.TempDir()
	world, err := BootstrapWorld(directory, filepath.Join(directory, "world"))
	if err != nil {
		t.Fatal(err)
	}
	deps, err := os.ReadFile(filepath.Join(world.ConfigDir, "deps.edn"))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"io.millstrand/batteries", "44b332fe42a025d4d30d6ff9a3a82d3a93111cba"} {
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
	if _, err := BootstrapWorld(directory, worldPath); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"deps.edn", "deps.local.edn", "init.local.clj"} {
		content, err := os.ReadFile(filepath.Join(worldPath, name))
		if err != nil || string(content) != name+" sentinel\n" {
			t.Fatalf("bootstrap overwrote %s: content=%q err=%v", name, content, err)
		}
	}
}
