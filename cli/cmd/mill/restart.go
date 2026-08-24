package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
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

// restartResultProjection is the closed command result.  Process metadata,
// probe detail, and log paths remain internal Mill status; they do not leak
// into the restart envelope owned by the core restart spec.
func restartResultProjection(status map[string]any) map[string]any {
	state, _ := status["state"].(string)
	if restartState, _ := status["restart_state"].(string); restartState == restartStateFailed {
		state = restartStateFailed
	}
	projection := map[string]any{
		"operation": "restart",
		"workspace": status["workspace"],
		"state":     state,
	}
	for _, key := range []string{"generation_id", "transition_id", "diagnostics"} {
		if value, ok := status[key]; ok {
			projection[key] = value
		}
	}
	return projection
}

func validateRestartResult(status map[string]any) error {
	return validateRestartProjection(status, true)
}

func validateRestartProjection(status map[string]any, withOperation bool) error {
	if status == nil {
		return errors.New("restart projection must be an object")
	}
	allowed := map[string]bool{"workspace": true, "state": true, "generation_id": true, "transition_id": true, "diagnostics": true}
	if withOperation {
		allowed["operation"] = true
		if status["operation"] != "restart" {
			return errors.New("restart projection operation must be restart")
		}
	} else if _, ok := status["operation"]; ok {
		return errors.New("mill status projection must not contain operation")
	}
	for key := range status {
		if !allowed[key] {
			return fmt.Errorf("restart projection contains unknown field %q", key)
		}
	}
	workspace, ok := status["workspace"].(string)
	if !ok || strings.TrimSpace(workspace) == "" {
		return errors.New("restart projection workspace must be non-blank")
	}
	state, ok := status["state"].(string)
	if !ok || !map[string]bool{restartStateProbing: true, restartStateRestarting: true, restartStateRunning: true, restartStateFailed: true}[state] {
		return fmt.Errorf("restart projection has unknown state %q", status["state"])
	}
	for _, key := range []string{"generation_id", "transition_id"} {
		if value, present := status[key]; present {
			text, ok := value.(string)
			if !ok || strings.TrimSpace(text) == "" {
				return fmt.Errorf("restart projection %s must be non-blank", key)
			}
		}
	}
	if diagnostics, present := status["diagnostics"]; present {
		rows, ok := restartDiagnosticRows(diagnostics)
		if !ok || len(rows) == 0 {
			return errors.New("restart projection diagnostics must be a non-empty array")
		}
		for index, row := range rows {
			if err := validateRestartDiagnostic(row); err != nil {
				return fmt.Errorf("restart projection diagnostics[%d]: %w", index, err)
			}
		}
	}
	switch state {
	case restartStateProbing:
		if _, ok := status["generation_id"]; !ok {
			return errors.New("probing restart projection missing generation_id")
		}
		if _, ok := status["transition_id"]; !ok {
			return errors.New("probing restart projection missing transition_id")
		}
		if _, ok := status["diagnostics"]; ok {
			return errors.New("probing restart projection must not contain diagnostics")
		}
	case restartStateRestarting:
		if _, ok := status["transition_id"]; !ok {
			return errors.New("restarting restart projection missing transition_id")
		}
		if _, ok := status["generation_id"]; ok {
			return errors.New("restarting restart projection must not contain generation_id")
		}
		if _, ok := status["diagnostics"]; ok {
			return errors.New("restarting restart projection must not contain diagnostics")
		}
	case restartStateRunning:
		if _, ok := status["generation_id"]; !ok {
			return errors.New("running restart projection missing generation_id")
		}
		if _, ok := status["diagnostics"]; ok {
			return errors.New("running restart projection must not contain diagnostics")
		}
	case restartStateFailed:
		if _, ok := status["diagnostics"]; !ok {
			return errors.New("failed restart projection missing diagnostics")
		}
	}
	return nil
}

func restartDiagnosticRows(value any) ([]map[string]any, bool) {
	switch rows := value.(type) {
	case []map[string]any:
		return rows, true
	case []any:
		result := make([]map[string]any, len(rows))
		for index, row := range rows {
			mapped, ok := row.(map[string]any)
			if !ok {
				return nil, false
			}
			result[index] = mapped
		}
		return result, true
	default:
		return nil, false
	}
}

func validateRestartDiagnostic(row map[string]any) error {
	allowed := map[string]bool{"stage": true, "status": true, "data": true, "generation_id": true, "transition_id": true}
	for key := range row {
		if !allowed[key] {
			return fmt.Errorf("diagnostic contains unknown field %q", key)
		}
	}
	stage, ok := row["stage"].(string)
	if !ok || strings.TrimSpace(stage) == "" {
		return errors.New("diagnostic stage must be a non-empty string")
	}
	status, ok := row["status"].(string)
	if !ok || !map[string]bool{"completed": true, "failed": true, "skipped": true, "in-progress": true}[status] {
		return errors.New("diagnostic status is invalid")
	}
	if data, ok := row["data"]; ok {
		if _, ok := data.(map[string]any); !ok {
			return errors.New("diagnostic data must be an object")
		}
	}
	return nil
}

func validateMillStatusProjection(status map[string]any) error {
	return validateRestartProjection(status, false)
}

func millStatusProjection(status map[string]any) map[string]any {
	state, _ := status["state"].(string)
	if restartState, _ := status["restart_state"].(string); restartState != "" {
		state = restartState
	}
	if !map[string]bool{restartStateProbing: true, restartStateRestarting: true, restartStateRunning: true, restartStateFailed: true}[state] {
		return nil
	}
	projection := map[string]any{"state": state, "workspace": status["config_dir"]}
	for _, key := range []string{"generation_id", "transition_id", "diagnostics"} {
		if value, ok := status[key]; ok {
			projection[key] = value
		}
	}
	return projection
}

func validateAdmissionState(admission map[string]any) error {
	allowed := map[string]bool{"state": true, "generation_id": true, "transition_id": true}
	for key := range admission {
		if !allowed[key] {
			return fmt.Errorf("admission state contains unknown field %q", key)
		}
	}
	state, ok := admission["state"].(string)
	if !ok || (state != "open" && state != "closed") {
		return errors.New("admission state has an invalid state")
	}
	if state == "open" {
		generation, ok := admission["generation_id"].(string)
		if !ok || strings.TrimSpace(generation) == "" {
			return errors.New("open admission state requires generation_id")
		}
		if _, ok := admission["transition_id"]; ok {
			return errors.New("open admission state must not contain transition_id")
		}
	}
	if state == "closed" {
		transition, ok := admission["transition_id"].(string)
		if !ok || strings.TrimSpace(transition) == "" {
			return errors.New("closed admission state requires transition_id")
		}
		if _, ok := admission["generation_id"]; ok {
			return errors.New("closed admission state must not contain generation_id")
		}
	}
	return nil
}

type weaverTransition struct {
	mu           sync.Mutex
	world        config.World
	old          *weaverChild
	transitionID string
	createdAt    time.Time
	stateValue   string
	// cutoverStarted remains true after the replacement becomes ready so an
	// admitted old-generation request that reports EOF after transition
	// cleanup still receives weaver/restarted rather than an ordinary loss.
	cutoverStarted bool
	generationID   string
	probe          *restartProbeResult
	// retryStartup records that a previous probe already completed and only
	// replacement startup needs to be retried.  This is the recovery path after
	// a cutover startup failure, when no old generation remains admitted.
	retryStartup         bool
	oldGenerationStopped bool
	result               map[string]any
	err                  error
	done                 chan struct{}
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
	if state == restartStateRestarting {
		t.cutoverStarted = true
	}
	if probe != nil {
		copy := *probe
		if copy.Completed == nil {
			copy.Completed = []string{}
		}
		if copy.Diagnostics == nil {
			copy.Diagnostics = []map[string]any{}
		}
		t.probe = &copy
	}
	t.mu.Unlock()
	record := restartRecord{State: state, TransitionID: t.transitionID, GenerationID: t.generationID}
	if t.old != nil {
		record.PreviousGeneration = t.old.generationID
	}
	record.OldGenerationStopped = t.oldGenerationStopped
	if probe != nil {
		copy := *probe
		if copy.Completed == nil {
			copy.Completed = []string{}
		}
		if copy.Diagnostics == nil {
			copy.Diagnostics = []map[string]any{}
		}
		record.Probe = &copy
	} else {
		record.Probe = t.probe
	}
	record.Failure = failure
	if err := validateRestartRecord(record, validateRestartRecordForWrite); err != nil {
		s.mu.Unlock()
		return err
	}
	err := writeRestartRecordFn(t.world, record)
	s.mu.Unlock()
	return err
}

func validateRestartRecord(record restartRecord, mode restartRecordValidationMode) error {
	if strings.TrimSpace(record.TransitionID) == "" {
		return errors.New("restart record requires transition_id")
	}
	if mode == validateRestartRecordForWrite {
		if record.UpdatedAt != "" {
			return errors.New("restart record updated_at is writer-owned")
		}
	} else if strings.TrimSpace(record.UpdatedAt) == "" {
		return errors.New("restart record requires updated_at")
	}
	switch record.State {
	case restartStateProbing, restartStateRestarting, restartStateRunning, restartStateFailed:
	default:
		return fmt.Errorf("restart record has unknown state %q", record.State)
	}
	if record.GenerationID != "" && strings.TrimSpace(record.GenerationID) == "" {
		return errors.New("restart record generation_id must be non-blank")
	}
	if record.PreviousGeneration != "" && strings.TrimSpace(record.PreviousGeneration) == "" {
		return errors.New("restart record previous_generation_id must be non-blank")
	}
	if record.OldGenerationStopped && (strings.TrimSpace(record.GenerationID) == "" || record.Probe == nil || !record.Probe.Success) {
		return errors.New("stopped generation requires a successful probe and generation_id")
	}
	if record.State == restartStateProbing && (strings.TrimSpace(record.GenerationID) == "" || record.Probe != nil || record.Failure != nil || record.OldGenerationStopped) {
		return errors.New("probing restart record has contradictory fields")
	}
	if record.State == restartStateRunning && strings.TrimSpace(record.GenerationID) == "" {
		return errors.New("running restart record requires generation_id")
	}
	if record.State == restartStateFailed && record.Failure == nil {
		return errors.New("failed restart record requires failure")
	}
	if record.Failure != nil {
		if strings.TrimSpace(record.Failure.Stage) == "" || strings.TrimSpace(record.Failure.Message) == "" {
			return errors.New("restart failure requires non-blank stage and message")
		}
		if record.State == restartStateRunning {
			if record.Failure.Stage != "probe" || record.Probe == nil || record.Probe.Success {
				return errors.New("running restart failure requires a failed probe")
			}
		} else if record.State != restartStateFailed {
			return fmt.Errorf("restart failure is contradictory for state %q", record.State)
		}
	}
	if record.OldGenerationStopped && record.Failure != nil && record.Failure.Stage != "launch" {
		return fmt.Errorf("stopped generation cannot have %s failure", record.Failure.Stage)
	}
	if record.Probe != nil {
		if err := record.Probe.validate(); err != nil {
			return fmt.Errorf("restart record probe: %w", err)
		}
	}
	return nil
}

func (s *server) completeTransition(t *weaverTransition, result map[string]any, err error, retain bool) {
	s.mu.Lock()
	t.result, t.err = result, err
	if !retain && s.transitions[t.world.ConfigDir] == t {
		delete(s.transitions, t.world.ConfigDir)
		if s.lastTransitions == nil {
			s.lastTransitions = map[string]*weaverTransition{}
		}
		s.lastTransitions[t.world.ConfigDir] = t
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
	if old != nil && (old.cmd == nil || old.cmd.Process == nil || !processAlive(old.cmd.Process.Pid)) {
		delete(s.children, world.ConfigDir)
		old = nil
	}
	var retryProbe *restartProbeResult
	var retryRecord *restartRecord
	if old == nil {
		if record, ok, recordErr := readRestartRecordDetailed(world); recordErr != nil {
			s.mu.Unlock()
			return nil, recordErr
		} else if ok && record.State == restartStateFailed {
			// A failed replacement has already completed its disposable probe.
			// Retry only the authoritative startup step; there is no admitted old
			// generation to use as a new probe baseline.
			if record.Probe == nil || !record.Probe.Success || !record.OldGenerationStopped || record.Failure == nil || record.Failure.Stage != "launch" {
				s.mu.Unlock()
				return nil, errors.New("failed restart record is not a retryable replacement launch failure")
			}
			copy := *record.Probe
			retryProbe = &copy
			retryRecord = &record
		}
	}
	if old == nil {
		if status, stale := readStatus(world); status != nil && !stale {
			if retryRecord != nil {
				s.mu.Unlock()
				return nil, errors.New("cannot retry replacement while selected workspace metadata still identifies a live generation")
			}
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
		} else if status, stale := readStatus(world); status != nil && stale {
			s.mu.Unlock()
			return nil, fmt.Errorf("cannot retry replacement with stale selected workspace metadata: %v", status["stale_reason"])
		} else if retryProbe == nil {
			s.mu.Unlock()
			return nil, fmt.Errorf("no running weaver for selected workspace")
		}
	}
	state := restartStateProbing
	if retryProbe != nil {
		state = restartStateRestarting
	}
	t := &weaverTransition{world: world, old: old, transitionID: newOpaqueID("transition"), createdAt: time.Now(), stateValue: state, probe: retryProbe, retryStartup: retryProbe != nil, oldGenerationStopped: retryRecord != nil && retryRecord.OldGenerationStopped, done: make(chan struct{})}
	if t.old != nil && t.old.generationID == "" {
		t.old.generationID = newOpaqueID("generation")
	}
	if t.old != nil {
		t.generationID = t.old.generationID
	} else if retryRecord != nil {
		// Carry the durable stopped generation identity through a replacement
		// retry so a second launch failure remains a valid persisted record.
		t.generationID = retryRecord.GenerationID
	}
	s.transitions[world.ConfigDir] = t
	s.mu.Unlock()
	if err := s.setTransitionState(t, state, retryProbe, nil); err != nil {
		if t.old != nil && !t.retryStartup {
			// The first durable transition write is before admission closes. Keep
			// the verified old generation routable and remove the failed in-memory
			// probe rather than leaving a forever-probing transition with no
			// recoverable result.
			t.mu.Lock()
			t.stateValue = restartStateRunning
			t.probe = nil
			t.mu.Unlock()
			status := s.admittedGenerationStatus(t.world, t)
			status["restart_state"] = restartStateRunning
			status["probe_error"] = err.Error()
			s.completeTransition(t, status, err, false)
			return nil, err
		}
		s.completeTransition(t, nil, err, true)
		return nil, err
	}
	go s.runRestartTransition(t, req)
	return waitForLifecycleTransition(t, readyTimeoutFor(req.ReadyTimeoutMs))
}

func (s *server) runRestartTransition(t *weaverTransition, req client.MillWorldRequest) {
	admission := s.workspaceAdmissionLock(t.world.ConfigDir)
	source, err := resolveLaunchSource(req.CWD)
	if err != nil {
		s.failProbe(t, nil, err)
		return
	}
	probe := restartProbeResult{}
	if t.retryStartup {
		if t.probe == nil || !t.probe.Success {
			s.failRestart(t, "probe", errors.New("replacement retry has no successful probe"), "")
			return
		}
		probe = *t.probe
	} else {
		probe, err = probeRuntime(source, t.world)
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
	}
	// Probe execution is deliberately outside the admission lock. The old
	// generation remains admitted and must continue serving invokes while the
	// candidate is gated; lock only the irreversible stop-and-replace cutover.
	admission.Lock()
	defer admission.Unlock()
	if err := s.setTransitionState(t, restartStateRestarting, &probe, nil); err != nil {
		s.failRestart(t, "state", err, probe.Log)
		return
	}
	if t.old != nil {
		if err := stopRecordedGeneration(t.old, t.world); err != nil {
			s.failRestart(t, "stop", err, probe.Log)
			return
		}
		t.oldGenerationStopped = true
		// The stop proof is durable before replacement launch. A later retry may
		// reuse the successful probe only when this record survives the launch
		// failure and metadata remains absent.
		if err := s.setTransitionState(t, restartStateRestarting, &probe, nil); err != nil {
			s.failRestart(t, "state", err, "")
			return
		}
		s.releaseChild(t.world.ConfigDir, t.old)
	}
	// Replacement work is shared by every caller.  A caller's timeout only
	// bounds its join on t.done; it must never cancel or shorten the shared
	// mill readiness policy.
	status, child, err := s.launchReplacement(source, t.world, req.Name, defaultWeaverReadyTimeout)
	if err != nil {
		logPath := ""
		var launchErr *replacementLaunchError
		if errors.As(err, &launchErr) {
			logPath = launchErr.logPath
		}
		s.failRestart(t, "launch", err, logPath)
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
	if t.old == nil || t.retryStartup {
		s.failRestart(t, "probe", err, "")
		return
	}
	if probe == nil {
		probe = &restartProbeResult{
			Success:         false,
			Stage:           "probe/failure",
			ProbeWorkspace:  t.world.StateDir,
			SourceWorkspace: t.world.ConfigDir,
			Completed:       []string{},
			Diagnostics: []map[string]any{{
				"stage":  "probe/transport",
				"status": "failed",
				"data":   map[string]any{"message": err.Error()},
			}},
			Log: weaverLogPath(t.world.StateDir),
		}
	}
	if stateErr := s.setTransitionState(t, restartStateRunning, probe, &restartFailure{Stage: "probe", Message: err.Error()}); stateErr != nil {
		status := s.admittedGenerationStatus(t.world, t)
		status["restart_state"] = restartStateRunning
		status["state_write_error"] = stateErr.Error()
		s.completeTransition(t, status, fmt.Errorf("record probe failure in restart state: %w", stateErr), false)
		return
	}
	status := s.admittedGenerationStatus(t.world, t)
	// The old generation remains admitted, but the closed restart command must
	// describe the refused transition rather than masquerading as a successful
	// replacement.  The public projection has no probe-specific field; use its
	// failed state and structured diagnostics while keeping admission status
	// independently open through the old metadata.
	status["probe_error"] = err.Error()
	status["restart_state"] = restartStateFailed
	status["diagnostics"] = []map[string]any{{
		"stage":  "probe",
		"status": "failed",
		"data": map[string]any{
			"message":       err.Error(),
			"generation_id": status["generation_id"],
			"transition_id": t.transitionID,
		},
	}}
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
