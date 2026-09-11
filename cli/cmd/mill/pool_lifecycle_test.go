package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
)

const poolTestBasis = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

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
		if err := register(exec.Command("sleep", "60")); err != nil {
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
	snapshot, err := registry.Snapshot("backend")
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if cmd.Process != nil && processAlive(cmd.Process.Pid) {
			terminatePID(cmd.Process.Pid)
			_, _ = cmd.Process.Wait()
		}
	})
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
	_, _ = cmd.Process.Wait()
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
