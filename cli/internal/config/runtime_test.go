package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStateRootUsesXDGStateHome(t *testing.T) {
	xdg := filepath.Join(t.TempDir(), "state")
	t.Setenv("XDG_STATE_HOME", xdg)
	root, err := StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(xdg, "millstrand")
	if root != want {
		t.Fatalf("StateRoot() = %q, want %q", root, want)
	}
}

func TestStateRootUsesHomeFallback(t *testing.T) {
	home := t.TempDir()
	t.Setenv("XDG_STATE_HOME", "")
	t.Setenv("HOME", home)
	root, err := StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(home, ".local", "state", "millstrand")
	if root != want {
		t.Fatalf("StateRoot() = %q, want %q", root, want)
	}
}

func TestRuntimeWorldUsesSafeHashedDirectory(t *testing.T) {
	xdg := filepath.Join(t.TempDir(), "state")
	t.Setenv("XDG_STATE_HOME", xdg)
	configDir := filepath.Join(t.TempDir(), "repo", ".ms")
	w, err := RuntimeWorld(configDir)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(w.StateDir, filepath.Join(xdg, "millstrand", "weavers")+string(filepath.Separator)) {
		t.Fatalf("unexpected state dir %q", w.StateDir)
	}
	hash := filepath.Base(w.StateDir)
	if len(hash) != 32 {
		t.Fatalf("hash length = %d, want 32", len(hash))
	}
	if strings.ContainsAny(hash, `/\\:`) {
		t.Fatalf("hash is not path-safe: %q", hash)
	}
	if w.ConfigDir == "" || !filepath.IsAbs(w.ConfigDir) {
		t.Fatalf("unexpected config identity %q", w.ConfigDir)
	}
	if w.DataDir != filepath.Join(w.StateDir, "data") {
		t.Fatalf("unexpected data dir %q", w.DataDir)
	}
	if _, ok := os.LookupEnv("XDG_STATE_HOME"); !ok {
		t.Fatal("test did not isolate XDG_STATE_HOME")
	}
}
