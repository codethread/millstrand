package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"

	"millstrand-strand-cli/internal/config"
)

// restartProbeResult is the only Clojure-owned shape Mill accepts. The parser
// validates it once at the subprocess boundary; transition code receives this
// typed value and never inspects raw JSON.
type restartProbeResult struct {
	Success         bool             `json:"success"`
	Stage           string           `json:"stage"`
	ProbeWorkspace  string           `json:"probe/workspace"`
	SourceWorkspace string           `json:"source/workspace"`
	Completed       []string         `json:"completed"`
	Diagnostics     []map[string]any `json:"diagnostics"`
	Log             string           `json:"log"`
}

type restartProbeWire struct {
	Success         *bool             `json:"success"`
	Stage           *string           `json:"stage"`
	ProbeWorkspace  *string           `json:"probe/workspace"`
	SourceWorkspace *string           `json:"source/workspace"`
	Completed       *[]string         `json:"completed"`
	Diagnostics     *[]map[string]any `json:"diagnostics"`
	Log             *string           `json:"log"`
}

const maxProbeStderr = 64 * 1024

type cappedBuffer struct {
	buf       bytes.Buffer
	limit     int
	truncated bool
}

func (b *cappedBuffer) Write(p []byte) (int, error) {
	if b.buf.Len() < b.limit {
		remaining := b.limit - b.buf.Len()
		if len(p) > remaining {
			_, _ = b.buf.Write(p[:remaining])
			b.truncated = true
			return len(p), nil
		}
		return b.buf.Write(p)
	}
	b.truncated = true
	return len(p), nil
}

func (b *cappedBuffer) String() string {
	value := strings.TrimSpace(b.buf.String())
	if b.truncated {
		if value == "" {
			return "[probe stderr truncated]"
		}
		return value + " [probe stderr truncated]"
	}
	return value
}

func (b *cappedBuffer) Len() int { return b.buf.Len() }

func (r restartProbeResult) validate() error {
	if strings.TrimSpace(r.Stage) == "" {
		return errors.New("restart probe result missing stage")
	}
	if strings.TrimSpace(r.ProbeWorkspace) == "" {
		return errors.New("restart probe result missing probe/workspace")
	}
	if strings.TrimSpace(r.SourceWorkspace) == "" {
		return errors.New("restart probe result missing source/workspace")
	}
	if strings.TrimSpace(r.Log) == "" {
		return errors.New("restart probe result missing log")
	}
	if r.Success && r.Stage != "probe/complete" {
		return fmt.Errorf("restart probe success has unexpected stage %q", r.Stage)
	}
	if !r.Success && r.Stage != "probe/failure" {
		return fmt.Errorf("restart probe failure has unexpected stage %q", r.Stage)
	}
	for index, stage := range r.Completed {
		if strings.TrimSpace(stage) == "" {
			return fmt.Errorf("restart probe completed[%d] must be non-blank", index)
		}
	}
	for index, diagnostic := range r.Diagnostics {
		if err := validateProbeDiagnostic(diagnostic); err != nil {
			return fmt.Errorf("restart probe diagnostics[%d]: %w", index, err)
		}
	}
	return nil
}

func validateProbeDiagnostic(row map[string]any) error {
	allowed := map[string]bool{"stage": true, "status": true, "data": true, "at": true}
	for key := range row {
		if !allowed[key] {
			return fmt.Errorf("diagnostic contains unknown field %q", key)
		}
	}
	stage, ok := row["stage"].(string)
	if !ok || strings.TrimSpace(stage) == "" {
		return errors.New("diagnostic stage must be a non-blank string")
	}
	status, ok := row["status"].(string)
	if !ok || !map[string]bool{"completed": true, "failed": true, "skipped": true, "in-progress": true}[status] {
		return errors.New("diagnostic status is invalid")
	}
	if at, present := row["at"]; present {
		value, ok := at.(string)
		if !ok || strings.TrimSpace(value) == "" {
			return errors.New("diagnostic at must be a non-blank string")
		}
	}
	if data, present := row["data"]; present {
		if _, ok := data.(map[string]any); !ok {
			return errors.New("diagnostic data must be an object")
		}
	}
	return nil
}

func decodeRestartProbe(data []byte) (restartProbeResult, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	var raw map[string]json.RawMessage
	if err := decoder.Decode(&raw); err != nil {
		return restartProbeResult{}, fmt.Errorf("malformed restart probe JSON: %w", err)
	}
	if raw == nil {
		return restartProbeResult{}, errors.New("malformed restart probe JSON: top-level value must be an object")
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		if err == nil {
			return restartProbeResult{}, errors.New("malformed restart probe JSON: multiple values")
		}
		return restartProbeResult{}, fmt.Errorf("malformed restart probe JSON: %w", err)
	}
	expected := map[string]bool{"success": true, "stage": true, "probe/workspace": true, "source/workspace": true, "completed": true, "diagnostics": true, "log": true}
	for key, value := range raw {
		if !expected[key] {
			return restartProbeResult{}, fmt.Errorf("restart probe result contains unknown field %q", key)
		}
		if string(bytes.TrimSpace(value)) == "null" {
			return restartProbeResult{}, fmt.Errorf("restart probe result field %q must not be null", key)
		}
	}
	for key := range expected {
		if _, ok := raw[key]; !ok {
			return restartProbeResult{}, fmt.Errorf("restart probe result missing field %q", key)
		}
	}
	b, err := json.Marshal(raw)
	if err != nil {
		return restartProbeResult{}, fmt.Errorf("malformed restart probe JSON: %w", err)
	}
	var wire restartProbeWire
	strict := json.NewDecoder(bytes.NewReader(b))
	strict.DisallowUnknownFields()
	if err := strict.Decode(&wire); err != nil {
		return restartProbeResult{}, fmt.Errorf("malformed restart probe JSON: %w", err)
	}
	if wire.Success == nil || wire.Stage == nil || wire.ProbeWorkspace == nil || wire.SourceWorkspace == nil || wire.Completed == nil || wire.Diagnostics == nil || wire.Log == nil {
		return restartProbeResult{}, errors.New("malformed restart probe JSON: required field has invalid shape")
	}
	for i, diagnostic := range *wire.Diagnostics {
		if diagnostic == nil {
			return restartProbeResult{}, fmt.Errorf("restart probe result diagnostics[%d] must be an object", i)
		}
	}
	result := restartProbeResult{Success: *wire.Success, Stage: *wire.Stage, ProbeWorkspace: *wire.ProbeWorkspace, SourceWorkspace: *wire.SourceWorkspace, Completed: *wire.Completed, Diagnostics: *wire.Diagnostics, Log: *wire.Log}
	if err := result.validate(); err != nil {
		return restartProbeResult{}, err
	}
	return result, nil
}

var probeRuntime = runFreshRuntimeProbe

const freshRuntimeProbeExpression = `(require 'clojure.data.json 'millstrand.core.db 'millstrand.core.weaver.basis 'millstrand.core.weaver.runtime) (let [source (System/getenv "MILLSTRAND_PROBE_SOURCE") generation-basis (millstrand.core.weaver.basis/create-generation-basis source {:local/root source}) wire-baseline (clojure.data.json/read-str (slurp (System/getenv "MILLSTRAND_PROBE_BASELINE_PATH"))) baseline {:status (keyword (get wire-baseline "status")) :projection (get wire-baseline "projection")} result (millstrand.core.weaver.runtime/fresh-runtime-probe! {:config-dir (System/getenv "MILLSTRAND_PROBE_CONFIG") :state-dir (System/getenv "MILLSTRAND_PROBE_STATE") :data-dir (System/getenv "MILLSTRAND_PROBE_DATA")} {:old-generation-baseline baseline :generation-basis generation-basis :expected-version (System/getenv "MILLSTRAND_PROBE_VERSION")}) stage->wire (fn [value] (if (keyword? value) (subs (str value) 1) value)) wire (-> (select-keys result [:success :stage :probe/workspace :source/workspace :completed :diagnostics :log]) (update :stage stage->wire) (update :completed #(mapv stage->wire %)))] (spit (System/getenv "MILLSTRAND_PROBE_RESULT") (clojure.data.json/write-str wire :key-fn millstrand.core.db/json-key)))`

func freshRuntimeProbeArgs(source string) []string {
	probeDeps := `{:deps {org.clojure/tools.deps {:mvn/version "0.31.1642"}}}`
	return []string{"-Srepro", "-Sdeps", probeDeps, "-M", "-e", freshRuntimeProbeExpression}
}

func runFreshRuntimeProbe(source string, world config.World) (restartProbeResult, error) {
	status, stale := readStatus(world)
	if status == nil || stale {
		return restartProbeResult{}, errors.New("cannot establish admitted old-generation baseline")
	}
	identity, err := identityFromStatus(status)
	if err != nil {
		return restartProbeResult{}, fmt.Errorf("old-generation baseline identity is invalid: %w", err)
	}
	projectionStatus, err := runtimeStatusWithRegistryProjection(identity)
	if err != nil {
		return restartProbeResult{}, fmt.Errorf("old-generation registry projection failed: %w", err)
	}
	projection, ok := projectionStatus["registry_projection"]
	if !ok {
		return restartProbeResult{}, errors.New("old-generation baseline status omitted registry_projection")
	}
	baseline, err := json.Marshal(map[string]any{"status": "admitted", "projection": projection})
	if err != nil {
		return restartProbeResult{}, fmt.Errorf("marshal old-generation baseline: %w", err)
	}
	baselineFile, err := os.CreateTemp("", "millstrand-probe-baseline-*.json")
	if err != nil {
		return restartProbeResult{}, fmt.Errorf("create restart probe baseline frame: %w", err)
	}
	baselinePath := baselineFile.Name()
	defer func() { _ = os.Remove(baselinePath) }()
	if _, err := baselineFile.Write(baseline); err != nil {
		_ = baselineFile.Close()
		return restartProbeResult{}, fmt.Errorf("write restart probe baseline frame: %w", err)
	}
	if err := baselineFile.Close(); err != nil {
		return restartProbeResult{}, fmt.Errorf("close restart probe baseline frame: %w", err)
	}
	resultFile, err := os.CreateTemp("", "millstrand-probe-result-*.json")
	if err != nil {
		return restartProbeResult{}, fmt.Errorf("create restart probe result sink: %w", err)
	}
	resultPath := resultFile.Name()
	if err := resultFile.Close(); err != nil {
		_ = os.Remove(resultPath)
		return restartProbeResult{}, fmt.Errorf("close restart probe result sink: %w", err)
	}
	defer func() { _ = os.Remove(resultPath) }()
	cmd := exec.Command("clojure", freshRuntimeProbeArgs(source)...)
	cmd.Dir = source
	cmd.Env = append(os.Environ(), "MILLSTRAND_PROBE_SOURCE="+source, "MILLSTRAND_PROBE_VERSION="+config.Version, "MILLSTRAND_PROBE_CONFIG="+world.ConfigDir, "MILLSTRAND_PROBE_STATE="+world.StateDir, "MILLSTRAND_PROBE_DATA="+world.DataDir, "MILLSTRAND_PROBE_BASELINE_PATH="+baselinePath, "MILLSTRAND_PROBE_RESULT="+resultPath)
	var stderr cappedBuffer
	stderr.limit = maxProbeStderr
	// User init/module code may write ordinary stdout.  The probe result has a
	// dedicated file sink, so that output is diagnostic noise rather than a
	// framing hazard or an unbounded buffer.
	cmd.Stdout = io.Discard
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if stderr.Len() > 0 {
			return restartProbeResult{}, fmt.Errorf("restart probe process failed: %w: %s", err, stderr.String())
		}
		return restartProbeResult{}, fmt.Errorf("restart probe process failed: %w", err)
	}
	framed, readErr := os.ReadFile(resultPath)
	if readErr != nil {
		if stderr.Len() > 0 {
			return restartProbeResult{}, fmt.Errorf("restart probe did not write framed result: %w; probe stderr: %s", readErr, stderr.String())
		}
		return restartProbeResult{}, fmt.Errorf("restart probe did not write framed result: %w", readErr)
	}
	result, err := decodeRestartProbe(framed)
	if err != nil {
		if stderr.Len() > 0 {
			return restartProbeResult{}, fmt.Errorf("%w; probe stderr: %s", err, stderr.String())
		}
		return restartProbeResult{}, err
	}
	return result, nil
}
