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
	PreviousWeaver       string              `json:"previous_weaver_id,omitempty"`
	UpdatedAt            string              `json:"updated_at"`
	OldGenerationStopped bool                `json:"old_generation_stopped,omitempty"`
	Probe                *restartProbeResult `json:"probe,omitempty"`
	Failure              *restartFailure     `json:"failure,omitempty"`
	// These flags retain whether optional wire fields were present. A missing
	// generation identity is different from an explicitly published empty
	// value, and an explicit false stop flag must not disappear in projections.
	generationIDPresent         bool `json:"-"`
	oldGenerationStoppedPresent bool `json:"-"`
}

type restartRecordValidationMode uint8

const (
	validateRestartRecordForWrite restartRecordValidationMode = iota
	validateRestartRecordFromDisk
)

func restartRecordPath(world config.World) string {
	return filepath.Join(world.StateDir, "restart.json")
}

func writeRestartRecord(world config.World, record restartRecord) error {
	if err := validateRestartRecord(record, validateRestartRecordForWrite); err != nil {
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
	var record restartRecord
	encoded, err := json.Marshal(raw)
	if err != nil {
		return restartRecord{}, false, fmt.Errorf("encode restart record %s: %w", restartRecordPath(world), err)
	}
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&record); err != nil {
		return restartRecord{}, false, fmt.Errorf("decode restart record %s: %w", restartRecordPath(world), err)
	}
	for field, value := range raw {
		if string(bytes.TrimSpace(value)) == "null" {
			return restartRecord{}, false, fmt.Errorf("invalid restart record %s: %s must not be null", restartRecordPath(world), field)
		}
	}
	if err := validateRestartRecord(record, validateRestartRecordFromDisk); err != nil {
		return restartRecord{}, false, fmt.Errorf("invalid restart record %s: %w", restartRecordPath(world), err)
	}
	record.generationIDPresent = rawFieldPresent(raw, "generation_id")
	record.oldGenerationStoppedPresent = rawFieldPresent(raw, "old_generation_stopped")
	return record, true, nil
}

func rawFieldPresent(raw map[string]json.RawMessage, field string) bool {
	_, present := raw[field]
	return present
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

func (r restartRecord) status(world config.World) map[string]any {
	status := baseStatus(world, r.State)
	status["operation"] = "restart"
	status["workspace"] = world.ConfigDir
	if r.GenerationID != "" || r.generationIDPresent {
		status["generation_id"] = r.GenerationID
	}
	if r.PreviousGeneration != "" {
		status["previous_generation_id"] = r.PreviousGeneration
	}
	status["transition_id"] = r.TransitionID
	if r.OldGenerationStopped || r.oldGenerationStoppedPresent {
		status["old_generation_stopped"] = r.OldGenerationStopped
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
