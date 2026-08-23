package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

const (
	restartStateProbing    = "probing"
	restartStateRestarting = "restarting"
	restartStateRunning    = "running"
	restartStateFailed     = "failed"
)

// restartProbeResult is the only Clojure-owned shape Mill accepts.  The
// parser below deliberately validates it once at the subprocess boundary;
// transition code receives this typed value and never inspects raw JSON.
type restartProbeResult struct {
	Success         bool             `json:"success"`
	Stage           string           `json:"stage"`
	ProbeWorkspace  string           `json:"probe/workspace"`
	SourceWorkspace string           `json:"source/workspace"`
	Completed       []string         `json:"completed"`
	Diagnostics     []map[string]any `json:"diagnostics"`
	Log             string           `json:"log"`
}

func (r restartProbeResult) validate() error {
	if r.Stage == "" {
		return errors.New("restart probe result missing stage")
	}
	if r.ProbeWorkspace == "" {
		return errors.New("restart probe result missing probe/workspace")
	}
	if r.SourceWorkspace == "" {
		return errors.New("restart probe result missing source/workspace")
	}
	if r.Log == "" {
		return errors.New("restart probe result missing log")
	}
	if r.Success && r.Stage != "probe/complete" {
		return fmt.Errorf("restart probe success has unexpected stage %q", r.Stage)
	}
	if !r.Success && r.Stage != "probe/failure" {
		return fmt.Errorf("restart probe failure has unexpected stage %q", r.Stage)
	}
	return nil
}

func decodeRestartProbe(data []byte) (restartProbeResult, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	var result restartProbeResult
	if err := decoder.Decode(&result); err != nil {
		return restartProbeResult{}, fmt.Errorf("malformed restart probe JSON: %w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		if err == nil {
			return restartProbeResult{}, errors.New("malformed restart probe JSON: multiple values")
		}
		return restartProbeResult{}, fmt.Errorf("malformed restart probe JSON: %w", err)
	}
	if err := result.validate(); err != nil {
		return restartProbeResult{}, err
	}
	return result, nil
}

// probeRuntime is replaceable by focused lifecycle tests.  The production
// implementation invokes the Clojure-owned probe exactly once and parses its
// one JSON result at this boundary.
var probeRuntime = runFreshRuntimeProbe

const freshRuntimeProbeExpression = `(require '[clojure.data.json :as json] '[millstrand.core.weaver.runtime :as runtime]) (println (json/write-str (runtime/fresh-runtime-probe! {:config-dir (System/getenv "MILLSTRAND_PROBE_CONFIG") :state-dir (System/getenv "MILLSTRAND_PROBE_STATE") :data-dir (System/getenv "MILLSTRAND_PROBE_DATA")})))`

func runFreshRuntimeProbe(source string, world config.World) (restartProbeResult, error) {
	cmd := exec.Command("clojure", "-M:millstrand", "-e", freshRuntimeProbeExpression)
	cmd.Dir = source
	cmd.Env = append(os.Environ(),
		"MILLSTRAND_PROBE_CONFIG="+world.ConfigDir,
		"MILLSTRAND_PROBE_STATE="+world.StateDir,
		"MILLSTRAND_PROBE_DATA="+world.DataDir,
	)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if stderr.Len() > 0 {
			return restartProbeResult{}, fmt.Errorf("restart probe process failed: %w: %s", err, strings.TrimSpace(stderr.String()))
		}
		return restartProbeResult{}, fmt.Errorf("restart probe process failed: %w", err)
	}
	result, err := decodeRestartProbe(stdout.Bytes())
	if err != nil {
		if stderr.Len() > 0 {
			return restartProbeResult{}, fmt.Errorf("%w; probe stderr: %s", err, strings.TrimSpace(stderr.String()))
		}
		return restartProbeResult{}, err
	}
	return result, nil
}

type restartFailure struct {
	Stage        string `json:"stage"`
	Message      string `json:"message"`
	LogPath      string `json:"log_path,omitempty"`
	ExitEvidence string `json:"exit_evidence,omitempty"`
}

type restartRecord struct {
	State              string              `json:"state"`
	TransitionID       string              `json:"transition_id"`
	GenerationID       string              `json:"generation_id"`
	PreviousGeneration string              `json:"previous_generation_id,omitempty"`
	UpdatedAt          string              `json:"updated_at"`
	Probe              *restartProbeResult `json:"probe,omitempty"`
	Failure            *restartFailure     `json:"failure,omitempty"`
}

func restartRecordPath(world config.World) string {
	return filepath.Join(world.StateDir, "restart.json")
}

func writeRestartRecord(world config.World, record restartRecord) error {
	if record.State != restartStateProbing && record.State != restartStateRestarting && record.State != restartStateRunning && record.State != restartStateFailed {
		return fmt.Errorf("invalid restart state %q", record.State)
	}
	record.UpdatedAt = time.Now().UTC().Format(time.RFC3339Nano)
	data, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal restart state: %w", err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(world.StateDir, "restart.json.*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if _, err := tmp.Write(append(data, '\n')); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, restartRecordPath(world))
}

func readRestartRecord(world config.World) (restartRecord, bool) {
	data, err := os.ReadFile(restartRecordPath(world))
	if err != nil {
		return restartRecord{}, false
	}
	var record restartRecord
	if err := json.Unmarshal(data, &record); err != nil || record.State == "" || record.TransitionID == "" {
		return restartRecord{}, false
	}
	return record, true
}

func (r restartRecord) status(world config.World) map[string]any {
	status := baseStatus(world, r.State)
	if r.GenerationID != "" {
		status["generation_id"] = r.GenerationID
	}
	if r.PreviousGeneration != "" {
		status["previous_generation_id"] = r.PreviousGeneration
	}
	status["transition_id"] = r.TransitionID
	if r.Probe != nil {
		status["probe"] = *r.Probe
	}
	if r.Failure != nil {
		status["failure"] = *r.Failure
	}
	return status
}

func transitionResultStatus(t *weaverTransition) map[string]any {
	if t.result != nil {
		return t.result
	}
	status := baseStatus(t.world, t.state())
	status["transition_id"] = t.transitionID
	return status
}

func mergeRestartRecordStatus(status map[string]any, record restartRecord) {
	if record.GenerationID != "" {
		status["generation_id"] = record.GenerationID
	}
	if record.TransitionID != "" {
		status["transition_id"] = record.TransitionID
	}
	if record.Probe != nil {
		status["probe"] = *record.Probe
	}
	if record.Failure != nil {
		status["restart_failure"] = *record.Failure
	}
}

type weaverTransition struct {
	mu           sync.Mutex
	world        config.World
	old          *weaverChild
	transitionID string
	stateValue   string
	generationID string
	probe        *restartProbeResult
	result       map[string]any
	err          error
	done         chan struct{}
}

func (t *weaverTransition) state() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.stateValue
}

func (s *server) lifecycleTransition(configDir string) *weaverTransition {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transitions[configDir]
}

func readyTimeoutFor(ms int64) time.Duration {
	if ms > 0 {
		return time.Duration(ms) * time.Millisecond
	}
	return defaultWeaverReadyTimeout
}

func waitForLifecycleTransition(t *weaverTransition, timeout time.Duration) (map[string]any, error) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-t.done:
		return t.result, t.err
	case <-timer.C:
		return nil, fmt.Errorf("weaver restart did not become ready before timeout")
	}
}

func newOpaqueID(prefix string) string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		// crypto/rand failure is a process-level inability to create the opaque
		// identity that protects the lifecycle boundary; fail loudly.
		panic(fmt.Sprintf("generate %s id: %v", prefix, err))
	}
	return prefix + "-" + hex.EncodeToString(b[:])
}

func (s *server) admittedGenerationStatus(world config.World, t *weaverTransition) map[string]any {
	if t.old != nil {
		if status, stale := readStatus(world); status != nil && !stale {
			status["generation_id"] = t.old.generationID
			status["transition_id"] = t.transitionID
			status["restart_state"] = restartStateProbing
			return status
		}
	}
	return baseStatusWithName(world, restartStateProbing, "")
}

func (s *server) setTransitionState(t *weaverTransition, state string, probe *restartProbeResult, failure *restartFailure) error {
	s.mu.Lock()
	t.mu.Lock()
	t.stateValue = state
	if probe != nil {
		copy := *probe
		t.probe = &copy
	}
	t.mu.Unlock()
	record := restartRecord{State: state, TransitionID: t.transitionID, GenerationID: t.generationID}
	if t.old != nil {
		record.PreviousGeneration = t.old.generationID
	}
	if probe != nil {
		record.Probe = probe
	} else {
		record.Probe = t.probe
	}
	record.Failure = failure
	err := writeRestartRecord(t.world, record)
	s.mu.Unlock()
	return err
}

func (s *server) completeTransition(t *weaverTransition, result map[string]any, err error, retain bool) {
	s.mu.Lock()
	t.result, t.err = result, err
	if !retain && s.transitions[t.world.ConfigDir] == t {
		delete(s.transitions, t.world.ConfigDir)
	}
	close(t.done)
	s.mu.Unlock()
}

func (s *server) restartWeaver(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	if req.ReadyTimeoutMs < 0 {
		return nil, fmt.Errorf("invalid ready_timeout_ms %d: must be positive milliseconds, or omitted for the default", req.ReadyTimeoutMs)
	}
	s.mu.Lock()
	if s.transitions == nil {
		s.transitions = map[string]*weaverTransition{}
	}
	if existing := s.transitions[world.ConfigDir]; existing != nil {
		if existing.state() == restartStateFailed {
			// A later restart is the explicit recovery operation from retained
			// failed state; replace only the completed transition record.
			delete(s.transitions, world.ConfigDir)
		} else {
			s.mu.Unlock()
			return waitForLifecycleTransition(existing, readyTimeoutFor(req.ReadyTimeoutMs))
		}
	}
	old := s.children[world.ConfigDir]
	if old == nil {
		if status, stale := readStatus(world); status != nil && !stale {
			pid, ok := status["pid"].(int)
			if !ok || pid <= 0 {
				s.mu.Unlock()
				return nil, errors.New("selected workspace weaver metadata is missing a recorded pid")
			}
			process, findErr := os.FindProcess(pid)
			if findErr != nil {
				s.mu.Unlock()
				return nil, fmt.Errorf("find recorded weaver pid %d: %w", pid, findErr)
			}
			old = &weaverChild{cmd: &exec.Cmd{Process: process}, world: world, name: fmt.Sprint(status["name"]), generationID: fmt.Sprint(status["generation_id"])}
		} else if record, ok := readRestartRecord(world); !ok || record.State != restartStateFailed {
			s.mu.Unlock()
			return nil, fmt.Errorf("no running weaver for selected workspace")
		}
	}
	t := &weaverTransition{world: world, old: old, transitionID: newOpaqueID("transition"), stateValue: restartStateProbing, done: make(chan struct{})}
	if t.old != nil && t.old.generationID == "" {
		t.old.generationID = newOpaqueID("generation")
	}
	s.transitions[world.ConfigDir] = t
	s.mu.Unlock()
	if err := s.setTransitionState(t, restartStateProbing, nil, nil); err != nil {
		s.completeTransition(t, nil, err, true)
		return nil, err
	}
	go s.runRestartTransition(t, req)
	return waitForLifecycleTransition(t, readyTimeoutFor(req.ReadyTimeoutMs))
}

func (s *server) runRestartTransition(t *weaverTransition, req client.MillWorldRequest) {
	source, err := resolveLaunchSource(req.CWD)
	if err != nil {
		s.failRestart(t, "probe", err, "")
		return
	}
	probe, err := probeRuntime(source, t.world)
	if err != nil {
		// Probe transport/shape failures are retained as diagnostics while the
		// admitted old generation remains in service.
		s.failProbe(t, nil, err)
		return
	}
	if !probe.Success {
		s.failProbe(t, &probe, fmt.Errorf("restart probe failed at %s", probe.Stage))
		return
	}
	if err := s.setTransitionState(t, restartStateRestarting, &probe, nil); err != nil {
		s.failRestart(t, "state", err, probe.Log)
		return
	}
	if t.old != nil {
		if err := stopRecordedGeneration(t.old, t.world); err != nil {
			s.failRestart(t, "stop", err, probe.Log)
			return
		}
	}
	status, child, err := s.launchReplacement(source, t.world, req.Name, readyTimeoutFor(req.ReadyTimeoutMs))
	if err != nil {
		s.failRestart(t, "launch", err, probe.Log)
		return
	}
	t.generationID = child.generationID
	status["generation_id"] = child.generationID
	status["transition_id"] = t.transitionID
	if err := s.setTransitionState(t, restartStateRunning, &probe, nil); err != nil {
		s.failRestart(t, "state", err, weaverLogPath(t.world.StateDir))
		return
	}
	s.completeTransition(t, status, nil, false)
}

func (s *server) failProbe(t *weaverTransition, probe *restartProbeResult, err error) {
	_ = s.setTransitionState(t, restartStateRunning, probe, &restartFailure{Stage: "probe", Message: err.Error()})
	status := s.admittedGenerationStatus(t.world, t)
	status["probe_error"] = err.Error()
	s.completeTransition(t, status, nil, false)
}

func (s *server) failRestart(t *weaverTransition, stage string, err error, logPath string) {
	failure := &restartFailure{Stage: stage, Message: err.Error(), LogPath: logPath}
	_ = s.setTransitionState(t, restartStateFailed, nil, failure)
	status := baseStatus(t.world, restartStateFailed)
	status["transition_id"] = t.transitionID
	status["failure"] = *failure
	s.completeTransition(t, status, nil, true)
}

func (s *server) launchReplacement(source string, world config.World, requestedName string, timeout time.Duration) (map[string]any, *weaverChild, error) {
	var err error
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		return nil, nil, err
	}
	if err := os.MkdirAll(world.DataDir, 0o755); err != nil {
		return nil, nil, err
	}
	name, err := friendlyName(world, requestedName)
	if err != nil {
		return nil, nil, err
	}
	logPath := weaverLogPath(world.StateDir)
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, nil, err
	}
	_, _ = fmt.Fprintf(logFile, "=== weaver replacement %s config_dir=%s ===\n", time.Now().UTC().Format(time.RFC3339), world.ConfigDir)
	cmd, err := launchWeaver(source, weaverArgs(world, name), logFile, logFile)
	if err != nil {
		_ = logFile.Close()
		return nil, nil, err
	}
	done := make(chan error, 1)
	child := &weaverChild{cmd: cmd, world: world, name: name, done: done, generationID: newOpaqueID("generation")}
	s.mu.Lock()
	if s.children == nil {
		s.children = map[string]*weaverChild{}
	}
	s.children[world.ConfigDir] = child
	s.mu.Unlock()
	go func() {
		err := cmd.Wait()
		_ = logFile.Close()
		done <- err
	}()
	status, err := waitForReadyStatus(world, cmd.Process.Pid, done, timeout)
	if err != nil {
		_ = terminateAndConfirm(child, 2*time.Second)
		s.mu.Lock()
		if s.children[world.ConfigDir] == child {
			delete(s.children, world.ConfigDir)
		}
		s.mu.Unlock()
		return nil, nil, fmt.Errorf("replacement weaver failed readiness: %w; weaver log: %s", err, logPath)
	}
	status["generation_id"] = child.generationID
	return status, child, nil
}

func stopRecordedGeneration(child *weaverChild, world config.World) error {
	if child == nil || child.cmd == nil || child.cmd.Process == nil || child.cmd.Process.Pid <= 0 {
		return errors.New("restart has no recorded weaver pid")
	}
	if !terminateAndConfirm(child, 5*time.Second) {
		return fmt.Errorf("could not confirm termination of recorded weaver pid %d", child.cmd.Process.Pid)
	}
	cleanupWorldArtifacts(world)
	return nil
}

func terminateAndConfirm(child *weaverChild, grace time.Duration) bool {
	pid := child.cmd.Process.Pid
	terminatePID(pid)
	if waitRecordedExit(child, grace) {
		return true
	}
	// Escalation still targets only the PID recorded for this generation.
	_ = syscall.Kill(pid, syscall.SIGKILL)
	return waitRecordedExit(child, 2*time.Second)
}

func waitRecordedExit(child *weaverChild, timeout time.Duration) bool {
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	for {
		if !processAlive(child.cmd.Process.Pid) {
			return true
		}
		select {
		case <-child.done:
			return !processAlive(child.cmd.Process.Pid)
		case <-deadline.C:
			return !processAlive(child.cmd.Process.Pid)
		case <-ticker.C:
		}
	}
}
