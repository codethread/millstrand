//go:build integration

package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/config"
)

func TestRunPooledProbeProcessActualClojureChild(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	workingRoot, err := canonicalProbePath(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	originalConfig := filepath.Join(workingRoot, "member", config.DefaultWorkspace)
	if err := os.MkdirAll(originalConfig, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(originalConfig, config.ConfigFileName), []byte(`{"configFormat":"alpha","name":"probe-child"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	source, err := filepath.Abs(filepath.Join("..", "..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	source, err = canonicalProbePath(source)
	if err != nil {
		t.Fatal(err)
	}
	probeRoot := filepath.Join(workingRoot, "probe")
	manifest := poolProbeManifest{
		Format:               poolProbeFormat,
		JVMPool:              "backend",
		ProbeID:              "probe-child",
		CandidateHostID:      "probe-host-child",
		CandidateGeneration:  "probe-generation-child",
		ProbeRoot:            probeRoot,
		MillstrandSource:     source,
		Result:               filepath.Join(probeRoot, "result.json"),
		CollectiveDiagnostic: filepath.Join(probeRoot, "collective.jsonl"),
		Members: []poolProbeMember{{
			OriginalConfigDir:     originalConfig,
			OriginalSourceCWD:     filepath.Dir(filepath.Dir(originalConfig)),
			ProbeConfigDir:        filepath.Join(probeRoot, "members", "member", "config"),
			ProbeStateDir:         filepath.Join(probeRoot, "members", "member", "state"),
			ProbeDataDir:          filepath.Join(probeRoot, "members", "member", "data"),
			MemberDiagnostic:      filepath.Join(probeRoot, "members", "member", "diagnostic.jsonl"),
			Name:                  "probe-child",
			CandidateWeaverID:     "probe-weaver-child",
			CandidateGenerationID: "probe-generation-child",
		}},
	}
	if err := validatePoolProbeManifest(manifest); err != nil {
		t.Fatal(err)
	}
	result, err := runPooledProbeProcess(manifest)
	if err == nil {
		t.Fatalf("expected the dependency-free child fixture to fail explicitly, got success %+v", result)
	}
	if !strings.Contains(err.Error(), "reported failure") {
		t.Fatalf("actual child failure was not decoded as a probe result: %v", err)
	}
	if result.Success || result.Stage != "probe/failure" {
		t.Fatalf("actual child returned invalid failure result: %+v", result)
	}
	if _, err := os.Stat(filepath.Join(manifest.Members[0].ProbeConfigDir, config.ConfigFileName)); err != nil {
		t.Fatalf("child boundary did not receive copied config: %v", err)
	}
}
