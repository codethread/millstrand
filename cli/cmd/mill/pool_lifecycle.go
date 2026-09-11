package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
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
	host := &weaverHost{Pool: snapshot.Pool, HostID: hostID, HostGenerationID: hostGeneration, MembershipRev: snapshot.Revision, LaunchToken: newOpaqueID("launch"), Admission: &sync.RWMutex{}, ReadyPath: filepath.Join(hostDir, "ready.json"), ManifestPath: filepath.Join(hostDir, "launch-"+hostID+".json"), LogPath: filepath.Join(hostDir, "host.log"), Allowances: map[string]config.World{}}
	manifest := poolLaunchManifest{Format: poolLaunchFormat, JVMPool: snapshot.Pool, HostID: hostID, HostGenerationID: hostGeneration, MembershipRevision: snapshot.Revision, MillstrandSource: source, MillstrandVersion: config.Version}
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
	if err := clearPooledRestartRecords(host.Members); err != nil {
		return nil, fmt.Errorf("pooled host started but restart state cleanup failed: %w", err)
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
				status, stale := readStatus(member.World)
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
		return nil, err
	}
	for _, member := range host.Members {
		if member.Identity.WeaverID != "" {
			if err := cleanupWorldArtifactsOwned(member.World, member.Identity); err != nil {
				return nil, err
			}
		}
	}
	if err := removePoolReadyMarker(host); err != nil {
		// The process and member artifacts have already stopped, but retaining
		// the host routes keeps the failed cleanup inspectable and prevents a
		// later lifecycle call from treating the stale marker as an unknown host.
		host.Live = false
		return nil, fmt.Errorf("stop JVM pool host %q: %w", host.Pool, err)
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
	hostDir, err := jvmpool.PoolHostDir(root, pool)
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
	process, err := os.FindProcess(marker.PID)
	if err != nil {
		return nil, err
	}
	host := &weaverHost{Pool: pool, HostID: marker.HostID, HostGenerationID: marker.HostGenerationID, MembershipRev: marker.MembershipRevision, PID: marker.PID, cmd: &exec.Cmd{Process: process}, Admission: &sync.RWMutex{}, ReadyPath: filepath.Join(hostDir, "ready.json"), ManifestPath: filepath.Join(hostDir, filepath.Base(launchPaths[0])), LogPath: filepath.Join(hostDir, "host.log"), Live: true, Allowances: map[string]config.World{}}
	statuses := map[string]map[string]any{}
	for _, expected := range manifest.Members {
		world, worldErr := config.RuntimeWorld(expected.ConfigDir)
		if worldErr != nil {
			return nil, worldErr
		}
		status, stale := readStatus(world)
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
		if recordErr := writePooledRestartRecords(host.Members, world, restartStateRunning, transitionID, probe, &restartFailure{Stage: "probe", Message: err.Error(), LogPath: probe.Log}, false); recordErr != nil {
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
	if err := cleanupPoolProbe(manifest); err != nil {
		return nil, err
	}
	admission := s.poolAdmissionLock(host.Pool)
	admission.Lock()
	defer admission.Unlock()
	if err := stopPoolProcess(host); err != nil {
		return nil, err
	}
	for _, member := range host.Members {
		if member.Identity.WeaverID != "" {
			if err := cleanupWorldArtifactsOwned(member.World, member.Identity); err != nil {
				return nil, err
			}
		}
	}
	if err := removePoolReadyMarker(host); err != nil {
		// Keep the stopped host in Mill custody so status/recovery code and an
		// operator can inspect the failed cutover rather than losing the only
		// evidence of which generation owned the marker.
		host.Live = false
		return nil, fmt.Errorf("restart JVM pool host %q cleanup failed: %w", host.Pool, err)
	}
	s.removePoolHost(host)
	replacement, err := s.startPooledWeaverFromSnapshot(req, world, pool, nil, snapshot)
	if err != nil {
		probe := pooledRestartProbeResult(manifest, result, err)
		failure := &restartFailure{Stage: "launch", Message: err.Error(), LogPath: host.LogPath}
		if recordErr := writePooledRestartRecords(host.Members, world, restartStateFailed, transitionID, probe, failure, true); recordErr != nil {
			return replacement, fmt.Errorf("%v; retain pooled replacement failure: %w", err, recordErr)
		}
		return replacement, err
	}
	return pooledRestartResult(world, replacement), nil
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

func writePooledRestartRecords(members []poolMember, selected config.World, state, transitionID string, probe *restartProbeResult, failure *restartFailure, oldGenerationStopped bool) error {
	recordMembers := append([]poolMember(nil), members...)
	selectedPresent := false
	for _, member := range members {
		if member.World.ConfigDir == selected.ConfigDir {
			selectedPresent = true
			break
		}
	}
	if !selectedPresent {
		recordMembers = append(recordMembers, poolMember{World: selected})
	}
	for _, member := range recordMembers {
		recordState := state
		record := restartRecord{State: recordState, TransitionID: transitionID, Probe: probe, Failure: failure, OldGenerationStopped: oldGenerationStopped}
		if member.GenerationID != "" {
			record.PreviousGeneration = member.GenerationID
			record.PreviousWeaver = member.WeaverID
		}
		if state == restartStateRunning {
			record.GenerationID = member.GenerationID
			if record.GenerationID == "" {
				recordState = restartStateFailed
				record.State = recordState
			}
		}
		if err := writeRestartRecordFn(member.World, record); err != nil {
			return fmt.Errorf("workspace %s: %w", member.World.ConfigDir, err)
		}
	}
	return nil
}

func clearPooledRestartRecords(members []poolMember) error {
	for _, member := range members {
		err := os.Remove(restartRecordPath(member.World))
		if err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("workspace %s: %w", member.World.ConfigDir, err)
		}
	}
	return nil
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
