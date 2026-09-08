package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
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
	probePresent                bool `json:"-"`
	probeSuccess                bool `json:"-"`
}

type restartSummaryCacheEntry struct {
	size    int64
	modTime time.Time
	record  restartRecord
	present bool
	err     error
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

// readRestartRecordSummary reads the small lifecycle projection without
// decoding probe diagnostics. The detailed reader above remains the explicit
// path for restart inspection and retry, where the complete record is needed.
func readRestartRecordSummary(world config.World) (restartRecord, bool, error) {
	path := restartRecordPath(world)
	file, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return restartRecord{}, false, nil
		}
		return restartRecord{}, false, fmt.Errorf("read restart record %s: %w", path, err)
	}
	defer func() { _ = file.Close() }()

	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	start, err := decoder.Token()
	if err != nil {
		return restartRecord{}, false, fmt.Errorf("decode restart record %s: %w", path, err)
	}
	if start != json.Delim('{') {
		return restartRecord{}, false, fmt.Errorf("decode restart record %s: record must be a JSON object", path)
	}
	record := restartRecord{}
	seen := map[string]bool{}
	for decoder.More() {
		keyToken, err := decoder.Token()
		if err != nil {
			return restartRecord{}, false, fmt.Errorf("decode restart record %s: %w", path, err)
		}
		key, ok := keyToken.(string)
		if !ok {
			return restartRecord{}, false, fmt.Errorf("decode restart record %s: field name is not a string", path)
		}
		if seen[key] {
			return restartRecord{}, false, fmt.Errorf("decode restart record %s: duplicate field %q", path, key)
		}
		seen[key] = true
		switch key {
		case "state":
			record.State, err = decodeSummaryString(decoder, key)
		case "transition_id":
			record.TransitionID, err = decodeSummaryString(decoder, key)
		case "generation_id":
			record.GenerationID, err = decodeSummaryString(decoder, key)
			record.generationIDPresent = err == nil
		case "previous_generation_id":
			record.PreviousGeneration, err = decodeSummaryString(decoder, key)
		case "previous_weaver_id":
			record.PreviousWeaver, err = decodeSummaryString(decoder, key)
		case "updated_at":
			record.UpdatedAt, err = decodeSummaryString(decoder, key)
		case "old_generation_stopped":
			record.OldGenerationStopped, err = decodeSummaryBool(decoder, key)
			record.oldGenerationStoppedPresent = err == nil
		case "probe":
			record.probePresent, record.probeSuccess, err = decodeRestartProbeSummary(decoder)
		case "failure":
			var failure *restartFailure
			err = decoder.Decode(&failure)
			if err == nil && failure == nil {
				err = fmt.Errorf("%s must not be null", key)
			}
			record.Failure = failure
		default:
			err = fmt.Errorf("unknown field %q", key)
		}
		if err != nil {
			return restartRecord{}, false, fmt.Errorf("decode restart record %s: %s: %w", path, key, err)
		}
	}
	if _, err := decoder.Token(); err != nil {
		return restartRecord{}, false, fmt.Errorf("decode restart record %s: %w", path, err)
	}
	if _, err := decoder.Token(); err != io.EOF {
		if err == nil {
			return restartRecord{}, false, fmt.Errorf("decode restart record %s: contains multiple JSON values", path)
		}
		return restartRecord{}, false, fmt.Errorf("decode restart record %s: trailing data: %w", path, err)
	}
	if err := validateRestartRecordSummary(record); err != nil {
		return restartRecord{}, false, err
	}
	return record, true, nil
}

func decodeSummaryString(decoder *json.Decoder, field string) (string, error) {
	var value *string
	if err := decoder.Decode(&value); err != nil {
		return "", err
	}
	if value == nil {
		return "", fmt.Errorf("%s must not be null", field)
	}
	return *value, nil
}

func decodeSummaryBool(decoder *json.Decoder, field string) (bool, error) {
	var value *bool
	if err := decoder.Decode(&value); err != nil {
		return false, err
	}
	if value == nil {
		return false, fmt.Errorf("%s must not be null", field)
	}
	return *value, nil
}

func decodeRestartProbeSummary(decoder *json.Decoder) (bool, bool, error) {
	start, err := decoder.Token()
	if err != nil {
		return false, false, err
	}
	if start != json.Delim('{') {
		return false, false, errors.New("probe must be an object")
	}
	seen := map[string]bool{}
	var success *bool
	var stage, probeWorkspace, sourceWorkspace, log *string
	var completed *[]string
	var diagnostics bool
	for decoder.More() {
		keyToken, err := decoder.Token()
		if err != nil {
			return false, false, err
		}
		key := keyToken.(string)
		if seen[key] {
			return false, false, fmt.Errorf("probe contains duplicate field %q", key)
		}
		seen[key] = true
		switch key {
		case "success":
			if err := decoder.Decode(&success); err != nil {
				return false, false, err
			}
		case "stage":
			if err := decoder.Decode(&stage); err != nil {
				return false, false, err
			}
		case "probe/workspace":
			if err := decoder.Decode(&probeWorkspace); err != nil {
				return false, false, err
			}
		case "source/workspace":
			if err := decoder.Decode(&sourceWorkspace); err != nil {
				return false, false, err
			}
		case "completed":
			if err := decoder.Decode(&completed); err != nil {
				return false, false, err
			}
		case "diagnostics":
			if err := skipSummaryValue(decoder, false); err != nil {
				return false, false, err
			}
			diagnostics = true
		case "log":
			if err := decoder.Decode(&log); err != nil {
				return false, false, err
			}
		default:
			return false, false, fmt.Errorf("probe contains unknown field %q", key)
		}
	}
	if _, err := decoder.Token(); err != nil {
		return false, false, err
	}
	if success == nil || stage == nil || probeWorkspace == nil || sourceWorkspace == nil || completed == nil || !diagnostics || log == nil {
		return false, false, errors.New("probe is missing required fields")
	}
	if *success && *stage != "probe/complete" || !*success && *stage != "probe/failure" {
		return false, false, fmt.Errorf("probe success has unexpected stage %q", *stage)
	}
	for index, value := range *completed {
		if strings.TrimSpace(value) == "" {
			return false, false, fmt.Errorf("probe completed[%d] must be non-blank", index)
		}
	}
	return true, *success, nil
}

func skipSummaryValue(decoder *json.Decoder, allowNull bool) error {
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	if token == nil {
		if allowNull {
			return nil
		}
		return errors.New("value must not be null")
	}
	delim, ok := token.(json.Delim)
	if !ok || (delim != '{' && delim != '[') {
		return nil
	}
	closing := json.Delim(']')
	if delim == '{' {
		closing = '}'
	}
	for decoder.More() {
		if delim == '{' {
			if _, err := decoder.Token(); err != nil {
				return err
			}
		}
		if err := skipSummaryValue(decoder, true); err != nil {
			return err
		}
	}
	end, err := decoder.Token()
	if err != nil {
		return err
	}
	if end != closing {
		return fmt.Errorf("unexpected closing token %v", end)
	}
	return nil
}

func validateRestartRecordSummary(record restartRecord) error {
	if strings.TrimSpace(record.TransitionID) == "" {
		return errors.New("invalid restart record: restart record requires transition_id")
	}
	if strings.TrimSpace(record.UpdatedAt) == "" {
		return errors.New("invalid restart record: restart record requires updated_at")
	}
	switch record.State {
	case restartStateProbing, restartStateRestarting, restartStateRunning, restartStateFailed:
	default:
		return fmt.Errorf("invalid restart record: restart record has unknown state %q", record.State)
	}
	if record.GenerationID != "" && strings.TrimSpace(record.GenerationID) == "" {
		return errors.New("invalid restart record: restart record generation_id must be non-blank")
	}
	if record.PreviousGeneration != "" && strings.TrimSpace(record.PreviousGeneration) == "" {
		return errors.New("invalid restart record: restart record previous_generation_id must be non-blank")
	}
	if record.PreviousWeaver != "" && strings.TrimSpace(record.PreviousWeaver) == "" {
		return errors.New("invalid restart record: restart record previous_weaver_id must be non-blank")
	}
	if record.OldGenerationStopped && (!record.probePresent || !record.probeSuccess || strings.TrimSpace(record.GenerationID) == "") {
		return errors.New("invalid restart record: stopped generation requires a successful probe and generation_id")
	}
	if record.State == restartStateProbing && (strings.TrimSpace(record.GenerationID) == "" || record.probePresent || record.Failure != nil || record.OldGenerationStopped) {
		return errors.New("invalid restart record: probing restart record has contradictory fields")
	}
	if record.State == restartStateRunning && strings.TrimSpace(record.GenerationID) == "" {
		return errors.New("invalid restart record: running restart record requires generation_id")
	}
	if record.State == restartStateFailed && record.Failure == nil {
		return errors.New("invalid restart record: failed restart record requires failure")
	}
	if record.Failure != nil {
		if strings.TrimSpace(record.Failure.Stage) == "" || strings.TrimSpace(record.Failure.Message) == "" {
			return errors.New("invalid restart record: restart failure requires non-blank stage and message")
		}
		if record.State == restartStateRunning && (record.Failure.Stage != "probe" || !record.probePresent || record.probeSuccess) {
			return errors.New("invalid restart record: running restart failure requires a failed probe")
		}
		if record.State != restartStateRunning && record.State != restartStateFailed {
			return fmt.Errorf("invalid restart record: restart failure is contradictory for state %q", record.State)
		}
	}
	if record.OldGenerationStopped && record.Failure != nil && record.Failure.Stage != "launch" {
		return fmt.Errorf("invalid restart record: stopped generation cannot have %s failure", record.Failure.Stage)
	}
	return nil
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

func (r restartRecord) compactStatus(world config.World) map[string]any {
	status := baseStatus(world, r.State)
	if r.GenerationID != "" {
		status["generation_id"] = r.GenerationID
	}
	if r.PreviousGeneration != "" {
		status["previous_generation_id"] = r.PreviousGeneration
	}
	status["transition_id"] = r.TransitionID
	if r.OldGenerationStopped || r.oldGenerationStoppedPresent {
		status["old_generation_stopped"] = r.OldGenerationStopped
	}
	if r.Failure != nil {
		status["restart_failure"] = *r.Failure
	}
	return status
}

func mergeRestartRecordCompactStatus(status map[string]any, record restartRecord) {
	if record.GenerationID != "" {
		status["generation_id"] = record.GenerationID
	}
	if record.TransitionID != "" {
		status["transition_id"] = record.TransitionID
	}
	if record.Failure != nil {
		status["restart_failure"] = *record.Failure
	}
}
