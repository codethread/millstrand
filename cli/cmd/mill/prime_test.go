package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/config"
)

func writeSourceFixture(t *testing.T, files map[string]string) string {
	t.Helper()
	src := t.TempDir()
	if err := os.WriteFile(filepath.Join(src, "deps.edn"), []byte("{}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	for rel, body := range files {
		path := filepath.Join(src, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return src
}

func TestMillPrimeCommandHierarchy(t *testing.T) {
	root := newMillCommand()
	cmd, args, err := root.Find([]string{"prime", "millstrand"})
	if err != nil {
		t.Fatalf("mill prime millstrand was not found: %v", err)
	}
	if got := cmd.CommandPath(); got != "mill prime millstrand" {
		t.Fatalf("command path = %q, want %q", got, "mill prime millstrand")
	}
	if len(args) != 0 {
		t.Fatalf("mill prime millstrand left arguments: %v", args)
	}
	removed, args, err := root.Find([]string{"prime", "strand"})
	if err == nil && removed.CommandPath() == "mill prime strand" && len(args) == 0 {
		t.Fatal("removed command mill prime strand is still registered")
	}
	prime, _, err := root.Find([]string{"prime"})
	if err != nil {
		t.Fatal(err)
	}
	if err := prime.Args(prime, []string{"strand"}); err == nil {
		t.Fatal("mill prime accepts the removed strand topic as an argument")
	}
}

func TestRenderMillstrandPrimePrintsSourceAndReference(t *testing.T) {
	src := writeSourceFixture(t, map[string]string{"docs/reference.md": "# Reference\n"})
	t.Setenv("MILLSTRAND_SOURCE", src)

	got, err := renderMillstrandPrime()
	if err != nil {
		t.Fatal(err)
	}
	want := fmt.Sprintf("Millstrand source: %s\nMillstrand reference: %s\n", src, filepath.Join(src, "docs", "reference.md"))
	if got != want {
		t.Fatalf("millstrand prime output = %q, want %q", got, want)
	}
}

func TestRenderMillstrandPrimeFailsWithoutCanonicalReference(t *testing.T) {
	src := writeSourceFixture(t, nil)
	t.Setenv("MILLSTRAND_SOURCE", src)
	reference := filepath.Join(src, "docs", "reference.md")

	if _, err := renderMillstrandPrime(); err == nil || !strings.Contains(err.Error(), reference) {
		t.Fatalf("expected missing-reference error naming %q, got: %v", reference, err)
	}
}

func TestRenderMillstrandPrimeFailsWhenSourceUnresolvable(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "does-not-exist")
	t.Setenv("MILLSTRAND_SOURCE", missing)
	if _, err := renderMillstrandPrime(); err == nil || !strings.Contains(err.Error(), missing) {
		t.Fatalf("expected source error naming missing path %q, got: %v", missing, err)
	}
}

func TestRenderMillstrandPrimeSourceFallbackFailureNamesCWD(t *testing.T) {
	t.Setenv("MILLSTRAND_SOURCE", "")
	origInstalled := config.InstalledSource
	config.InstalledSource = ""
	t.Cleanup(func() { config.InstalledSource = origInstalled })
	cwd := t.TempDir()
	t.Chdir(cwd)

	if _, err := renderMillstrandPrime(); err == nil || !strings.Contains(err.Error(), cwd) {
		t.Fatalf("expected source-resolution error naming cwd %q, got: %v", cwd, err)
	}
}
