package main

import (
	"path/filepath"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/config"
)

func TestMillChangelogCommandHierarchy(t *testing.T) {
	root := newMillCommand()
	cmd, args, err := root.Find([]string{"changelog"})
	if err != nil {
		t.Fatal(err)
	}
	if cmd.CommandPath() != "mill changelog" || len(args) != 0 {
		t.Fatalf("unexpected changelog command: path=%q args=%v", cmd.CommandPath(), args)
	}
}

func TestRenderChangelogPrintsResolvedFile(t *testing.T) {
	want := "# Changelog\n\n## 0.5.1 - 2026-09-01\n"
	source := writeSourceFixture(t, map[string]string{
		"VERSION":      "0.5.1\n",
		"CHANGELOG.md": want,
	})
	t.Setenv("MILLSTRAND_SOURCE", source)

	got, err := renderChangelog()
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != want {
		t.Fatalf("changelog output = %q, want %q", got, want)
	}
}

func TestRenderChangelogRejectsDifferentReleaseContent(t *testing.T) {
	source := writeSourceFixture(t, map[string]string{"VERSION": "0.5.2\n", "CHANGELOG.md": "# New release\n"})
	t.Setenv("MILLSTRAND_SOURCE", source)
	original := config.Version
	config.Version = "0.5.1"
	t.Cleanup(func() { config.Version = original })
	if _, err := renderChangelog(); err == nil || !strings.Contains(err.Error(), "does not match") {
		t.Fatalf("expected release-specific changelog rejection, got %v", err)
	}
}

func TestRenderChangelogFailsWithoutInstalledChangelog(t *testing.T) {
	source := writeSourceFixture(t, map[string]string{"VERSION": "0.5.1\n"})
	t.Setenv("MILLSTRAND_SOURCE", source)

	if _, err := renderChangelog(); err == nil || !strings.Contains(err.Error(), filepath.Join(source, changelogFileName)) {
		t.Fatalf("expected missing changelog path, got %v", err)
	}
}

func TestRenderChangelogRejectsMalformedInstalledVersion(t *testing.T) {
	source := writeSourceFixture(t, map[string]string{
		"VERSION":      "next\n",
		"CHANGELOG.md": "# Changelog\n",
	})
	t.Setenv("MILLSTRAND_SOURCE", source)

	if _, err := renderChangelog(); err == nil || !strings.Contains(err.Error(), "release identity") {
		t.Fatalf("expected release identity failure, got %v", err)
	}
}
