package client

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"time"

	"skein-strand-cli/internal/errfmt"
)

// InvokeThroughMill sends an invoke envelope through the local mill routing path
// and relays the weaver's NDJSON response to stdout/stderr. The envelope rides
// as the mill payload, which the mill forwards verbatim as the weaver `invoke`
// operation arguments (SPEC-002-D004.C6). A single-result response prints as one
// JSON line; a streaming response relays each emitted line as received and exits
// by the terminator's success flag (SPEC-002-D004.C7).
//
// It returns the process exit code: 0 on a successful single/stream response, a
// non-zero code on an error frame or terminator, and 130 on interrupt.
func InvokeThroughMill(world MillWorldRequest, envelope map[string]any, stdout, stderr io.Writer) (int, error) {
	code, err := invokeThroughMill(world, envelope, stdout, stderr)
	return code, asTransport(err)
}

func invokeThroughMill(world MillWorldRequest, envelope map[string]any, stdout, stderr io.Writer) (int, error) {
	meta, err := ReadMillMetadata()
	if err != nil {
		return 1, err
	}
	requestID := fmt.Sprintf("%d", time.Now().UnixNano())
	req := MillRequest{ProtocolVersion: MillProtocolVersion, RequestID: requestID, MillID: meta.MillID, Operation: "invoke", World: world, Payload: envelope}

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := (&net.Dialer{}).DialContext(ctx, "unix", meta.SocketPath)
	if err != nil {
		return 1, &TransportError{
			Err:  fmt.Errorf("mill socket unreachable; start one with: mill start: %w", err),
			Code: errfmt.CodeMillUnreachable,
		}
	}
	defer func() { _ = conn.Close() }()

	// SIGINT during a (possibly streaming) response closes the connection so the
	// relay read unblocks and exits non-zero cleanly (SPEC-002-D004.C7).
	interrupted := make(chan struct{})
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt)
	go func() {
		select {
		case <-sig:
			close(interrupted)
			_ = conn.Close()
		case <-ctx.Done():
		}
	}()
	defer signal.Stop(sig)

	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return 1, fmt.Errorf("mill socket write failed: %w", err)
	}
	code, err := RelayResponse(bufio.NewReader(conn), stdout, stderr, commandFromEnvelope(envelope))
	select {
	case <-interrupted:
		return 130, nil
	default:
		return code, err
	}
}

// RelayResponse reads newline-delimited response frames and relays them,
// returning the process exit code. The first frame self-describes: a `stream`
// header switches to line-by-line relay until a `done` terminator; otherwise it
// is a single `ok` response. Emitted stream lines are written verbatim (flushed
// per line); errors surface on stderr with a non-zero exit.
func RelayResponse(r *bufio.Reader, stdout, stderr io.Writer, command []string) (int, error) {
	line, err := readFrameLine(r)
	if err != nil {
		return 1, err
	}
	frame, err := decodeFrame(line)
	if err != nil {
		return 1, err
	}
	if isStreamHeader(frame) {
		return relayStream(r, stdout, stderr, command)
	}
	return relaySingle(frame, line, stdout, stderr, command)
}

func relaySingle(frame map[string]json.RawMessage, line []byte, stdout, stderr io.Writer, command []string) (int, error) {
	ok, err := frameBool(frame, "ok")
	if err != nil {
		return 1, fmt.Errorf("malformed weaver response: %w", err)
	}
	if ok {
		// A `verbatim` frame carries a default help transform's rendered output as
		// a JSON string; its decoded value is relayed byte-for-byte, never
		// re-encoded as JSON (DELTA-Dtf-002.CC1). Every other single result is
		// relayed as canonical envelope JSON.
		if verbatim, _ := frameBool(frame, "verbatim"); verbatim {
			return relayVerbatim(frame["result"], stdout)
		}
		var resp struct {
			Result any `json:"result"`
		}
		if err := json.Unmarshal(line, &resp); err != nil {
			return 1, fmt.Errorf("malformed weaver response: %w", err)
		}
		b, err := encodeJSONNoEscape(resp.Result)
		if err != nil {
			return 1, err
		}
		if _, err := fmt.Fprintln(stdout, string(b)); err != nil {
			return 1, fmt.Errorf("writing weaver response: %w", err)
		}
		return 0, nil
	}
	return surfaceError(frame["error"], stderr, command)
}

// relayVerbatim writes a verbatim-flagged result to stdout with no JSON
// re-encoding. The frame's `result` is a JSON string (the transport encoding of
// the help transform's output); its decoded value is the exact text to relay, so
// text stays text with no quoting or escaping (DELTA-Dtf-002.CC1, SPEC-002.C4/
// C36). A trailing newline is added only when absent, mirroring the stream relay.
func relayVerbatim(raw json.RawMessage, stdout io.Writer) (int, error) {
	var s string
	if err := json.Unmarshal(raw, &s); err != nil {
		return 1, fmt.Errorf("malformed weaver verbatim result: %w", err)
	}
	if _, err := io.WriteString(stdout, s); err != nil {
		return 1, fmt.Errorf("writing weaver response: %w", err)
	}
	if len(s) == 0 || s[len(s)-1] != '\n' {
		if _, err := fmt.Fprintln(stdout); err != nil {
			return 1, fmt.Errorf("writing weaver response: %w", err)
		}
	}
	return 0, nil
}

func relayStream(r *bufio.Reader, stdout, stderr io.Writer, command []string) (int, error) {
	for {
		line, err := readFrameLine(r)
		if err != nil {
			return 1, fmt.Errorf("weaver stream ended without terminator: %w", err)
		}
		frame, err := decodeFrame(line)
		if err != nil {
			return 1, err
		}
		if done, _ := frameBool(frame, "done"); done {
			success, err := frameBool(frame, "success")
			if err != nil {
				return 1, fmt.Errorf("malformed weaver stream terminator: %w", err)
			}
			if success {
				return 0, nil
			}
			return surfaceError(frame["error"], stderr, command)
		}
		// Emitted value: relay the frame verbatim, flushing per line. The line
		// carries its own trailing newline; a final unterminated line gets one.
		if _, err := stdout.Write(line); err != nil {
			return 1, fmt.Errorf("writing weaver stream frame: %w", err)
		}
		if len(line) == 0 || line[len(line)-1] != '\n' {
			if _, err := fmt.Fprintln(stdout); err != nil {
				return 1, fmt.Errorf("writing weaver stream frame: %w", err)
			}
		}
	}
}

// surfaceError renders a response error envelope to stderr and returns the
// typed error as well as a non-zero exit code. Callers that need machine
// handling (such as mill bin) can preserve its code and details without
// reverse-parsing the human rendering.
func surfaceError(raw json.RawMessage, stderr io.Writer, command []string) (int, error) {
	// Every way a failure frame can fail to carry an envelope is the same fault:
	// skew between this bin, the mill, and the weaver. It is not rendered here —
	// returning it unrendered leaves the single rendering to the caller, which
	// renders every failure the relay did not, and the raw value rides in the
	// message so nothing is lost. The wire taxonomy is exactly the three types
	// validResponseError accepts (SPEC-004.C24), so a frame claiming any other
	// never reaches a consumer as if the weaver had declared it.
	if len(raw) == 0 {
		return 1, malformedWeaverError(raw, errors.New("failure frame carries no error value"))
	}
	var re ResponseError
	if err := json.Unmarshal(raw, &re); err != nil {
		return 1, malformedWeaverError(raw, err)
	}
	if !validResponseError(&re) {
		return 1, malformedWeaverError(raw, errors.New("error envelope does not match protocol"))
	}
	errfmt.Render(stderr, re.forRendering(command), errfmt.ModeFor(stderr))
	return 1, &re
}

// malformedWeaverError types a failure frame this bin could not read as an
// envelope, keeping the raw value beside the reason it was rejected.
func malformedWeaverError(raw json.RawMessage, reason error) error {
	if len(raw) == 0 {
		return &TransportError{
			Err:  fmt.Errorf("malformed weaver error: %w", reason),
			Code: errfmt.CodeWeaverResponseMalformed,
		}
	}
	return &TransportError{
		Err:  fmt.Errorf("malformed weaver error %s: %w", raw, reason),
		Code: errfmt.CodeWeaverResponseMalformed,
	}
}

// commandFromEnvelope reads back the op name and argv the dispatcher assembled,
// which is the command the user typed.
func commandFromEnvelope(envelope map[string]any) []string {
	command := []string{}
	if name, ok := envelope["name"].(string); ok && name != "" {
		command = append(command, name)
	}
	switch argv := envelope["argv"].(type) {
	case []string:
		command = append(command, argv...)
	case []any:
		for _, token := range argv {
			if text, ok := token.(string); ok {
				command = append(command, text)
			}
		}
	}
	return command
}

func readFrameLine(r *bufio.Reader) ([]byte, error) {
	line, err := r.ReadBytes('\n')
	if len(line) == 0 && err != nil {
		return nil, err
	}
	// A final frame may arrive without a trailing newline; tolerate that as long
	// as we have content.
	return line, nil
}

func decodeFrame(line []byte) (map[string]json.RawMessage, error) {
	var frame map[string]json.RawMessage
	if err := json.Unmarshal(line, &frame); err != nil {
		return nil, fmt.Errorf("malformed weaver response frame: %w", err)
	}
	return frame, nil
}

func isStreamHeader(frame map[string]json.RawMessage) bool {
	b, err := frameBool(frame, "stream")
	return err == nil && b
}

func frameBool(frame map[string]json.RawMessage, key string) (bool, error) {
	raw, ok := frame[key]
	if !ok {
		return false, fmt.Errorf("missing %q", key)
	}
	var v bool
	if err := json.Unmarshal(raw, &v); err != nil {
		return false, fmt.Errorf("%q is not a boolean", key)
	}
	return v, nil
}
