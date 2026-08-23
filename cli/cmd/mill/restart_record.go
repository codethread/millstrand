package main

import (
	"encoding/json"
	"fmt"
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
	State              string              `json:"state"`
	TransitionID       string              `json:"transition_id"`
	GenerationID       string              `json:"generation_id"`
	PreviousGeneration string              `json:"previous_generation_id,omitempty"`
	UpdatedAt          string              `json:"updated_at"`
	Probe              *restartProbeResult `json:"probe,omitempty"`
	Failure            *restartFailure     `json:"failure,omitempty"`
}

func restartRecordPath(world config.World) string {
	return filepath.Join(world.StateDir, "restart.json")
}

func writeRestartRecord(world config.World, record restartRecord) error {
	if record.State != restartStateProbing && record.State != restartStateRestarting && record.State != restartStateRunning && record.State != restartStateFailed {
		return fmt.Errorf("invalid restart state %q", record.State)
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

func readRestartRecord(world config.World) (restartRecord, bool) {
	data, err := os.ReadFile(restartRecordPath(world))
	if err != nil {
		return restartRecord{}, false
	}
	var record restartRecord
	if err := json.Unmarshal(data, &record); err != nil || record.State == "" || record.TransitionID == "" {
		return restartRecord{}, false
	}
	return record, true
}

func (r restartRecord) status(world config.World) map[string]any {
	status := baseStatus(world, r.State)
	if r.GenerationID != "" {
		status["generation_id"] = r.GenerationID
	}
	if r.PreviousGeneration != "" {
		status["previous_generation_id"] = r.PreviousGeneration
	}
	status["transition_id"] = r.TransitionID
	if r.Probe != nil {
		status["probe"] = *r.Probe
	}
	if r.Failure != nil {
		status["failure"] = *r.Failure
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
