package main

import (
	"encoding/json"
	"strings"
	"testing"
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
