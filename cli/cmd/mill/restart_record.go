package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"

	"millstrand-strand-cli/internal/config"
)

type restartFailure struct {
	Stage        string `json:"stage"`
	Message      string `json:"message"`
	LogPath      string `json:"log_path,omitempty"`
	ExitEvidence string `json:"exit_evidence,omitempty"`
}

type restartRecord struct {
	State                string              `json:"state"`
	TransitionID         string              `json:"transition_id"`
	GenerationID         string              `json:"generation_id"`
	PreviousGeneration   string              `json:"previous_generation_id,omitempty"`
	UpdatedAt            string              `json:"updated_at"`
	OldGenerationStopped bool                `json:"old_generation_stopped,omitempty"`
	Probe                *restartProbeResult `json:"probe,omitempty"`
	Failure              *restartFailure     `json:"failure,omitempty"`
}

func restartRecordPath(world config.World) string {
	return filepath.Join(world.StateDir, "restart.json")
}

func writeRestartRecord(world config.World, record restartRecord) error {
	if err := validateRestartRecord(record); err != nil {
		return fmt.Errorf("invalid restart record: %w", err)
	}
	record.UpdatedAt = time.Now().UTC().Format(time.RFC3339Nano)
	data, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal restart state: %w", err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(world.StateDir, "restart.json.*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if _, err := tmp.Write(append(data, '\n')); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, restartRecordPath(world))
}

func readRestartRecordDetailed(world config.World) (restartRecord, bool, error) {
	data, err := os.ReadFile(restartRecordPath(world))
	if err != nil {
		if os.IsNotExist(err) {
			return restartRecord{}, false, nil
		}
		return restartRecord{}, false, fmt.Errorf("read restart record %s: %w", restartRecordPath(world), err)
	}
	raw, err := decodeObject(data, "restart record")
	if err != nil {
		return restartRecord{}, false, fmt.Errorf("decode restart record %s: %w", restartRecordPath(world), err)
	}
	allowed := map[string]bool{"state": true, "transition_id": true, "generation_id": true, "previous_generation_id": true, "updated_at": true, "old_generation_stopped": true, "probe": true, "failure": true}
	for key := range raw {
		if !allowed[key] {
			return restartRecord{}, false, fmt.Errorf("invalid restart record %s: unknown field %q", restartRecordPath(world), key)
		}
	}
	var record restartRecord
	encoded, err := json.Marshal(raw)
	if err != nil {
		return restartRecord{}, false, fmt.Errorf("encode restart record %s: %w", restartRecordPath(world), err)
	}
	if err := json.Unmarshal(encoded, &record); err != nil {
		return restartRecord{}, false, fmt.Errorf("decode restart record %s: %w", restartRecordPath(world), err)
	}
	for _, required := range []string{"state", "transition_id", "updated_at"} {
		if value, ok := raw[required]; !ok || string(bytes.TrimSpace(value)) == "null" {
			return restartRecord{}, false, fmt.Errorf("invalid restart record %s: missing %s", restartRecordPath(world), required)
		}
	}
	switch record.State {
	case restartStateProbing, restartStateRestarting, restartStateRunning, restartStateFailed:
	default:
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: unknown state %q", restartRecordPath(world), record.State)
	}
	if record.OldGenerationStopped && record.State != restartStateRestarting && record.State != restartStateFailed && record.State != restartStateRunning {
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: old_generation_stopped is contradictory for state %q", restartRecordPath(world), record.State)
	}
	if record.OldGenerationStopped && record.GenerationID == "" {
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: old_generation_stopped requires generation_id", restartRecordPath(world))
	}
	if record.UpdatedAt == "" {
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: updated_at must not be blank", restartRecordPath(world))
	}
	for _, optional := range []string{"generation_id", "previous_generation_id"} {
		if value, present := raw[optional]; present {
			var text string
			if err := json.Unmarshal(value, &text); err != nil || len(bytes.TrimSpace([]byte(text))) == 0 {
				return restartRecord{}, false, fmt.Errorf("invalid restart record %s: %s must be a non-blank string", restartRecordPath(world), optional)
			}
		}
	}
	if record.State == restartStateFailed && record.Failure == nil {
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: failed state requires failure", restartRecordPath(world))
	}
	if record.Failure != nil && record.State != restartStateFailed {
		if record.State != restartStateRunning || record.Failure.Stage != "probe" || record.Probe == nil || record.Probe.Success {
			return restartRecord{}, false, fmt.Errorf("invalid restart record %s: failure is contradictory for state %q", restartRecordPath(world), record.State)
		}
	}
	if record.OldGenerationStopped && record.Failure != nil && record.Failure.Stage != "launch" {
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: stopped generation cannot have %s failure", restartRecordPath(world), record.Failure.Stage)
	}
	if record.OldGenerationStopped && (record.Probe == nil || !record.Probe.Success) {
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: stopped generation requires successful probe", restartRecordPath(world))
	}
	if record.State == restartStateRunning && record.GenerationID == "" {
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: running state requires generation_id", restartRecordPath(world))
	}
	if record.State == restartStateProbing && record.GenerationID == "" {
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: probing state requires generation_id", restartRecordPath(world))
	}
	if failureRaw, ok := raw["failure"]; ok {
		if err := validateFailureJSON(failureRaw); err != nil {
			return restartRecord{}, false, fmt.Errorf("invalid restart record %s: %w", restartRecordPath(world), err)
		}
	}
	if probeRaw, ok := raw["probe"]; ok {
		if string(bytes.TrimSpace(probeRaw)) == "null" {
			return restartRecord{}, false, fmt.Errorf("invalid restart record %s: probe must not be null", restartRecordPath(world))
		}
		if _, err := decodeRestartProbe(probeRaw); err != nil {
			return restartRecord{}, false, fmt.Errorf("invalid restart record %s: %w", restartRecordPath(world), err)
		}
	}
	return record, true, nil
}

func decodeObject(data []byte, label string) (map[string]json.RawMessage, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	var raw map[string]json.RawMessage
	if err := decoder.Decode(&raw); err != nil {
		return nil, fmt.Errorf("%s is not a JSON object: %w", label, err)
	}
	if raw == nil {
		return nil, fmt.Errorf("%s must be a JSON object", label)
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		if err == nil {
			return nil, fmt.Errorf("%s contains multiple JSON values", label)
		}
		return nil, fmt.Errorf("%s has trailing JSON: %w", label, err)
	}
	return raw, nil
}

func validateFailureJSON(data json.RawMessage) error {
	raw, err := decodeObject(data, "restart failure")
	if err != nil {
		return err
	}
	allowed := map[string]bool{"stage": true, "message": true, "log_path": true, "exit_evidence": true}
	for key := range raw {
		if !allowed[key] {
			return fmt.Errorf("restart failure contains unknown field %q", key)
		}
	}
	for _, required := range []string{"stage", "message"} {
		value, ok := raw[required]
		if !ok || string(bytes.TrimSpace(value)) == "null" {
			return fmt.Errorf("restart failure missing %s", required)
		}
		var text string
		if err := json.Unmarshal(value, &text); err != nil || text == "" {
			return fmt.Errorf("restart failure %s must be a non-empty string", required)
		}
	}
	return nil
}

func readRestartRecord(world config.World) (restartRecord, bool) {
	record, present, _ := readRestartRecordDetailed(world)
	return record, present
}

func (r restartRecord) status(world config.World) map[string]any {
	status := baseStatus(world, r.State)
	status["operation"] = "restart"
	status["workspace"] = world.ConfigDir
	if r.GenerationID != "" {
		status["generation_id"] = r.GenerationID
	}
	if r.PreviousGeneration != "" {
		status["previous_generation_id"] = r.PreviousGeneration
	}
	status["transition_id"] = r.TransitionID
	if r.OldGenerationStopped {
		status["old_generation_stopped"] = true
	}
	if r.Probe != nil {
		status["probe"] = *r.Probe
	}
	if r.Failure != nil {
		status["failure"] = *r.Failure
		status["diagnostics"] = []map[string]any{{
			"stage":  r.Failure.Stage,
			"status": "failed",
			"data": map[string]any{
				"message":  r.Failure.Message,
				"log_path": r.Failure.LogPath,
			},
		}}
	}
	return status
}

func mergeRestartRecordStatus(status map[string]any, record restartRecord) {
	if record.GenerationID != "" {
		status["generation_id"] = record.GenerationID
	}
	if record.TransitionID != "" {
		status["transition_id"] = record.TransitionID
	}
	if record.Probe != nil {
		status["probe"] = *record.Probe
	}
	if record.Failure != nil {
		status["restart_failure"] = *record.Failure
	}
}
