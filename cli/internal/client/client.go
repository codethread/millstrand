package client

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"syscall"

	"skein-strand-cli/internal/errfmt"
)

const protocolVersion = 1

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
	DatabasePath *string `json:"database_path"`
	DaemonID     string  `json:"weaver_id"`
	ConfigDir    string  `json:"config_dir"`
	StateDir     string  `json:"state_dir"`
	DataDir      string  `json:"data_dir"`
	Name         string  `json:"name"`
	SocketPath   string  `json:"socket_path"`
	StartedAt    string  `json:"started_at"`
	NREPL        struct {
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
	p, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	return p.Signal(syscall.Signal(0)) == nil
}
