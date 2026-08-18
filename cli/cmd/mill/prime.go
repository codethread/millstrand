package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// primeManifestPath is the frozen index for authored prime topics such as
// strand. The millstrand source/reference pointers live directly in this
// binary and do not have a manifest topic.
const primeManifestPath = "docs/prime/index.json"

// primeManifestVersion is the manifest schema this mill understands. A future
// checkout needing richer rendering bumps the manifest version, and this guard
// turns that skew into a loud upgrade instruction instead of garbled output.
const primeManifestVersion = 1

type primeManifest struct {
	Version int               `json:"version"`
	Topics  map[string]string `json:"topics"`
}

// renderPrime resolves the Millstrand source. The millstrand topic returns its
// source and canonical reference paths directly; authored topics are rendered
// from the checkout's prime manifest. Authored rendering replaces every
// {{.Source}} token and interprets no other template construct.
func renderPrime(topic string) (string, error) {
	cwd, err := os.Getwd()
	if err != nil {
		return "", fmt.Errorf("mill prime %s cannot determine the cwd used to resolve the Millstrand source: %w", topic, err)
	}
	source, err := resolveLaunchSource(cwd)
	if err != nil {
		return "", fmt.Errorf("mill prime %s cannot resolve the Millstrand source that hosts the docs from cwd %s: %w", topic, cwd, err)
	}
	if topic == "millstrand" {
		return renderMillstrandPrime(source)
	}
	manifestPath := filepath.Join(source, filepath.FromSlash(primeManifestPath))
	raw, err := os.ReadFile(manifestPath)
	if err != nil {
		return "", fmt.Errorf("mill prime %s: reading prime manifest: %w", topic, err)
	}
	var manifest primeManifest
	if err := json.Unmarshal(raw, &manifest); err != nil {
		return "", fmt.Errorf("mill prime %s: parsing prime manifest %s: %w", topic, manifestPath, err)
	}
	if manifest.Version != primeManifestVersion {
		return "", fmt.Errorf("mill prime %s: manifest %s is version %d but this mill supports version %d; upgrade mill", topic, manifestPath, manifest.Version, primeManifestVersion)
	}
	rel, ok := manifest.Topics[topic]
	if !ok {
		return "", fmt.Errorf("mill prime %s: manifest %s declares no %q topic", topic, manifestPath, topic)
	}
	if err := validatePrimeTopicPath(rel); err != nil {
		return "", fmt.Errorf("mill prime %s: manifest %s topic %q has invalid path %q: %w", topic, manifestPath, topic, rel, err)
	}
	body, err := os.ReadFile(filepath.Join(source, filepath.FromSlash(rel)))
	if err != nil {
		return "", fmt.Errorf("mill prime %s: reading manifest topic file %s: %w", topic, rel, err)
	}
	return strings.ReplaceAll(string(body), "{{.Source}}", source), nil
}

func renderMillstrandPrime(source string) (string, error) {
	reference := filepath.Join(source, "docs", "reference.md")
	info, err := os.Stat(reference)
	if err != nil {
		return "", fmt.Errorf("mill prime millstrand: canonical reference %s is unavailable: %w", reference, err)
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("mill prime millstrand: canonical reference is not a regular file: %s", reference)
	}
	return fmt.Sprintf("Millstrand source: %s\nMillstrand reference: %s\n", source, reference), nil
}

func validatePrimeTopicPath(rel string) error {
	if rel == "" {
		return fmt.Errorf("path is empty")
	}
	if strings.HasPrefix(rel, "/") {
		return fmt.Errorf("path must be relative")
	}
	if len(rel) >= 2 && ((rel[0] >= 'A' && rel[0] <= 'Z') || (rel[0] >= 'a' && rel[0] <= 'z')) && rel[1] == ':' {
		return fmt.Errorf("path must not have a Windows volume prefix")
	}
	if strings.Contains(rel, `\`) {
		return fmt.Errorf("path must use slash separators")
	}
	for _, segment := range strings.Split(rel, "/") {
		if segment == "" || segment == "." || segment == ".." {
			return fmt.Errorf("path contains invalid segment %q", segment)
		}
	}
	return nil
}

func runPrime(topic string) error {
	out, err := renderPrime(topic)
	if err != nil {
		return err
	}
	if _, err := fmt.Fprint(os.Stdout, out); err != nil {
		return fmt.Errorf("writing prime output: %w", err)
	}
	return nil
}
