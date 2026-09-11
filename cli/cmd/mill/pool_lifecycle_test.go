package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
)

const poolTestBasis = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func TestPoolOwnedBlockingHelperProcess(t *testing.T) {
	if os.Getenv("MILLSTRAND_POOL_HELPER_PROCESS") != "1" {
		return
	}
	readyPath := os.Getenv("MILLSTRAND_POOL_HELPER_READY")
	if readyPath == "" {
		os.Exit(2)
	}
	if err := os.WriteFile(readyPath, []byte("ready\n"), 0o600); err != nil {
		os.Exit(2)
	}
	_, _ = bufio.NewReader(os.Stdin).ReadString('\n')
}

func startPoolOwnedBlockingProcess(t *testing.T) *exec.Cmd {
	t.Helper()
	readyPath := filepath.Join(t.TempDir(), "ready")
	cmd := exec.Command(os.Args[0], "-test.run=TestPoolOwnedBlockingHelperProcess", "--")
	cmd.Env = append(os.Environ(), "MILLSTRAND_POOL_HELPER_PROCESS=1", "MILLSTRAND_POOL_HELPER_READY="+readyPath)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	waitForPoolTestFile(t, readyPath)
	t.Cleanup(func() {
		if cmd.Process != nil && processAlive(cmd.Process.Pid) {
			_, _ = stdin.Write([]byte("release\n"))
			_ = stdin.Close()
		}
		if cmd.ProcessState == nil {
			_ = cmd.Wait()
		}
	})
	return cmd
}

func waitForPoolTestFile(t *testing.T, path string) {
	t.Helper()
	deadline := time.NewTimer(5 * time.Second)
	defer deadline.Stop()
	ticker := time.NewTicker(5 * time.Millisecond)
	defer ticker.Stop()
	for {
		if _, err := os.Stat(path); err == nil {
			return
		} else if !os.IsNotExist(err) {
			t.Fatalf("stat helper readiness marker %s: %v", path, err)
		}
		select {
		case <-deadline.C:
			t.Fatalf("timed out waiting for helper readiness marker %s", path)
		case <-ticker.C:
		}
	}
}

func TestStopPooledWeaverPropagatesMembershipReadFailure(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	world := config.World{ConfigDir: filepath.Join(t.TempDir(), ".millstrand")}
	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	membershipPath := filepath.Join(root, "jvm-pools", jvmpool.MembershipFile)
	if err := os.MkdirAll(filepath.Dir(membershipPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(membershipPath, []byte("{"), 0o644); err != nil {
		t.Fatal(err)
	}
	registry, err := jvmpool.OpenPath(membershipPath)
	if err != nil {
		t.Fatal(err)
	}
	_, err = (&server{poolRegistry: registry}).stopPooledWeaver(world)
	if err == nil || !strings.Contains(err.Error(), world.ConfigDir) || !strings.Contains(err.Error(), membershipPath) {
		t.Fatalf("membership failure was not contextualized: %v", err)
	}
}

func TestStopPooledWeaverPropagatesDiscoveryFailure(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	world := config.World{ConfigDir: filepath.Join(t.TempDir(), ".millstrand")}
	source := t.TempDir()
	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	registry, err := jvmpool.New(root)
	if err != nil {
		t.Fatal(err)
	}
	pool := "backend"
	if _, err := registry.Reconcile(jvmpool.Member{ConfigDir: world.ConfigDir, SourceCWD: source, JVMPool: pool}, nil); err != nil {
		t.Fatal(err)
	}
	hostDir, err := jvmpool.PoolHostDir(root, pool)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(hostDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(hostDir, "ready.json"), []byte("{"), 0o644); err != nil {
		t.Fatal(err)
	}
	_, err = (&server{poolRegistry: registry}).stopPooledWeaver(world)
	if err == nil || !strings.Contains(err.Error(), world.ConfigDir) || !strings.Contains(err.Error(), pool) {
		t.Fatalf("discovery failure was not contextualized: %v", err)
	}
}

func TestStopPooledWeaverRetainsCustodyWhenReadyMarkerCleanupFails(t *testing.T) {
	world := config.World{ConfigDir: filepath.Join(t.TempDir(), ".millstrand")}
	markerPath := filepath.Join(t.TempDir(), "ready-dir")
	if err := os.MkdirAll(filepath.Join(markerPath, "non-empty"), 0o755); err != nil {
		t.Fatal(err)
	}
	host := &weaverHost{Pool: "backend", HostID: "host-1", Live: true, ReadyPath: markerPath, Members: []poolMember{{World: world}}}
	s := &server{poolHosts: map[string]*weaverHost{"backend": host}, poolMembers: map[string]*weaverHost{world.ConfigDir: host}}
	_, err := s.stopPooledWeaver(world)
	if err == nil || !strings.Contains(err.Error(), markerPath) {
		t.Fatalf("ready-marker cleanup failure was not returned: %v", err)
	}
	if s.poolHosts["backend"] != host || s.poolMembers[world.ConfigDir] != host {
		t.Fatalf("failed cleanup discarded host custody: hosts=%#v members=%#v", s.poolHosts, s.poolMembers)
	}
	if host.Live {
		t.Fatal("failed cleanup left stopped host marked live")
	}
}

func TestPooledRestartResultUsesClosedRouteEnvelope(t *testing.T) {
	world := config.World{ConfigDir: filepath.Join(t.TempDir(), ".millstrand")}
	status := map[string]any{
		"state":              "running",
		"config_dir":         world.ConfigDir,
		"generation_id":      "generation-new",
		"jvm_pool":           "backend",
		"live_members":       []string{world.ConfigDir},
		"pending_members":    []string{},
		"restart_required":   false,
		"host_generation_id": "host-generation-new",
	}
	s := &server{
		meta: client.MillMetadata{ProtocolVersion: client.MillProtocolVersion, MillID: "mill-test"},
		restartFn: func(client.MillWorldRequest) (map[string]any, error) {
			return pooledRestartResult(world, status), nil
		},
	}
	response := callMillRequest(t, s, client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion,
		RequestID:       "restart-request",
		MillID:          "mill-test",
		Operation:       "weaver-restart",
		World:           client.MillWorldRequest{ConfigDir: world.ConfigDir},
		Payload:         map[string]any{},
	})
	if !response.OK {
		t.Fatalf("pooled restart route rejected valid result: %#v", response.Error)
	}
	projection, ok := response.Result.(map[string]any)
	if !ok {
		t.Fatalf("restart route returned %T, want object", response.Result)
	}
	if err := validateRestartResult(projection); err != nil {
		t.Fatalf("pooled restart route returned invalid projection: %#v: %v", projection, err)
	}
	if projection["workspace"] != world.ConfigDir || projection["generation_id"] != "generation-new" {
		t.Fatalf("pooled restart route lost selected workspace or generation: %#v", projection)
	}
	for _, key := range []string{"jvm_pool", "live_members", "pending_members"} {
		if _, present := projection[key]; present {
			t.Fatalf("closed restart projection leaked pool field %q: %#v", key, projection)
		}
	}
}

func TestPooledRestartProbeResultRetainsObservedFailureOnly(t *testing.T) {
	manifest := poolProbeTestManifest()
	result := poolProbeTestResult(manifest)
	result.Success = false
	result.Stage = "probe/failure"
	result.Completed = []string{"probe/basis", "member/A"}
	result.Members[0].Status = "failed"
	probe := pooledRestartProbeResult(manifest, result, errors.New("candidate source missing"))
	if err := probe.validate(); err != nil {
		t.Fatalf("pooled probe result was not a valid restart probe: %v", err)
	}
	if !reflect.DeepEqual(probe.Completed, result.Completed) {
		t.Fatalf("pooled probe changed observed completed stages: got=%v want=%v", probe.Completed, result.Completed)
	}
	if len(probe.Diagnostics) != 1 || probe.Diagnostics[0]["stage"] != "probe/failure" {
		t.Fatalf("pooled probe did not retain one honest failure summary: %#v", probe.Diagnostics)
	}
	data := probe.Diagnostics[0]["data"].(map[string]any)
	if _, ok := data["members"]; !ok || data["message"] != "candidate source missing" {
		t.Fatalf("pooled probe summary lost structured result evidence: %#v", data)
	}
	for _, row := range probe.Diagnostics {
		if row["stage"] == "evaluate" || row["stage"] == "staged" {
			t.Fatalf("pooled probe fabricated isolated lifecycle stage: %#v", row)
		}
	}
}

func TestPooledRestartRecordsRetainCutoverTruthAndPendingDiagnostics(t *testing.T) {
	oldWorld := config.World{ConfigDir: filepath.Join(t.TempDir(), "old", ".millstrand"), StateDir: filepath.Join(t.TempDir(), "old-state")}
	pendingWorld := config.World{ConfigDir: filepath.Join(t.TempDir(), "pending", ".millstrand"), StateDir: filepath.Join(t.TempDir(), "pending-state")}
	probe := &restartProbeResult{Success: true, Stage: "probe/complete", ProbeWorkspace: "/tmp/probe", SourceWorkspace: oldWorld.ConfigDir, Completed: []string{"probe/complete"}, Diagnostics: []map[string]any{}, Log: "/tmp/probe.log"}
	failure := &restartFailure{Stage: "launch", Message: "replacement startup failed", LogPath: "/tmp/host.log"}
	old := poolMember{World: oldWorld, WeaverID: "old-weaver", GenerationID: "old-generation"}
	if err := writePooledRestartRecords([]poolMember{old}, pendingWorld, restartStateFailed, "transition-1", probe, failure, true); err != nil {
		t.Fatal(err)
	}
	record, ok, err := readRestartRecordDetailed(oldWorld)
	if err != nil || !ok || record.State != restartStateFailed || record.GenerationID != "" || !record.OldGenerationStopped || record.PreviousGeneration != "old-generation" || record.PreviousWeaver != "old-weaver" {
		t.Fatalf("cutover record lost old-generation truth: record=%#v ok=%v err=%v", record, ok, err)
	}
	pendingRecord, ok, err := readRestartRecordDetailed(pendingWorld)
	if err != nil || !ok || pendingRecord.State != restartStateFailed || pendingRecord.GenerationID != "" {
		t.Fatalf("pending initiator record admitted a generation: record=%#v ok=%v err=%v", pendingRecord, ok, err)
	}
	status := map[string]any{"state": "pending"}
	merged := (&server{}).mergePooledDetailedRestartStatus(pendingWorld, status)
	if merged["state"] != "pending" || merged["probe"] == nil || merged["restart_failure"] == nil {
		t.Fatalf("pending status lost initiator diagnostics or state: %#v", merged)
	}
}

func TestPooledDetailedStatusKeepsStoppedStateAfterFailedProbe(t *testing.T) {
	world := config.World{ConfigDir: filepath.Join(t.TempDir(), ".millstrand"), StateDir: filepath.Join(t.TempDir(), "state")}
	probe := &restartProbeResult{Success: false, Stage: "probe/failure", ProbeWorkspace: "/tmp/probe", SourceWorkspace: world.ConfigDir, Completed: []string{"probe/basis"}, Diagnostics: []map[string]any{{"stage": "probe/failure", "status": "failed", "data": map[string]any{"message": "missing source"}}}, Log: "/tmp/probe.log"}
	if err := writeRestartRecord(world, restartRecord{State: restartStateRunning, TransitionID: "transition-1", GenerationID: "old-generation", Probe: probe, Failure: &restartFailure{Stage: "probe", Message: "missing source"}}); err != nil {
		t.Fatal(err)
	}
	status := (&server{}).mergePooledDetailedRestartStatus(world, map[string]any{"state": "stopped"})
	if status["state"] != "stopped" || status["probe"] == nil || status["restart_failure"] == nil {
		t.Fatalf("historical probe record overwrote current stopped state: %#v", status)
	}
}

func TestPooledStartDoesNotRediscoverMembershipAfterProbe(t *testing.T) {
	source := tempSource(t)
	cfgA := tempConfig(t, source)
	cfgB := tempConfig(t, source)
	worldA, err := config.RuntimeWorld(cfgA)
	if err != nil {
		t.Fatal(err)
	}
	worldB, err := config.RuntimeWorld(cfgB)
	if err != nil {
		t.Fatal(err)
	}
	snapshot := jvmpool.PoolSnapshot{
		Pool:     "backend",
		Revision: "membership-before-probe",
		Members:  []jvmpool.Member{{ConfigDir: worldA.ConfigDir, SourceCWD: source, JVMPool: "backend"}},
	}
	_, err = (&server{}).startPooledWeaverFromSnapshot(client.MillWorldRequest{CWD: source, ConfigDir: worldB.ConfigDir}, worldB, "backend", nil, snapshot)
	if err == nil || !strings.Contains(err.Error(), "not registered in JVM pool") {
		t.Fatalf("replacement startup rediscovered an unprobed member: %v", err)
	}
}

func poolAdmissionFixture(t *testing.T, members int) (*weaverHost, poolReadyMarker, map[string]map[string]any) {
	t.Helper()
	host := &weaverHost{Pool: "backend", HostID: "host-1", HostGenerationID: "generation-1", MembershipRev: "membership-1", PID: os.Getpid()}
	marker := poolReadyMarker{Format: poolReadyFormat, JVMPool: host.Pool, HostID: host.HostID, HostGenerationID: host.HostGenerationID, MembershipRevision: host.MembershipRev, PID: host.PID, BasisFingerprint: poolTestBasis}
	statuses := map[string]map[string]any{}
	for i := 0; i < members; i++ {
		configDir := filepath.Join(t.TempDir(), ".millstrand")
		world := config.World{ConfigDir: configDir, StateDir: filepath.Join(configDir, "state"), DataDir: filepath.Join(configDir, "data")}
		weaverID := fmt.Sprintf("weaver-%d", i)
		generationID := fmt.Sprintf("member-generation-%d", i)
		socket := filepath.Join(world.StateDir, "weaver.sock")
		member := poolMember{World: world, WeaverID: weaverID, GenerationID: generationID}
		host.Members = append(host.Members, member)
		marker.Members = append(marker.Members, poolReadyMember{ConfigDir: configDir, WeaverID: weaverID, GenerationID: generationID, SocketPath: socket, NREPLHost: "127.0.0.1", NREPLPort: 4000 + i})
		statuses[configDir] = map[string]any{
			"pid": host.PID, "weaver_id": weaverID, "generation_id": generationID,
			"started_at": "2026-09-11T00:00:00Z", "socket_path": socket,
			"config_dir": configDir, "state_dir": world.StateDir, "data_dir": world.DataDir,
			"jvm_pool": host.Pool, "host_id": host.HostID, "host_generation_id": host.HostGenerationID,
			"basis_fingerprint": poolTestBasis, "member_basis_fingerprint": poolTestBasis,
		}
	}
	return host, marker, statuses
}

func TestPoolAdmissionRequiresEndpointStatusProofForEveryMember(t *testing.T) {
	host, marker, statuses := poolAdmissionFixture(t, 2)
	original := poolAdmissionStatus
	defer func() { poolAdmissionStatus = original }()
	called := 0
	poolAdmissionStatus = func(weaverIdentity) (map[string]any, error) {
		called++
		return nil, errors.New("socket has not started serving")
	}
	if err := validatePoolAdmission(host, marker, statuses); err == nil {
		t.Fatal("pooled host was admitted without endpoint status proof")
	}
	if called != 1 {
		t.Fatalf("endpoint proof calls = %d, want the first actual member to be proved before admission", called)
	}
}

func TestPoolProbeRejectsMissingLiveBaselineProjection(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	if err := config.SetLocalJVMPool(cfg, "backend"); err != nil {
		t.Fatal(err)
	}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	host := &weaverHost{Pool: "backend", Members: []poolMember{{World: world, Identity: weaverIdentity{PID: os.Getpid(), WeaverID: "weaver-1", GenerationID: "generation-1", StartedAt: "started", Socket: filepath.Join(world.StateDir, "weaver.sock"), ConfigDir: world.ConfigDir, StateDir: world.StateDir, DataDir: world.DataDir}}}}
	snapshot := jvmpool.PoolSnapshot{Pool: "backend", Revision: "membership-1", Members: []jvmpool.Member{{ConfigDir: world.ConfigDir, SourceCWD: source, JVMPool: "backend"}}}
	original := poolProbeBaselineStatus
	defer func() { poolProbeBaselineStatus = original }()
	poolProbeBaselineStatus = func(weaverIdentity) (map[string]any, error) {
		return nil, errors.New("status endpoint unavailable")
	}
	if _, err := poolProbeManifestForHost(host, snapshot, source, t.TempDir()); err == nil || !strings.Contains(err.Error(), "baseline status failed") {
		t.Fatalf("missing live baseline was accepted: %v", err)
	}
}

func TestRestartRejectsLivePooledOwnerAfterConfigBecomesIsolated(t *testing.T) {
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	host := &weaverHost{Pool: "backend", HostID: "host-1", Live: true, Members: []poolMember{{World: world}}}
	s := &server{poolMembers: map[string]*weaverHost{world.ConfigDir: host}, poolHosts: map[string]*weaverHost{"backend": host}}
	_, err = s.restartWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg})
	var responseErr *client.ResponseError
	if !errors.As(err, &responseErr) || responseErr.Code != "mill/jvm-pool-stop-required" {
		t.Fatalf("live pooled owner was not protected from isolated restart: %v", err)
	}
}

func TestInitRejectsLiveIsolatedOwnerBeforePoolMutation(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	originalVersion := config.Version
	config.Version = "0.5.1"
	t.Cleanup(func() { config.Version = originalVersion })
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	cmd := startPoolOwnedBlockingProcess(t)
	writeWeaverMetadata(t, world, cmd.Process.Pid, "isolated-live")

	launches := 0
	originalLaunch := launchWeaver
	launchWeaver = func(string, []string, []string, func(*exec.Cmd) error, io.Writer, io.Writer) (*exec.Cmd, error) {
		launches++
		return nil, errors.New("unexpected launch")
	}
	t.Cleanup(func() { launchWeaver = originalLaunch })

	pool := "backend"
	s := &server{}
	_, err = s.reconcileInitPool(world, source, &pool)
	var responseErr *client.ResponseError
	if !errors.As(err, &responseErr) || responseErr.Code != "mill/jvm-pool-stop-required" {
		t.Fatalf("live isolated owner was not protected from pool init: %v", err)
	}
	if launches != 0 {
		t.Fatalf("live isolated init launched %d weavers", launches)
	}
	if _, err := os.Stat(filepath.Join(cfg, config.LocalConfigFileName)); !os.IsNotExist(err) {
		t.Fatalf("live isolated init wrote local config: %v", err)
	}
	registry, err := jvmpool.New(mustStateRoot(t))
	if err != nil {
		t.Fatal(err)
	}
	snapshot, err := registry.Snapshot(pool)
	if err != nil {
		t.Fatal(err)
	}
	if len(snapshot.Members) != 0 {
		t.Fatalf("live isolated init registered a pool member: %#v", snapshot.Members)
	}
}

func TestStartRejectsLivePooledPlacementEdits(t *testing.T) {
	source := tempSource(t)
	cases := []struct {
		name string
		pool *string
	}{
		{name: "pooled to isolated"},
		{name: "pool move", pool: func() *string { value := "other"; return &value }()},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := tempConfig(t, source)
			if tc.pool != nil {
				if err := config.SetLocalJVMPool(cfg, *tc.pool); err != nil {
					t.Fatal(err)
				}
			}
			world, err := config.RuntimeWorld(cfg)
			if err != nil {
				t.Fatal(err)
			}
			host := &weaverHost{Pool: "backend", HostID: "host-1", Live: true, Members: []poolMember{{World: world}}}
			s := &server{poolMembers: map[string]*weaverHost{world.ConfigDir: host}, poolHosts: map[string]*weaverHost{"backend": host}}
			_, err = s.startWeaver(client.MillWorldRequest{CWD: source, ConfigDir: cfg})
			var responseErr *client.ResponseError
			if !errors.As(err, &responseErr) || responseErr.Code != "mill/jvm-pool-stop-required" {
				t.Fatalf("live pooled placement edit was not refused: %v", err)
			}
			if s.poolHosts["backend"] != host || s.poolMembers[world.ConfigDir] != host {
				t.Fatalf("placement refusal changed live owner: hosts=%#v members=%#v", s.poolHosts, s.poolMembers)
			}
		})
	}
}

func TestPendingPoolStatusReportsCompleteRegisteredProjection(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfgA := tempConfig(t, source)
	cfgB := tempConfig(t, source)
	cfgC := tempConfig(t, source)
	for _, cfg := range []string{cfgA, cfgB, cfgC} {
		if err := config.SetLocalJVMPool(cfg, "backend"); err != nil {
			t.Fatal(err)
		}
	}
	worlds := make([]config.World, 0, 3)
	for _, cfg := range []string{cfgA, cfgB, cfgC} {
		world, err := config.RuntimeWorld(cfg)
		if err != nil {
			t.Fatal(err)
		}
		worlds = append(worlds, world)
	}
	registry, err := jvmpool.New(mustStateRoot(t))
	if err != nil {
		t.Fatal(err)
	}
	for _, world := range worlds {
		if _, err := registry.Reconcile(jvmpool.Member{ConfigDir: world.ConfigDir, SourceCWD: source, JVMPool: "backend"}, nil); err != nil {
			t.Fatal(err)
		}
	}
	snapshot, err := registry.Snapshot("backend")
	if err != nil {
		t.Fatal(err)
	}
	registered := poolConfigDirsFromSnapshot(snapshot)
	pending := []string{worlds[1].ConfigDir, worlds[2].ConfigDir}
	sort.Strings(pending)
	host := &weaverHost{Pool: "backend", HostID: "host-1", Live: true, Members: []poolMember{{World: worlds[0]}}}
	s := &server{poolHosts: map[string]*weaverHost{"backend": host}, poolRegistry: registry}
	statusB, ok, err := s.poolStatusForWorld(worlds[1])
	if err != nil || !ok {
		t.Fatalf("pending member status failed: status=%#v ok=%v err=%v", statusB, ok, err)
	}
	statusC, ok, err := s.poolStatusForWorld(worlds[2])
	if err != nil || !ok {
		t.Fatalf("second pending member status failed: status=%#v ok=%v err=%v", statusC, ok, err)
	}
	for name, status := range map[string]map[string]any{"B": statusB, "C": statusC} {
		if status["state"] != "pending" || !reflect.DeepEqual(status["registered_members"], registered) || !reflect.DeepEqual(status["live_members"], []string{worlds[0].ConfigDir}) || !reflect.DeepEqual(status["pending_members"], pending) {
			t.Fatalf("pending %s status has incomplete projection: %#v", name, status)
		}
	}
}

func TestPendingPoolMemberDoesNotLaunchASecondHost(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfgA := tempConfig(t, source)
	cfgB := tempConfig(t, source)
	for _, cfg := range []string{cfgA, cfgB} {
		if err := config.SetLocalJVMPool(cfg, "backend"); err != nil {
			t.Fatal(err)
		}
	}
	worldA, err := config.RuntimeWorld(cfgA)
	if err != nil {
		t.Fatal(err)
	}
	worldB, err := config.RuntimeWorld(cfgB)
	if err != nil {
		t.Fatal(err)
	}
	registry, err := jvmpool.New(mustStateRoot(t))
	if err != nil {
		t.Fatal(err)
	}
	for _, member := range []jvmpool.Member{{ConfigDir: worldA.ConfigDir, SourceCWD: source, JVMPool: "backend"}, {ConfigDir: worldB.ConfigDir, SourceCWD: source, JVMPool: "backend"}} {
		if _, err := registry.Reconcile(member, nil); err != nil {
			t.Fatal(err)
		}
	}
	process, err := os.FindProcess(os.Getpid())
	if err != nil {
		t.Fatal(err)
	}
	host := &weaverHost{Pool: "backend", HostGenerationID: "generation-1", PID: os.Getpid(), Live: true, cmd: &exec.Cmd{Process: process}, Members: []poolMember{{World: worldA}}}
	s := &server{poolHosts: map[string]*weaverHost{"backend": host}, poolRegistry: registry}
	launches := 0
	original := launchWeaver
	defer func() { launchWeaver = original }()
	launchWeaver = func(string, []string, []string, func(*exec.Cmd) error, io.Writer, io.Writer) (*exec.Cmd, error) {
		launches++
		return nil, errors.New("unexpected launch")
	}
	_, err = s.startPooledWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfgB}, worldB, "backend", nil)
	var responseErr *client.ResponseError
	if !errors.As(err, &responseErr) || responseErr.Code != "mill/jvm-pool-restart-required" {
		t.Fatalf("pending member did not return restart-required: %v", err)
	}
	if launches != 0 {
		t.Fatalf("pending member launched %d host processes", launches)
	}
}

func TestFailedPoolHostLaunchLeavesNoPartialRoute(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	if err := config.SetLocalJVMPool(cfg, "backend"); err != nil {
		t.Fatal(err)
	}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	registry, err := jvmpool.New(mustStateRoot(t))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := registry.Reconcile(jvmpool.Member{ConfigDir: world.ConfigDir, SourceCWD: source, JVMPool: "backend"}, nil); err != nil {
		t.Fatal(err)
	}
	s := &server{poolRegistry: registry}
	original := launchWeaver
	defer func() { launchWeaver = original }()
	launchWeaver = func(_ string, _ []string, _ []string, register func(*exec.Cmd) error, _ io.Writer, _ io.Writer) (*exec.Cmd, error) {
		if err := register(&exec.Cmd{}); err != nil {
			return nil, err
		}
		return nil, errors.New("replacement launch failed")
	}
	if _, err := s.startPooledWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}, world, "backend", nil); err == nil {
		t.Fatal("failed pool launch unexpectedly succeeded")
	}
	if s.poolHosts != nil && s.poolHosts["backend"] != nil {
		t.Fatalf("failed pool launch left a host route: %#v", s.poolHosts["backend"])
	}
	if s.poolMembers != nil && s.poolMembers[world.ConfigDir] != nil {
		t.Fatal("failed pool launch left a member route")
	}
}

func TestRediscoveredPoolWithIsolatedDesiredConfigReportsRunningAndStopsCollectively(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	originalVersion := config.Version
	config.Version = "0.5.1"
	t.Cleanup(func() { config.Version = originalVersion })
	source := tempSource(t)
	cfgA := tempConfig(t, source)
	cfgB := tempConfig(t, source)
	cfgC := tempConfig(t, source)
	for _, cfg := range []string{cfgA, cfgB, cfgC} {
		if err := config.SetLocalJVMPool(cfg, "backend"); err != nil {
			t.Fatal(err)
		}
	}
	worldA, err := config.RuntimeWorld(cfgA)
	if err != nil {
		t.Fatal(err)
	}
	worldB, err := config.RuntimeWorld(cfgB)
	if err != nil {
		t.Fatal(err)
	}
	worldC, err := config.RuntimeWorld(cfgC)
	if err != nil {
		t.Fatal(err)
	}
	registry, err := jvmpool.New(mustStateRoot(t))
	if err != nil {
		t.Fatal(err)
	}
	for _, member := range []jvmpool.Member{{ConfigDir: worldA.ConfigDir, SourceCWD: source, JVMPool: "backend"}, {ConfigDir: worldB.ConfigDir, SourceCWD: source, JVMPool: "backend"}, {ConfigDir: worldC.ConfigDir, SourceCWD: source, JVMPool: "backend"}} {
		if _, err := registry.Reconcile(member, nil); err != nil {
			t.Fatal(err)
		}
	}
	snapshot, err := registry.Snapshot("backend")
	if err != nil {
		t.Fatal(err)
	}
	cmd := startPoolOwnedBlockingProcess(t)
	hostID, hostGeneration := "host-rediscovered", "host-generation-rediscovered"
	members := []poolLaunchMember{
		{ConfigDir: worldA.ConfigDir, SourceCWD: source, StateDir: worldA.StateDir, DataDir: worldA.DataDir, Name: "a", WeaverID: "weaver-a", GenerationID: "generation-a", DependencyDiagnostic: dependencyDiagnosticPath(worldA)},
		{ConfigDir: worldB.ConfigDir, SourceCWD: source, StateDir: worldB.StateDir, DataDir: worldB.DataDir, Name: "b", WeaverID: "weaver-b", GenerationID: "generation-b", DependencyDiagnostic: dependencyDiagnosticPath(worldB)},
	}
	sort.Slice(members, func(i, j int) bool { return members[i].ConfigDir < members[j].ConfigDir })
	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	hostDir, err := jvmpool.PoolHostDir(root, "backend")
	if err != nil {
		t.Fatal(err)
	}
	manifest := poolLaunchManifest{Format: poolLaunchFormat, JVMPool: "backend", HostID: hostID, HostGenerationID: hostGeneration, MembershipRevision: snapshot.Revision, MillstrandSource: source, MillstrandVersion: config.Version, Members: members}
	manifestPath := filepath.Join(hostDir, "launch-"+hostID+".json")
	if err := writePoolLaunchManifest(manifestPath, manifest); err != nil {
		t.Fatal(err)
	}
	marker := poolReadyMarker{Format: poolReadyFormat, JVMPool: "backend", HostID: hostID, HostGenerationID: hostGeneration, MembershipRevision: snapshot.Revision, PID: cmd.Process.Pid, BasisFingerprint: poolTestBasis}
	statuses := map[string]map[string]any{}
	for _, member := range members {
		world := worldA
		if member.ConfigDir == worldB.ConfigDir {
			world = worldB
		}
		writePooledMemberMetadata(t, world, cmd.Process.Pid, member.WeaverID, member.GenerationID, hostID, hostGeneration)
		marker.Members = append(marker.Members, poolReadyMember{ConfigDir: member.ConfigDir, WeaverID: member.WeaverID, GenerationID: member.GenerationID, SocketPath: filepath.Join(member.StateDir, "weaver.sock"), NREPLHost: "127.0.0.1", NREPLPort: 4100})
		status, stale := readStatus(world)
		if stale || status == nil {
			t.Fatalf("pooled member metadata was not readable: status=%#v stale=%v", status, stale)
		}
		statuses[member.ConfigDir] = status
	}
	if err := atomicPoolJSON(filepath.Join(hostDir, "ready.json"), marker); err != nil {
		t.Fatal(err)
	}
	originalAdmission := poolAdmissionStatus
	poolAdmissionStatus = func(identity weaverIdentity) (map[string]any, error) {
		return statuses[identity.ConfigDir], nil
	}
	t.Cleanup(func() { poolAdmissionStatus = originalAdmission })
	// The desired config has opted out, but status must retain the recorded
	// live placement after Mill rehydrates the host.
	for _, world := range []config.World{worldA, worldB} {
		if err := os.WriteFile(filepath.Join(world.ConfigDir, config.LocalConfigFileName), []byte(`{"JVMPool":null}`), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	listServer := &server{}
	rows, err := listServer.weaverList()
	if err != nil {
		t.Fatalf("fresh server pool list failed: %v", err)
	}
	if len(rows) != 3 {
		t.Fatalf("fresh server list should include two live and one pending member: %#v", rows)
	}
	registered := poolConfigDirsFromSnapshot(snapshot)
	pending := []string{worldC.ConfigDir}
	for _, row := range rows {
		if row["jvm_pool"] != "backend" || !reflect.DeepEqual(row["registered_members"], registered) || !reflect.DeepEqual(row["live_members"], []string{worldA.ConfigDir, worldB.ConfigDir}) || !reflect.DeepEqual(row["pending_members"], pending) {
			t.Fatalf("fresh server list lost exact pool projection: %#v", row)
		}
	}
	s := &server{}
	status, ok, err := s.poolStatusForWorld(worldA)
	if err != nil || !ok {
		t.Fatalf("rediscovered pooled member status failed: status=%#v ok=%v err=%v", status, ok, err)
	}
	if status["state"] != "running" || status["jvm_pool"] != "backend" {
		t.Fatalf("rediscovered pooled member was not running: %#v", status)
	}
	if got := status["live_members"].([]string); len(got) != 2 {
		t.Fatalf("rediscovered status lost a live member: %#v", status)
	}
	if _, err := s.stopWeaver(client.MillWorldRequest{CWD: source, ConfigDir: cfgA}); err != nil {
		t.Fatalf("collective stop through isolated desired config failed: %v", err)
	}
	_ = cmd.Wait()
	if processAlive(cmd.Process.Pid) {
		t.Fatalf("collective stop left host pid %d alive", cmd.Process.Pid)
	}
	if _, err := os.Stat(filepath.Join(hostDir, "ready.json")); !os.IsNotExist(err) {
		t.Fatalf("collective stop left host marker: %v", err)
	}
	for _, world := range []config.World{worldA, worldB} {
		if _, err := os.Stat(filepath.Join(world.StateDir, "weaver.json")); !os.IsNotExist(err) {
			t.Fatalf("collective stop left member metadata for %s: %v", world.ConfigDir, err)
		}
	}
}

func writePooledMemberMetadata(t *testing.T, world config.World, pid int, weaverID, generationID, hostID, hostGenerationID string) {
	t.Helper()
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	databasePath := world.DBPath
	metadata := client.Metadata{ProtocolVersion: client.ProtocolVersion, Version: config.Version, PID: pid, DatabaseKind: "sqlite-file", DatabaseLabel: world.DBPath, DatabasePath: &databasePath, DaemonID: weaverID, GenerationID: generationID, BasisFingerprint: poolTestBasis, JVMPool: "backend", HostID: hostID, HostGenerationID: hostGenerationID, MemberBasisFingerprint: poolTestBasis, ConfigDir: world.ConfigDir, StateDir: world.StateDir, DataDir: world.DataDir, Name: filepath.Base(world.ConfigDir), SocketPath: filepath.Join(world.StateDir, "weaver.sock"), StartedAt: "2026-09-11T00:00:00Z"}
	metadata.NREPL.Host = "127.0.0.1"
	metadata.NREPL.Port = 4100
	b, err := json.Marshal(metadata)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), b, 0o644); err != nil {
		t.Fatal(err)
	}
}

func mustStateRoot(t *testing.T) string {
	t.Helper()
	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	return root
}
