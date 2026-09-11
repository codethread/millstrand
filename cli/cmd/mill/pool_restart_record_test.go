package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"millstrand-strand-cli/internal/config"
)

func poolRestartFixture(t *testing.T, state string) (string, poolRestartRecord) {
	t.Helper()
	root := t.TempDir()
	a := filepath.Join(root, "a", ".millstrand")
	b := filepath.Join(root, "b", ".millstrand")
	registered := []string{a, b}
	old := &poolRestartHost{HostID: "host-old", HostGeneration: "host-generation-old", PID: os.Getpid(), BasisFingerprint: poolTestBasis, Members: []poolRestartMember{{ConfigDir: a, WeaverID: "weaver-a", GenerationID: "generation-a"}}}
	newHost := &poolRestartHost{HostID: "host-new", HostGeneration: "host-generation-new", PID: os.Getpid(), BasisFingerprint: poolTestBasis, Members: []poolRestartMember{{ConfigDir: a, WeaverID: "weaver-a-new", GenerationID: "generation-a-new"}, {ConfigDir: b, WeaverID: "weaver-b-new", GenerationID: "generation-b-new"}}}
	probe := &restartProbeResult{Success: true, Stage: "probe/complete", ProbeWorkspace: filepath.Join(root, "probe"), SourceWorkspace: a, Completed: []string{"probe/complete"}, Diagnostics: []map[string]any{}, Log: filepath.Join(root, "probe.log")}
	record := poolRestartRecord{Format: poolRestartRecordFormat, JVMPool: "backend", State: state, TransitionID: "transition-1", MembershipRevision: "membership-1", RegisteredMembers: registered, PendingMembers: []string{}}
	switch state {
	case restartStateProbing:
		record.AdmittedHost = old
		record.PendingMembers = []string{b}
	case restartStateRestarting:
		record.PreviousHost = old
		record.PendingMembers = []string{b}
		record.Probe = probe
	case restartStateRunning:
		record.AdmittedHost = newHost
	case restartStateFailed:
		record.PreviousHost = old
		record.PendingMembers = []string{b}
		record.Probe = probe
		record.Failure = &restartFailure{Stage: "launch", Message: "replacement failed", LogPath: filepath.Join(root, "host.log")}
		record.OldGenerationStopped = true
	}
	if state == restartStateRunning && record.PreviousHost != nil {
		record.Probe = probe
		record.OldGenerationStopped = true
	}
	path, err := poolRestartRecordPath(filepath.Join(root, "state"), "backend")
	if err != nil {
		t.Fatal(err)
	}
	return path, record
}

func TestPoolRestartRecordRoundTripsEveryState(t *testing.T) {
	for _, state := range []string{restartStateProbing, restartStateRestarting, restartStateRunning, restartStateFailed} {
		t.Run(state, func(t *testing.T) {
			path, record := poolRestartFixture(t, state)
			if err := writePoolRestartRecord(path, record); err != nil {
				t.Fatal(err)
			}
			got, present, err := readPoolRestartRecord(path, "backend")
			if err != nil || !present {
				t.Fatalf("read record = %#v present=%v err=%v", got, present, err)
			}
			got.UpdatedAt = ""
			if !reflect.DeepEqual(got, record) {
				t.Fatalf("round trip = %#v, want %#v", got, record)
			}
		})
	}
}

func TestPoolRestartRecordRoundTripsRunningTransitionOutcomes(t *testing.T) {
	t.Run("successful replacement", func(t *testing.T) {
		path, record := poolRestartFixture(t, restartStateRunning)
		previous := *record.AdmittedHost
		previous.HostID = "host-old"
		previous.HostGeneration = "host-generation-old"
		previous.Members = append([]poolRestartMember(nil), previous.Members[:1]...)
		record.PreviousHost = &previous
		record.Probe = &restartProbeResult{Success: true, Stage: "probe/complete", ProbeWorkspace: filepath.Join(t.TempDir(), "probe"), SourceWorkspace: record.RegisteredMembers[0], Completed: []string{"probe/complete"}, Diagnostics: []map[string]any{}, Log: filepath.Join(t.TempDir(), "probe.log")}
		record.OldGenerationStopped = true
		if err := writePoolRestartRecord(path, record); err != nil {
			t.Fatal(err)
		}
		record.PreviousHost.Members[0].ConfigDir = filepath.Join(t.TempDir(), "unregistered", ".millstrand")
		if err := writePoolRestartRecord(path, record); err == nil {
			t.Fatal("unregistered previous member was accepted")
		}
	})

	t.Run("failed probe with pending newcomer", func(t *testing.T) {
		path, record := poolRestartFixture(t, restartStateProbing)
		record.State = restartStateRunning
		record.Probe = &restartProbeResult{Success: false, Stage: "probe/failure", ProbeWorkspace: filepath.Join(t.TempDir(), "probe"), SourceWorkspace: record.RegisteredMembers[0], Completed: []string{"probe/basis"}, Diagnostics: []map[string]any{}, Log: filepath.Join(t.TempDir(), "probe.log")}
		record.Failure = &restartFailure{Stage: "probe", Message: "candidate rejected"}
		if err := writePoolRestartRecord(path, record); err != nil {
			t.Fatal(err)
		}
	})
}

func TestPoolRestartRecordRejectsContradictionsAndClosedShapeViolations(t *testing.T) {
	path, record := poolRestartFixture(t, restartStateRunning)
	record.PendingMembers = []string{filepath.Join(filepath.Dir(filepath.Dir(path)), "pending", ".millstrand")}
	if err := writePoolRestartRecord(path, record); err == nil {
		t.Fatal("contradictory pending set was accepted")
	}

	validPath, valid := poolRestartFixture(t, restartStateRunning)
	if err := writePoolRestartRecord(validPath, valid); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(validPath)
	if err != nil {
		t.Fatal(err)
	}
	validData := append([]byte(nil), data...)
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatal(err)
	}
	raw["extra"] = true
	data, err = json.Marshal(raw)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(validPath, data, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := readPoolRestartRecord(validPath, "backend"); err == nil {
		t.Fatal("unknown field was accepted")
	}
	if _, _, err := readPoolRestartRecord(filepath.Join(filepath.Dir(validPath), "wrong.json"), "backend"); err == nil {
		t.Fatal("path mismatch was accepted")
	}

	for _, test := range []struct {
		name   string
		mutate func(map[string]any)
	}{
		{"unknown member field", func(member map[string]any) { member["extra"] = true }},
		{"missing member field", func(member map[string]any) { delete(member, "generation_id") }},
		{"null member field", func(member map[string]any) { member["weaver_id"] = nil }},
		{"wrong member field type", func(member map[string]any) { member["config_dir"] = 42 }},
	} {
		t.Run(test.name, func(t *testing.T) {
			var nested map[string]any
			if err := json.Unmarshal(validData, &nested); err != nil {
				t.Fatal(err)
			}
			host := nested["admitted_host"].(map[string]any)
			member := host["members"].([]any)[0].(map[string]any)
			test.mutate(member)
			encoded, err := json.Marshal(nested)
			if err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(validPath, encoded, 0o644); err != nil {
				t.Fatal(err)
			}
			if _, _, err := readPoolRestartRecord(validPath, "backend"); err == nil {
				t.Fatal("invalid nested member was accepted")
			}
		})
	}
}

func TestPoolRestartSummaryCacheInvalidatesOnReplacement(t *testing.T) {
	path, first := poolRestartFixture(t, restartStateRunning)
	if err := writePoolRestartRecord(path, first); err != nil {
		t.Fatal(err)
	}
	s := &server{}
	got, present, err := s.readPoolRestartRecordSummaryCached(path, "backend")
	if err != nil || !present || got.Probe != nil {
		t.Fatalf("first summary = %#v present=%v err=%v", got, present, err)
	}
	second := first
	second.TransitionID = "transition-2"
	if err := writePoolRestartRecord(path, second); err != nil {
		t.Fatal(err)
	}
	got, present, err = s.readPoolRestartRecordSummaryCached(path, "backend")
	if err != nil || !present || got.TransitionID != "transition-2" {
		t.Fatalf("replacement summary = %#v present=%v err=%v", got, present, err)
	}
}

func TestReadRuntimeStatusDoesNotOverlayIsolatedRestartHistory(t *testing.T) {
	configDir := filepath.Join(t.TempDir(), ".millstrand")
	world := config.World{ConfigDir: configDir, StateDir: filepath.Join(configDir, "state"), DataDir: filepath.Join(configDir, "data"), DBPath: filepath.Join(configDir, "data", "millstrand.sqlite")}
	writeWeaverMetadata(t, world, os.Getpid(), "fresh-weaver")
	if err := writeRestartRecord(world, restartRecord{State: restartStateRunning, TransitionID: "old-transition", GenerationID: "old-generation"}); err != nil {
		t.Fatal(err)
	}
	raw, stale := readRuntimeStatus(world)
	if stale || raw["generation_id"] != "generation-fresh-weaver" {
		t.Fatalf("raw runtime status = %#v stale=%v", raw, stale)
	}
	decorated, stale := readStatus(world)
	if stale || decorated["generation_id"] != "old-generation" {
		t.Fatalf("decorated status = %#v stale=%v", decorated, stale)
	}
}

func TestPoolAdmissionUsesFreshMetadataAlongsideRetainedIsolatedRecord(t *testing.T) {
	world := config.World{ConfigDir: filepath.Join(t.TempDir(), ".millstrand")}
	world.StateDir = filepath.Join(world.ConfigDir, "state")
	world.DataDir = filepath.Join(world.ConfigDir, "data")
	world.DBPath = filepath.Join(world.DataDir, "millstrand.sqlite")
	writeWeaverMetadata(t, world, os.Getpid(), "fresh-weaver")
	metadataPath := filepath.Join(world.StateDir, "weaver.json")
	metadataBytes, err := os.ReadFile(metadataPath)
	if err != nil {
		t.Fatal(err)
	}
	var metadata map[string]any
	if err := json.Unmarshal(metadataBytes, &metadata); err != nil {
		t.Fatal(err)
	}
	metadata["jvm_pool"] = "backend"
	metadata["host_id"] = "host-1"
	metadata["host_generation_id"] = "host-generation-1"
	metadata["pool_restart_path"] = filepath.Join(t.TempDir(), "restart.json")
	metadata["member_basis_fingerprint"] = poolTestBasis
	metadataBytes, err = json.Marshal(metadata)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(metadataPath, metadataBytes, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := writeRestartRecord(world, restartRecord{State: restartStateRunning, TransitionID: "isolated-transition", GenerationID: "isolated-generation"}); err != nil {
		t.Fatal(err)
	}
	status, stale := readRuntimeStatus(world)
	if stale || status["generation_id"] != "generation-fresh-weaver" {
		t.Fatalf("runtime status did not preserve fresh generation: %#v stale=%v", status, stale)
	}
	host := &weaverHost{Pool: "backend", HostID: "host-1", HostGenerationID: "host-generation-1", MembershipRev: "membership-1", PID: os.Getpid(), Members: []poolMember{{World: world, WeaverID: "fresh-weaver", GenerationID: "generation-fresh-weaver"}}}
	marker := poolReadyMarker{Format: poolReadyFormat, JVMPool: host.Pool, HostID: host.HostID, HostGenerationID: host.HostGenerationID, MembershipRevision: host.MembershipRev, PID: host.PID, BasisFingerprint: poolTestBasis, Members: []poolReadyMember{{ConfigDir: world.ConfigDir, WeaverID: "fresh-weaver", GenerationID: "generation-fresh-weaver", SocketPath: status["socket_path"].(string), NREPLHost: "127.0.0.1", NREPLPort: 5555}}}
	original := poolAdmissionStatus
	t.Cleanup(func() { poolAdmissionStatus = original })
	poolAdmissionStatus = func(weaverIdentity) (map[string]any, error) { return status, nil }
	if err := validatePoolAdmission(host, marker, map[string]map[string]any{world.ConfigDir: status}); err != nil {
		t.Fatalf("fresh pooled metadata was rejected due to isolated history: %v", err)
	}
}

func TestPooledStatusRejectsPartialHostRecordCoordinates(t *testing.T) {
	world := config.World{ConfigDir: filepath.Join(t.TempDir(), ".millstrand")}
	for _, status := range []map[string]any{
		{"state": "pending", "jvm_pool": "backend"},
		{"state": "pending", "jvm_pool": 42, "pool_restart_path": "/tmp/restart.json"},
		{"state": "pending", "jvm_pool": "backend", "pool_restart_path": false},
	} {
		merged := (&server{}).mergePooledDetailedRestartStatus(world, status)
		if merged["state"] != "stale" || merged["stale_reason"] == nil {
			t.Fatalf("partial pooled coordinates did not fail loudly: %#v", merged)
		}
	}
}

func TestPooledStatusProjectsHostRecordOverMemberHistory(t *testing.T) {
	path, record := poolRestartFixture(t, restartStateRunning)
	if err := writePoolRestartRecord(path, record); err != nil {
		t.Fatal(err)
	}
	memberState := t.TempDir()
	world := config.World{ConfigDir: record.RegisteredMembers[0], StateDir: memberState}
	if err := writeRestartRecord(world, restartRecord{State: restartStateFailed, TransitionID: "isolated-history", Failure: &restartFailure{Stage: "launch", Message: "unrelated"}}); err != nil {
		t.Fatal(err)
	}
	status := map[string]any{
		"state":             "pending",
		"jvm_pool":          record.JVMPool,
		"pool_restart_path": path,
	}
	merged := (&server{}).mergePooledDetailedRestartStatus(world, status)
	if merged["state"] != "pending" || merged["restart_state"] != restartStateRunning || merged["transition_id"] != record.TransitionID {
		t.Fatalf("pooled status lost host transition: %#v", merged)
	}
	if failure, ok := merged["restart_failure"]; ok && failure != nil {
		t.Fatalf("pooled status projected unrelated member history: %#v", merged)
	}
}
