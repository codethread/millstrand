package client

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"millstrand-strand-cli/internal/errfmt"
	"millstrand-strand-cli/internal/process"
)

const protocolVersion = 3

// ProtocolVersion is the JSON socket protocol version the bin speaks; exported
// for the dispatcher's --version and dry-run frame identity.
const ProtocolVersion = protocolVersion

type Metadata struct {
	ProtocolVersion int    `json:"protocol_version"`
	PID             int    `json:"pid"`
	DatabaseKind    string `json:"database_kind"`
	DatabaseLabel   string `json:"database_label"`
	// Pointer so a JSON null (required for sqlite-memory) is distinguishable
	// from an accidental empty string.
	DatabasePath     *string `json:"database_path"`
	DaemonID         string  `json:"weaver_id"`
	GenerationID     string  `json:"generation_id"`
	BasisFingerprint string  `json:"basis_fingerprint"`
	ConfigDir        string  `json:"config_dir"`
	StateDir         string  `json:"state_dir"`
	DataDir          string  `json:"data_dir"`
	Name             string  `json:"name"`
	SocketPath       string  `json:"socket_path"`
	StartedAt        string  `json:"started_at"`
	NREPL            struct {
		Host string `json:"host"`
		Port int    `json:"port"`
	} `json:"nrepl"`
}

type ResponseError struct {
	Type    string         `json:"type"`
	Code    string         `json:"code"`
	Message string         `json:"message"`
	Details map[string]any `json:"details"`
}

// Error is the plain single-line rendering, which errfmt owns: the Go error
// string and what stderr prints outside a terminal are the same bytes.
func (e *ResponseError) Error() string {
	if e == nil {
		return "weaver error"
	}
	return e.forRendering(nil).PlainMessage()
}

// forRendering maps the decoded envelope into the renderer's input without
// flattening it first, so pretty mode still has the typed fields to work with.
func (e *ResponseError) forRendering(command []string) errfmt.Error {
	return errfmt.Error{Type: e.Type, Code: e.Code, Message: e.Message, Details: e.Details, Command: command}
}

// TransportError marks a failure to reach or speak to the local mill: no
// socket, stale metadata, a write that never landed, a frame that would not
// decode. It is raised here rather than decoded from a frame, but its taxonomy
// is transport all the same (SPEC-002.C4c).
type TransportError struct {
	Err error
	// Code names which transport failure this is, for a JSON-mode consumer.
	// Empty means the generic one asTransport stamps.
	Code string
}

func (e *TransportError) Error() string { return e.Err.Error() }

func (e *TransportError) Unwrap() error { return e.Err }

// asTransport marks everything a mill call failed with that is not a decoded
// envelope. Applied once at each call boundary, it saves every caller from
// re-deciding which failures were the transport's, and leaves a failure that
// already named itself alone.
func asTransport(err error) error {
	if err == nil {
		return nil
	}
	var responseErr *ResponseError
	if errors.As(err, &responseErr) {
		return err
	}
	var transportErr *TransportError
	if errors.As(err, &transportErr) {
		return err
	}
	return &TransportError{Err: err, Code: errfmt.CodeMillTransportFailed}
}

// ForRendering maps whatever a mill call returned into the renderer's input: a
// decoded envelope keeps its typed fields, an unreachable or skewed mill is
// transport, and anything else is a bad invocation.
func ForRendering(err error, command []string) errfmt.Error {
	var responseErr *ResponseError
	if errors.As(err, &responseErr) {
		return responseErr.forRendering(command)
	}
	var transportErr *TransportError
	if errors.As(err, &transportErr) {
		return errfmt.LocalError(errfmt.TypeTransport, transportErr.Code, err, command)
	}
	return errfmt.FromError(err, command)
}

func encodeJSONNoEscape(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}

func validResponseError(e *ResponseError) bool {
	if e.Code == "" || e.Message == "" || e.Details == nil {
		return false
	}
	switch e.Type {
	case "domain", "protocol", "transport":
		return true
	default:
		return false
	}
}

// DatabasePathString returns the file-backed database path, or "" when the
// metadata carries no path (sqlite-memory publishes an explicit null).
func (m Metadata) DatabasePathString() string {
	if m.DatabasePath == nil {
		return ""
	}
	return *m.DatabasePath
}

// ValidateStorageIdentity fails unless database kind, label, and path are
// mutually consistent: sqlite-file requires a non-blank label == path;
// sqlite-memory requires a non-blank label and a null path.
func ValidateStorageIdentity(m Metadata) error {
	if strings.TrimSpace(m.DatabaseLabel) == "" {
		return fmt.Errorf("blank weaver storage label for kind %q", m.DatabaseKind)
	}
	switch m.DatabaseKind {
	case "sqlite-file":
		if m.DatabasePath == nil || strings.TrimSpace(*m.DatabasePath) == "" || m.DatabaseLabel != *m.DatabasePath {
			return fmt.Errorf("inconsistent sqlite-file storage metadata: label %q path %q", m.DatabaseLabel, m.DatabasePathString())
		}
	case "sqlite-memory":
		if m.DatabasePath != nil {
			return fmt.Errorf("inconsistent sqlite-memory storage metadata: label %q path %q", m.DatabaseLabel, *m.DatabasePath)
		}
	default:
		return fmt.Errorf("unknown weaver storage kind %q", m.DatabaseKind)
	}
	return nil
}

func pidAlive(pid int) bool {
	return process.Alive(pid)
}
