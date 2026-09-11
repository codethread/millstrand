package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
)

func poolWeaverArgs(manifestPath, source string) []string {
	bootstrapSource, _ := json.Marshal(filepath.Join(source, "src"))
	bootstrapDeps := fmt.Sprintf("{:aliases {:millstrand/bootstrap {:replace-paths [%s] :replace-deps {org.clojure/clojure {:mvn/version \"1.12.0\"} org.clojure/data.json {:mvn/version \"2.5.1\"} org.clojure/tools.deps {:mvn/version \"0.31.1642\"}}}}}", bootstrapSource)
	return []string{"-Srepro", "-Sdeps", bootstrapDeps, "-M:millstrand/bootstrap", "-m", "millstrand.core.weaver.pool", "--pool-manifest", manifestPath}
}

func poolHostFromSnapshot(snapshot jvmpool.PoolSnapshot, source string, root string) (poolLaunchManifest, *weaverHost, error) {
	if len(snapshot.Members) == 0 {
		return poolLaunchManifest{}, nil, fmt.Errorf("JVM pool %q has no registered members", snapshot.Pool)
	}
	canonicalRoot, err := canonicalProbePath(root)
	if err != nil {
		return poolLaunchManifest{}, nil, fmt.Errorf("canonicalize JVM pool state root %s: %w", root, err)
	}
	hostDir, err := jvmpool.PoolHostDir(canonicalRoot, snapshot.Pool)
	if err != nil {
		return poolLaunchManifest{}, nil, err
	}
	hostID, hostGeneration := newOpaqueID("host"), newOpaqueID("host-generation")
	poolRestartPath := filepath.Join(hostDir, poolRestartRecordFile)
	host := &weaverHost{Pool: snapshot.Pool, HostID: hostID, HostGenerationID: hostGeneration, MembershipRev: snapshot.Revision, LaunchToken: newOpaqueID("launch"), Admission: &sync.RWMutex{}, ReadyPath: filepath.Join(hostDir, "ready.json"), ManifestPath: filepath.Join(hostDir, "launch-"+hostID+".json"), LogPath: filepath.Join(hostDir, "host.log"), PoolRestartPath: poolRestartPath, Allowances: map[string]config.World{}}
	manifest := poolLaunchManifest{Format: poolLaunchFormat, JVMPool: snapshot.Pool, HostID: hostID, HostGenerationID: hostGeneration, MembershipRevision: snapshot.Revision, PoolRestartPath: poolRestartPath, MillstrandSource: source, MillstrandVersion: config.Version}
	for _, registered := range snapshot.Members {
		world, err := config.RuntimeWorld(registered.ConfigDir)
		if err != nil {
			return poolLaunchManifest{}, nil, err
		}
		stateDir, err := canonicalProbePath(world.StateDir)
		if err != nil {
			return poolLaunchManifest{}, nil, fmt.Errorf("canonicalize JVM pool member state %s: %w", world.StateDir, err)
		}
		dataDir, err := canonicalProbePath(world.DataDir)
		if err != nil {
			return poolLaunchManifest{}, nil, fmt.Errorf("canonicalize JVM pool member data %s: %w", world.DataDir, err)
		}
		world.StateDir, world.DataDir = stateDir, dataDir
		name, err := friendlyName(world, "")
		if err != nil {
			return poolLaunchManifest{}, nil, err
		}
		member := poolLaunchMember{ConfigDir: registered.ConfigDir, SourceCWD: registered.SourceCWD, StateDir: world.StateDir, DataDir: world.DataDir, Name: name, WeaverID: newOpaqueID("weaver"), GenerationID: newOpaqueID("generation"), DependencyDiagnostic: dependencyDiagnosticPath(world)}
		manifest.Members = append(manifest.Members, member)
		host.Members = append(host.Members, poolMember{World: world, SourceCWD: registered.SourceCWD, Name: name, WeaverID: member.WeaverID, GenerationID: member.GenerationID})
		host.Allowances[member.WeaverID] = world
	}
	return manifest, host, nil
}

func (s *server) startPooledWeaver(req client.MillWorldRequest, world config.World, pool string, shutdown <-chan struct{}) (map[string]any, error) {
	snapshot, err := s.poolSnapshot(pool)
	if err != nil {
		return nil, err
	}
	return s.startPooledWeaverFromSnapshot(req, world, pool, shutdown, snapshot)
}

// startPooledWeaverFromSnapshot launches exactly the registered member set
// supplied by its caller. In particular, a replacement must not rediscover
// membership after its probe: a registration made during the probe remains
// pending for a later explicit restart.
func (s *server) startPooledWeaverFromSnapshot(req client.MillWorldRequest, world config.World, pool string, shutdown <-chan struct{}, snapshot jvmpool.PoolSnapshot) (map[string]any, error) {
	selected, ok := memberForPool(snapshot, world.ConfigDir)
	if !ok {
		return nil, fmt.Errorf("workspace %s is not registered in JVM pool %q", world.ConfigDir, pool)
	}
	s.mu.Lock()
	s.ensurePoolMapsLocked()
	if claim := s.poolStartClaims[pool]; claim != nil {
		s.mu.Unlock()
		if !waitForStartClaimWithShutdown(claim, shutdown) {
			return nil, errors.New("JVM pool start cancelled during mill shutdown")
		}
		return s.startPooledWeaver(req, world, pool, shutdown)
	}
	claim := make(chan struct{})
	s.poolStartClaims[pool] = claim
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.poolStartClaims[pool] == claim {
			delete(s.poolStartClaims, pool)
			close(claim)
		}
		s.mu.Unlock()
	}()
	s.mu.Lock()
	if existing := s.poolHosts[pool]; existing != nil && existing.Live && existing.cmd != nil && existing.cmd.Process != nil && processAlive(existing.PID) {
		s.mu.Unlock()
		if !poolHostHasMember(existing, world.ConfigDir) {
			return nil, poolPendingError(existing, snapshot, world.ConfigDir)
		}
		status := s.poolStatusForMember(existing, selected.ConfigDir)
		addPoolPending(status, existing, snapshot)
		return status, nil
	}
	s.mu.Unlock()
	if discovered, discoverErr := s.discoverPoolHost(pool); discoverErr != nil {
		return nil, discoverErr
	} else if discovered != nil {
		if !poolHostHasMember(discovered, world.ConfigDir) {
			return nil, poolPendingError(discovered, snapshot, world.ConfigDir)
		}
		status := s.poolStatusForMember(discovered, selected.ConfigDir)
		addPoolPending(status, discovered, snapshot)
		return status, nil
	}
	source, err := resolveLaunchSource(req.CWD)
	if err != nil {
		return nil, err
	}
	root, err := config.StateRoot()
	if err != nil {
		return nil, err
	}
	manifest, host, err := poolHostFromSnapshot(snapshot, source, root)
	if err != nil {
		return nil, err
	}
	if err := writePoolLaunchManifest(host.ManifestPath, manifest); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(host.LogPath), 0o755); err != nil {
		return nil, err
	}
	logFile, err := os.OpenFile(host.LogPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, err
	}
	_, _ = fmt.Fprintf(logFile, "=== pool host start %s pool=%s ===\n", time.Now().UTC().Format(time.RFC3339), pool)
	register := func(cmd *exec.Cmd) error {
		s.mu.Lock()
		defer s.mu.Unlock()
		s.ensurePoolMapsLocked()
		if old := s.poolHosts[pool]; old != nil && old.Live {
			return fmt.Errorf("JVM pool host %q is already running", pool)
		}
		host.cmd = cmd
		s.poolHosts[pool] = host
		return nil
	}
	cmd, err := launchWeaver(source, poolWeaverArgs(host.ManifestPath, source), launchTokenEnv(host.LaunchToken), register, logFile, logFile)
	if err != nil {
		_ = logFile.Close()
		s.removePoolHost(host)
		return nil, err
	}
	s.mu.Lock()
	if s.poolHosts[pool] == host {
		host.PID = cmd.Process.Pid
	}
	s.mu.Unlock()
	done := make(chan error, 1)
	waitDone := make(chan struct{})
	host.cmd = cmd
	go func() {
		defer close(waitDone)
		err := cmd.Wait()
		_ = logFile.Close()
		done <- err
	}()
	status, err := waitForPoolReady(host, done, waitDone, readyTimeoutFor(req.ReadyTimeoutMs), shutdown)
	if err != nil {
		terminateProcess(cmd.Process)
		waitForStartedChild(cmd, done, waitDone, 5*time.Second)
		s.removePoolHost(host)
		return nil, err
	}
	s.mu.Lock()
	host.Live = true
	for i := range host.Members {
		memberStatus := status[host.Members[i].World.ConfigDir]
		identity, identityErr := identityFromStatus(memberStatus)
		if identityErr != nil {
			s.mu.Unlock()
			return nil, identityErr
		}
		host.Members[i].Identity = identity
		host.Members[i].MemberBasis, _ = memberStatus["member_basis_fingerprint"].(string)
		s.poolMembers[host.Members[i].World.ConfigDir] = host
	}
	s.mu.Unlock()
	marker, markerErr := readPoolReady(host.ReadyPath)
	if markerErr != nil {
		return nil, fmt.Errorf("read pooled host readiness after startup: %w", markerErr)
	}
	host.BasisFingerprint = marker.BasisFingerprint
	if err := writePoolRestartRecordForHost(host, snapshot, restartStateRunning, newOpaqueID("transition"), nil, nil, false, nil); err != nil {
		return nil, fmt.Errorf("pooled host started but restart state persistence failed: %w", err)
	}
	statusResult := s.poolStatusForMember(host, selected.ConfigDir)
	addPoolPending(statusResult, host, snapshot)
	return statusResult, nil
}

func addPoolPending(status map[string]any, host *weaverHost, snapshot jvmpool.PoolSnapshot) {
	if status == nil || host == nil {
		return
	}
	seen := map[string]bool{}
	for _, member := range host.Members {
		seen[member.World.ConfigDir] = true
	}
	registered := make([]string, 0, len(snapshot.Members))
	pending := make([]string, 0)
	for _, member := range snapshot.Members {
		registered = append(registered, member.ConfigDir)
		if !seen[member.ConfigDir] {
			pending = append(pending, member.ConfigDir)
		}
	}
	status["registered_members"] = registered
	status["pending_members"] = pending
	status["restart_required"] = len(pending) > 0
}

func waitForPoolReady(host *weaverHost, done <-chan error, waitDone <-chan struct{}, timeout time.Duration, shutdown <-chan struct{}) (map[string]map[string]any, error) {
	deadline := time.Now().Add(timeout)
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	for {
		marker, markerErr := readPoolReady(host.ReadyPath)
		if markerErr == nil {
			statuses := map[string]map[string]any{}
			valid := true
			for _, member := range host.Members {
				status, stale := readRuntimeStatus(member.World)
				if status == nil || stale {
					valid = false
					break
				}
				statuses[member.World.ConfigDir] = status
			}
			if valid {
				if err := validatePoolAdmission(host, marker, statuses); err != nil {
					return nil, err
				}
				return statuses, nil
			}
		}
		select {
		case err := <-done:
			if err != nil {
				return nil, fmt.Errorf("JVM pool host exited before readiness: %w", err)
			}
			return nil, errors.New("JVM pool host exited before readiness")
		default:
		}
		if !processAlive(host.PID) {
			return nil, fmt.Errorf("JVM pool host pid %d exited before readiness", host.PID)
		}
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("JVM pool host did not publish readiness before timeout: %v", markerErr)
		}
		if shutdown == nil {
			<-ticker.C
			continue
		}
		select {
		case <-ticker.C:
		case <-shutdown:
			return nil, errors.New("JVM pool start cancelled during mill shutdown")
		}
	}
}

// poolAdmissionStatus is the endpoint-backed identity proof used before a
// host becomes jointly routable.  Keep it injectable for deterministic
// lifecycle tests; production uses the real Unix-socket status request.
var poolAdmissionStatus = runtimeStatus

func poolHostHasMember(host *weaverHost, configDir string) bool {
	for _, member := range host.Members {
		if member.World.ConfigDir == configDir {
			return true
		}
	}
	return false
}

func poolPendingError(host *weaverHost, snapshot jvmpool.PoolSnapshot, selected string) error {
	live := make([]string, 0, len(host.Members))
	seen := map[string]bool{}
	for _, member := range host.Members {
		live = append(live, member.World.ConfigDir)
		seen[member.World.ConfigDir] = true
	}
	pending := make([]string, 0)
	for _, member := range snapshot.Members {
		if !seen[member.ConfigDir] {
			pending = append(pending, member.ConfigDir)
		}
	}
	details := map[string]any{"jvm_pool": host.Pool, "selected_workspace": selected, "live_members": live, "pending_members": pending, "host_pid": host.PID, "host_generation_id": host.HostGenerationID, "restart_command": "mill weaver restart"}
	return &client.ResponseError{Type: "domain", Code: "mill/jvm-pool-restart-required", Message: "JVM pool member is pending; restart the pool to admit it", Details: details}
}

func (s *server) removePoolHost(host *weaverHost) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.poolHosts[host.Pool] == host {
		delete(s.poolHosts, host.Pool)
	}
	for _, member := range host.Members {
		if s.poolMembers[member.World.ConfigDir] == host {
			delete(s.poolMembers, member.World.ConfigDir)
		}
	}
}

func stopPoolProcess(host *weaverHost) error {
	if host == nil || host.cmd == nil || host.cmd.Process == nil || !processAlive(host.PID) {
		return nil
	}
	terminateProcess(host.cmd.Process)
	waitForPIDExit(host.PID, 5*time.Second)
	return nil
}

func (s *server) stopPooledWeaver(world config.World) (map[string]any, error) {
	s.mu.Lock()
	host := poolHostForConfigLocked(s, world.ConfigDir)
	s.mu.Unlock()
	if host == nil {
		pool, recorded, err := s.registeredPoolForConfig(world.ConfigDir)
		if err != nil {
			return nil, fmt.Errorf("read JVM pool membership for workspace %s: %w", world.ConfigDir, err)
		}
		if recorded {
			host, err = s.discoverPoolHost(pool)
			if err != nil {
				return nil, fmt.Errorf("discover JVM pool host %q for workspace %s: %w", pool, world.ConfigDir, err)
			}
			s.mu.Lock()
			if host == nil {
				host = s.poolHosts[pool]
			}
			s.mu.Unlock()
		}
	}
	if host == nil {
		return baseStatus(world, "stopped"), nil
	}
	admission := s.poolAdmissionLock(host.Pool)
	admission.Lock()
	defer admission.Unlock()
	if err := stopPoolProcess(host); err != nil {
		return nil, fmt.Errorf("stop JVM pool host %q process: %w", host.Pool, err)
	}
	// The process is no longer live even if a later artifact cleanup fails.
	// Keep the host and member routes in custody so the failed cleanup remains
	// inspectable, but never let a stopped host receive lifecycle traffic.
	host.Live = false
	for _, member := range host.Members {
		if member.Identity.WeaverID != "" {
			if err := cleanupWorldArtifactsOwned(member.World, member.Identity); err != nil {
				return nil, fmt.Errorf("stop JVM pool host %q member %s artifact cleanup failed: %w", host.Pool, member.World.ConfigDir, err)
			}
		}
	}
	if err := removePoolReadyMarker(host); err != nil {
		// Retaining the host routes keeps the failed cleanup inspectable and
		// prevents a later lifecycle call from treating the stale marker as an
		// unknown host.
		return nil, fmt.Errorf("stop JVM pool host %q: %w", host.Pool, err)
	}
	if err := os.Remove(host.PoolRestartPath); err != nil && !os.IsNotExist(err) {
		return nil, fmt.Errorf("stop JVM pool host %q restart state cleanup failed: %w", host.Pool, err)
	}
	s.removePoolHost(host)
	status := baseStatus(world, "stopped")
	status["jvm_pool"] = host.Pool
	status["registered_members"] = poolConfigDirs(host.Members)
	status["live_members"] = []string{}
	status["pending_members"] = []string{}
	status["restart_required"] = false
	return status, nil
}

func removePoolReadyMarker(host *weaverHost) error {
	if host == nil || strings.TrimSpace(host.ReadyPath) == "" {
		return errors.New("JVM pool ready marker path is blank")
	}
	if err := os.Remove(host.ReadyPath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("remove JVM pool ready marker %s: %w", host.ReadyPath, err)
	}
	return nil
}

// discoverPoolHost rehydrates an already serving host after Mill itself has
// restarted. It is admitted only after the same ready-marker, metadata, and
// endpoint identity proof used for a fresh launch; a PID alone is never enough.
func (s *server) discoverPoolHost(pool string) (*weaverHost, error) {
	root, err := config.StateRoot()
	if err != nil {
		return nil, err
	}
	canonicalRoot, err := canonicalProbePath(root)
	if err != nil {
		return nil, fmt.Errorf("canonicalize JVM pool state root %s: %w", root, err)
	}
	hostDir, err := jvmpool.PoolHostDir(canonicalRoot, pool)
	if err != nil {
		return nil, err
	}
	marker, err := readPoolReady(filepath.Join(hostDir, "ready.json"))
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if !processAlive(marker.PID) {
		return nil, nil
	}
	launchPaths, err := filepath.Glob(filepath.Join(hostDir, "launch-*.json"))
	if err != nil {
		return nil, err
	}
	var manifest poolLaunchManifest
	for _, path := range launchPaths {
		candidate, readErr := readPoolLaunchManifest(path)
		if readErr == nil && candidate.HostID == marker.HostID {
			manifest = candidate
			break
		}
	}
	if manifest.HostID == "" {
		return nil, errors.New("running JVM pool host has no matching launch manifest")
	}
	expectedRestartPath, err := poolRestartRecordPath(canonicalRoot, pool)
	if err != nil {
		return nil, err
	}
	if manifest.PoolRestartPath != expectedRestartPath {
		return nil, fmt.Errorf("running JVM pool host has mismatched pool restart path %q", manifest.PoolRestartPath)
	}
	process, err := os.FindProcess(marker.PID)
	if err != nil {
		return nil, err
	}
	host := &weaverHost{Pool: pool, HostID: marker.HostID, HostGenerationID: marker.HostGenerationID, BasisFingerprint: marker.BasisFingerprint, MembershipRev: marker.MembershipRevision, PID: marker.PID, cmd: &exec.Cmd{Process: process}, Admission: &sync.RWMutex{}, ReadyPath: filepath.Join(hostDir, "ready.json"), ManifestPath: filepath.Join(hostDir, filepath.Base(launchPaths[0])), LogPath: filepath.Join(hostDir, "host.log"), PoolRestartPath: manifest.PoolRestartPath, Live: true, Allowances: map[string]config.World{}}
	statuses := map[string]map[string]any{}
	for _, expected := range manifest.Members {
		world, worldErr := config.RuntimeWorld(expected.ConfigDir)
		if worldErr != nil {
			return nil, worldErr
		}
		status, stale := readRuntimeStatus(world)
		if status == nil || stale {
			return nil, fmt.Errorf("JVM pool member %s is not ready", expected.ConfigDir)
		}
		identity, identityErr := identityFromStatus(status)
		if identityErr != nil {
			return nil, identityErr
		}
		memberBasis, _ := status["member_basis_fingerprint"].(string)
		member := poolMember{World: world, SourceCWD: expected.SourceCWD, Name: expected.Name, WeaverID: expected.WeaverID, GenerationID: expected.GenerationID, Identity: identity, MemberBasis: memberBasis}
		host.Members = append(host.Members, member)
		host.Allowances[expected.WeaverID] = world
		statuses[expected.ConfigDir] = status
	}
	if err := validatePoolAdmission(host, marker, statuses); err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.ensurePoolMapsLocked()
	if existing := s.poolHosts[pool]; existing != nil && existing.Live {
		host = existing
	} else {
		s.poolHosts[pool] = host
		for _, member := range host.Members {
			s.poolMembers[member.World.ConfigDir] = host
		}
	}
	s.mu.Unlock()
	return host, nil
}

func (s *server) restartPooledWeaver(req client.MillWorldRequest, world config.World, pool string) (map[string]any, error) {
	var err error
	s.mu.Lock()
	host := poolHostForConfigLocked(s, world.ConfigDir)
	if host == nil && pool != "" {
		host = s.poolHosts[pool]
	}
	s.mu.Unlock()
	if host == nil && pool != "" {
		host, err = s.discoverPoolHost(pool)
		if err != nil {
			return nil, err
		}
	}
	if host == nil || !host.Live {
		if pool == "" {
			pool, _ = configuredPool(world)
		}
		if pool == "" {
			return nil, errors.New("no running JVM pool host")
		}
		snapshot, snapshotErr := s.poolSnapshot(pool)
		if snapshotErr != nil {
			return nil, snapshotErr
		}
		status, startErr := s.startPooledWeaverFromSnapshot(req, world, pool, nil, snapshot)
		if startErr != nil {
			return status, startErr
		}
		return pooledRestartResult(world, status), nil
	}
	if pool == "" {
		pool = host.Pool
	}
	transitionID := newOpaqueID("transition")
	snapshot, err := s.poolSnapshot(pool)
	if err != nil {
		return nil, err
	}
	source, err := resolveLaunchSource(req.CWD)
	if err != nil {
		return nil, err
	}
	root, err := config.StateRoot()
	if err != nil {
		return nil, err
	}
	manifest, err := poolProbeManifestForHost(host, snapshot, source, root)
	if err != nil {
		return nil, err
	}
	if err := validatePoolProbeManifest(manifest); err != nil {
		return nil, err
	}
	if err := writePoolRestartRecordForHost(host, snapshot, restartStateProbing, transitionID, nil, nil, false, nil); err != nil {
		return nil, fmt.Errorf("persist pooled probe state: %w", err)
	}
	result, err := executePooledProbe(manifest)
	if err != nil {
		status := s.poolStatusForMember(host, world.ConfigDir)
		if status == nil {
			status = baseStatus(world, "pending")
			status["jvm_pool"] = host.Pool
			status["live_members"] = poolConfigDirs(host.Members)
		}
		addPoolPending(status, host, snapshot)
		probe := pooledRestartProbeResult(manifest, result, err)
		if logErr := retainPooledProbeLog(probe, err); logErr != nil {
			return status, fmt.Errorf("%v; retain pooled probe log: %w", err, logErr)
		}
		if recordErr := writePoolRestartRecordForHost(host, snapshot, restartStateRunning, transitionID, probe, &restartFailure{Stage: "probe", Message: err.Error(), LogPath: probe.Log}, false, nil); recordErr != nil {
			return status, fmt.Errorf("%v; retain pooled probe failure: %w", err, recordErr)
		}
		status["probe_error"] = err.Error()
		status["restart_state"] = restartStateFailed
		status["diagnostics"] = []map[string]any{{
			"stage":  "probe",
			"status": "failed",
			"data": map[string]any{
				"message":       err.Error(),
				"generation_id": status["generation_id"],
				"transition_id": transitionID,
			},
		}}
		restartBoundaryStatus(world, status)
		return status, nil
	}
	// Persist the validated result before private-root cleanup and cutover. A
	// caller can inspect this artifact if the subsequent replacement fails.
	hostDir := filepath.Dir(host.ManifestPath)
	if err := atomicPoolJSON(filepath.Join(hostDir, "restart-probe-result.json"), result); err != nil {
		return nil, err
	}
	probe := pooledRestartProbeResult(manifest, result, nil)
	if err := cleanupPoolProbe(manifest); err != nil {
		failure := &restartFailure{Stage: "probe-cleanup", Message: err.Error(), LogPath: probe.Log}
		if recordErr := writePoolRestartRecordForHost(host, snapshot, restartStateFailed, transitionID, probe, failure, false, host); recordErr != nil {
			return nil, fmt.Errorf("cleanup pooled probe: %v; persist failure: %w", err, recordErr)
		}
		return nil, fmt.Errorf("cleanup pooled probe: %w", err)
	}
	if err := writePoolRestartRecordForHost(host, snapshot, restartStateRestarting, transitionID, probe, nil, false, host); err != nil {
		return nil, fmt.Errorf("persist pooled cutover state: %w", err)
	}
	admission := s.poolAdmissionLock(host.Pool)
	admission.Lock()
	defer admission.Unlock()
	if err := stopPoolProcess(host); err != nil {
		failure := &restartFailure{Stage: "stop", Message: err.Error(), LogPath: host.LogPath}
		if recordErr := writePoolRestartRecordForHost(host, snapshot, restartStateFailed, transitionID, probe, failure, false, host); recordErr != nil {
			return nil, fmt.Errorf("stop JVM pool host %q process: %v; persist failure: %w", host.Pool, err, recordErr)
		}
		return nil, fmt.Errorf("stop JVM pool host %q process: %w", host.Pool, err)
	}
	// Preserve custody of a stopped host on every subsequent cleanup failure;
	// it must not remain routable while its old generation is being retired.
	host.Live = false
	if err := writePoolRestartRecordForHost(host, snapshot, restartStateRestarting, transitionID, probe, nil, true, host); err != nil {
		return nil, fmt.Errorf("persist stopped JVM pool host %q: %w", host.Pool, err)
	}
	for _, member := range host.Members {
		if member.Identity.WeaverID != "" {
			if err := cleanupWorldArtifactsOwned(member.World, member.Identity); err != nil {
				failure := &restartFailure{Stage: "artifact-cleanup", Message: err.Error(), LogPath: host.LogPath}
				if recordErr := writePoolRestartRecordForHost(host, snapshot, restartStateFailed, transitionID, probe, failure, true, host); recordErr != nil {
					return nil, fmt.Errorf("restart JVM pool host %q member %s artifact cleanup failed: %v; persist failure: %w", host.Pool, member.World.ConfigDir, err, recordErr)
				}
				return nil, fmt.Errorf("restart JVM pool host %q member %s artifact cleanup failed: %w", host.Pool, member.World.ConfigDir, err)
			}
		}
	}
	if err := removePoolReadyMarker(host); err != nil {
		// Keep the stopped host in Mill custody so status/recovery code and an
		// operator can inspect the failed cutover rather than losing the only
		// evidence of which generation owned the marker.
		failure := &restartFailure{Stage: "marker-cleanup", Message: err.Error(), LogPath: host.LogPath}
		if recordErr := writePoolRestartRecordForHost(host, snapshot, restartStateFailed, transitionID, probe, failure, true, host); recordErr != nil {
			return nil, fmt.Errorf("restart JVM pool host %q cleanup failed: %v; persist failure: %w", host.Pool, err, recordErr)
		}
		return nil, fmt.Errorf("restart JVM pool host %q cleanup failed: %w", host.Pool, err)
	}
	s.removePoolHost(host)
	replacement, err := s.startPooledWeaverFromSnapshot(req, world, pool, nil, snapshot)
	if err != nil {
		probe := pooledRestartProbeResult(manifest, result, err)
		failure := &restartFailure{Stage: "launch", Message: err.Error(), LogPath: host.LogPath}
		if recordErr := writePoolRestartRecordForHost(host, snapshot, restartStateFailed, transitionID, probe, failure, true, host); recordErr != nil {
			return replacement, fmt.Errorf("%v; retain pooled replacement failure: %w", err, recordErr)
		}
		return replacement, err
	}
	s.mu.Lock()
	replacementHost := poolHostForConfigLocked(s, world.ConfigDir)
	s.mu.Unlock()
	if replacementHost != nil {
		if err := writePoolRestartRecordForHost(replacementHost, snapshot, restartStateRunning, transitionID, probe, nil, true, host); err != nil {
			return replacement, fmt.Errorf("persist pooled running state: %w", err)
		}
	}
	return pooledRestartResult(world, replacement), nil
}

func writePoolRestartRecordForHost(host *weaverHost, snapshot jvmpool.PoolSnapshot, state, transitionID string, probe *restartProbeResult, failure *restartFailure, oldGenerationStopped bool, previous *weaverHost) error {
	if host == nil {
		return errors.New("pooled restart record host is missing")
	}
	if strings.TrimSpace(host.PoolRestartPath) == "" {
		return errors.New("pooled restart record path is missing")
	}
	registered := poolConfigDirsFromSnapshot(snapshot)
	if len(registered) == 0 {
		registered = poolConfigDirs(host.Members)
	}
	record := poolRestartRecord{
		Format: poolRestartRecordFormat, JVMPool: host.Pool, State: state,
		TransitionID: transitionID, MembershipRevision: snapshot.Revision,
		RegisteredMembers: registered, PendingMembers: poolPendingDirs(host, registered),
		Probe: probe, Failure: failure, OldGenerationStopped: oldGenerationStopped,
	}
	switch state {
	case restartStateProbing:
		record.AdmittedHost = poolRestartHostFromWeaverHost(host)
	case restartStateRestarting, restartStateFailed:
		record.PreviousHost = poolRestartHostFromWeaverHost(previousOrHost(previous, host))
	case restartStateRunning:
		record.AdmittedHost = poolRestartHostFromWeaverHost(host)
		if previous != nil {
			record.PreviousHost = poolRestartHostFromWeaverHost(previous)
		}
	}
	return writePoolRestartRecord(host.PoolRestartPath, record)
}

func previousOrHost(previous, host *weaverHost) *weaverHost {
	if previous != nil {
		return previous
	}
	return host
}

func poolRestartHostFromWeaverHost(host *weaverHost) *poolRestartHost {
	if host == nil || len(host.Members) == 0 {
		return nil
	}
	basis := host.BasisFingerprint
	if basis == "" {
		for _, member := range host.Members {
			if member.MemberBasis != "" {
				basis = member.MemberBasis
				break
			}
		}
	}
	if basis == "" {
		return nil
	}
	result := &poolRestartHost{HostID: host.HostID, HostGeneration: host.HostGenerationID, PID: host.PID, BasisFingerprint: basis}
	for _, member := range host.Members {
		weaverID, generationID := member.Identity.WeaverID, member.Identity.GenerationID
		if weaverID == "" {
			weaverID = member.WeaverID
		}
		if generationID == "" {
			generationID = member.GenerationID
		}
		result.Members = append(result.Members, poolRestartMember{ConfigDir: member.World.ConfigDir, WeaverID: weaverID, GenerationID: generationID})
	}
	sort.Slice(result.Members, func(i, j int) bool { return result.Members[i].ConfigDir < result.Members[j].ConfigDir })
	return result
}

func poolPendingDirs(host *weaverHost, registered []string) []string {
	seen := map[string]bool{}
	for _, member := range host.Members {
		seen[member.World.ConfigDir] = true
	}
	result := make([]string, 0)
	for _, configDir := range registered {
		if !seen[configDir] {
			result = append(result, configDir)
		}
	}
	return result
}

func pooledRestartProbeResult(manifest poolProbeManifest, result poolProbeResult, err error) *restartProbeResult {
	probeRoot := result.ProbeRoot
	if strings.TrimSpace(probeRoot) == "" {
		probeRoot = manifest.ProbeRoot
	}
	sourceWorkspace := result.SourceWorkspace
	if strings.TrimSpace(sourceWorkspace) == "" && len(manifest.Members) > 0 {
		sourceWorkspace = manifest.Members[0].OriginalConfigDir
	}
	logPath := result.Log
	if strings.TrimSpace(logPath) == "" {
		logPath = filepath.Join(manifest.ProbeRoot, "pool-probe.log")
	}
	completed := result.Completed
	if completed == nil {
		completed = []string{}
	}
	diagnostics := pooledRestartProbeFailureDiagnostics(result, err)
	stage := "probe/failure"
	if result.Success {
		stage = "probe/complete"
	}
	return &restartProbeResult{Success: result.Success, Stage: stage, ProbeWorkspace: probeRoot, SourceWorkspace: sourceWorkspace, Completed: completed, Diagnostics: diagnostics, Log: logPath}
}

func pooledRestartProbeFailureDiagnostics(result poolProbeResult, err error) []map[string]any {
	if result.Success {
		return []map[string]any{}
	}
	stage := result.Stage
	if strings.TrimSpace(stage) == "" {
		stage = "probe/transport"
	}
	data := map[string]any{"completed": append([]string(nil), result.Completed...)}
	if strings.TrimSpace(result.CollectiveDiagnostic) != "" {
		data["collective_diagnostic"] = result.CollectiveDiagnostic
	}
	if err != nil {
		data["message"] = err.Error()
	}
	members := make([]map[string]any, 0, len(result.Members))
	for _, member := range result.Members {
		members = append(members, map[string]any{
			"workspace":     member.OriginalConfigDir,
			"baseline_kind": member.BaselineKind,
			"status":        member.Status,
			"diagnostic":    member.MemberDiagnostic,
		})
	}
	data["members"] = members
	return []map[string]any{{"stage": stage, "status": "failed", "data": data}}
}

func retainPooledProbeLog(probe *restartProbeResult, failure error) error {
	if probe == nil || strings.TrimSpace(probe.Log) == "" {
		return errors.New("pooled probe log path is blank")
	}
	if _, err := os.Stat(probe.Log); err == nil {
		return nil
	} else if !os.IsNotExist(err) {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(probe.Log), 0o755); err != nil {
		return err
	}
	return os.WriteFile(probe.Log, []byte(failure.Error()+"\n"), 0o644)
}

// pooledRestartResult adapts ordinary pool status to the closed restart
// lifecycle boundary. Pool-specific fields remain available to status/list;
// the mill restart handler projects this map to the standard restart wire
// envelope before validation.
func pooledRestartResult(world config.World, status map[string]any) map[string]any {
	if status == nil {
		return nil
	}
	result := cloneStatus(status)
	result["operation"] = "restart"
	result["workspace"] = world.ConfigDir
	return result
}

func poolConfigDirs(members []poolMember) []string {
	result := make([]string, 0, len(members))
	for _, member := range members {
		result = append(result, member.World.ConfigDir)
	}
	return result
}
