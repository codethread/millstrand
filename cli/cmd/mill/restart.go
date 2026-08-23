package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"sync"
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

func transitionResultStatus(t *weaverTransition) map[string]any {
	if t.result != nil {
		return restartBoundaryStatus(t.world, t.result)
	}
	status := baseStatus(t.world, t.state())
	status["transition_id"] = t.transitionID
	return restartBoundaryStatus(t.world, status)
}

func restartBoundaryStatus(world config.World, status map[string]any) map[string]any {
	status["operation"] = "weaver-restart"
	status["workspace"] = world.ConfigDir
	return status
}

func validateRestartResult(status map[string]any) error {
	if status == nil || status["operation"] != "weaver-restart" {
		return errors.New("restart result missing operation")
	}
	workspace, ok := status["workspace"].(string)
	if !ok || workspace == "" {
		return errors.New("restart result missing workspace")
	}
	state, ok := status["state"].(string)
	if !ok || state == "" {
		return errors.New("restart result missing state")
	}
	if state == restartStateRunning {
		generation, ok := status["generation_id"].(string)
		if !ok || generation == "" {
			return errors.New("running restart result missing generation_id")
		}
	}
	if state == restartStateFailed {
		if _, ok := status["failure"]; !ok {
			return errors.New("failed restart result missing failure diagnostics")
		}
		diagnostics, ok := status["diagnostics"].([]map[string]any)
		if !ok || len(diagnostics) == 0 {
			return errors.New("failed restart result missing diagnostics")
		}
	}
	return nil
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

var (
	writeRestartRecordFn = writeRestartRecord
	waitForStartClaim    = func(claim chan struct{}) { <-claim }
)

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
			return restartBoundaryStatus(world, status)
		}
	}
	return restartBoundaryStatus(world, baseStatusWithName(world, restartStateProbing, ""))
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
	err := writeRestartRecordFn(t.world, record)
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
	if claim := s.startClaim(world.ConfigDir); claim != nil {
		waitForStartClaim(claim)
		return s.restartWeaver(req)
	}
	s.mu.Lock()
	if s.transitions == nil {
		s.transitions = map[string]*weaverTransition{}
	}
	if claim := s.startClaims[world.ConfigDir]; claim != nil {
		s.mu.Unlock()
		waitForStartClaim(claim)
		return s.restartWeaver(req)
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
			identity, identityErr := identityFromStatus(status)
			if identityErr != nil {
				s.mu.Unlock()
				return nil, fmt.Errorf("selected workspace weaver metadata identity is unusable: %w", identityErr)
			}
			old = &weaverChild{cmd: &exec.Cmd{Process: process}, world: world, name: fmt.Sprint(status["name"]), generationID: fmt.Sprint(status["generation_id"]), identity: identity, unsupervised: true}
		} else if record, ok, recordErr := readRestartRecordDetailed(world); recordErr != nil {
			s.mu.Unlock()
			return nil, recordErr
		} else if !ok || record.State != restartStateFailed {
			s.mu.Unlock()
			return nil, fmt.Errorf("no running weaver for selected workspace")
		}
	}
	t := &weaverTransition{world: world, old: old, transitionID: newOpaqueID("transition"), stateValue: restartStateProbing, done: make(chan struct{})}
	if t.old != nil && t.old.generationID == "" {
		t.old.generationID = newOpaqueID("generation")
	}
	if t.old != nil {
		t.generationID = t.old.generationID
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
	// Replacement work is shared by every caller.  A caller's timeout only
	// bounds its join on t.done; it must never cancel or shorten the shared
	// mill readiness policy.
	status, child, err := s.launchReplacement(source, t.world, req.Name, defaultWeaverReadyTimeout)
	if err != nil {
		s.failRestart(t, "launch", err, probe.Log)
		return
	}
	t.generationID = child.generationID
	status["generation_id"] = child.generationID
	status["transition_id"] = t.transitionID
	status["probe"] = probe
	restartBoundaryStatus(t.world, status)
	if err := s.setTransitionState(t, restartStateRunning, &probe, nil); err != nil {
		s.failRestart(t, "state", err, weaverLogPath(t.world.StateDir))
		return
	}
	s.completeTransition(t, status, nil, false)
}

func (s *server) failProbe(t *weaverTransition, probe *restartProbeResult, err error) {
	if stateErr := s.setTransitionState(t, restartStateRunning, probe, &restartFailure{Stage: "probe", Message: err.Error()}); stateErr != nil {
		status := s.admittedGenerationStatus(t.world, t)
		status["restart_state"] = restartStateRunning
		status["state_write_error"] = stateErr.Error()
		s.completeTransition(t, status, fmt.Errorf("record probe failure in restart state: %w", stateErr), false)
		return
	}
	status := s.admittedGenerationStatus(t.world, t)
	status["probe_error"] = err.Error()
	status["restart_state"] = restartStateRunning
	restartBoundaryStatus(t.world, status)
	s.completeTransition(t, status, nil, false)
}

func (s *server) failRestart(t *weaverTransition, stage string, err error, logPath string) {
	failure := &restartFailure{Stage: stage, Message: err.Error(), LogPath: logPath}
	stateErr := s.setTransitionState(t, restartStateFailed, nil, failure)
	if stateErr != nil {
		failure.Message = fmt.Sprintf("%s; restart state write failed: %v", failure.Message, stateErr)
	}
	status := baseStatus(t.world, restartStateFailed)
	status["transition_id"] = t.transitionID
	status["failure"] = *failure
	status["diagnostics"] = []map[string]any{{
		"stage":  failure.Stage,
		"status": "failed",
		"data": map[string]any{
			"message":  failure.Message,
			"log_path": failure.LogPath,
		},
	}}
	restartBoundaryStatus(t.world, status)
	// Keep the failed transition available in memory even when its durable
	// record could not be written; callers must see the original failure.
	s.completeTransition(t, status, stateErr, true)
}
