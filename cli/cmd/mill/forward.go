package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"time"

	"millstrand-strand-cli/internal/client"
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

	world, err := resolveLifecycleWorld(req.World)
	if err != nil {
		writeErrorFrame(w, req.RequestID, &client.ResponseError{Type: "transport", Code: "mill/invoke-world-failed", Message: "invoke world resolution failed", Details: map[string]any{"detail": err.Error()}})
		return
	}
	status, stale := readStatus(world)
	if status == nil {
		writeErrorFrame(w, req.RequestID, &client.ResponseError{Type: "domain", Code: "mill/no-selected-weaver", Message: "no running weaver for selected workspace; start one with: mill weaver start", Details: map[string]any{"config_dir": world.ConfigDir}})
		return
	}
	if stale {
		writeErrorFrame(w, req.RequestID, &client.ResponseError{Type: "transport", Code: "mill/stale-selected-weaver", Message: "stale selected workspace weaver metadata", Details: map[string]any{"config_dir": world.ConfigDir, "stale_reason": status["stale_reason"]}})
		return
	}
	socketPath, _ := status["socket_path"].(string)
	weaverID, _ := status["weaver_id"].(string)
	wrote, err := relayInvoke(socketPath, weaverID, req.Payload, envelopeTimeoutMs(req.Payload), w)
	if err != nil && !wrote {
		writeErrorFrame(w, req.RequestID, &client.ResponseError{Type: "transport", Code: "mill/weaver-forward-failed", Message: "weaver forwarding failed", Details: map[string]any{"detail": err.Error()}})
	}
}

// relayInvoke dials the weaver socket, writes the invoke request frame, and
// streams the weaver's NDJSON response lines to w verbatim, flushing per line.
// A stream header switches the proxy to unbounded line-relay until the weaver
// closes (terminator); a single-result response is one line. It never buffers
// the whole response and holds no shared lock, so concurrent connections are
// not starved. Returns whether any frame was written (so the caller only
// synthesizes an error frame for a pre-relay failure) and the transport error.
func relayInvoke(socketPath, weaverID string, envelope map[string]any, timeoutMs int64, w *bufio.Writer) (bool, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
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
	if err := json.NewEncoder(conn).Encode(reqFrame); err != nil {
		return false, fmt.Errorf("weaver socket write failed: %w", err)
	}

	r := bufio.NewReader(conn)
	wrote := false
	streaming := false
	for {
		line, readErr := r.ReadBytes('\n')
		if len(line) > 0 {
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
			wrote = true
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
