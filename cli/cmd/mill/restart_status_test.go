package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

func restartStatusFixture(t testing.TB) (*server, client.MillWorldRequest) {
	t.Helper()
	world, cfg := forwardWorld(t)
	probe := &restartProbeResult{
		Stage: "probe/failure", ProbeWorkspace: "probe", SourceWorkspace: cfg,
		Log: "probe.log", Completed: []string{},
		Diagnostics: []map[string]any{{
			"stage": "probe/failure", "status": "failed",
			"data": map[string]any{"detail": strings.Repeat("x", 5*1024*1024)},
		}},
	}
	if err := writeRestartRecord(world, restartRecord{
		State: restartStateFailed, TransitionID: "retained-transition", Probe: probe,
		Failure: &restartFailure{Stage: "probe", Message: "retained failure"},
	}); err != nil {
		t.Fatal(err)
	}
	return &server{transitions: map[string]*weaverTransition{}}, client.MillWorldRequest{ConfigDir: world.ConfigDir}
}

func TestRestartStatusCacheInvalidatesAfterLocalRecordChange(t *testing.T) {
	s, request := restartStatusFixture(t)
	first, err := s.weaverStatus(request)
	if err != nil {
		t.Fatal(err)
	}
	if failure := first["restart_failure"].(restartFailure); failure.Message != "retained failure" {
		t.Fatalf("unexpected initial failure: %#v", first)
	}
	world, err := resolveLifecycleWorld(request)
	if err != nil {
		t.Fatal(err)
	}
	if err := writeRestartRecord(world, restartRecord{
		State: restartStateFailed, TransitionID: "changed-transition",
		Failure: &restartFailure{Stage: "launch", Message: "changed failure"},
	}); err != nil {
		t.Fatal(err)
	}
	second, err := s.weaverStatus(request)
	if err != nil {
		t.Fatal(err)
	}
	failure, ok := second["restart_failure"].(restartFailure)
	if !ok || failure.Message != "changed failure" || second["transition_id"] != "changed-transition" {
		t.Fatalf("status cache did not observe changed local record: %#v", second)
	}
}

func TestRestartStatusCacheInvalidatesAfterRecordRemoval(t *testing.T) {
	s, request := restartStatusFixture(t)
	if _, err := s.weaverStatus(request); err != nil {
		t.Fatal(err)
	}
	world, err := resolveLifecycleWorld(request)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(restartRecordPath(world)); err != nil {
		t.Fatal(err)
	}
	status, err := s.weaverStatus(request)
	if err != nil || status["state"] != "none" || status["restart_failure"] != nil {
		t.Fatalf("status cache retained removed local record: %#v, %v", status, err)
	}
}

func TestRestartStatusCacheInvalidatesAfterAtomicReplacement(t *testing.T) {
	world, cfg := forwardWorld(t)
	path := restartRecordPath(world)
	write := func(transition string) {
		t.Helper()
		data := []byte(fmt.Sprintf(`{"state":"failed","transition_id":"%s","updated_at":"2026-09-08T19:00:00Z","failure":{"stage":"launch","message":"same"}}`, transition))
		if err := os.WriteFile(path, append(data, '\n'), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("one")
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	s := &server{transitions: map[string]*weaverTransition{}}
	request := client.MillWorldRequest{ConfigDir: cfg}
	first, err := s.weaverStatus(request)
	if err != nil || first["transition_id"] != "one" {
		t.Fatalf("unexpected initial status: %#v, %v", first, err)
	}
	tmp := filepath.Join(world.StateDir, "restart.replacement")
	data := []byte(`{"state":"failed","transition_id":"two","updated_at":"2026-09-08T19:00:00Z","failure":{"stage":"launch","message":"same"}}`)
	if err := os.WriteFile(tmp, append(data, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(tmp, path); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, info.ModTime(), info.ModTime()); err != nil {
		t.Fatal(err)
	}
	second, err := s.weaverStatus(request)
	if err != nil || second["transition_id"] != "two" {
		t.Fatalf("status cache missed atomic replacement with unchanged size and mtime: %#v, %v", second, err)
	}
}

func TestRestartStatusWarmPollReadsDetailedRecordOnce(t *testing.T) {
	s, request := restartStatusFixture(t)
	original := readRestartRecordDetailedFn
	var reads int
	readRestartRecordDetailedFn = func(world config.World) (restartRecord, bool, error) {
		reads++
		return original(world)
	}
	t.Cleanup(func() { readRestartRecordDetailedFn = original })
	for range 2 {
		if _, err := s.weaverStatus(request); err != nil {
			t.Fatal(err)
		}
	}
	if reads != 1 {
		t.Fatalf("warm status polls reread unchanged restart record %d times", reads)
	}
}

func TestRestartStatusUsesCompactProjectionAndDetailedReaderRetainsProbe(t *testing.T) {
	s, world := restartStatusFixture(t)
	request := client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion, RequestID: "progress", World: world,
		Operation: "weaver-restart-status",
	}
	compactBefore, err := s.weaverStatus(world)
	if err != nil || compactBefore["state"] != "failed" || compactBefore["probe"] != nil || compactBefore["diagnostics"] != nil {
		t.Fatalf("status must omit retained probe diagnostics: %#v, %v", compactBefore, err)
	}
	if failure, ok := compactBefore["restart_failure"].(restartFailure); !ok || failure.Message != "retained failure" {
		t.Fatalf("status must retain the compact failure explanation: %#v", compactBefore)
	}
	// The explicit status-details request is read-only and retains the full
	// probe report that routine status intentionally leaves out.
	detailedStatus, err := s.weaverStatus(client.MillWorldRequest{ConfigDir: world.ConfigDir, Details: true})
	if err != nil {
		t.Fatal(err)
	}
	if probe, ok := detailedStatus["probe"].(restartProbeResult); !ok || len(probe.Diagnostics) != 1 {
		t.Fatalf("status --details lost probe diagnostics: %#v", detailedStatus)
	}
	actualWorld, err := resolveLifecycleWorld(world)
	if err != nil {
		t.Fatal(err)
	}
	detailed, present, err := readRestartRecordDetailed(actualWorld)
	if err != nil || !present || detailed.Probe == nil || len(detailed.Probe.Diagnostics) != 1 {
		t.Fatalf("detailed restart record lost probe diagnostics: %#v present=%v err=%v", detailed, present, err)
	}
	for _, phase := range []string{"idle", "probing", "restarting", "running", "failed"} {
		t.Run(phase, func(t *testing.T) {
			if phase != "idle" {
				s.transitions[world.ConfigDir] = &weaverTransition{stateValue: phase, result: compactBefore}
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
	compactAfter, err := s.weaverStatus(world)
	if err != nil || !reflect.DeepEqual(compactAfter, compactBefore) {
		t.Fatalf("polling changed retained status: %v", err)
	}
}

func TestRestartStatusCompactsFailedInMemoryTransition(t *testing.T) {
	world, cfg := forwardWorld(t)
	s := &server{transitions: map[string]*weaverTransition{world.ConfigDir: {
		world: world, transitionID: "transition", stateValue: restartStateFailed,
		result: map[string]any{
			"state":         restartStateFailed,
			"workspace":     cfg,
			"transition_id": "transition",
			"probe":         map[string]any{"diagnostics": strings.Repeat("x", 1024)},
			"failure":       restartFailure{Stage: "launch", Message: "failed"},
			"diagnostics":   []map[string]any{{"stage": "launch", "status": "failed"}},
		},
	}}}
	status, err := s.weaverStatus(client.MillWorldRequest{ConfigDir: cfg})
	if err != nil {
		t.Fatal(err)
	}
	if status["probe"] != nil || status["diagnostics"] != nil {
		t.Fatalf("in-memory failed transition leaked detailed diagnostics: %#v", status)
	}
	if failure, ok := status["restart_failure"].(restartFailure); !ok || failure.Message != "failed" {
		t.Fatalf("in-memory failed transition lost compact failure: %#v", status)
	}
	detailed, err := s.weaverStatus(client.MillWorldRequest{ConfigDir: cfg, Details: true})
	if err != nil || detailed["diagnostics"] == nil || detailed["probe"] == nil {
		t.Fatalf("explicit details did not retain in-memory diagnostics: %#v, %v", detailed, err)
	}
}

func TestRestartStatusRejectsMalformedResponse(t *testing.T) {
	for _, state := range []string{"idle", "probing", "restarting", "running", "failed"} {
		if got, err := restartStatusState(map[string]any{"state": state}); err != nil || got != state {
			t.Fatalf("valid phase %q rejected: %q, %v", state, got, err)
		}
	}
	for _, result := range []any{
		nil, "probing",
		map[string]any{},
		map[string]any{"state": nil},
		map[string]any{"state": 1},
		map[string]any{"state": "unknown"},
		map[string]any{"state": "probing", "probe": "unexpected"},
	} {
		if _, err := restartStatusState(result); err == nil {
			t.Fatalf("malformed progress accepted: %v", result)
		}
	}
}

func BenchmarkRestartPolling(b *testing.B) {
	s, world := restartStatusFixture(b)
	detailedWorld, err := resolveLifecycleWorld(world)
	if err != nil {
		b.Fatal(err)
	}
	for _, variant := range []struct {
		name string
		read func(client.MillWorldRequest) (map[string]any, error)
	}{
		{"compact-status", s.weaverStatus},
		{"progress", s.weaverRestartStatus},
		{"detailed-inspection", func(client.MillWorldRequest) (map[string]any, error) {
			record, present, err := readRestartRecordDetailed(detailedWorld)
			if err != nil {
				return nil, err
			}
			if !present {
				return nil, fmt.Errorf("restart record missing")
			}
			return record.status(detailedWorld), nil
		}},
	} {
		b.Run(variant.name, func(b *testing.B) {
			if _, err := variant.read(world); err != nil {
				b.Fatal(err)
			}
			b.ResetTimer()
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
