package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"path/filepath"
	"strings"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/process"
)

var controlOperations = map[string]bool{
	"process.launch":      true,
	"process.get":         true,
	"process.list-owned":  true,
	"process.cancel":      true,
	"process.acknowledge": true,
}

func (s *server) handleProcessControl(conn net.Conn, raw map[string]json.RawMessage) {
	request, err := decodeControlRequest(raw)
	requestID := request.RequestID
	if err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse(requestID, "protocol", "process/malformed-request", "malformed process control request", err.Error()))
		return
	}
	if !controlOperations[request.Operation] {
		_ = json.NewEncoder(conn).Encode(errorResponse(requestID, "protocol", "process/operation-not-allowed", "process operation is not available", request.Operation))
		return
	}
	world, err := s.admitControlCaller(request.WeaverID, request.LaunchToken)
	if err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse(requestID, "domain", "process/stale-weaver", "process control caller is not an admitted Weaver", err.Error()))
		return
	}
	custody, err := s.custodyFor(world)
	if err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse(requestID, "domain", "process/unavailable", "Mill process custody is unavailable", err.Error()))
		return
	}
	result, err := dispatchProcessControl(custody, request.Operation, request.Arguments)
	if err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse(requestID, "domain", processErrorCode(err), "process custody operation failed", err.Error()))
		return
	}
	_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: requestID, OK: true, Result: result})
}

// controlRequest is the decoded process control frame. LaunchToken is optional:
// only a weaver that has not yet published its identity needs to present it.
type controlRequest struct {
	RequestID   string
	WeaverID    string
	LaunchToken string
	Operation   string
	Arguments   map[string]any
}

func decodeControlRequest(raw map[string]json.RawMessage) (controlRequest, error) {
	allowed := map[string]bool{"protocol_version": true, "request_id": true, "weaver_id": true, "launch_token": true, "operation": true, "arguments": true}
	request := controlRequest{RequestID: controlString(raw, "request_id")}
	for key := range raw {
		if !allowed[key] {
			return request, fmt.Errorf("unknown control request field %q", key)
		}
	}
	var version int
	if err := unmarshalControl(raw, "protocol_version", &version); err != nil {
		return request, err
	}
	if version != client.MillProtocolVersion {
		return request, errors.New("unsupported process control protocol version")
	}
	for key, target := range map[string]any{"request_id": &request.RequestID, "weaver_id": &request.WeaverID, "operation": &request.Operation} {
		if err := unmarshalControl(raw, key, target); err != nil {
			return request, err
		}
	}
	if strings.TrimSpace(request.RequestID) == "" || strings.TrimSpace(request.WeaverID) == "" || strings.TrimSpace(request.Operation) == "" {
		return request, errors.New("request_id, weaver_id, and operation must be non-blank strings")
	}
	if _, present := raw["launch_token"]; present {
		if err := unmarshalControl(raw, "launch_token", &request.LaunchToken); err != nil {
			return request, err
		}
		if strings.TrimSpace(request.LaunchToken) == "" {
			return request, errors.New("launch_token must be a non-blank string when present")
		}
	}
	if err := unmarshalControl(raw, "arguments", &request.Arguments); err != nil || request.Arguments == nil {
		if err == nil {
			err = errors.New("arguments must be an object")
		}
		return request, err
	}
	return request, nil
}

func controlString(raw map[string]json.RawMessage, key string) string {
	var value string
	_ = json.Unmarshal(raw[key], &value)
	return value
}

func unmarshalControl(raw map[string]json.RawMessage, key string, target any) error {
	value, ok := raw[key]
	if !ok {
		return fmt.Errorf("missing control request field %q", key)
	}
	if err := json.Unmarshal(value, target); err != nil {
		return fmt.Errorf("control request field %q: %w", key, err)
	}
	return nil
}

func dispatchProcessControl(custody *process.Custody, operation string, arguments map[string]any) (any, error) {
	switch operation {
	case "process.launch":
		owner, key, specMap, err := launchArguments(arguments)
		if err != nil {
			return nil, err
		}
		spec, err := process.ParseLaunchSpec(specMap)
		if err != nil {
			return nil, err
		}
		return custody.Launch(owner, key, spec)
	case "process.get":
		handle, err := handleArgument(arguments)
		if err != nil {
			return nil, err
		}
		return custody.Get(handle)
	case "process.list-owned":
		for key := range arguments {
			if key != "owner" {
				return nil, fmt.Errorf("list-owned arguments contain unknown field %q", key)
			}
		}
		owner, ok := arguments["owner"].(string)
		if !ok || strings.TrimSpace(owner) == "" {
			return nil, errors.New("owner must be a non-blank string")
		}
		return custody.ListOwned(owner)
	case "process.cancel":
		owner, handle, err := ownerHandleArguments(arguments)
		if err != nil {
			return nil, err
		}
		return custody.Cancel(owner, handle)
	case "process.acknowledge":
		owner, handle, err := ownerHandleArguments(arguments)
		if err != nil {
			return nil, err
		}
		if err := custody.Acknowledge(owner, handle); err != nil {
			return nil, err
		}
		return map[string]any{"acknowledged": true, "handle": handle}, nil
	default:
		return nil, errors.New("unsupported process operation")
	}
}

func launchArguments(arguments map[string]any) (string, string, map[string]any, error) {
	for key := range arguments {
		if key != "owner" && key != "key" && key != "launch_spec" {
			return "", "", nil, fmt.Errorf("launch arguments contain unknown field %q", key)
		}
	}
	owner, ownerOK := arguments["owner"].(string)
	key, keyOK := arguments["key"].(string)
	spec, specOK := arguments["launch_spec"].(map[string]any)
	if !ownerOK || !keyOK || !specOK || strings.TrimSpace(owner) == "" || strings.TrimSpace(key) == "" {
		return "", "", nil, errors.New("launch arguments require non-blank owner, key, and launch_spec")
	}
	return owner, key, spec, nil
}

func handleArgument(arguments map[string]any) (string, error) {
	for key := range arguments {
		if key != "handle" {
			return "", fmt.Errorf("handle arguments contain unknown field %q", key)
		}
	}
	handle, ok := arguments["handle"].(string)
	if !ok || strings.TrimSpace(handle) == "" {
		return "", errors.New("handle must be a non-blank string")
	}
	return handle, nil
}

func ownerHandleArguments(arguments map[string]any) (string, string, error) {
	for key := range arguments {
		if key != "owner" && key != "handle" {
			return "", "", fmt.Errorf("owner/handle arguments contain unknown field %q", key)
		}
	}
	owner, ownerOK := arguments["owner"].(string)
	handle, handleOK := arguments["handle"].(string)
	if !ownerOK || strings.TrimSpace(owner) == "" {
		return "", "", errors.New("owner must be a non-blank string")
	}
	if !handleOK || strings.TrimSpace(handle) == "" {
		return "", "", errors.New("handle must be a non-blank string")
	}
	return owner, handle, nil
}

func processErrorCode(err error) string {
	message := strings.ToLower(err.Error())
	switch {
	case strings.Contains(message, "unknown custody handle"):
		return "process/unknown-handle"
	case strings.Contains(message, "different owner"):
		return "process/owner-mismatch"
	case strings.Contains(message, "already reserved"):
		return "process/conflicting-key"
	case strings.Contains(message, "tombstone"), strings.Contains(message, "already acknowledged"):
		return "process/conflicting-key"
	case strings.Contains(message, "malformed"), strings.Contains(message, "must be"), strings.Contains(message, "launch spec"):
		return "process/malformed-launch"
	default:
		return "process/error"
	}
}

// launchTokenEnvVar carries a mill-generated per-launch secret to the weaver it
// launched. It is never written to an artifact. Mill puts it only on that
// weaver's environment; any descendant that inherits the weaver env sees it.
const launchTokenEnvVar = "MILLSTRAND_MILL_LAUNCH_TOKEN"

func launchTokenEnv(token string) []string {
	if strings.TrimSpace(token) == "" {
		return nil
	}
	return []string{launchTokenEnvVar + "=" + token}
}

// admitControlCaller resolves the world whose custody a process control caller
// may reach.
//
// A ready weaver is admitted by published identity, exactly as before. A weaver
// that mill launched and is still starting has published no identity yet, but
// its config evaluation legitimately needs its own custody — to recover runs
// left by an earlier generation, or to schedule pending work. Such a caller is
// admitted only by presenting the launch token mill handed to that specific
// still-live launch, and the first admitted request pins the token to one
// weaver identity for the rest of startup. A stale or unrelated caller has
// neither a supervised identity nor a live launch token, and is rejected.
func (s *server) admitControlCaller(weaverID, launchToken string) (config.World, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, child := range s.children {
		if child != nil && child.identity.WeaverID == weaverID {
			return child.world, nil
		}
	}
	if strings.TrimSpace(launchToken) == "" {
		return config.World{}, fmt.Errorf("no running Weaver with identity %q", weaverID)
	}
	for _, child := range s.children {
		if child == nil || child.unsupervised || child.launchToken == "" || child.launchToken != launchToken {
			continue
		}
		if child.identity.WeaverID != "" {
			// The launch this token names has already published a different
			// identity, so the token no longer speaks for the caller.
			return config.World{}, fmt.Errorf("launch token belongs to Weaver %q, not %q", child.identity.WeaverID, weaverID)
		}
		if child.cmd == nil || child.cmd.Process == nil || !processAlive(child.cmd.Process.Pid) {
			return config.World{}, errors.New("launch token names a Weaver that is no longer running")
		}
		if child.startupWeaverID == "" {
			child.startupWeaverID = weaverID
		} else if child.startupWeaverID != weaverID {
			return config.World{}, fmt.Errorf("launch token is already bound to Weaver %q", child.startupWeaverID)
		}
		return child.world, nil
	}
	return config.World{}, fmt.Errorf("no running Weaver with identity %q", weaverID)
}

func (s *server) custodyFor(world config.World) (*process.Custody, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.custodies == nil {
		s.custodies = make(map[string]*process.Custody)
	}
	if custody := s.custodies[world.ConfigDir]; custody != nil {
		return custody, nil
	}
	custody, err := process.NewCustody(filepath.Join(world.StateDir, "processes"))
	if err != nil {
		return nil, err
	}
	s.custodies[world.ConfigDir] = custody
	return custody, nil
}
