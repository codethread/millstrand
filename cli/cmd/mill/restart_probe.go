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

func (r restartProbeResult) validate() error {
	if r.Stage == "" {
		return errors.New("restart probe result missing stage")
	}
	if r.ProbeWorkspace == "" {
		return errors.New("restart probe result missing probe/workspace")
	}
	if r.SourceWorkspace == "" {
		return errors.New("restart probe result missing source/workspace")
	}
	if r.Log == "" {
		return errors.New("restart probe result missing log")
	}
	if r.Success && r.Stage != "probe/complete" {
		return fmt.Errorf("restart probe success has unexpected stage %q", r.Stage)
	}
	if !r.Success && r.Stage != "probe/failure" {
		return fmt.Errorf("restart probe failure has unexpected stage %q", r.Stage)
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

const freshRuntimeProbeExpression = `(require '[clojure.data.json :as json] '[millstrand.core.db :as db] '[millstrand.core.weaver.runtime :as runtime]) (let [result (runtime/fresh-runtime-probe! {:config-dir (System/getenv "MILLSTRAND_PROBE_CONFIG") :state-dir (System/getenv "MILLSTRAND_PROBE_STATE") :data-dir (System/getenv "MILLSTRAND_PROBE_DATA")}) stage->wire (fn [value] (if (keyword? value) (subs (str value) 1) value)) wire (-> (select-keys result [:success :stage :probe/workspace :source/workspace :completed :diagnostics :log]) (update :stage stage->wire) (update :completed #(mapv stage->wire %)))] (println (json/write-str wire :key-fn db/json-key)))`

func runFreshRuntimeProbe(source string, world config.World) (restartProbeResult, error) {
	cmd := exec.Command("clojure", "-M:millstrand", "-e", freshRuntimeProbeExpression)
	cmd.Dir = source
	cmd.Env = append(os.Environ(), "MILLSTRAND_PROBE_CONFIG="+world.ConfigDir, "MILLSTRAND_PROBE_STATE="+world.StateDir, "MILLSTRAND_PROBE_DATA="+world.DataDir)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if stderr.Len() > 0 {
			return restartProbeResult{}, fmt.Errorf("restart probe process failed: %w: %s", err, strings.TrimSpace(stderr.String()))
		}
		return restartProbeResult{}, fmt.Errorf("restart probe process failed: %w", err)
	}
	result, err := decodeRestartProbe(stdout.Bytes())
	if err != nil {
		if stderr.Len() > 0 {
			return restartProbeResult{}, fmt.Errorf("%w; probe stderr: %s", err, strings.TrimSpace(stderr.String()))
		}
		return restartProbeResult{}, err
	}
	return result, nil
}
