package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeVersionFixture(t *testing.T, content string) string {
	t.Helper()
	source := t.TempDir()
	if err := os.WriteFile(filepath.Join(source, VersionFileName), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return source
}

func TestSourceVersionReadsCanonicalRelease(t *testing.T) {
	version, err := SourceVersion(writeVersionFixture(t, "0.5.1\n"))
	if err != nil {
		t.Fatal(err)
	}
	if version != "0.5.1" {
		t.Fatalf("source version = %q, want 0.5.1", version)
	}
}

func TestSourceVersionRejectsMalformedReleaseFiles(t *testing.T) {
	for _, content := range []string{"", "v0.5.1\n", "01.5.1\n", "0.5.1", "0.5.1\nextra\n"} {
		t.Run(strings.ReplaceAll(content, "\n", "-newline-"), func(t *testing.T) {
			if _, err := SourceVersion(writeVersionFixture(t, content)); err == nil || !strings.Contains(err.Error(), "MAJOR.MINOR.PATCH") {
				t.Fatalf("expected malformed version failure for %q, got %v", content, err)
			}
		})
	}
}

func TestSourceVersionRequiresVersionFile(t *testing.T) {
	source := t.TempDir()
	if _, err := SourceVersion(source); err == nil || !strings.Contains(err.Error(), filepath.Join(source, VersionFileName)) {
		t.Fatalf("expected missing version path, got %v", err)
	}
}

func TestValidateSourceVersionRejectsReleaseSkew(t *testing.T) {
	source := writeVersionFixture(t, "0.5.1\n")
	if _, err := ValidateSourceVersion(source, "0.5.0"); err == nil || !strings.Contains(err.Error(), "does not match") {
		t.Fatalf("expected release skew failure, got %v", err)
	}
	if version, err := ValidateSourceVersion(source, "dev"); err != nil || version != "0.5.1" {
		t.Fatalf("development source validation = %q, %v", version, err)
	}
}
