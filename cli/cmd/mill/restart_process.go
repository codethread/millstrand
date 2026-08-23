package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"syscall"
	"time"

	"millstrand-strand-cli/internal/config"
)

// weaverIdentity is the identity that must remain attached to a process while
// mill is deciding whether it may signal it.  PID is only one component: it
// can be reused after a process exits.
type weaverIdentity struct {
	PID       int
	WeaverID  string
	StartedAt string
	Socket    string
	ConfigDir string
	StateDir  string
	DataDir   string
}

func identityFromStatus(status map[string]any) (weaverIdentity, error) {
	pid, ok := status["pid"].(int)
	if !ok || pid <= 0 {
		return weaverIdentity{}, errors.New("missing valid pid")
	}
	identity := weaverIdentity{
		PID:       pid,
		WeaverID:  stringStatus(status, "weaver_id"),
		StartedAt: stringStatus(status, "started_at"),
		Socket:    stringStatus(status, "socket_path"),
		ConfigDir: stringStatus(status, "config_dir"),
		StateDir:  stringStatus(status, "state_dir"),
		DataDir:   stringStatus(status, "data_dir"),
	}
	if identity.WeaverID == "" || identity.StartedAt == "" || identity.Socket == "" || identity.ConfigDir == "" || identity.StateDir == "" || identity.DataDir == "" {
		return weaverIdentity{}, errors.New("missing weaver/start identity or runtime endpoint")
	}
	return identity, nil
}

func stringStatus(status map[string]any, key string) string {
	value, _ := status[key].(string)
	return value
}

func sameWeaverIdentity(a, b weaverIdentity) bool {
	return a.PID == b.PID && a.WeaverID == b.WeaverID && a.StartedAt == b.StartedAt && a.Socket == b.Socket && a.ConfigDir == b.ConfigDir && a.StateDir == b.StateDir && a.DataDir == b.DataDir
}

// verifyUnsupervisedIdentity proves the metadata-discovered process through
// the runtime itself, not merely through a reusable PID or a replaceable JSON
// file.  It is called immediately before TERM and again before KILL.
func verifyUnsupervisedIdentity(child *weaverChild, world config.World) error {
	if child == nil || !child.unsupervised {
		return nil
	}
	if child.identity.PID <= 0 || child.cmd == nil || child.cmd.Process == nil || child.cmd.Process.Pid != child.identity.PID {
		return errors.New("recorded unsupervised weaver identity is incomplete")
	}
	status, stale := readStatus(world)
	if status == nil || stale {
		return errors.New("recorded unsupervised weaver metadata is absent or stale")
	}
	current, err := identityFromStatus(status)
	if err != nil {
		return fmt.Errorf("recorded unsupervised weaver metadata is invalid: %w", err)
	}
	if !sameWeaverIdentity(current, child.identity) {
		return fmt.Errorf("recorded unsupervised weaver identity changed for pid %d", child.identity.PID)
	}
	if err := verifyRuntimeEndpoint(child.identity); err != nil {
		return fmt.Errorf("recorded unsupervised weaver endpoint identity failed: %w", err)
	}
	return nil
}

func verifyRuntimeEndpoint(identity weaverIdentity) error {
	_, err := runtimeStatus(identity)
	return err
}

func runtimeStatus(identity weaverIdentity) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := (&net.Dialer{}).DialContext(ctx, "unix", identity.Socket)
	if err != nil {
		return nil, fmt.Errorf("dial %s: %w", identity.Socket, err)
	}
	defer func() { _ = conn.Close() }()
	_ = conn.SetDeadline(time.Now().Add(time.Second))
	requestID := fmt.Sprintf("mill-identity-%d", time.Now().UnixNano())
	request := map[string]any{
		"protocol_version": 3,
		"request_id":       requestID,
		"weaver_id":        identity.WeaverID,
		"operation":        "status",
		"arguments":        map[string]any{},
		"options":          map[string]any{},
	}
	if err := json.NewEncoder(conn).Encode(request); err != nil {
		return nil, fmt.Errorf("write status request: %w", err)
	}
	var response struct {
		ProtocolVersion int            `json:"protocol_version"`
		RequestID       string         `json:"request_id"`
		OK              bool           `json:"ok"`
		Result          map[string]any `json:"result"`
		Error           map[string]any `json:"error"`
	}
	if err := json.NewDecoder(conn).Decode(&response); err != nil {
		return nil, fmt.Errorf("decode status response: %w", err)
	}
	if response.ProtocolVersion != 3 || response.RequestID != requestID || !response.OK || response.Result == nil {
		return nil, errors.New("runtime status response is not a successful matching frame")
	}
	if healthy, ok := response.Result["healthy"].(bool); !ok || !healthy {
		return nil, errors.New("runtime status is not healthy")
	}
	if endpointPID, err := jsonInt(response.Result["pid"]); err != nil || endpointPID != identity.PID {
		return nil, fmt.Errorf("runtime status pid mismatch: got %v expected %d", response.Result["pid"], identity.PID)
	}
	for key, expected := range map[string]string{
		"weaver_id": identity.WeaverID, "started_at": identity.StartedAt,
		"socket_path": identity.Socket, "config_dir": identity.ConfigDir,
		"state_dir": identity.StateDir, "data_dir": identity.DataDir,
	} {
		if got, ok := response.Result[key].(string); !ok || got != expected {
			return nil, fmt.Errorf("runtime status %s mismatch: got %v expected %q", key, response.Result[key], expected)
		}
	}
	return response.Result, nil
}

func jsonInt(value any) (int, error) {
	switch n := value.(type) {
	case int:
		return n, nil
	case float64:
		if n != float64(int(n)) {
			return 0, errors.New("not an integer")
		}
		return int(n), nil
	case json.Number:
		parsed, err := strconv.Atoi(string(n))
		return parsed, err
	default:
		return 0, errors.New("not a number")
	}
}

// signalRecordedPID never targets a process group.  Metadata-discovered
// processes are not mill-owned, so only the rechecked recorded PID may be
// signalled.
func signalRecordedPID(pid int, signal syscall.Signal) {
	if pid > 0 {
		_ = syscall.Kill(pid, signal)
	}
}

func (s *server) launchReplacement(source string, world config.World, requestedName string, timeout time.Duration) (map[string]any, *weaverChild, error) {
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
	status, err := waitForReplacementReadyStatus(world, cmd.Process.Pid, done, timeout)
	if err != nil {
		_, _ = terminateAndConfirm(child, 2*time.Second)
		_ = s.releaseChild(world.ConfigDir, child)
		return nil, nil, fmt.Errorf("replacement weaver failed readiness: %w; weaver log: %s", err, logPath)
	}
	identity, err := identityFromStatus(status)
	if err != nil {
		_, _ = terminateAndConfirm(child, 2*time.Second)
		_ = s.releaseChild(world.ConfigDir, child)
		return nil, nil, fmt.Errorf("replacement weaver identity is invalid: %w", err)
	}
	if identity.PID != cmd.Process.Pid {
		_, _ = terminateAndConfirm(child, 2*time.Second)
		_ = s.releaseChild(world.ConfigDir, child)
		return nil, nil, fmt.Errorf("replacement weaver identity pid %d does not match launched pid %d", identity.PID, cmd.Process.Pid)
	}
	child.identity = identity
	status["generation_id"] = child.generationID
	return status, child, nil
}

// waitForReplacementReadyStatus is a seam for deterministic replacement
// lifecycle tests; normal starts continue to call waitForReadyStatus directly.
var waitForReplacementReadyStatus = waitForReadyStatus

func stopRecordedGeneration(child *weaverChild, world config.World) error {
	if child == nil || child.cmd == nil || child.cmd.Process == nil || child.cmd.Process.Pid <= 0 {
		return errors.New("restart has no recorded weaver pid")
	}
	if err := verifyUnsupervisedIdentity(child, world); err != nil {
		return fmt.Errorf("refusing to signal metadata-discovered weaver: %w", err)
	}
	if stopped, err := terminateAndConfirm(child, 5*time.Second); err != nil {
		return err
	} else if !stopped {
		return fmt.Errorf("could not confirm termination of recorded weaver pid %d", child.cmd.Process.Pid)
	}
	cleanupWorldArtifacts(world)
	return nil
}

func terminateAndConfirm(child *weaverChild, grace time.Duration) (bool, error) {
	pid := child.cmd.Process.Pid
	if err := verifyUnsupervisedIdentity(child, child.world); err != nil {
		return false, err
	}
	if child.unsupervised {
		signalRecordedPID(pid, syscall.SIGTERM)
	} else {
		terminatePID(pid)
	}
	if waitRecordedExit(child, grace) {
		return true, nil
	}
	if err := verifyUnsupervisedIdentity(child, child.world); err != nil {
		return false, err
	}
	if child.unsupervised {
		signalRecordedPID(pid, syscall.SIGKILL)
	} else {
		_ = syscall.Kill(pid, syscall.SIGKILL)
	}
	return waitRecordedExit(child, 2*time.Second), nil
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

func waitForReadyStatus(world config.World, pid int, done <-chan error, timeout time.Duration) (map[string]any, error) {
	deadline := time.Now().Add(timeout)
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	for {
		status, stale := readStatus(world)
		if status != nil && !stale {
			identity, err := identityFromStatus(status)
			if err != nil {
				return nil, fmt.Errorf("weaver published invalid ready metadata: %w", err)
			}
			if identity.PID != pid {
				return nil, fmt.Errorf("weaver published ready metadata for pid %d, expected launched pid %d", identity.PID, pid)
			}
			return status, nil
		}
		if stale {
			return nil, fmt.Errorf("weaver published stale metadata during startup: %v", status["stale_reason"])
		}
		select {
		case err := <-done:
			if err != nil {
				return nil, fmt.Errorf("weaver exited before publishing ready metadata: %w", err)
			}
			return nil, fmt.Errorf("weaver exited before publishing ready metadata")
		default:
		}
		if !processAlive(pid) {
			return nil, fmt.Errorf("weaver exited before publishing ready metadata")
		}
		if time.Now().After(deadline) {
			terminatePID(pid)
			return nil, fmt.Errorf("weaver did not publish ready metadata before timeout")
		}
		<-ticker.C
	}
}
