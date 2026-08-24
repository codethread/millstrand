package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

// handleInvoke resolves the selected workspace weaver and relays its NDJSON
// response frames verbatim to the client connection. The invoke envelope rides
// as req.Payload and is forwarded unchanged as the weaver `invoke` operation
// arguments (SPEC-002-D004.C6). Unlike the wrapped MillResponse lifecycle ops,
// invoke never wraps: the strand dispatcher reads the weaver's own single/stream
// frames directly through the proxy.
func (s *server) handleInvoke(conn net.Conn, req client.MillRequest) {
	w := bufio.NewWriter(conn)
	defer func() { _ = w.Flush() }()
	callerCtx, stopCallerWatch := watchCallerConnection(conn)
	defer stopCallerWatch()

	world, err := resolveLifecycleWorld(req.World)
	if err != nil {
		writeErrorFrame(w, req.RequestID, &client.ResponseError{Type: "transport", Code: "mill/invoke-world-failed", Message: "invoke world resolution failed", Details: map[string]any{"detail": err.Error()}})
		return
	}
	var wrote bool
	var relayErr error
	admission := s.workspaceAdmissionLock(world.ConfigDir)
	for {
		if err := callerCtx.Err(); err != nil {
			return
		}
		admission.Lock()
		s.mu.Lock()
		status, transition, targetErr := s.admittedInvokeTargetLocked(world)
		if transition != nil {
			s.mu.Unlock()
			admission.Unlock()
			if _, err := waitForLifecycleTransitionContext(callerCtx, transition, readyTimeoutFor(envelopeTimeoutMs(req.Payload))); err != nil {
				if callerCtx.Err() != nil {
					return
				}
				writeErrorFrame(w, req.RequestID, &client.ResponseError{Type: "transport", Code: "mill/weaver-restart-failed", Message: "weaver restart did not admit an invocation", Details: map[string]any{"config_dir": world.ConfigDir, "detail": err.Error()}})
				return
			}
			continue
		}
		if targetErr != nil {
			writeErrorFrame(w, req.RequestID, targetErr)
			s.mu.Unlock()
			admission.Unlock()
			return
		}
		socketPath, _ := status["socket_path"].(string)
		weaverID, _ := status["weaver_id"].(string)
		// Target selection is protected by the workspace admission lock. Once
		// selected, release the process-global state mutex before any external
		// socket operation; the workspace lock remains held until the request
		// frame is admitted, so a same-workspace lifecycle transition cannot
		// cut over underneath this admission.
		s.mu.Unlock()
		admissionOpen := true
		wrote, relayErr = relayInvokeWithAdmission(callerCtx, socketPath, weaverID, req.Payload, envelopeTimeoutMs(req.Payload), w, func() {
			admission.Unlock()
			admissionOpen = false
		})
		if admissionOpen {
			admission.Unlock()
		}
		if relayErr == nil || wrote {
			return
		}
		break
	}
	if relayErr != nil && !wrote {
		writeErrorFrame(w, req.RequestID, &client.ResponseError{Type: "transport", Code: "mill/weaver-forward-failed", Message: "weaver forwarding failed", Details: map[string]any{"detail": relayErr.Error()}})
	}
}

func (s *server) workspaceAdmissionLock(configDir string) *sync.Mutex {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.admissionLocks == nil {
		s.admissionLocks = map[string]*sync.Mutex{}
	}
	if lock := s.admissionLocks[configDir]; lock != nil {
		return lock
	}
	lock := &sync.Mutex{}
	s.admissionLocks[configDir] = lock
	return lock
}

// watchCallerConnection turns a client disconnect into cancellation for the
// admission wait.  The mill request has already been decoded, so a read can
// only observe a caller closing the connection or violating the one-request
// protocol.  The short deadline keeps normal connected callers observable
// without consuming a second request frame.
func watchCallerConnection(conn net.Conn) (context.Context, func()) {
	ctx, cancel := context.WithCancel(context.Background())
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		var probe [1]byte
		for {
			_ = conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
			_, err := conn.Read(probe[:])
			if err == nil {
				// The one-request protocol permits whitespace after the JSON
				// value. It can arrive in a later write than the request frame;
				// consuming it is not caller cancellation.
				if probe[0] == ' ' || probe[0] == '\t' || probe[0] == '\r' || probe[0] == '\n' {
					continue
				}
				cancel()
				return
			}
			if !isTimeout(err) {
				cancel()
				return
			}
			select {
			case <-stop:
				return
			default:
			}
		}
	}()
	return ctx, func() {
		close(stop)
		_ = conn.SetReadDeadline(time.Now())
		<-done
		cancel()
	}
}

func isTimeout(err error) bool {
	var netErr net.Error
	return errors.As(err, &netErr) && netErr.Timeout()
}

func waitForLifecycleTransitionContext(ctx context.Context, t *weaverTransition, timeout time.Duration) (map[string]any, error) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-t.done:
		return t.result, t.err
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-timer.C:
		return nil, fmt.Errorf("weaver restart did not become ready before timeout")
	}
}

func (s *server) admittedInvokeTargetLocked(world config.World) (map[string]any, *weaverTransition, *client.ResponseError) {
	if transition := s.transitions[world.ConfigDir]; transition != nil {
		switch transition.state() {
		case restartStateProbing:
			status, stale := readStatus(world)
			if status != nil && !stale {
				if err := validateAdmissionState(map[string]any{"state": "open", "generation_id": status["generation_id"]}); err != nil {
					return nil, nil, &client.ResponseError{Type: "protocol", Code: "mill/admission-invalid", Message: "invalid open admission state", Details: map[string]any{"detail": err.Error()}}
				}
				return status, nil, nil
			}
			return nil, nil, &client.ResponseError{Type: "transport", Code: "mill/stale-selected-weaver", Message: "stale selected workspace weaver metadata", Details: map[string]any{"config_dir": world.ConfigDir, "stale_reason": status["stale_reason"]}}
		case restartStateRestarting:
			return nil, transition, nil
		case restartStateFailed:
			if err := validateAdmissionState(map[string]any{"state": "closed", "transition_id": transition.transitionID}); err != nil {
				return nil, nil, &client.ResponseError{Type: "protocol", Code: "mill/admission-invalid", Message: "invalid closed admission state", Details: map[string]any{"detail": err.Error()}}
			}
			return nil, nil, &client.ResponseError{Type: "domain", Code: "mill/weaver-restart-failed", Message: "weaver restart failed; no generation is admitted", Details: map[string]any{"config_dir": world.ConfigDir, "transition_id": transition.transitionID, "failure": transition.result}}
		}
	}
	status, stale := readStatus(world)
	if status == nil {
		return nil, nil, &client.ResponseError{Type: "domain", Code: "mill/no-selected-weaver", Message: "no running weaver for selected workspace; start one with: mill weaver start", Details: map[string]any{"config_dir": world.ConfigDir}}
	}
	if stale {
		return nil, nil, &client.ResponseError{Type: "transport", Code: "mill/stale-selected-weaver", Message: "stale selected workspace weaver metadata", Details: map[string]any{"config_dir": world.ConfigDir, "stale_reason": status["stale_reason"]}}
	}
	if generation, ok := status["generation_id"].(string); ok {
		if err := validateAdmissionState(map[string]any{"state": "open", "generation_id": generation}); err != nil {
			return nil, nil, &client.ResponseError{Type: "protocol", Code: "mill/admission-invalid", Message: "invalid open admission state", Details: map[string]any{"detail": err.Error()}}
		}
	}
	return status, nil, nil
}

// relayInvoke dials the weaver socket, writes the invoke request frame, and
// streams the weaver's NDJSON response lines to w verbatim, flushing per line.
// A stream header switches the proxy to unbounded line-relay until the weaver
// closes (terminator); a single-result response is one line. It never buffers
// the whole response and holds no shared lock, so concurrent connections are
// not starved. Returns whether any frame was written (so the caller only
// synthesizes an error frame for a pre-relay failure) and the transport error.
func relayInvokeWithAdmission(callerCtx context.Context, socketPath, weaverID string, envelope map[string]any, timeoutMs int64, w *bufio.Writer, admitted func()) (bool, error) {
	ctx, cancel := context.WithTimeout(callerCtx, time.Second)
	defer cancel()
	if err := callerCtx.Err(); err != nil {
		return false, err
	}
	conn, err := (&net.Dialer{}).DialContext(ctx, "unix", socketPath)
	if err != nil {
		return false, fmt.Errorf("weaver socket unreachable: %w", err)
	}
	defer func() { _ = conn.Close() }()

	requestID := fmt.Sprintf("%d", time.Now().UnixNano())
	reqFrame := map[string]any{"protocol_version": client.ProtocolVersion, "request_id": requestID, "weaver_id": weaverID, "operation": "invoke", "arguments": envelope, "options": map[string]any{}}
	if timeoutMs > 0 {
		// Bounded single-result ops honour the envelope timeout; the deadline is
		// cleared below once a stream header proves the response is unbounded.
		_ = conn.SetDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond))
	}
	if err := callerCtx.Err(); err != nil {
		return false, err
	}
	writeDone := make(chan error, 1)
	// The request may have reached the Weaver even when Encode reports a
	// broken pipe. Mark the send boundary before writing so the caller never
	// retries a possibly delivered mutation.
	sent := true
	go func() {
		writeDone <- json.NewEncoder(conn).Encode(reqFrame)
	}()
	select {
	case err := <-writeDone:
		if err != nil {
			return sent, fmt.Errorf("weaver socket write failed: %w", err)
		}
	case <-callerCtx.Done():
		_ = conn.Close()
		return false, callerCtx.Err()
	}
	if err := callerCtx.Err(); err != nil {
		return false, fmt.Errorf("weaver socket write failed: %w", err)
	}
	if admitted != nil {
		admitted()
	}

	r := bufio.NewReader(conn)
	wrote := false
	streaming := false
	for {
		line, readErr := r.ReadBytes('\n')
		if len(line) > 0 {
			// A partial client write is still delivery evidence. Set this before
			// handing bytes to the caller's writer, whose failure is response-side
			// and must not cause request replay.
			wrote = true
			if !streaming && isStreamHeaderLine(line) {
				streaming = true
				_ = conn.SetDeadline(time.Time{}) // streams run unbounded
			}
			if _, werr := w.Write(line); werr != nil {
				return wrote, werr
			}
			if ferr := w.Flush(); ferr != nil {
				return wrote, ferr
			}
		}
		if readErr != nil {
			if readErr == io.EOF {
				return wrote, nil
			}
			return wrote, readErr
		}
	}
}

// isStreamHeaderLine reports whether a weaver response line is a stream header
// ({"stream": true}); once seen, the proxy relays unbounded.
func isStreamHeaderLine(line []byte) bool {
	var frame struct {
		Stream bool `json:"stream"`
	}
	if err := json.Unmarshal(line, &frame); err != nil {
		return false
	}
	return frame.Stream
}

// envelopeTimeoutMs extracts the millisecond timeout the strand dispatcher put
// on the invoke envelope, or 0 when the op should run unbounded.
func envelopeTimeoutMs(envelope map[string]any) int64 {
	if v, ok := envelope["timeout"]; ok {
		switch n := v.(type) {
		case float64:
			return int64(n)
		case int64:
			return n
		case int:
			return int64(n)
		}
	}
	return 0
}

// writeErrorFrame emits a single weaver-shaped error frame the strand relay
// surfaces on stderr with a non-zero exit (mill-originated failures before the
// weaver leg is reached).
func writeErrorFrame(w *bufio.Writer, requestID string, re *client.ResponseError) {
	frame := map[string]any{"protocol_version": client.ProtocolVersion, "request_id": requestID, "ok": false, "result": nil, "error": re}
	_ = json.NewEncoder(w).Encode(frame)
	_ = w.Flush()
}
