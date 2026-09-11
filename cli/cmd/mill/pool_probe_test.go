package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
)

func poolProbeTestManifest() poolProbeManifest {
	return poolProbeManifest{
		Format: poolProbeFormat, JVMPool: "backend", ProbeID: "probe-1", CandidateHostID: "probe-host-1", CandidateGeneration: "probe-generation-1",
		ProbeRoot: "/tmp/probe-1", MillstrandSource: "/tmp/millstrand", Result: "/tmp/probe-1/result.json", CollectiveDiagnostic: "/tmp/probe-1/collective.jsonl",
		Members: []poolProbeMember{{OriginalConfigDir: "/tmp/A/.millstrand", OriginalSourceCWD: "/tmp/A", ProbeConfigDir: "/tmp/probe-1/members/A/config", ProbeStateDir: "/tmp/probe-1/members/A/state", ProbeDataDir: "/tmp/probe-1/members/A/data", MemberDiagnostic: "/tmp/probe-1/members/A/diagnostic.jsonl", Name: "A", CandidateWeaverID: "probe-weaver-1", CandidateGenerationID: "probe-generation-1", OldMemberBaseline: nil}},
	}
}

func poolProbeTestResult(manifest poolProbeManifest) poolProbeResult {
	return poolProbeResult{Format: poolProbeResultFormat, ProbeID: manifest.ProbeID, Success: true, Stage: "probe/complete", ProbeRoot: manifest.ProbeRoot, SourceWorkspace: "/tmp/A/.millstrand", Completed: []string{"basis/compose", "member/A"}, Members: []poolProbeResultMember{{OriginalConfigDir: manifest.Members[0].OriginalConfigDir, ProbeConfigDir: manifest.Members[0].ProbeConfigDir, CandidateWeaverID: manifest.Members[0].CandidateWeaverID, CandidateGenerationID: manifest.Members[0].CandidateGenerationID, BaselineKind: "newcomer", Status: "validated", RegistryProjection: map[string]any{}, RegistryDiff: nil, MemberDiagnostic: manifest.Members[0].MemberDiagnostic}}, CollectiveDiagnostic: "/tmp/probe-1/collective.jsonl", Log: "/tmp/probe-1/probe.log"}
}

func TestDecodePoolProbeResultRejectsMalformedSuccessVariants(t *testing.T) {
	manifest := poolProbeTestManifest()
	valid := poolProbeTestResult(manifest)
	cases := []struct {
		name string
		edit func(*poolProbeResult)
	}{
		{"missing member", func(result *poolProbeResult) { result.Members = nil }},
		{"duplicate member", func(result *poolProbeResult) { result.Members = append(result.Members, result.Members[0]) }},
		{"mismatched identity", func(result *poolProbeResult) { result.Members[0].CandidateWeaverID = "other" }},
		{"failed member", func(result *poolProbeResult) { result.Members[0].Status = "failed" }},
		{"newcomer diff", func(result *poolProbeResult) { result.Members[0].RegistryDiff = map[string]any{} }},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			result := valid
			test.edit(&result)
			data, err := json.Marshal(result)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := decodePoolProbeResult(data, manifest); err == nil {
				t.Fatal("malformed claimed success was accepted")
			}
		})
	}
}

func TestDecodePoolProbeResultRejectsUnknownFields(t *testing.T) {
	manifest := poolProbeTestManifest()
	data, err := json.Marshal(poolProbeTestResult(manifest))
	if err != nil {
		t.Fatal(err)
	}
	data = []byte(strings.TrimSpace(string(data))[:len(strings.TrimSpace(string(data)))-1] + `,"extra":true}`)
	if _, err := decodePoolProbeResult(data, manifest); err == nil {
		t.Fatal("unknown result field was accepted")
	}
}

func TestPoolProbeManifestUsesDistinctCanonicalMemberIdentityPaths(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	projectRoot := t.TempDir()
	configA := filepath.Join(projectRoot, "A", config.DefaultWorkspace)
	configB := filepath.Join(projectRoot, "B", config.DefaultWorkspace)
	for _, dir := range []string{configA, configB} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, config.ConfigFileName), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	source := t.TempDir()
	host := &weaverHost{Pool: "backend"}
	snapshot := jvmpool.PoolSnapshot{Pool: "backend", Revision: "membership-1", Members: []jvmpool.Member{
		{ConfigDir: configA, SourceCWD: filepath.Dir(filepath.Dir(configA)), JVMPool: "backend"},
		{ConfigDir: configB, SourceCWD: filepath.Dir(filepath.Dir(configB)), JVMPool: "backend"},
	}}
	manifest, err := poolProbeManifestForHost(host, snapshot, source, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if len(manifest.Members) != 2 {
		t.Fatalf("members = %d, want 2", len(manifest.Members))
	}
	if manifest.Members[0].ProbeConfigDir == manifest.Members[1].ProbeConfigDir {
		t.Fatalf("same-basename members share probe config path: %q", manifest.Members[0].ProbeConfigDir)
	}
	for _, member := range manifest.Members {
		identity, err := config.CanonicalConfigIdentity(member.OriginalConfigDir)
		if err != nil {
			t.Fatal(err)
		}
		want := filepath.Join(manifest.ProbeRoot, "members", "member-"+config.WorldHash(identity), "config")
		if member.ProbeConfigDir != want {
			t.Fatalf("probe config path = %q, want %q", member.ProbeConfigDir, want)
		}
	}
}

func TestPoolProbeManifestRejectsOverlappingPrivateMemberPaths(t *testing.T) {
	manifest := poolProbeTestManifest()
	manifest.Members = append(manifest.Members, poolProbeMember{
		OriginalConfigDir:     "/tmp/B/.millstrand",
		OriginalSourceCWD:     "/tmp/B",
		ProbeConfigDir:        "/tmp/probe-1/members/B/config",
		ProbeStateDir:         "/tmp/probe-1/members/A/config/private-state",
		ProbeDataDir:          "/tmp/probe-1/members/B/data",
		MemberDiagnostic:      "/tmp/probe-1/members/B/diagnostic.jsonl",
		Name:                  "B",
		CandidateWeaverID:     "probe-weaver-2",
		CandidateGenerationID: "probe-generation-2",
	})
	if err := validatePoolProbeManifest(manifest); err == nil || !strings.Contains(err.Error(), "private paths overlap") {
		t.Fatalf("overlapping private paths were accepted: %v", err)
	}
}

func TestPreparePoolProbeConfigsCopiesEveryMemberWithoutChangingOriginals(t *testing.T) {
	root := t.TempDir()
	originalA := filepath.Join(root, "A", config.DefaultWorkspace)
	originalB := filepath.Join(root, "B", config.DefaultWorkspace)
	for _, entry := range []struct {
		dir      string
		sentinel string
	}{
		{originalA, "A-startup"},
		{originalB, "B-startup"},
	} {
		if err := os.MkdirAll(filepath.Join(entry.dir, "nested"), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(entry.dir, "init.clj"), []byte(entry.sentinel), 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(entry.dir, "nested", "startup.edn"), []byte(entry.sentinel+"-nested"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	manifest := poolProbeTestManifest()
	manifest.ProbeRoot = filepath.Join(root, "probe")
	manifest.Result = filepath.Join(manifest.ProbeRoot, "result.json")
	manifest.CollectiveDiagnostic = filepath.Join(manifest.ProbeRoot, "collective.jsonl")
	manifest.Members = []poolProbeMember{
		{OriginalConfigDir: originalA, OriginalSourceCWD: filepath.Dir(filepath.Dir(originalA)), ProbeConfigDir: filepath.Join(manifest.ProbeRoot, "members", "a", "config"), ProbeStateDir: filepath.Join(manifest.ProbeRoot, "members", "a", "state"), ProbeDataDir: filepath.Join(manifest.ProbeRoot, "members", "a", "data"), MemberDiagnostic: filepath.Join(manifest.ProbeRoot, "members", "a", "diagnostic.jsonl"), Name: "A", CandidateWeaverID: "probe-weaver-1", CandidateGenerationID: "probe-generation-1"},
		{OriginalConfigDir: originalB, OriginalSourceCWD: filepath.Dir(filepath.Dir(originalB)), ProbeConfigDir: filepath.Join(manifest.ProbeRoot, "members", "b", "config"), ProbeStateDir: filepath.Join(manifest.ProbeRoot, "members", "b", "state"), ProbeDataDir: filepath.Join(manifest.ProbeRoot, "members", "b", "data"), MemberDiagnostic: filepath.Join(manifest.ProbeRoot, "members", "b", "diagnostic.jsonl"), Name: "B", CandidateWeaverID: "probe-weaver-2", CandidateGenerationID: "probe-generation-2"},
	}
	if err := validatePoolProbeManifest(manifest); err != nil {
		t.Fatal(err)
	}
	if err := preparePoolProbeConfigs(manifest); err != nil {
		t.Fatal(err)
	}
	for _, member := range manifest.Members {
		contents, err := os.ReadFile(filepath.Join(member.ProbeConfigDir, "init.clj"))
		if err != nil {
			t.Fatal(err)
		}
		original, err := os.ReadFile(filepath.Join(member.OriginalConfigDir, "init.clj"))
		if err != nil {
			t.Fatal(err)
		}
		if string(contents) != string(original) {
			t.Fatalf("copied startup sentinel = %q, original = %q", contents, original)
		}
		if _, err := os.Stat(filepath.Join(member.ProbeConfigDir, "nested", "startup.edn")); err != nil {
			t.Fatalf("nested config was not copied for %s: %v", member.OriginalConfigDir, err)
		}
	}
	if err := os.WriteFile(filepath.Join(originalA, "init.clj"), []byte("A-original-still-owned"), 0o644); err != nil {
		t.Fatal(err)
	}
	contents, err := os.ReadFile(filepath.Join(manifest.Members[0].ProbeConfigDir, "init.clj"))
	if err != nil {
		t.Fatal(err)
	}
	if string(contents) != "A-startup" {
		t.Fatalf("probe copy changed after original mutation: %q", contents)
	}
}

func TestPreparePoolProbeConfigsFailsForMissingSource(t *testing.T) {
	manifest := poolProbeTestManifest()
	manifest.ProbeRoot = t.TempDir()
	manifest.Result = filepath.Join(manifest.ProbeRoot, "result.json")
	manifest.CollectiveDiagnostic = filepath.Join(manifest.ProbeRoot, "collective.jsonl")
	manifest.Members[0].OriginalConfigDir = filepath.Join(manifest.ProbeRoot, "missing", config.DefaultWorkspace)
	manifest.Members[0].ProbeConfigDir = filepath.Join(manifest.ProbeRoot, "members", "member", "config")
	manifest.Members[0].ProbeStateDir = filepath.Join(manifest.ProbeRoot, "members", "member", "state")
	manifest.Members[0].ProbeDataDir = filepath.Join(manifest.ProbeRoot, "members", "member", "data")
	manifest.Members[0].MemberDiagnostic = filepath.Join(manifest.ProbeRoot, "members", "member", "diagnostic.jsonl")
	if err := preparePoolProbeConfigs(manifest); err == nil {
		t.Fatal("missing probe source was accepted")
	}
}

func TestExecutePooledProbeRejectsFailedResultWithoutSuccess(t *testing.T) {
	manifest := poolProbeTestManifest()
	manifest.ProbeRoot = t.TempDir()
	manifest.Result = filepath.Join(manifest.ProbeRoot, "result.json")
	manifest.CollectiveDiagnostic = filepath.Join(manifest.ProbeRoot, "collective.jsonl")
	manifest.Members[0].ProbeConfigDir = filepath.Join(manifest.ProbeRoot, "members", "config")
	manifest.Members[0].ProbeStateDir = filepath.Join(manifest.ProbeRoot, "members", "state")
	manifest.Members[0].ProbeDataDir = filepath.Join(manifest.ProbeRoot, "members", "data")
	manifest.Members[0].MemberDiagnostic = filepath.Join(manifest.ProbeRoot, "members", "diagnostic.jsonl")
	failed := poolProbeTestResult(manifest)
	failed.Success = false
	failed.Stage = "probe/failure"
	failed.Members[0].Status = "failed"
	original := poolProbeRuntime
	defer func() { poolProbeRuntime = original }()
	poolProbeRuntime = func(poolProbeManifest) (poolProbeResult, error) { return failed, nil }
	result, err := executePooledProbe(manifest)
	if err == nil || !strings.Contains(err.Error(), "reported failure") {
		t.Fatalf("failed probe was accepted for cutover: result=%+v err=%v", result, err)
	}
	if result.Success {
		t.Fatal("failed probe returned success")
	}
	if _, err := os.Stat(manifest.Result); err != nil {
		t.Fatalf("failed probe result was not retained: %v", err)
	}
}
