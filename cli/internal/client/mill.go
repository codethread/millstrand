package client

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"time"

	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/errfmt"
)

const MillProtocolVersion = 3

const defaultWeaverReadyTimeout = 5 * time.Minute

type MillMetadata struct {
	ProtocolVersion int    `json:"protocol_version"`
	PID             int    `json:"pid"`
	MillID          string `json:"mill_id"`
	StateRoot       string `json:"state_root"`
	SocketPath      string `json:"socket_path"`
	StartedAt       string `json:"started_at"`
	// MillBuild is the writing mill's config.BuildID; absent in metadata from
	// builds that predate stamping, so it never joins the required-field set.
	MillBuild string `json:"mill_build,omitempty"`
}

// ErrMillProtocolMismatch marks metadata written by a live mill speaking a
// different mill protocol than this binary: the fix is version alignment, so
// callers must not append the generic "start one with: mill start" remedy.
var ErrMillProtocolMismatch = errors.New("mill protocol mismatch")

type MillRequest struct {
	ProtocolVersion int              `json:"protocol_version"`
	RequestID       string           `json:"request_id"`
	MillID          string           `json:"mill_id"`
	Operation       string           `json:"operation"`
	World           MillWorldRequest `json:"world,omitempty"`
	Payload         map[string]any   `json:"payload"`
}

type MillWorldRequest struct {
	CWD            string `json:"cwd,omitempty"`
	ConfigDir      string `json:"config_dir,omitempty"`
	Source         string `json:"source,omitempty"`
	Name           string `json:"name,omitempty"`
	ReadyTimeoutMs int64  `json:"ready_timeout_ms,omitempty"`
	Stealth        bool   `json:"stealth,omitempty"`
}

type MillResponse struct {
	ProtocolVersion int            `json:"protocol_version"`
	RequestID       string         `json:"request_id"`
	OK              bool           `json:"ok"`
	Result          any            `json:"result"`
	Error           *ResponseError `json:"error"`
}

func MillStatus() (any, error) { return MillCall("status", MillWorldRequest{}) }

func MillCall(operation string, world MillWorldRequest) (any, error) {
	return MillCallPayload(operation, world, nil)
}

func MillCallPayload(operation string, world MillWorldRequest, payload map[string]any) (any, error) {
	result, err := millCallPayload(operation, world, payload)
	return result, asTransport(err)
}

func millCallPayload(operation string, world MillWorldRequest, payload map[string]any) (any, error) {
	if payload == nil {
		payload = map[string]any{}
	}
	meta, err := ReadMillMetadata()
	if err != nil {
		return nil, err
	}
	requestID := fmt.Sprintf("%d", time.Now().UnixNano())
	req := MillRequest{ProtocolVersion: MillProtocolVersion, RequestID: requestID, MillID: meta.MillID, Operation: operation, World: world, Payload: payload}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := (&net.Dialer{}).DialContext(ctx, "unix", meta.SocketPath)
	if err != nil {
		return nil, &TransportError{
			Err:  fmt.Errorf("mill socket unreachable; start one with: mill start: %w", err),
			Code: errfmt.CodeMillUnreachable,
		}
	}
	defer func() { _ = conn.Close() }()
	deadline := 5 * time.Second
	switch operation {
	case "weaver-start":
		// Weaver startup waits for the child process to publish ready metadata.
		// The client protocol deadline must cover the mill-side ready wait plus
		// a small cushion for response delivery.
		if world.ReadyTimeoutMs > 0 {
			deadline = time.Duration(world.ReadyTimeoutMs)*time.Millisecond + 5*time.Second
		} else {
			deadline = defaultWeaverReadyTimeout + 5*time.Second
		}
	case "weaver-stop":
		// Stop signals the weaver and waits for its shutdown hook to clean up
		// runtime metadata (supervised child or metadata-discovered pid), which
		// can outlast the short default protocol deadline.
		deadline = 15 * time.Second
	}
	_ = conn.SetDeadline(time.Now().Add(deadline))
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return nil, fmt.Errorf("mill socket write failed: %w", err)
	}
	var resp MillResponse
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&resp); err != nil {
		return nil, fmt.Errorf("malformed mill response: %w", err)
	}
	if resp.ProtocolVersion != MillProtocolVersion || resp.RequestID != requestID {
		return nil, errors.New("malformed mill response: protocol version or request id mismatch")
	}
	if !resp.OK {
		if resp.Error == nil || !validResponseError(resp.Error) || resp.Result != nil {
			return nil, errors.New("malformed mill response: error envelope does not match protocol")
		}
		return nil, resp.Error
	}
	if resp.Error != nil {
		return nil, errors.New("malformed mill response: success envelope includes error")
	}
	return resp.Result, nil
}

// ReadMillMetadata resolves the running mill from its published metadata. Every
// way it fails — no metadata, stale metadata, a dead pid, a mismatched socket —
// means there is no mill to talk to, so it types as transport and carries the
// code that says the remedy is to start one.
func ReadMillMetadata() (MillMetadata, error) {
	metadata, err := readMillMetadata()
	if err != nil {
		if errors.Is(err, ErrMillProtocolMismatch) {
			return MillMetadata{}, &TransportError{Err: err, Code: errfmt.CodeMillUnreachable}
		}
		return MillMetadata{}, &TransportError{
			Err:  fmt.Errorf("%w; start one with: mill start", err),
			Code: errfmt.CodeMillUnreachable,
		}
	}
	return metadata, nil
}

func samePath(a, b string) bool {
	ca, errA := filepath.EvalSymlinks(filepath.Clean(a))
	if errA != nil {
		ca = filepath.Clean(a)
	}
	cb, errB := filepath.EvalSymlinks(filepath.Clean(b))
	if errB != nil {
		cb = filepath.Clean(b)
	}
	return ca == cb
}

func readMillMetadata() (MillMetadata, error) {
	file, err := config.MillMetadataPath()
	if err != nil {
		return MillMetadata{}, err
	}
	b, err := os.ReadFile(file)
	if os.IsNotExist(err) {
		return MillMetadata{}, errors.New("no running mill")
	}
	if err != nil {
		return MillMetadata{}, err
	}
	var m MillMetadata
	if err := json.Unmarshal(b, &m); err != nil {
		return MillMetadata{}, fmt.Errorf("malformed mill metadata: %w", err)
	}
	root, err := config.StateRoot()
	if err != nil {
		return MillMetadata{}, err
	}
	if missing := missingMetadataFields(m); len(missing) > 0 {
		return MillMetadata{}, fmt.Errorf("malformed mill metadata: missing required fields %v in %s", missing, file)
	}
	if !samePath(m.StateRoot, root) {
		return MillMetadata{}, fmt.Errorf("mill metadata state root mismatch: %s", m.StateRoot)
	}
	if !samePath(m.SocketPath, filepath.Join(root, config.MillSocketFileName)) {
		return MillMetadata{}, fmt.Errorf("mill metadata socket mismatch: %s", m.SocketPath)
	}
	if !pidAlive(m.PID) {
		return MillMetadata{}, fmt.Errorf("stale mill metadata: pid %d is not alive", m.PID)
	}
	// Checked after pidAlive so the message can truthfully say the mill is
	// running: this is version skew, not an absent or stale mill.
	if m.ProtocolVersion != MillProtocolVersion {
		return MillMetadata{}, fmt.Errorf(
			"%w: the running mill (pid %d, build %s) wrote %s with mill protocol v%d, but this strand (build %s) speaks v%d; rebuild strand from the running mill's checkout, or restart mill from this build",
			ErrMillProtocolMismatch, m.PID, buildLabel(m.MillBuild), file, m.ProtocolVersion, buildLabel(config.BuildID), MillProtocolVersion)
	}
	return m, nil
}

// missingMetadataFields names required metadata fields carrying zero values.
// A zero protocol version is a missing field; a non-zero, non-matching one is
// the ErrMillProtocolMismatch case decided after liveness.
func missingMetadataFields(m MillMetadata) []string {
	var missing []string
	if m.ProtocolVersion == 0 {
		missing = append(missing, "protocol_version")
	}
	if m.PID == 0 {
		missing = append(missing, "pid")
	}
	if m.MillID == "" {
		missing = append(missing, "mill_id")
	}
	if m.StateRoot == "" {
		missing = append(missing, "state_root")
	}
	if m.SocketPath == "" {
		missing = append(missing, "socket_path")
	}
	if m.StartedAt == "" {
		missing = append(missing, "started_at")
	}
	return missing
}

// buildLabel renders a stamped build id, or names the absence of one so skew
// messages stay explicit when either binary predates build stamping.
func buildLabel(build string) string {
	if build == "" {
		return "unstamped"
	}
	return build
}
