//go:build integration

package cli_test

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// restartProcessHarness drives the built Mill through its public lifecycle
// commands. Each process identity is recorded when it is admitted so cleanup
// can only signal a PID that this test started.
type restartProcessHarness struct {
	mill      *exec.Cmd
	millBin   string
	strandBin string
	source    string
	pids      []int
}

func newRestartProcessHarness(t *testing.T) *restartProcessHarness {
	t.Helper()
	root := sourceRoot(t)
	for _, name := range []string{"mill", "strand"} {
		path := filepath.Join(root, "bin", name)
		if _, err := os.Stat(path); err != nil {
			t.Fatalf("built %s binary is required: %v (run make build first)", name, err)
		}
	}
	stateHome := shortTempDir(t)
	t.Setenv("XDG_STATE_HOME", filepath.Join(stateHome, "x"))
	t.Setenv("MILLSTRAND_SOURCE", root)

	h := &restartProcessHarness{millBin: filepath.Join(root, "bin", "mill"), strandBin: filepath.Join(root, "bin", "strand"), source: root}
	h.mill = exec.Command(h.millBin, "start")
	stdout, err := h.mill.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	h.mill.Dir = root
	h.mill.Stderr = io.Discard
	if err := h.mill.Start(); err != nil {
		t.Fatalf("start mill: %v", err)
	}
	h.pids = append(h.pids, h.mill.Process.Pid)
	scanner := bufio.NewScanner(stdout)
	for scanner.Scan() {
		if strings.Contains(scanner.Text(), "mill listening") {
			break
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("read mill readiness: %v", err)
	}
	if !strings.Contains(scanner.Text(), "mill listening") {
		t.Fatalf("mill exited before readiness (pid %d)", h.mill.Process.Pid)
	}
	t.Cleanup(func() { h.cleanup(t) })
	return h
}

func (h *restartProcessHarness) cleanup(t *testing.T) {
	t.Helper()
	if h.mill != nil && h.mill.Process != nil {
		pid := h.mill.Process.Pid
		if processExists(pid) {
			_ = h.mill.Process.Signal(os.Interrupt)
			reapMill(t, h.mill, 5*time.Second)
		} else {
			_ = h.mill.Wait()
		}
	}
	for _, pid := range h.pids {
		ensureProcessExit(t, pid, 2*time.Second)
	}
}

func reapMill(t *testing.T, cmd *exec.Cmd, timeout time.Duration) {
	t.Helper()
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-done:
		return
	case <-timer.C:
		pid := cmd.Process.Pid
		t.Errorf("mill pid %d did not exit within %s; escalating PID-scoped SIGKILL", pid, timeout)
		if err := syscall.Kill(pid, syscall.SIGKILL); err != nil && !errors.Is(err, syscall.ESRCH) {
			t.Errorf("PID-scoped SIGKILL failed for mill pid %d: %v", pid, err)
		}
		select {
		case <-done:
		case <-time.After(timeout):
			t.Errorf("could not reap mill pid %d after SIGKILL", pid)
		}
	}
}

func (h *restartProcessHarness) run(args ...string) (string, error) {
	cmd := exec.Command(h.millBin, args...)
	cmd.Dir = h.source
	var output bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &output
	err := cmd.Run()
	return output.String(), err
}

func (h *restartProcessHarness) runStrand(args ...string) (string, error) {
	cmd := exec.Command(h.strandBin, args...)
	cmd.Dir = h.source
	var output bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &output
	err := cmd.Run()
	return output.String(), err
}

func (h *restartProcessHarness) startWorld(t *testing.T, workspace string) map[string]any {
	t.Helper()
	h.initWorld(t, workspace)
	return h.startExistingWorld(t, workspace)
}

func (h *restartProcessHarness) initWorld(t *testing.T, workspace string) {
	t.Helper()
	if out, err := h.run("init", "--workspace", workspace); err != nil {
		t.Fatalf("mill init: %v\n%s", err, out)
	}
	// Keep the acceptance world local and deterministic. The generated init
	// would activate the repository's full spool graph, which is covered by the
	// ordinary end-to-end suite rather than this lifecycle boundary test.
	if err := os.WriteFile(filepath.Join(workspace, "spools.edn"), []byte("{:spools {}}\n"), 0o644); err != nil {
		t.Fatalf("write disposable spools.edn: %v", err)
	}
	if err := os.WriteFile(filepath.Join(workspace, "init.clj"), nil, 0o644); err != nil {
		t.Fatalf("write disposable init.clj: %v", err)
	}
}

func (h *restartProcessHarness) startExistingWorld(t *testing.T, workspace string) map[string]any {
	t.Helper()
	out, err := h.run("weaver", "start", "--workspace", workspace)
	if err != nil {
		t.Fatalf("weaver start: %v\n%s", err, out)
	}
	status := decodeObject(t, out)
	pid := requiredPID(t, status)
	h.pids = append(h.pids, pid)
	return status
}

func (h *restartProcessHarness) status(t *testing.T, workspace string) map[string]any {
	t.Helper()
	out, err := h.run("weaver", "status", "--workspace", workspace)
	if err != nil {
		t.Fatalf("weaver status: %v\n%s", err, out)
	}
	return decodeObject(t, out)
}

func (h *restartProcessHarness) startDuringProbe(t *testing.T, workspace string) map[string]any {
	t.Helper()
	return h.runJSON(t, "weaver", "start", "--workspace", workspace)
}

func (h *restartProcessHarness) runJSON(t *testing.T, args ...string) map[string]any {
	t.Helper()
	out, err := h.run(args...)
	if err != nil {
		t.Fatalf("%s: %v\n%s", strings.Join(args, " "), err, out)
	}
	return decodeObject(t, out)
}

func (h *restartProcessHarness) restartAsync(workspace, timeout string) <-chan processResult {
	result := make(chan processResult, 1)
	go func() {
		args := []string{"weaver", "restart", "--workspace", workspace}
		if timeout != "" {
			args = append(args, "--ready-timeout", timeout)
		}
		out, err := h.run(args...)
		result <- processResult{output: out, err: err}
	}()
	return result
}

type processResult struct {
	output string
	err    error
}

func TestDisposableWeaverRestartAcceptance(t *testing.T) {
	h := newRestartProcessHarness(t)

	t.Run("valid probe preserves old admission and converges callers", func(t *testing.T) {
		workspace := shortTempDir(t)
		started := filepath.Join(shortTempDir(t), "probe-started")
		release := filepath.Join(shortTempDir(t), "probe-release")
		h.initWorld(t, workspace)
		appendProbeGate(t, filepath.Join(workspace, "init.clj"), started, release)
		module := filepath.Join(workspace, "modules", "stdout.clj")
		if err := os.MkdirAll(filepath.Dir(module), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(module, []byte("(ns restart.stdout)\n(println \"probe module stdout must not corrupt the framed result\")\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		appendInit(t, filepath.Join(workspace, "init.clj"), fmt.Sprintf(
			"(millstrand.core.weaver.runtime/declare-module! millstrand.core.weaver.runtime/*runtime* :stdout/module {:file %s})\n",
			clojureString("modules/stdout.clj")))
		old := h.startExistingWorld(t, workspace)
		oldPID, oldGeneration := requiredPID(t, old), requiredString(t, old, "generation_id")

		first := h.restartAsync(workspace, "120s")
		waitForPath(t, started, 60*time.Second)
		probing := h.status(t, workspace)
		if probing["state"] != "running" || probing["restart_state"] != "probing" {
			t.Fatalf("probe did not retain old running generation: %#v", probing)
		}
		if requiredPID(t, probing) != oldPID || requiredString(t, probing, "generation_id") != oldGeneration {
			t.Fatalf("probe changed admitted identity: old=%#v probing=%#v", old, probing)
		}
		strandOutput, err := h.runStrand("--workspace", workspace, "help", "--json")
		if err != nil || !strings.Contains(strandOutput, "schema-version") {
			t.Fatalf("old generation did not serve public strand help during probe: %v\n%s", err, strandOutput)
		}

		startResult := h.startDuringProbe(t, workspace)
		if requiredPID(t, startResult) != oldPID || requiredString(t, startResult, "generation_id") != oldGeneration {
			t.Fatalf("start during probe did not return old generation: %#v", startResult)
		}
		timeout := h.restartAsync(workspace, "1ms")
		select {
		case result := <-timeout:
			if result.err == nil || !strings.Contains(result.output, "before timeout") {
				t.Fatalf("caller timeout did not remain caller-local: %#v", result)
			}
		case <-time.After(10 * time.Second):
			t.Fatal("timed restart caller did not return")
		}
		second := h.restartAsync(workspace, "120s")
		third := h.restartAsync(workspace, "120s")
		if err := os.WriteFile(release, []byte("release"), 0o644); err != nil {
			t.Fatal(err)
		}
		firstResult := awaitProcessResult(t, first)
		secondResult := awaitProcessResult(t, second)
		thirdResult := awaitProcessResult(t, third)
		final := decodeObject(t, firstResult.output)
		if err := validateRestartEnvelopeForAcceptance(final, "restart"); err != nil {
			t.Fatal(err)
		}
		finalStatus := h.status(t, workspace)
		for label, result := range map[string]processResult{"first": firstResult, "second": secondResult, "third": thirdResult} {
			if result.err != nil {
				t.Fatalf("%s restart caller failed: %v\n%s", label, result.err, result.output)
			}
			got := decodeObject(t, result.output)
			if got["state"] != "running" || requiredString(t, got, "generation_id") != requiredString(t, final, "generation_id") {
				t.Fatalf("%s caller did not converge: %#v final=%#v", label, got, final)
			}
		}
		if requiredString(t, final, "generation_id") == oldGeneration || requiredPID(t, finalStatus) == oldPID {
			t.Fatalf("restart did not perform exactly one generation change: old=%#v final=%#v", old, finalStatus)
		}
		h.pids = append(h.pids, requiredPID(t, finalStatus))
		assertSuccessfulProbeDiagnostics(t, finalStatus)
		if err := waitProcessExit(oldPID, 10*time.Second); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("invalid source and dependency probe retains diagnostics", func(t *testing.T) {
		workspace := shortTempDir(t)
		old := h.startWorld(t, workspace)
		oldPID, oldGeneration := requiredPID(t, old), requiredString(t, old, "generation_id")
		appendInit(t, filepath.Join(workspace, "init.clj"), fmt.Sprintf(
			"(millstrand.core.weaver.runtime/declare-module! millstrand.core.weaver.runtime/*runtime* :invalid/source {:file %s})\n",
			clojureString("modules/does-not-exist.clj")))
		out, err := h.run("weaver", "restart", "--workspace", workspace)
		if err != nil {
			t.Fatalf("invalid probe should retain old generation: %v\n%s", err, out)
		}
		status := decodeObject(t, out)
		if err := validateRestartEnvelopeForAcceptance(status, "restart"); err != nil {
			t.Fatal(err)
		}
		assertRetainedProbeFailure(t, h.status(t, workspace), oldPID, oldGeneration, "source/dependency")
	})

	t.Run("invalid candidate registry probe retains diagnostics", func(t *testing.T) {
		workspace := shortTempDir(t)
		old := h.startWorld(t, workspace)
		oldPID, oldGeneration := requiredPID(t, old), requiredString(t, old, "generation_id")
		module := filepath.Join(workspace, "modules", "invalid_candidate.clj")
		if err := os.MkdirAll(filepath.Dir(module), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(module, []byte(invalidCandidateModule), 0o644); err != nil {
			t.Fatal(err)
		}
		appendInit(t, filepath.Join(workspace, "init.clj"), fmt.Sprintf(
			"(millstrand.core.weaver.runtime/declare-module! millstrand.core.weaver.runtime/*runtime* :invalid/candidate {:file %s})\n",
			clojureString("modules/invalid_candidate.clj")))
		out, err := h.run("weaver", "restart", "--workspace", workspace)
		if err != nil {
			t.Fatalf("invalid candidate probe should retain old generation: %v\n%s", err, out)
		}
		status := decodeObject(t, out)
		if err := validateRestartEnvelopeForAcceptance(status, "restart"); err != nil {
			t.Fatal(err)
		}
		assertRetainedProbeFailure(t, h.status(t, workspace), oldPID, oldGeneration, "candidate-registry")
	})

	t.Run("replacement startup failure admits no generation", func(t *testing.T) {
		workspace := shortTempDir(t)
		failMarker := filepath.Join(shortTempDir(t), "fail-replacement")
		old := h.startWorld(t, workspace)
		oldPID := requiredPID(t, old)
		appendInit(t, filepath.Join(workspace, "init.clj"), fmt.Sprintf(
			"(when (and (nil? (System/getenv \"MILLSTRAND_PROBE_STATE\")) (.exists (java.io.File. %s))) (throw (ex-info \"replacement startup gate\" {:stage :startup})))\n",
			clojureString(failMarker)))
		if err := os.WriteFile(failMarker, []byte("fail"), 0o644); err != nil {
			t.Fatal(err)
		}
		out, err := h.run("weaver", "restart", "--workspace", workspace)
		if err != nil {
			t.Fatalf("replacement startup failure lost structured failed result: %v\n%s", err, out)
		}
		status := decodeObject(t, out)
		if err := validateRestartEnvelopeForAcceptance(status, "restart"); err != nil {
			t.Fatal(err)
		}
		if status["state"] != "failed" || status["generation_id"] != nil {
			t.Fatalf("failed replacement admitted a generation: %#v\ncommand=%s", status, out)
		}
		diagnostics, ok := status["diagnostics"].([]any)
		if !ok || len(diagnostics) == 0 {
			t.Fatalf("replacement failure lost stage/message/log: %#v", status)
		}
		failureData, _ := diagnostics[0].(map[string]any)
		data, _ := failureData["data"].(map[string]any)
		if failureData["stage"] == "" || data["message"] == "" || data["log_path"] == "" {
			t.Fatalf("replacement failure lost stage/message/log: %#v", status)
		}
		if processExists(oldPID) {
			t.Fatalf("old generation remained alive after cutover failure: pid=%d", oldPID)
		}
		if err := os.Remove(failMarker); err != nil {
			t.Fatal(err)
		}
		retryOut, retryErr := h.run("weaver", "restart", "--workspace", workspace)
		if retryErr != nil {
			t.Fatalf("failed replacement retry did not converge: %v\n%s", retryErr, retryOut)
		}
		retry := decodeObject(t, retryOut)
		if err := validateRestartEnvelopeForAcceptance(retry, "restart"); err != nil {
			t.Fatal(err)
		}
		if retry["state"] != "running" || requiredString(t, retry, "generation_id") == "" {
			t.Fatalf("replacement retry did not admit exactly one generation: %#v", retry)
		}
		retryStatus := h.status(t, workspace)
		h.pids = append(h.pids, requiredPID(t, retryStatus))
	})
}

const invalidCandidateModule = `(ns restart.invalid-candidate
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.runtime.alpha :as runtime]))
(s/def ::entry map?)
(defn reject-candidate [_]
  (throw (ex-info "candidate registry rejected" {:reason :invalid-candidate})))
(runtime/collect-kind! ::state
  {:id ::items :entry-spec ::entry :binding-moment :test/use
   :candidate-validator 'restart.invalid-candidate/reject-candidate})
(runtime/collect-entry! ::items :one {:value 1})
`

func appendProbeGate(t *testing.T, initPath, started, release string) {
	t.Helper()
	text := fmt.Sprintf(`
(let [started %s release %s]
  (when (System/getenv "MILLSTRAND_PROBE_STATE")
    (println "probe init stdout must not corrupt the framed result")
    (spit started "started")
    (while (not (.exists (java.io.File. release)))
      (Thread/yield))))
`, clojureString(started), clojureString(release))
	contents, err := os.ReadFile(initPath)
	if err != nil {
		t.Fatalf("read init.clj: %v", err)
	}
	if err := os.WriteFile(initPath, append([]byte(text+"\n"), contents...), 0o644); err != nil {
		t.Fatalf("prepend init.clj: %v", err)
	}
}

func appendInit(t *testing.T, path, text string) {
	t.Helper()
	file, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatalf("open init.clj: %v", err)
	}
	defer func() { _ = file.Close() }()
	if _, err := file.WriteString("\n" + text); err != nil {
		t.Fatalf("append init.clj: %v", err)
	}
}

func assertRetainedProbeFailure(t *testing.T, status map[string]any, oldPID int, oldGeneration, label string) {
	t.Helper()
	if status["state"] != "running" || requiredPID(t, status) != oldPID || requiredString(t, status, "generation_id") != oldGeneration {
		t.Fatalf("%s probe changed admitted generation: %#v", label, status)
	}
	probe, ok := status["probe"].(map[string]any)
	if !ok || probe["success"] != false || probe["stage"] != "probe/failure" {
		t.Fatalf("%s probe failure diagnostics missing: %#v", label, status)
	}
	probePath := requiredString(t, probe, "probe/workspace")
	logPath := requiredString(t, probe, "log")
	if !pathExists(probePath) || !pathExists(logPath) {
		t.Fatalf("%s probe diagnostics were not retained at path/log: %#v", label, probe)
	}
	completed, ok := probe["completed"].([]any)
	if !ok || len(completed) == 0 {
		t.Fatalf("%s probe lost completed stages: %#v", label, probe)
	}
	diagnostics, ok := probe["diagnostics"].([]any)
	if !ok || len(diagnostics) == 0 {
		t.Fatalf("%s probe lost structured diagnostics: %#v", label, probe)
	}
	assertProbeDiagnostics(t, diagnostics, label)
	failure, ok := status["restart_failure"].(map[string]any)
	if !ok || failure["stage"] != "probe" || failure["message"] == "" {
		t.Fatalf("%s probe lost failure context: %#v", label, status)
	}
}

func assertProbeDiagnostics(t *testing.T, diagnostics []any, label string) {
	t.Helper()
	seen := map[string]bool{}
	for _, raw := range diagnostics {
		entry, ok := raw.(map[string]any)
		if !ok {
			t.Fatalf("%s diagnostic is not an object: %#v", label, raw)
		}
		stage, ok := entry["stage"].(string)
		if !ok || stage == "" {
			t.Fatalf("%s diagnostic is missing stage: %#v", label, entry)
		}
		seen[stage] = true
		data, _ := entry["data"].(map[string]any)
		if stage == "materialize" {
			if _, ok := data["roots"]; !ok {
				t.Fatalf("%s root outcomes missing: %#v", label, entry)
			}
		}
		if stage == "evaluate" {
			if _, ok := data["modules"]; !ok {
				t.Fatalf("%s module outcomes missing: %#v", label, entry)
			}
		}
		if stage == "staged" {
			projection, ok := data["candidate-registries"].(map[string]any)
			if !ok || len(projection) == 0 {
				t.Fatalf("%s owned candidate projection missing: %#v", label, entry)
			}
			assertOldGenerationDiff(t, data, label)
		}
		if stage == "plan" {
			assertLifecyclePlan(t, entry, label)
		}
		if stage == "publication" || stage == "apply" || stage == "rearm" {
			if entry["status"] != "skipped" {
				t.Fatalf("%s lifecycle stage was not recorded as skipped: %#v", label, entry)
			}
		}
	}
	for _, stage := range []string{"materialize", "evaluate", "staged", "publication", "apply", "rearm", "plan", "failure"} {
		if !seen[stage] {
			t.Fatalf("%s diagnostics missing stage %q: %v", label, stage, seen)
		}
	}
}

func assertSuccessfulProbeDiagnostics(t *testing.T, status map[string]any) {
	t.Helper()
	probe, ok := status["probe"].(map[string]any)
	if !ok || probe["success"] != true || probe["stage"] != "probe/complete" {
		t.Fatalf("successful probe diagnostics missing: %#v", status)
	}
	diagnostics, ok := probe["diagnostics"].([]any)
	if !ok || len(diagnostics) == 0 {
		t.Fatalf("successful probe lost diagnostics: %#v", probe)
	}
	seen := map[string]bool{}
	for _, raw := range diagnostics {
		entry, ok := raw.(map[string]any)
		if !ok {
			t.Fatalf("successful probe diagnostic is not an object: %#v", raw)
		}
		stage, _ := entry["stage"].(string)
		seen[stage] = true
		data, _ := entry["data"].(map[string]any)
		if stage == "staged" {
			if projection, ok := data["candidate-registries"].(map[string]any); !ok || len(projection) == 0 {
				t.Fatalf("successful probe lost owned candidate projection: %#v", entry)
			}
			assertOldGenerationDiff(t, data, "successful probe")
		}
		if stage == "plan" {
			assertLifecyclePlan(t, entry, "successful probe")
		}
		if stage == "publication" || stage == "apply" || stage == "rearm" {
			if entry["status"] != "skipped" {
				t.Fatalf("successful probe lifecycle stage was not recorded as skipped: %#v", entry)
			}
		}
	}
	for _, stage := range []string{"materialize", "evaluate", "staged", "validate", "plan", "publication", "apply", "rearm"} {
		if !seen[stage] {
			t.Fatalf("successful probe diagnostics missing stage %q: %v", stage, seen)
		}
	}
}

func assertOldGenerationDiff(t *testing.T, data map[string]any, label string) {
	t.Helper()
	diff, ok := data["old-generation/diff"].(map[string]any)
	if !ok {
		t.Fatalf("%s old-generation diff missing: %#v", label, data)
	}
	if diff["baseline-status"] != "admitted" {
		t.Fatalf("%s old-generation baseline was not admitted: %#v", label, diff)
	}
	if _, ok := diff["old"].(map[string]any); !ok {
		t.Fatalf("%s old-generation baseline projection missing: %#v", label, diff)
	}
	if _, ok := diff["new"].(map[string]any); !ok {
		t.Fatalf("%s candidate projection missing from semantic diff: %#v", label, diff)
	}
	if _, ok := diff["changed?"].(bool); !ok {
		t.Fatalf("%s semantic diff changed? flag is not boolean: %#v", label, diff)
	}
	changes, ok := diff["changes"].(map[string]any)
	if !ok {
		t.Fatalf("%s semantic diff changes missing: %#v", label, diff)
	}
	for _, key := range []string{"added", "removed", "changed"} {
		if _, ok := changes[key].(map[string]any); !ok {
			t.Fatalf("%s semantic diff %s map missing: %#v", label, key, diff)
		}
	}
}

func assertLifecyclePlan(t *testing.T, entry map[string]any, label string) {
	t.Helper()
	data, ok := entry["data"].(map[string]any)
	if !ok || len(data) == 0 {
		t.Fatalf("%s lifecycle plan diagnostic has no payload: %#v", label, entry)
	}
	if entry["status"] == "completed" {
		if _, ok := data["lifecycle/plan"].(map[string]any); !ok {
			t.Fatalf("%s completed lifecycle plan is missing its plan payload: %#v", label, entry)
		}
		return
	}
	if entry["status"] != "skipped" || data["available?"] != false || data["reason"] == "" {
		t.Fatalf("%s lifecycle plan was neither completed nor explicitly unavailable: %#v", label, entry)
	}
	if _, ok := data["plan"].(map[string]any); !ok {
		t.Fatalf("%s unavailable lifecycle plan is missing its payload: %#v", label, entry)
	}
}

func awaitProcessResult(t *testing.T, result <-chan processResult) processResult {
	t.Helper()
	select {
	case value := <-result:
		return value
	case <-time.After(180 * time.Second):
		t.Fatal("lifecycle caller did not complete")
		return processResult{}
	}
}

func waitForPath(t *testing.T, path string, timeout time.Duration) {
	t.Helper()
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	tick := time.NewTicker(2 * time.Millisecond)
	defer tick.Stop()
	for {
		if pathExists(path) {
			return
		}
		select {
		case <-deadline.C:
			t.Fatalf("timed out waiting for %s", path)
		case <-tick.C:
		}
	}
}

func waitProcessExit(pid int, timeout time.Duration) error {
	if pid <= 0 {
		return fmt.Errorf("cannot wait for invalid pid %d", pid)
	}
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	tick := time.NewTicker(5 * time.Millisecond)
	defer tick.Stop()
	for processExists(pid) {
		select {
		case <-deadline.C:
			return fmt.Errorf("pid %d did not exit within %s", pid, timeout)
		case <-tick.C:
		}
	}
	return nil
}

func ensureProcessExit(t *testing.T, pid int, timeout time.Duration) {
	t.Helper()
	if pid <= 0 || !processExists(pid) {
		return
	}
	if err := syscall.Kill(pid, syscall.SIGTERM); err != nil && !errors.Is(err, syscall.ESRCH) {
		t.Errorf("PID-scoped SIGTERM failed for pid %d: %v", pid, err)
		return
	}
	if err := waitProcessExit(pid, timeout); err == nil {
		return
	} else {
		t.Logf("graceful PID-scoped cleanup did not finish: %v; escalating SIGKILL to pid %d", err, pid)
	}
	if err := syscall.Kill(pid, syscall.SIGKILL); err != nil && !errors.Is(err, syscall.ESRCH) {
		t.Errorf("PID-scoped SIGKILL failed for pid %d: %v", pid, err)
		return
	}
	if err := waitProcessExit(pid, timeout); err != nil {
		t.Errorf("PID-scoped cleanup could not confirm pid %d exited after SIGKILL: %v", pid, err)
	}
}

func processExists(pid int) bool {
	if pid <= 0 {
		return false
	}
	err := syscall.Kill(pid, 0)
	if err == nil {
		return true
	}
	return errors.Is(err, syscall.EPERM)
}

func pathExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func decodeObject(t *testing.T, output string) map[string]any {
	t.Helper()
	var value map[string]any
	if err := json.Unmarshal([]byte(strings.TrimSpace(output)), &value); err != nil {
		t.Fatalf("expected JSON object, got %q: %v", output, err)
	}
	return value
}

func validateRestartEnvelopeForAcceptance(value map[string]any, operation string) error {
	allowed := map[string]bool{"operation": true, "workspace": true, "state": true, "generation_id": true, "transition_id": true, "diagnostics": true}
	for key := range value {
		if !allowed[key] {
			return fmt.Errorf("restart envelope contains unknown key %q: %#v", key, value)
		}
	}
	if value["operation"] != operation || value["workspace"] == "" || value["state"] == "" {
		return fmt.Errorf("restart envelope missing identity fields: %#v", value)
	}
	return nil
}

func requiredString(t *testing.T, value map[string]any, key string) string {
	t.Helper()
	result, ok := value[key].(string)
	if !ok || result == "" {
		t.Fatalf("missing non-empty %s in %#v", key, value)
	}
	return result
}

func requiredPID(t *testing.T, value map[string]any) int {
	t.Helper()
	number, ok := value["pid"].(float64)
	if !ok || number <= 0 || number != float64(int(number)) {
		t.Fatalf("missing positive pid in %#v", value)
	}
	return int(number)
}

func clojureString(value string) string {
	return strconv.Quote(value)
}
