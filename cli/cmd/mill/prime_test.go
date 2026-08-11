package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/config"
)

// writeSourceFixture lays out a minimal Millstrand checkout: deps.edn (so source
// resolution accepts it) plus a prime manifest and topic files.
func writeSourceFixture(t *testing.T, manifest string, topics map[string]string) string {
	t.Helper()
	src := t.TempDir()
	if err := os.WriteFile(filepath.Join(src, "deps.edn"), []byte("{}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if manifest != "" {
		if err := os.MkdirAll(filepath.Join(src, "docs", "prime"), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(src, primeManifestPath), []byte(manifest), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	for rel, body := range topics {
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
	for _, topic := range []string{"strand", "millstrand"} {
		cmd, args, err := root.Find([]string{"prime", topic})
		if err != nil {
			t.Fatalf("mill prime %s was not found: %v", topic, err)
		}
		if got := cmd.CommandPath(); got != "mill prime "+topic {
			t.Fatalf("command path = %q, want %q", got, "mill prime "+topic)
		}
		if len(args) != 0 {
			t.Fatalf("mill prime %s left arguments: %v", topic, args)
		}
	}
	for _, old := range [][]string{{"strand", "prime"}, {"millstrand", "prime"}} {
		if _, _, err := root.Find(old); err == nil {
			t.Fatalf("legacy command mill %s prime is still registered", old[0])
		}
	}
}

func TestRenderPrimeReadsManifestTopicsAndInterpolatesSource(t *testing.T) {
	src := writeSourceFixture(t,
		`{"version": 1, "topics": {"millstrand": "docs/prime/millstrand.md", "strand": "docs/prime/strand.md"}}`,
		map[string]string{
			"docs/prime/millstrand.md": "Source lives at {{.Source}} and again {{.Source}}.\n",
			"docs/prime/strand.md":     "No token here.\n",
		})
	t.Setenv("MILLSTRAND_SOURCE", src)

	out, err := renderPrime("millstrand")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(out, src) != 2 {
		t.Fatalf("expected both {{.Source}} tokens replaced with %q:\n%s", src, out)
	}
	if strings.Contains(out, "{{") {
		t.Fatalf("millstrand prime left an unrendered token:\n%s", out)
	}

	out, err = renderPrime("strand")
	if err != nil {
		t.Fatal(err)
	}
	if out != "No token here.\n" {
		t.Fatalf("strand prime altered a token-free file: %q", out)
	}
}

func TestRenderPrimeFailsWhenSourceUnresolvable(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "does-not-exist")
	t.Setenv("MILLSTRAND_SOURCE", missing)
	if _, err := renderPrime("millstrand"); err == nil || !strings.Contains(err.Error(), missing) {
		t.Fatalf("expected source error naming missing path %q, got: %v", missing, err)
	}
}

func TestRenderPrimeSourceFallbackFailureNamesCWD(t *testing.T) {
	t.Setenv("MILLSTRAND_SOURCE", "")
	origInstalled := config.InstalledSource
	config.InstalledSource = ""
	t.Cleanup(func() { config.InstalledSource = origInstalled })
	cwd := t.TempDir()
	t.Chdir(cwd)

	if _, err := renderPrime("millstrand"); err == nil || !strings.Contains(err.Error(), cwd) {
		t.Fatalf("expected source-resolution error naming cwd %q, got: %v", cwd, err)
	}
}

func TestRenderPrimeFailsWithoutManifest(t *testing.T) {
	src := writeSourceFixture(t, "", nil)
	t.Setenv("MILLSTRAND_SOURCE", src)
	manifestPath := filepath.Join(src, filepath.FromSlash(primeManifestPath))
	if _, err := renderPrime("millstrand"); err == nil || !strings.Contains(err.Error(), manifestPath) {
		t.Fatalf("expected missing-manifest error naming %q, got: %v", manifestPath, err)
	}
}

func TestRenderPrimeRejectsUnsupportedManifestVersion(t *testing.T) {
	src := writeSourceFixture(t,
		`{"version": 2, "topics": {"millstrand": "docs/prime/millstrand.md"}}`,
		map[string]string{"docs/prime/millstrand.md": "future format\n"})
	t.Setenv("MILLSTRAND_SOURCE", src)
	_, err := renderPrime("millstrand")
	if err == nil || !strings.Contains(err.Error(), "upgrade mill") {
		t.Fatalf("expected loud version-mismatch error instructing an upgrade, got: %v", err)
	}
}

func TestRenderPrimeFailsOnUnknownTopic(t *testing.T) {
	src := writeSourceFixture(t,
		`{"version": 1, "topics": {"millstrand": "docs/prime/millstrand.md"}}`,
		map[string]string{"docs/prime/millstrand.md": "ok\n"})
	t.Setenv("MILLSTRAND_SOURCE", src)
	manifestPath := filepath.Join(src, filepath.FromSlash(primeManifestPath))
	if _, err := renderPrime("nope"); err == nil || !strings.Contains(err.Error(), manifestPath) || !strings.Contains(err.Error(), `"nope"`) {
		t.Fatalf("expected unknown-topic error naming manifest %q and topic, got: %v", manifestPath, err)
	}
}

func TestRenderPrimeRejectsInvalidManifestTopicPaths(t *testing.T) {
	tests := []struct {
		name string
		path string
	}{
		{name: "traversal", path: "../../x"},
		{name: "absolute", path: "/tmp/x"},
		{name: "empty", path: ""},
		{name: "Windows volume", path: "C:/tmp/x"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			manifest := fmt.Sprintf(`{"version": 1, "topics": {"millstrand": %q}}`, tt.path)
			src := writeSourceFixture(t, manifest, nil)
			t.Setenv("MILLSTRAND_SOURCE", src)
			manifestPath := filepath.Join(src, filepath.FromSlash(primeManifestPath))

			_, err := renderPrime("millstrand")
			if err == nil || !strings.Contains(err.Error(), manifestPath) || !strings.Contains(err.Error(), `topic "millstrand"`) || !strings.Contains(err.Error(), fmt.Sprintf("%q", tt.path)) {
				t.Fatalf("expected invalid-path error naming manifest %q, topic, and path %q; got: %v", manifestPath, tt.path, err)
			}
		})
	}
}

func TestRenderPrimeUnreadableTopicNamesPath(t *testing.T) {
	const rel = "docs/prime/missing.md"
	src := writeSourceFixture(t,
		`{"version": 1, "topics": {"millstrand": "docs/prime/missing.md"}}`, nil)
	t.Setenv("MILLSTRAND_SOURCE", src)

	if _, err := renderPrime("millstrand"); err == nil || !strings.Contains(err.Error(), rel) {
		t.Fatalf("expected unreadable-topic error naming %q, got: %v", rel, err)
	}
}

// TestRepoPrimeManifestPointsAtRealFiles guards the shipped contract: the
// repo's own manifest parses at the supported version, every topic file
// exists, and no topic file uses a template construct beyond the one
// {{.Source}} token renderPrime substitutes.
func TestRepoPrimeManifestPointsAtRealFiles(t *testing.T) {
	repoRoot, err := filepath.Abs(filepath.Join("..", "..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("MILLSTRAND_SOURCE", repoRoot)

	raw, err := os.ReadFile(filepath.Join(repoRoot, primeManifestPath))
	if err != nil {
		t.Fatalf("repo prime manifest missing: %v", err)
	}
	var manifest primeManifest
	if err := json.Unmarshal(raw, &manifest); err != nil {
		t.Fatalf("repo prime manifest does not parse: %v", err)
	}
	if manifest.Version != primeManifestVersion {
		t.Fatalf("repo prime manifest version %d != supported %d", manifest.Version, primeManifestVersion)
	}
	if len(manifest.Topics) == 0 {
		t.Fatal("repo prime manifest declares no topics")
	}
	for _, topic := range []string{"millstrand", "strand"} {
		if _, ok := manifest.Topics[topic]; !ok {
			t.Fatalf("repo prime manifest does not declare required topic %q", topic)
		}
	}
	tokenRe := regexp.MustCompile(`\{\{[^}]*\}\}`)
	for topic, rel := range manifest.Topics {
		body, err := os.ReadFile(filepath.Join(repoRoot, filepath.FromSlash(rel)))
		if err != nil {
			t.Fatalf("topic %q points at unreadable file %s: %v", topic, rel, err)
		}
		for _, m := range tokenRe.FindAllString(string(body), -1) {
			if m != "{{.Source}}" {
				t.Fatalf("topic %q file %s uses unsupported template construct %q", topic, rel, m)
			}
		}
		if out, err := renderPrime(topic); err != nil {
			t.Fatalf("rendering repo topic %q failed: %v", topic, err)
		} else if strings.Contains(out, "{{") {
			t.Fatalf("rendering repo topic %q left an unrendered token", topic)
		}
	}
}
