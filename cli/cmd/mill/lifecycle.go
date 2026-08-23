package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/process"
)

func resolveLifecycleWorld(req client.MillWorldRequest) (config.World, error) {
	world, err := config.BootstrapTargetWorld(req.CWD, req.ConfigDir)
	if err != nil {
		return config.World{}, err
	}
	_, loaded, err := config.Load(world.ConfigDir)
	if err != nil {
		return config.World{}, err
	}
	return loaded, nil
}

const defaultWeaverReadyTimeout = 5 * time.Minute

// sourceDiagOut receives launch-source warning diagnostics (e.g. a configured
// installed source that has become unusable and is being bypassed). Defaults to
// stderr so it never corrupts stdout doc/JSON output; overridable in tests.
var sourceDiagOut io.Writer = os.Stderr

func resolveLaunchSource(cwd string) (string, error) {
	if source := os.Getenv("MILLSTRAND_SOURCE"); source != "" {
		return config.ValidateSource("MILLSTRAND_SOURCE", source)
	}
	var installedErr error
	if config.InstalledSource != "" {
		resolved, err := config.ValidateSource("installed Millstrand source", config.InstalledSource)
		if err == nil {
			return resolved, nil
		}
		installedErr = err
	}
	root, rootErr := config.GitRoot(cwd)
	if rootErr == nil {
		resolved, err := config.ValidateSource("canonical Millstrand checkout cwd", root)
		if err == nil {
			if installedErr != nil {
				// mill was installed with a source checkout that has since become
				// unusable; the cwd fallback keeps launch working but the operator
				// must know which checkout ran and that a reinstall is pending —
				// silently launching a different checkout is the trap this warns off.
				_, _ = fmt.Fprintf(sourceDiagOut, "warning: configured installed Millstrand source %q is unusable (%v); launching weaver from cwd checkout %q instead — reinstall mill from the canonical checkout to refresh it\n", config.InstalledSource, installedErr, resolved)
			}
			return resolved, nil
		}
		rootErr = err
	}
	if installedErr != nil {
		return "", fmt.Errorf("unable to resolve Millstrand source for weaver launch; installed source is unusable (%w), and cwd is not a canonical Millstrand checkout (%v); set MILLSTRAND_SOURCE to a Millstrand checkout or reinstall mill from the canonical checkout", installedErr, rootErr)
	}
	return "", fmt.Errorf("unable to resolve Millstrand source for weaver launch; set MILLSTRAND_SOURCE to a Millstrand checkout, reinstall mill with a valid install-time source, or run mill weaver start from a canonical Millstrand checkout cwd containing deps.edn")
}

func weaverArgs(world config.World, name string) []string {
	args := []string{"-M:millstrand", "-m", "millstrand.core.weaver.runtime", "--workspace", world.ConfigDir, "--state-dir", world.StateDir, "--data-dir", world.DataDir}
	if name != "" {
		args = append(args, "--name", name)
	}
	return args
}

func friendlyName(world config.World, requested string) (string, error) {
	if requested != "" {
		if strings.TrimSpace(requested) == "" {
			return "", fmt.Errorf("weaver name must not be blank")
		}
		return requested, nil
	}
	cfg, _, err := config.Load(world.ConfigDir)
	if err != nil {
		return "", err
	}
	if cfg.Name != "" {
		return cfg.Name, nil
	}
	base := filepath.Base(world.ConfigDir)
	if base == "." || base == string(filepath.Separator) || strings.TrimSpace(base) == "" {
		return "", fmt.Errorf("unable to derive weaver name from workspace %s", world.ConfigDir)
	}
	return base, nil
}

func (s *server) startWeaver(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	if req.ReadyTimeoutMs < 0 {
		return nil, fmt.Errorf("invalid ready_timeout_ms %d: must be positive milliseconds, or omitted for the default", req.ReadyTimeoutMs)
	}
	if transition := s.lifecycleTransition(world.ConfigDir); transition != nil {
		// A probe leaves the admitted old generation serving.  Starting during
		// cutover joins the one shared replacement instead of launching a second
		// child; failed state is retained for an explicit restart recovery.
		if transition.state() == restartStateProbing {
			return s.admittedGenerationStatus(world, transition), nil
		}
		return waitForLifecycleTransition(transition, readyTimeoutFor(req.ReadyTimeoutMs))
	}
	if record, ok := readRestartRecord(world); ok && record.State == restartStateFailed {
		return record.status(world), nil
	}
	s.mu.Lock()
	if child := s.children[world.ConfigDir]; child != nil && child.cmd.Process != nil && processAlive(child.cmd.Process.Pid) {
		status, stale := readStatus(world)
		if status != nil && !stale {
			status["generation_id"] = child.generationID
			s.mu.Unlock()
			return status, nil
		}
		if status == nil {
			status = baseStatusWithName(world, "starting", child.name)
			status["pid"] = child.cmd.Process.Pid
		}
		s.mu.Unlock()
		return status, nil
	}
	if status, stale := readStatus(world); status != nil {
		s.mu.Unlock()
		if !stale {
			return status, nil
		}
		return nil, fmt.Errorf("stale weaver metadata for selected workspace: %v", status["stale_reason"])
	}
	s.mu.Unlock()
	source, err := resolveLaunchSource(req.CWD)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(world.DataDir, 0o755); err != nil {
		return nil, err
	}
	name, err := friendlyName(world, req.Name)
	if err != nil {
		return nil, err
	}
	// Weaver stdout/stderr go to a per-weaver log, never to mill's own log:
	// appended across restarts so a crashed boot stays post-mortem readable,
	// and deliberately left in place by cleanupWorldArtifacts.
	logPath := weaverLogPath(world.StateDir)
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, err
	}
	_, _ = fmt.Fprintf(logFile, "=== weaver start %s config_dir=%s ===\n", time.Now().UTC().Format(time.RFC3339), world.ConfigDir)
	cmd, err := launchWeaver(source, weaverArgs(world, name), logFile, logFile)
	if err != nil {
		_ = logFile.Close()
		return nil, err
	}
	done := make(chan error, 1)
	s.mu.Lock()
	if child := s.children[world.ConfigDir]; child != nil && child.cmd.Process != nil && processAlive(child.cmd.Process.Pid) {
		s.mu.Unlock()
		terminateProcess(cmd.Process)
		go func() {
			_ = cmd.Wait()
			_ = logFile.Close()
		}()
		status := baseStatusWithName(world, "starting", child.name)
		status["pid"] = child.cmd.Process.Pid
		status["generation_id"] = child.generationID
		return status, nil
	}
	registered := &weaverChild{cmd: cmd, world: world, name: name, done: done, generationID: newOpaqueID("generation")}
	s.children[world.ConfigDir] = registered
	s.mu.Unlock()
	go func() {
		err := cmd.Wait()
		_ = logFile.Close()
		done <- err
	}()
	// Weaver startup includes JVM boot plus trusted config evaluation
	// (spool sync and module loads), which can far exceed a bare boot.
	readyTimeout := defaultWeaverReadyTimeout
	if req.ReadyTimeoutMs > 0 {
		readyTimeout = time.Duration(req.ReadyTimeoutMs) * time.Millisecond
	}
	status, err := waitForReadyStatus(world, cmd.Process.Pid, done, readyTimeout)
	if err != nil {
		terminateProcess(cmd.Process)
		select {
		case <-done:
		default:
		}
		// The ready wait runs unlocked, so a sibling start may have replaced
		// this entry after our weaver died; only the still-registered owner
		// may remove supervision state and world artifacts, or a failed
		// early start would tear down its successor's healthy weaver.
		if s.releaseChild(world.ConfigDir, registered) {
			cleanupWorldArtifacts(world)
		}
		if tail := tailOfFile(logPath, 4096); tail != "" {
			return nil, fmt.Errorf("%w; weaver log tail (%s):\n%s", err, logPath, tail)
		}
		return nil, fmt.Errorf("%w; weaver log: %s", err, logPath)
	}
	identity, err := identityFromStatus(status)
	if err != nil {
		return nil, fmt.Errorf("weaver ready metadata identity is invalid: %w", err)
	}
	if identity.PID != cmd.Process.Pid {
		return nil, fmt.Errorf("weaver ready metadata pid %d does not match launched pid %d", identity.PID, cmd.Process.Pid)
	}
	registered.identity = identity
	status["generation_id"] = registered.generationID
	millLogf("weaver started config_dir=%s state_dir=%s pid=%v", world.ConfigDir, world.StateDir, status["pid"])
	return status, nil
}

// releaseChild removes the supervision entry for configDir only when it is
// still the given child, reporting whether the caller owns the world's
// artifacts. A false return means another start replaced the entry and now
// owns the workspace.
func (s *server) releaseChild(configDir string, child *weaverChild) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.children[configDir] != child {
		return false
	}
	delete(s.children, configDir)
	return true
}

func (s *server) weaverStatus(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	return s.weaverStatusForWorld(world), nil
}

func (s *server) weaverReplContext(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	status := s.weaverStatusForWorld(world)
	if status["state"] != "running" {
		return status, nil
	}
	source, err := resolveLaunchSource(req.CWD)
	if err != nil {
		return nil, err
	}
	status["source"] = source
	return status, nil
}

func (s *server) weaverList() ([]map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	seen := map[string]bool{}
	rows := []map[string]any{}
	for _, child := range s.children {
		status := s.weaverStatusForWorldLocked(child.world)
		rows = append(rows, status)
		seen[child.world.StateDir] = true
	}
	root, err := config.StateRoot()
	if err != nil {
		return nil, err
	}
	matches, err := filepath.Glob(filepath.Join(root, "weavers", "*", "weaver.json"))
	if err != nil {
		return nil, err
	}
	for _, path := range matches {
		stateDir := filepath.Dir(path)
		if seen[stateDir] {
			continue
		}
		status, err := readStatusFile(path)
		if err != nil {
			return nil, err
		}
		rows = append(rows, status)
	}
	return rows, nil
}

func (s *server) weaverStatusForWorld(world config.World) map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.weaverStatusForWorldLocked(world)
}

func (s *server) weaverStatusForWorldLocked(world config.World) map[string]any {
	if transition := s.transitions[world.ConfigDir]; transition != nil {
		switch transition.state() {
		case restartStateProbing:
			return s.admittedGenerationStatus(world, transition)
		case restartStateRestarting:
			status := baseStatusWithName(world, restartStateRestarting, "")
			status["transition_id"] = transition.transitionID
			return status
		case restartStateFailed:
			return transitionResultStatus(transition)
		}
	}
	if record, ok := readRestartRecord(world); ok && record.State == restartStateFailed {
		return record.status(world)
	}
	if status, stale := readStatus(world); status != nil {
		if stale {
			status["state"] = "stale"
		}
		return status
	}
	if child := s.children[world.ConfigDir]; child != nil {
		if child.cmd.Process != nil && processAlive(child.cmd.Process.Pid) {
			status := baseStatusWithName(world, "starting", child.name)
			status["pid"] = child.cmd.Process.Pid
			status["generation_id"] = child.generationID
			return status
		}
		status := baseStatusWithName(world, "stopped", child.name)
		return status
	}
	return baseStatus(world, "none")
}

func (s *server) stopWeaver(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	child := s.children[world.ConfigDir]
	if child == nil || child.cmd.Process == nil || !processAlive(child.cmd.Process.Pid) {
		delete(s.children, world.ConfigDir)
		if status, stale := readStatus(world); status != nil {
			if stale {
				// Loud staleness check (as the old socket stop had): drop the
				// dead/mismatched runtime metadata and report stopped.
				cleanupWorldArtifacts(world)
				return baseStatus(world, "stopped"), nil
			}
			// A live weaver this mill does not supervise (e.g. started by a
			// previous mill): the socket `stop` op no longer exists, so signal
			// the pid and let the weaver's shutdown hook clean its own runtime
			// metadata, then wait for that cleanup.
			pid, ok := status["pid"].(int)
			if !ok || pid <= 0 {
				return nil, fmt.Errorf("selected workspace weaver metadata is missing a valid pid")
			}
			terminatePID(pid)
			waitForPIDExit(pid, 5*time.Second)
			cleanupWorldArtifacts(world)
			st := baseStatus(world, "stopped")
			st["pid"] = pid
			millLogf("weaver stopped (unsupervised) config_dir=%s state_dir=%s pid=%d", world.ConfigDir, world.StateDir, pid)
			return st, nil
		}
		return baseStatus(world, "stopped"), nil
	}
	pid := child.cmd.Process.Pid
	terminateProcess(child.cmd.Process)
	select {
	case <-child.done:
	case <-time.After(5 * time.Second):
		_ = child.cmd.Process.Kill()
		<-child.done
	}
	cleanupWorldArtifacts(world)
	delete(s.children, world.ConfigDir)
	status := baseStatus(world, "stopped")
	status["pid"] = pid
	millLogf("weaver stopped config_dir=%s state_dir=%s pid=%d", world.ConfigDir, world.StateDir, pid)
	return status, nil
}

func (s *server) stopAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, child := range s.children {
		if child.cmd != nil && child.cmd.Process != nil && processAlive(child.cmd.Process.Pid) {
			terminateProcess(child.cmd.Process)
			select {
			case <-child.done:
			case <-time.After(5 * time.Second):
				_ = child.cmd.Process.Kill()
				<-child.done
			}
			cleanupWorldArtifacts(child.world)
		}
	}
}

func readStatus(world config.World) (map[string]any, bool) {
	metadataPath := filepath.Join(world.StateDir, "weaver.json")
	b, err := os.ReadFile(metadataPath)
	if err != nil {
		return nil, false
	}
	var m client.Metadata
	if err := json.Unmarshal(b, &m); err != nil {
		st := baseStatus(world, "stale")
		st["stale_reason"] = fmt.Sprintf("malformed weaver metadata: %v", err)
		return st, true
	}
	if staleReason := validateMetadata(world, m); staleReason != "" {
		st := baseStatus(world, "stale")
		st["stale_reason"] = staleReason
		return st, true
	}
	status := statusFromMetadata(m, "running")
	if record, ok := readRestartRecord(world); ok && record.State == restartStateRunning {
		mergeRestartRecordStatus(status, record)
	}
	return status, false
}

func readStatusFile(path string) (map[string]any, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var m client.Metadata
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, fmt.Errorf("malformed weaver metadata %s: %w", path, err)
	}
	world := config.World{ConfigDir: m.ConfigDir, StateDir: m.StateDir, DataDir: m.DataDir, DBPath: m.DatabasePathString()}
	if staleReason := validateMetadata(world, m); staleReason != "" {
		if strings.HasPrefix(staleReason, "pid ") {
			st := statusFromMetadata(m, "stale")
			st["stale_reason"] = staleReason
			return st, nil
		}
		return nil, fmt.Errorf("malformed weaver metadata %s: %s", path, staleReason)
	}
	return statusFromMetadata(m, "running"), nil
}

func statusFromMetadata(m client.Metadata, state string) map[string]any {
	return map[string]any{
		"state":          state,
		"config_dir":     m.ConfigDir,
		"state_dir":      m.StateDir,
		"data_dir":       m.DataDir,
		"database_kind":  m.DatabaseKind,
		"database_label": m.DatabaseLabel,
		"database_path":  m.DatabasePath,
		"name":           m.Name,
		"pid":            m.PID,
		"weaver_id":      m.DaemonID,
		"socket_path":    m.SocketPath,
		"nrepl":          m.NREPL,
		"started_at":     m.StartedAt,
		"log_path":       weaverLogPath(m.StateDir),
	}
}

func validateMetadata(world config.World, m client.Metadata) string {
	if m.ProtocolVersion != client.ProtocolVersion || m.PID == 0 || m.DaemonID == "" || m.ConfigDir == "" || m.StateDir == "" || m.DataDir == "" || strings.TrimSpace(m.Name) == "" || m.SocketPath == "" || m.StartedAt == "" || m.NREPL.Host == "" || m.NREPL.Port == 0 {
		return "malformed weaver metadata: missing required fields"
	}
	if err := client.ValidateStorageIdentity(m); err != nil {
		return err.Error()
	}
	if m.DatabaseKind != "sqlite-file" {
		return fmt.Sprintf("mill supervises file-backed weavers; unsupported storage kind %q", m.DatabaseKind)
	}
	if !samePath(m.ConfigDir, world.ConfigDir) || !samePath(m.StateDir, world.StateDir) || !samePath(m.DataDir, world.DataDir) || !samePath(m.DatabasePathString(), world.DBPath) || !samePath(m.SocketPath, filepath.Join(world.StateDir, "weaver.sock")) {
		return "weaver metadata identity mismatch"
	}
	if !processAlive(m.PID) {
		return fmt.Sprintf("pid %d is not alive", m.PID)
	}
	return ""
}

func samePath(a, b string) bool {
	if a == "" || b == "" {
		return a == b
	}
	realA, errA := filepath.EvalSymlinks(a)
	if errA != nil {
		realA = filepath.Clean(a)
	}
	realB, errB := filepath.EvalSymlinks(b)
	if errB != nil {
		realB = filepath.Clean(b)
	}
	return realA == realB
}

func cleanupWorldArtifacts(world config.World) {
	_ = os.Remove(filepath.Join(world.StateDir, "weaver.json"))
	_ = os.Remove(filepath.Join(world.StateDir, "weaver.edn"))
	_ = os.Remove(filepath.Join(world.StateDir, "weaver.sock"))
}

func weaverLogPath(stateDir string) string {
	return filepath.Join(stateDir, "weaver.log")
}

// tailOfFile returns up to the last maxBytes of the file, trimmed, or "" when
// the file is missing, unreadable, or empty — a diagnostic must never turn a
// lifecycle failure into an I/O error of its own.
func tailOfFile(path string, maxBytes int64) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer func() { _ = f.Close() }()
	info, err := f.Stat()
	if err != nil {
		return ""
	}
	offset := info.Size() - maxBytes
	if offset < 0 {
		offset = 0
	}
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return ""
	}
	b, err := io.ReadAll(f)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func baseStatus(world config.World, state string) map[string]any {
	return baseStatusWithName(world, state, "")
}

func baseStatusWithName(world config.World, state string, requestedName string) map[string]any {
	name, err := friendlyName(world, requestedName)
	if err != nil {
		name = ""
	}
	return map[string]any{
		"state":      state,
		"config_dir": world.ConfigDir,
		"state_dir":  world.StateDir,
		"data_dir":   world.DataDir,
		// mill-managed workspace weavers are always file-backed SQLite
		"database_kind":  "sqlite-file",
		"database_label": world.DBPath,
		"database_path":  world.DBPath,
		"name":           name,
		"log_path":       weaverLogPath(world.StateDir),
	}
}

func terminateProcess(p *os.Process) {
	if p == nil {
		return
	}
	terminatePID(p.Pid)
	_ = p.Signal(syscall.SIGTERM)
}

func terminatePID(pid int) {
	if pid > 0 {
		_ = syscall.Kill(-pid, syscall.SIGTERM)
		if p, err := os.FindProcess(pid); err == nil {
			_ = p.Signal(syscall.SIGTERM)
		}
	}
}

// waitForPIDExit blocks until pid is no longer alive or timeout elapses,
// escalating to SIGKILL on timeout so a stuck weaver cannot linger.
func waitForPIDExit(pid int, timeout time.Duration) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if !processAlive(pid) {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	if pid > 0 {
		_ = syscall.Kill(pid, syscall.SIGKILL)
	}
}

func processAlive(pid int) bool {
	return process.Alive(pid)
}
