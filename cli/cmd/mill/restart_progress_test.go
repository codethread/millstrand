package main

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/client"
)

func restartProgressFixture(t testing.TB) (*server, client.MillWorldRequest) {
	t.Helper()
	world, cfg := forwardWorld(t)
	probe := &restartProbeResult{
		Stage: "probe/failure", ProbeWorkspace: "probe", SourceWorkspace: cfg,
		Log: "probe.log", Completed: []string{},
		Diagnostics: []map[string]any{{"stage": "probe/failure", "status": "failed",
			"data": map[string]any{"detail": strings.Repeat("x", 3*1024*1024)}}},
	}
	if err := writeRestartRecord(world, restartRecord{
		State: restartStateFailed, TransitionID: "retained-transition", Probe: probe,
		Failure: &restartFailure{Stage: "probe", Message: "retained failure"},
	}); err != nil {
		t.Fatal(err)
	}
	return &server{transitions: map[string]*weaverTransition{}}, client.MillWorldRequest{ConfigDir: world.ConfigDir}
}

func TestRestartProgressReportsOnlyActivePhaseAndRetainsFullStatus(t *testing.T) {
	s, world := restartProgressFixture(t)
	request := client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion, RequestID: "progress", World: world,
		Operation: "weaver-restart-progress",
	}
	fullBefore, err := s.weaverStatus(world)
	if err != nil || fullBefore["state"] != "failed" || fullBefore["probe"] == nil {
		t.Fatalf("missing retained failure: %v, %v", fullBefore["state"], err)
	}
	for _, phase := range []string{"idle", "probing", "restarting", "running", "failed"} {
		t.Run(phase, func(t *testing.T) {
			if phase != "idle" {
				s.transitions[world.ConfigDir] = &weaverTransition{stateValue: phase, result: fullBefore}
			}
			response := callMillRequest(t, s, request)
			if !response.OK {
				t.Fatalf("progress request failed: %+v", response.Error)
			}
			want := map[string]any{"state": phase}
			if !reflect.DeepEqual(response.Result, want) {
				t.Fatalf("progress must contain only active phase: got %v", response.Result)
			}
		})
	}
	delete(s.transitions, world.ConfigDir)
	fullAfter, err := s.weaverStatus(world)
	if err != nil || !reflect.DeepEqual(fullAfter, fullBefore) {
		t.Fatalf("polling changed retained status: %v", err)
	}
}

func TestRestartProgressRejectsMalformedResponse(t *testing.T) {
	for _, state := range []string{"idle", "probing", "restarting", "running", "failed"} {
		if got, err := restartProgressState(map[string]any{"state": state}); err != nil || got != state {
			t.Fatalf("valid phase %q rejected: %q, %v", state, got, err)
		}
	}
	for _, result := range []any{nil, "probing", map[string]any{},
		map[string]any{"state": nil}, map[string]any{"state": 1},
		map[string]any{"state": "unknown"},
		map[string]any{"state": "probing", "probe": "unexpected"}} {
		if _, err := restartProgressState(result); err == nil {
			t.Fatalf("malformed progress accepted: %v", result)
		}
	}
}

func BenchmarkRestartPolling(b *testing.B) {
	s, world := restartProgressFixture(b)
	for _, variant := range []struct {
		name string
		read func(client.MillWorldRequest) (map[string]any, error)
	}{{"full-status", s.weaverStatus}, {"progress", s.weaverRestartProgress}} {
		b.Run(variant.name, func(b *testing.B) {
			b.ReportAllocs()
			for b.Loop() {
				result, err := variant.read(world)
				if err != nil {
					b.Fatal(err)
				}
				encoded, err := json.Marshal(result)
				if err != nil {
					b.Fatal(err)
				}
				b.ReportMetric(float64(len(encoded)), "bytes/response")
			}
		})
	}
}
