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
		// Keep the transition that admitted the old generation alongside the
		// target.  The transition may leave s.transitions after replacement is
		// ready, but an accepted old-generation request still needs its planned
		// interruption outcome classified as weaver/restarted.
		admittedTransition := s.admittedInvocationTransitionLocked(world.ConfigDir, status)
		// Target selection is protected by the workspace admission lock. Once
		// selected, release the process-global state mutex before any external
		// socket operation; the workspace lock remains held until the request
		// frame is admitted, so a same-workspace lifecycle transition cannot
		// cut over underneath this admission.
		s.mu.Unlock()
		admissionOpen := true
		outcome := relayInvokeWithAdmission(callerCtx, socketPath, weaverID, req.Payload, envelopeTimeoutMs(req.Payload), w, func() {
			admission.Unlock()
			admissionOpen = false
		}, nil)
		if admissionOpen {
			admission.Unlock()
		}
		if outcome.err == nil || outcome.clientWriteFailed {
			return
		}
		if callerCtx.Err() != nil {
			return
		}
		// A request that may have reached the Weaver is never replayed. Emit a
		// transport frame when no response exists so the caller can distinguish
		// an ambiguous send from a successful relay.
		code := "mill/weaver-forward-failed"
		message := "weaver forwarding failed"
		details := map[string]any{"detail": outcome.err.Error(), "request_delivery": outcome.requestDelivery}
		if outcome.requestDelivery && plannedCutoverInterrupted(admittedTransition) {
			code = "weaver/restarted"
			message = "weaver replacement interrupted an admitted invocation"
			details["sent_once"] = true
			details["response"] = "ambiguous"
			details["transition_id"] = admittedTransition.transitionID
		}
		writeErrorFrame(w, req.RequestID, &client.ResponseError{Type: "transport", Code: code, Message: message, Details: details})
		return
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

func (s *server) admittedInvocationTransitionLocked(configDir string, status map[string]any) *weaverTransition {
	t := s.transitions[configDir]
	if t == nil || t.old == nil {
		return nil
	}
	weaverID, _ := status["weaver_id"].(string)
	generationID, _ := status["generation_id"].(string)
	if (t.old.identity.WeaverID == "" || t.old.identity.WeaverID != weaverID) &&
		(t.old.generationID == "" || t.old.generationID != generationID) {
		return nil
	}
	switch t.state() {
	case restartStateProbing, restartStateRestarting:
		return t
	default:
		return nil
	}
}

func plannedCutoverInterrupted(t *weaverTransition) bool {
	if t == nil {
		return false
	}
	t.mu.Lock()
	cutoverStarted := t.cutoverStarted
	t.mu.Unlock()
	return cutoverStarted
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
				// Metadata written by a legacy Weaver has no generation identity.
				// The transition minted one when it admitted this old process; bind
				// the invocation to that synthesized identity for the duration of
				// the gated probe without rewriting legacy metadata.
				status = cloneStatus(status)
				if transition.old != nil && stringStatus(status, "generation_id") == "" {
					status["generation_id"] = transition.old.generationID
				}
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

func cloneStatus(status map[string]any) map[string]any {
	clone := make(map[string]any, len(status)+1)
	for key, value := range status {
		clone[key] = value
	}
	return clone
}

// relayInvoke dials the weaver socket, writes the invoke request frame, and
// streams the weaver's NDJSON response lines to w verbatim, flushing per line.
// A stream header switches the proxy to unbounded line-relay until the weaver
// closes (terminator); a single-result response is one line. It never buffers
// the whole response and holds no shared lock, so concurrent connections are
// not starved. Request delivery and response relay are tracked separately:
// delivery ambiguity forbids replay, while the absence of a response frame
// still produces a transport error for the caller.
type relayOutcome struct {
	requestDelivery   bool
	responseWritten   bool
	clientWriteFailed bool
	err               error
}

func relayInvokeWithAdmission(callerCtx context.Context, socketPath, weaverID string, envelope map[string]any, timeoutMs int64, w *bufio.Writer, admitted, writeStarted func()) relayOutcome {
	ctx, cancel := context.WithTimeout(callerCtx, time.Second)
	defer cancel()
	if err := callerCtx.Err(); err != nil {
		return relayOutcome{err: err}
	}
	conn, err := (&net.Dialer{}).DialContext(ctx, "unix", socketPath)
	if err != nil {
		return relayOutcome{err: fmt.Errorf("weaver socket unreachable: %w", err)}
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
		return relayOutcome{err: err}
	}
	writeDone := make(chan error, 1)
	deliveryStarted := make(chan struct{})
	go func() {
		close(deliveryStarted)
		if writeStarted != nil {
			writeStarted()
		}
		writeDone <- json.NewEncoder(conn).Encode(reqFrame)
	}()
	select {
	case err := <-writeDone:
		if err != nil {
			return relayOutcome{requestDelivery: true, err: fmt.Errorf("weaver socket write failed: %w", err)}
		}
	case <-callerCtx.Done():
		_ = conn.Close()
		select {
		case <-deliveryStarted:
			return relayOutcome{requestDelivery: true, err: callerCtx.Err()}
		default:
			return relayOutcome{err: callerCtx.Err()}
		}
	}
	if err := callerCtx.Err(); err != nil {
		return relayOutcome{requestDelivery: true, err: fmt.Errorf("weaver socket write failed: %w", err)}
	}
	if admitted != nil {
		admitted()
	}

	r := bufio.NewReader(conn)
	outcome := relayOutcome{requestDelivery: true}
	streaming := false
	streamTerminated := false
	for {
		line, readErr := r.ReadBytes('\n')
		completeFrame := len(line) > 0 && line[len(line)-1] == '\n'
		if completeFrame {
			// A complete response frame is delivery evidence. Set this before
			// handing bytes to the caller's writer, whose failure is response-side
			// and must not cause request replay.
			outcome.responseWritten = true
			if !streaming && isStreamHeaderLine(line) {
				streaming = true
				_ = conn.SetDeadline(time.Time{}) // streams run unbounded
			}
			if streaming && isStreamTerminatorLine(line, requestID) {
				streamTerminated = true
			}
			if _, werr := w.Write(line); werr != nil {
				outcome.clientWriteFailed = true
				outcome.err = werr
				return outcome
			}
			if ferr := w.Flush(); ferr != nil {
				outcome.clientWriteFailed = true
				outcome.err = ferr
				return outcome
			}
			// A complete non-stream frame is the whole response. Do not read for
			// another frame and turn a later socket error into a contradictory
			// transport response.
			if !streaming || streamTerminated {
				return outcome
			}
		} else if len(line) > 0 {
			// A response is not delivered until its newline-delimited frame is
			// complete. Do not relay a truncated or garbage partial line; the
			// request remains ambiguous and the caller receives a transport error.
			outcome.err = errors.New("weaver closed connection before a complete response frame")
			return outcome
		}
		if readErr != nil {
			if readErr == io.EOF {
				if !outcome.responseWritten || (streaming && !streamTerminated) {
					outcome.err = errors.New("weaver closed connection without a response frame")
				}
				return outcome
			}
			outcome.err = readErr
			return outcome
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

func isStreamTerminatorLine(line []byte, requestID string) bool {
	var frame map[string]json.RawMessage
	if err := json.Unmarshal(line, &frame); err != nil {
		return false
	}
	if frame == nil {
		return false
	}
	var done, success bool
	if err := json.Unmarshal(frame["done"], &done); err != nil || !done {
		return false
	}
	if err := json.Unmarshal(frame["success"], &success); err != nil {
		return false
	}
	if !hasExactKeys(frame, map[string]bool{"protocol_version": true, "request_id": true, "done": true, "success": true, "result": success, "error": !success}) {
		return false
	}
	var version int
	if err := json.Unmarshal(frame["protocol_version"], &version); err != nil || version != client.ProtocolVersion {
		return false
	}
	var actualRequestID string
	if err := json.Unmarshal(frame["request_id"], &actualRequestID); err != nil || actualRequestID == "" || actualRequestID != requestID {
		return false
	}
	return true
}

func hasExactKeys(frame map[string]json.RawMessage, expected map[string]bool) bool {
	requiredCount := 0
	for _, required := range expected {
		if required {
			requiredCount++
		}
	}
	if len(frame) != requiredCount {
		return false
	}
	for key, required := range expected {
		_, present := frame[key]
		if present != required {
			return false
		}
	}
	return true
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
