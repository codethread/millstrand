//go:build integration

package cli_test

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestJVMPoolLifecycleAcceptance proves the public process boundary for a
// pooled host. The fixture deliberately uses no external coordinates, but it
// still writes a valid deps.edn because dependency discovery is part of the
// admission contract.
func TestJVMPoolLifecycleAcceptance(t *testing.T) {
	h := newRestartProcessHarness(t)
	pool := "backend"
	workspaceA := shortTempDir(t)
	workspaceB := shortTempDir(t)
	workspaceC := shortTempDir(t)
	workspaceD := shortTempDir(t)

	initPoolMember(t, h, workspaceA, pool, "A")
	initPoolMember(t, h, workspaceB, pool, "B")
	h.initWorld(t, workspaceC)

	// Registration is durable membership, not a start request. No member has
	// a metadata file before the first explicit lifecycle command.
	for _, workspace := range []string{workspaceA, workspaceB, workspaceD} {
		if _, err := os.Stat(filepath.Join(workspace, "weaver.json")); !os.IsNotExist(err) {
			t.Fatalf("pool member %s unexpectedly started during init: %v", workspace, err)
		}
	}

	startedA := startRegisteredPool(t, h, workspaceA)
	statusA := h.status(t, workspaceA)
	statusB := h.status(t, workspaceB)
	assertPooledMemberIdentity(t, statusA, "A")
	assertPooledMemberIdentity(t, statusB, "B")
	if requiredPID(t, statusA) != requiredPID(t, statusB) {
		t.Fatalf("pooled members did not share one host pid: A=%#v B=%#v", statusA, statusB)
	}
	if requiredString(t, statusA, "weaver_id") == requiredString(t, statusB, "weaver_id") || requiredString(t, statusA, "generation_id") == requiredString(t, statusB, "generation_id") {
		t.Fatalf("pooled members reused runtime identity: A=%#v B=%#v", statusA, statusB)
	}
	for _, key := range []string{"state_dir", "data_dir", "database_path", "socket_path"} {
		if requiredString(t, statusA, key) == requiredString(t, statusB, key) {
			t.Fatalf("pooled members share %s: A=%#v B=%#v", key, statusA, statusB)
		}
	}
	if nreplPort(t, statusA) == nreplPort(t, statusB) {
		t.Fatalf("pooled members share nREPL port: A=%#v B=%#v", statusA, statusB)
	}
	if requiredString(t, statusA, "host_generation_id") != requiredString(t, statusB, "host_generation_id") || requiredString(t, statusA, "jvm_pool") != pool {
		t.Fatalf("pooled host identity is inconsistent: A=%#v B=%#v", statusA, statusB)
	}
	assertExactPIDIsLive(t, requiredPID(t, statusA))
	t.Logf("pooled host pid=%d, A weaver=%s generation=%s, B weaver=%s generation=%s", requiredPID(t, statusA), requiredString(t, statusA, "weaver_id"), requiredString(t, statusA, "generation_id"), requiredString(t, statusB, "weaver_id"), requiredString(t, statusB, "generation_id"))
	if startedA["state"] != "running" {
		t.Fatalf("pool start did not return running state: %#v", startedA)
	}

	// C has no pool configuration and therefore gets its own supervised JVM.
	startedC := startUnpooledWorld(t, h, workspaceC)
	if requiredPID(t, startedC) == requiredPID(t, statusA) {
		t.Fatalf("isolated member unexpectedly shares pooled host pid: pool=%#v isolated=%#v", statusA, startedC)
	}
	initPoolMember(t, h, workspaceD, pool, "D")

	for _, member := range []struct {
		workspace string
		value     string
	}{
		{workspaceA, "A"}, {workspaceB, "B"},
	} {
		out, err := h.runStrand("--workspace", member.workspace, "pool-identity")
		if err != nil {
			t.Fatalf("strand pool-identity for %s: %v\n%s", member.value, err, out)
		}
		assertIdentityResult(t, out, member.value, h.status(t, member.workspace))
		out, err = runPoolREPL(h, member.workspace)
		if err != nil {
			t.Fatalf("trusted repl for %s: %v\n%s", member.value, err, out)
		}
		if !strings.Contains(out, requiredString(t, h.status(t, member.workspace), "weaver_id")) {
			t.Fatalf("trusted repl selected the wrong runtime for %s: %q", member.value, out)
		}
	}
	childPIDs := map[string]int{}
	for _, member := range []struct {
		workspace string
		value     string
	}{
		{workspaceA, "A"}, {workspaceB, "B"},
	} {
		out, err := h.runStrand("--workspace", member.workspace, "pool-child")
		if err != nil {
			t.Fatalf("launch owned child for %s: %v\n%s", member.value, err, out)
		}
		child := decodeObject(t, out)
		if (child["phase"] != "starting" && child["phase"] != "running") || child["handle"] == "" {
			t.Fatalf("owned child for %s did not reach running: %#v", member.value, child)
		}
		pidPath := filepath.Join(member.workspace, "pool-child.pid")
		waitForPath(t, pidPath, 20*time.Second)
		pid := readPIDFile(t, pidPath)
		if !processExists(pid) {
			t.Fatalf("owned child for %s exited before replacement: pid=%d", member.value, pid)
		}
		out, err = h.runStrand("--workspace", member.workspace, "pool-reconcile-child")
		if err != nil || decodeObject(t, out)["phase"] != "running" {
			t.Fatalf("owned child for %s did not reconcile to running: %v\n%s", member.value, err, out)
		}
		childPIDs[member.value] = pid
		h.pids = append(h.pids, pid)
		t.Logf("member %s custody handle=%s child pid=%d", member.value, child["handle"], pid)
	}

	// Adding D while the host is live records pending membership without
	// creating a second host or disturbing already-admitted members.
	beforePendingPID := requiredPID(t, h.status(t, workspaceA))
	out, err := h.run("weaver", "start", "--workspace", workspaceD)
	if err == nil {
		t.Fatalf("pending member start unexpectedly succeeded: %s", out)
	}
	if !strings.Contains(out, "mill/jvm-pool-restart-required") {
		t.Fatalf("pending member start lost structured restart-required result: %v\n%s", err, out)
	}
	if requiredPID(t, h.status(t, workspaceA)) != beforePendingPID || requiredPID(t, h.status(t, workspaceB)) != beforePendingPID {
		t.Fatalf("pending member start disrupted the admitted host: %s", out)
	}
	assertPendingProjection(t, h, workspaceD, workspaceA, workspaceB, workspaceD)

	oldGeneration := requiredString(t, h.status(t, workspaceA), "generation_id")
	oldPID := beforePendingPID
	restarted, err := h.run("weaver", "restart", "--workspace", workspaceD)
	if err != nil {
		t.Fatalf("restart through pending member failed: %v\n%s", err, restarted)
	}
	finalA := h.status(t, workspaceA)
	finalB := h.status(t, workspaceB)
	finalD := h.status(t, workspaceD)
	newPID := requiredPID(t, finalA)
	h.pids = append(h.pids, newPID)
	if newPID == oldPID || requiredString(t, finalA, "generation_id") == oldGeneration {
		t.Fatalf("pool restart did not admit a new generation: old pid=%d generation=%s final=%#v", oldPID, oldGeneration, finalA)
	}
	if requiredPID(t, finalB) != newPID || requiredPID(t, finalD) != newPID {
		t.Fatalf("pool restart did not admit all members under one host: A=%#v B=%#v D=%#v", finalA, finalB, finalD)
	}
	if err := waitProcessExit(oldPID, 20*time.Second); err != nil {
		t.Fatal(err)
	}
	assertIdentityResult(t, mustRunStrand(t, h, workspaceD), "D", finalD)
	for _, member := range []struct {
		workspace string
		value     string
	}{
		{workspaceA, "A"}, {workspaceB, "B"},
	} {
		out, err := h.runStrand("--workspace", member.workspace, "pool-reconcile-child")
		if err != nil {
			t.Fatalf("reconcile owned child for %s after replacement: %v\n%s", member.value, err, out)
		}
		child := decodeObject(t, out)
		if child["phase"] != "running" || !processExists(childPIDs[member.value]) {
			t.Fatalf("member %s lost native child custody across replacement: child=%#v pid=%d", member.value, child, childPIDs[member.value])
		}
	}
	for _, member := range []struct {
		workspace string
		value     string
	}{
		{workspaceA, "A"}, {workspaceB, "B"},
	} {
		out, err := h.runStrand("--workspace", member.workspace, "pool-stop-child")
		if err != nil {
			t.Fatalf("stop owned child for %s: %v\n%s", member.value, err, out)
		}
		if decodeObject(t, out)["acknowledged"] != true {
			t.Fatalf("owned child for %s was not acknowledged: %s", member.value, out)
		}
		if err := waitProcessExit(childPIDs[member.value], 10*time.Second); err != nil {
			t.Fatal(err)
		}
	}

	// Collective stop is available through any member. Registration remains,
	// so a later start reconstructs the complete admitted set.
	if out, err := h.run("weaver", "stop", "--workspace", workspaceB); err != nil {
		t.Fatalf("collective stop through B failed: %v\n%s", err, out)
	}
	if err := waitProcessExit(newPID, 20*time.Second); err != nil {
		t.Fatal(err)
	}
	for _, workspace := range []string{workspaceA, workspaceB, workspaceD} {
		status := h.status(t, workspace)
		if status["state"] != "stopped" {
			t.Fatalf("collective stop left %s running: %#v", workspace, status)
		}
	}

	// Mill itself can restart independently of pool membership. Starting it
	// again with the same isolated XDG state must retain the registry.
	restartMillForPoolAcceptance(t, h)
	restarted, err = h.run("weaver", "start", "--workspace", workspaceA)
	if err != nil {
		t.Fatalf("start after Mill restart failed: %v\n%s", err, restarted)
	}
	restoredA := h.status(t, workspaceA)
	restoredB := h.status(t, workspaceB)
	restoredD := h.status(t, workspaceD)
	restoredPID := requiredPID(t, restoredA)
	h.pids = append(h.pids, restoredPID)
	if requiredPID(t, restoredB) != restoredPID || requiredPID(t, restoredD) != restoredPID {
		t.Fatalf("durable membership did not restore one complete host: A=%#v B=%#v D=%#v", restoredA, restoredB, restoredD)
	}
	if requiredString(t, restoredA, "weaver_id") == requiredString(t, restoredB, "weaver_id") || requiredString(t, restoredA, "weaver_id") == requiredString(t, restoredD, "weaver_id") {
		t.Fatalf("restored members lost distinct runtime identities: A=%#v B=%#v D=%#v", restoredA, restoredB, restoredD)
	}
	assertIdentityResult(t, mustRunStrand(t, h, workspaceA), "A", restoredA)
	assertIdentityResult(t, mustRunStrand(t, h, workspaceB), "B", restoredB)
	assertIdentityResult(t, mustRunStrand(t, h, workspaceD), "D", restoredD)

	// A repeated start on an admitted member is idempotent.
	repeated, err := h.run("weaver", "start", "--workspace", workspaceB)
	if err != nil {
		t.Fatalf("repeated admitted start failed: %v\n%s", err, repeated)
	}
	if requiredPID(t, h.status(t, workspaceA)) != restoredPID || requiredPID(t, h.status(t, workspaceB)) != restoredPID || requiredPID(t, h.status(t, workspaceD)) != restoredPID {
		t.Fatalf("repeated admitted start changed host pid: %s", repeated)
	}
}

func TestJVMPoolProbeFailureAcceptance(t *testing.T) {
	t.Run("failed probe retains old host and pending newcomer", func(t *testing.T) {
		h := newRestartProcessHarness(t)
		pool := "probe-source"
		workspaceA := shortTempDir(t)
		workspaceB := shortTempDir(t)
		workspaceD := shortTempDir(t)
		initPoolMember(t, h, workspaceA, pool, "A")
		initPoolMember(t, h, workspaceB, pool, "B")
		old := startRegisteredPool(t, h, workspaceA)
		oldPID := requiredPID(t, old)
		oldGeneration := requiredString(t, old, "generation_id")
		initPoolMember(t, h, workspaceD, pool, "D")
		if err := os.WriteFile(filepath.Join(workspaceD, "init.clj"), []byte("(load-file \"/private/millstrand-missing-pool-module.clj\")\n"), 0o644); err != nil {
			t.Fatal(err)
		}

		out, err := h.run("weaver", "restart", "--workspace", workspaceD)
		assertPooledFailedRestartResponse(t, out, err, workspaceD, "missing-source probe")
		statusA := h.statusDetails(t, workspaceA)
		statusB := h.statusDetails(t, workspaceB)
		if requiredPID(t, statusA) != oldPID || requiredString(t, statusA, "generation_id") != oldGeneration || requiredPID(t, statusB) != oldPID {
			t.Fatalf("failed probe changed the admitted host: A=%#v B=%#v", statusA, statusB)
		}
		expectedMembers := map[string]string{
			canonicalWorkspace(t, workspaceA): "live",
			canonicalWorkspace(t, workspaceB): "live",
			canonicalWorkspace(t, workspaceD): "newcomer",
		}
		assertPooledProbeFailure(t, statusA, oldPID, oldGeneration, expectedMembers, "pooled missing-source probe A")
		if statusB["state"] != "running" || requiredPID(t, statusB) != oldPID {
			t.Fatalf("pooled missing-source probe B changed admitted host: %#v", statusB)
		}
		assertPooledProbeDiagnostics(t, statusB, expectedMembers, "pooled missing-source probe B")
		pending := h.statusDetails(t, workspaceD)
		if pending["state"] != "pending" || containsString(pending["live_members"].([]any), canonicalWorkspace(t, workspaceD)) {
			t.Fatalf("failed probe admitted or lost newcomer state: %#v", pending)
		}
		if generation, ok := pending["generation_id"]; ok && generation != nil && strings.TrimSpace(fmt.Sprint(generation)) != "" {
			t.Fatalf("failed probe admitted a generation for newcomer: %#v", pending)
		}
		assertPooledProbeDiagnostics(t, pending, expectedMembers, "pooled missing-source pending newcomer")
	})

	t.Run("post-cutover startup failure admits no partial pool", func(t *testing.T) {
		h := newRestartProcessHarness(t)
		pool := "probe-cutover"
		workspaceA := shortTempDir(t)
		workspaceB := shortTempDir(t)
		failureMarker := filepath.Join(shortTempDir(t), "fail-serving-startup")
		initPoolMember(t, h, workspaceA, pool, "A")
		initPoolMember(t, h, workspaceB, pool, "B")
		old := startRegisteredPool(t, h, workspaceA)
		oldPID := requiredPID(t, old)
		for _, workspace := range []string{workspaceA, workspaceB} {
			appendInit(t, filepath.Join(workspace, "init.clj"), fmt.Sprintf(
				"(when (and (nil? (System/getenv \"MILLSTRAND_POOL_PROBE_MANIFEST\")) (.exists (java.io.File. %s))) (throw (ex-info \"pooled replacement startup gate\" {:stage :startup})))\n",
				clojureString(failureMarker)))
		}
		if err := os.WriteFile(failureMarker, []byte("fail"), 0o644); err != nil {
			t.Fatal(err)
		}

		out, err := h.run("weaver", "restart", "--workspace", workspaceA)
		if err == nil {
			failure := decodeObject(t, out)
			if failure["state"] != "failed" || failure["generation_id"] != nil {
				t.Fatalf("post-cutover failure admitted a partial generation: %#v", failure)
			}
		} else if !strings.Contains(out, "mill/weaver-restart-failed") || strings.Contains(out, "mill/weaver-restart-invalid-result") || strings.Contains(strings.ToLower(out), "eof") {
			t.Fatalf("post-cutover failure lost its structured error: %v\n%s", err, out)
		}
		if processExists(oldPID) {
			t.Fatalf("old pooled host remained alive after post-cutover failure: pid=%d", oldPID)
		}
		failureStatus := h.status(t, workspaceA)
		failureStatusB := h.status(t, workspaceB)
		for label, status := range map[string]map[string]any{
			"A": failureStatus, "B": failureStatusB,
			"A detailed": h.statusDetails(t, workspaceA),
			"B detailed": h.statusDetails(t, workspaceB),
		} {
			if status["state"] != "failed" || status["generation_id"] != nil || status["old_generation_stopped"] != true {
				t.Fatalf("post-cutover failure status for %s admitted a partial generation or lost cutover truth: %#v", label, status)
			}
		}
		if err := os.Remove(failureMarker); err != nil {
			t.Fatal(err)
		}
		retry, err := h.run("weaver", "restart", "--workspace", workspaceB)
		if err != nil {
			t.Fatalf("post-cutover retry failed: %v\n%s", err, retry)
		}
		retryStatus := h.status(t, workspaceA)
		h.pids = append(h.pids, requiredPID(t, retryStatus))
		if retryStatus["state"] != "running" || requiredPID(t, h.status(t, workspaceB)) != requiredPID(t, retryStatus) {
			t.Fatalf("retry did not admit exactly one complete pool: A=%#v B=%#v", retryStatus, h.status(t, workspaceB))
		}
	})
}

func runPoolREPL(h *restartProcessHarness, workspace string) (string, error) {
	cmd := exec.Command(h.millBin, "weaver", "repl", "--stdin", "--workspace", workspace)
	cmd.Dir = h.source
	cmd.Stdin = strings.NewReader("(:nonce (:metadata millstrand.core.weaver.runtime/*runtime*))\n")
	var output bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &output
	err := cmd.Run()
	return output.String(), err
}

func initPoolMember(t *testing.T, h *restartProcessHarness, workspace, pool, value string) {
	t.Helper()
	out, err := h.run("init", "--workspace", workspace, "--jvm-pool", pool)
	if err != nil {
		t.Fatalf("mill init --jvm-pool %s: %v\n%s", value, err, out)
	}
	if err := os.WriteFile(filepath.Join(workspace, "deps.edn"), []byte("{}\n"), 0o644); err != nil {
		t.Fatalf("write %s deps.edn: %v", value, err)
	}
	ns := "pool.member." + strings.ToLower(value)
	owner := ":pool-member-" + strings.ToLower(value)
	childKey := "pool-child-" + strings.ToLower(value)
	childCommand := "echo $$ > " + clojureString(filepath.Join(workspace, "pool-child.pid")) + "; sleep 300"
	init := fmt.Sprintf(`(ns %s
  (:require [millstrand.api.current.alpha :as current]
            [millstrand.api.process.alpha :as process]
            [millstrand.api.weaver.alpha :as weaver]))

(defn pool-identity-op [ctx]
  (let [metadata (:op/runtime-metadata ctx)]
    {:member %q
     :weaver-id (:nonce metadata)
     :generation-id (:generation-id metadata)
     :database-path (:canonical-db-path metadata)}))

(defn child-record [ctx]
  (first (process/list-owned (:op/runtime ctx) %s)))

(defn pool-child-op [ctx]
  (let [record (process/launch! (:op/runtime ctx) %s %q
                                {:argv ["sh" "-c" %q]
                                 :cwd %q
                                 :env {}})]
    {:phase (name (:phase record)) :handle (:handle record)}))

(defn pool-reconcile-child-op [ctx]
  (let [record (child-record ctx)]
    {:phase (name (:phase record)) :handle (:handle record)}))

(defn pool-stop-child-op [ctx]
  (let [runtime (:op/runtime ctx)
        record (child-record ctx)]
    (process/cancel! runtime %s (:handle record))
    (process/acknowledge! runtime %s (:handle record))))

(weaver/register-op! (current/runtime) 'pool-identity
                     {:doc "Return the selected pooled runtime identity."
                      :arg-spec {:op "pool-identity"
                                 :doc "Return the selected pooled runtime identity."
                                 :hook-class :read
                                 :deadline-class :standard}}
                     '%s/pool-identity-op)

(weaver/register-op! (current/runtime) 'pool-child
                     {:doc "Launch the member-owned native child."
                      :arg-spec {:op "pool-child" :doc "Launch the member-owned native child."
                                 :hook-class :mutating :deadline-class :standard}}
                     '%s/pool-child-op)
(weaver/register-op! (current/runtime) 'pool-reconcile-child
                     {:doc "Reconcile the member-owned native child."
                      :arg-spec {:op "pool-reconcile-child" :doc "Reconcile the member-owned native child."
                                 :hook-class :read :deadline-class :standard}}
                     '%s/pool-reconcile-child-op)
(weaver/register-op! (current/runtime) 'pool-stop-child
                     {:doc "Stop and acknowledge the member-owned native child."
                      :arg-spec {:op "pool-stop-child" :doc "Stop and acknowledge the member-owned native child."
                                 :hook-class :mutating :deadline-class :standard}}
                     '%s/pool-stop-child-op)
`, ns, value, owner, owner, childKey, childCommand, workspace, owner, owner, ns, ns, ns, ns)
	if err := os.WriteFile(filepath.Join(workspace, "init.clj"), []byte(init), 0o644); err != nil {
		t.Fatalf("write %s init.clj: %v", value, err)
	}
}

func startRegisteredPool(t *testing.T, h *restartProcessHarness, workspace string) map[string]any {
	t.Helper()
	out, err := h.run("weaver", "start", "--workspace", workspace)
	if err != nil {
		t.Fatalf("start pooled workspace: %v\n%s", err, out)
	}
	status := decodeObject(t, out)
	h.pids = append(h.pids, requiredPID(t, status))
	return status
}

func startUnpooledWorld(t *testing.T, h *restartProcessHarness, workspace string) map[string]any {
	t.Helper()
	out, err := h.run("weaver", "start", "--workspace", workspace)
	if err != nil {
		t.Fatalf("start isolated workspace: %v\n%s", err, out)
	}
	status := decodeObject(t, out)
	h.pids = append(h.pids, requiredPID(t, status))
	return status
}

func assertPooledFailedRestartResponse(t *testing.T, output string, runErr error, workspace, label string) {
	t.Helper()
	if runErr != nil {
		t.Fatalf("%s returned an unstructured restart error: %v\n%s", label, runErr, output)
	}
	result := decodeObject(t, output)
	if err := validateRestartEnvelopeForAcceptance(result, "restart"); err != nil {
		t.Fatalf("%s returned an invalid closed restart envelope: %v (%#v)", label, err, result)
	}
	if result["state"] != "failed" || result["workspace"] != canonicalWorkspace(t, workspace) {
		t.Fatalf("%s selected the wrong failed restart result: %#v", label, result)
	}
	if generation, ok := result["generation_id"]; ok && generation != nil && strings.TrimSpace(fmt.Sprint(generation)) != "" {
		t.Fatalf("%s exposed an admitted generation: %#v", label, result)
	}
	diagnostics, ok := result["diagnostics"].([]any)
	if !ok || len(diagnostics) != 1 {
		t.Fatalf("%s lost its single closed failure diagnostic: %#v", label, result)
	}
	diagnostic, ok := diagnostics[0].(map[string]any)
	if !ok || diagnostic["stage"] != "probe" || diagnostic["status"] != "failed" {
		t.Fatalf("%s returned the wrong failure diagnostic: %#v", label, result)
	}
	data, ok := diagnostic["data"].(map[string]any)
	if !ok || strings.TrimSpace(fmt.Sprint(data["message"])) == "" || strings.TrimSpace(fmt.Sprint(data["transition_id"])) == "" {
		t.Fatalf("%s failure diagnostic lost message or transition evidence: %#v", label, diagnostic)
	}
}

func assertPooledProbeFailure(t *testing.T, status map[string]any, oldPID int, oldGeneration string, expectedMembers map[string]string, label string) {
	t.Helper()
	if status["state"] != "running" || requiredPID(t, status) != oldPID || requiredString(t, status, "generation_id") != oldGeneration {
		t.Fatalf("%s changed admitted generation: %#v", label, status)
	}
	assertPooledProbeDiagnostics(t, status, expectedMembers, label)
}

func assertPooledProbeDiagnostics(t *testing.T, status map[string]any, expectedMembers map[string]string, label string) {
	t.Helper()
	probe, ok := status["probe"].(map[string]any)
	if !ok || probe["success"] != false {
		t.Fatalf("%s retained probe success or omitted probe: %#v", label, status)
	}
	stage, ok := probe["stage"].(string)
	if !ok || strings.TrimSpace(stage) == "" || stage != "probe/failure" {
		t.Fatalf("%s retained the wrong observed probe stage: %#v", label, probe)
	}
	probePath := requiredString(t, probe, "probe/workspace")
	logPath := requiredString(t, probe, "log")
	if !pathExists(probePath) || !pathExists(logPath) {
		t.Fatalf("%s probe diagnostics were not retained at path/log: %#v", label, probe)
	}
	completed, ok := probe["completed"].([]any)
	if !ok || len(completed) == 0 {
		t.Fatalf("%s probe lost observed completed stages: %#v", label, probe)
	}
	for _, raw := range completed {
		stage, ok := raw.(string)
		if !ok || strings.TrimSpace(stage) == "" {
			t.Fatalf("%s probe completed stage is blank: %#v", label, probe)
		}
		if map[string]bool{"evaluate": true, "staged": true, "plan": true, "publication": true, "apply": true, "rearm": true}[stage] {
			t.Fatalf("%s fabricated isolated lifecycle stage %q: %#v", label, stage, probe)
		}
	}
	diagnostics, ok := probe["diagnostics"].([]any)
	if !ok || len(diagnostics) != 1 {
		t.Fatalf("%s probe did not retain exactly one failure diagnostic: %#v", label, probe)
	}
	diagnostic, ok := diagnostics[0].(map[string]any)
	if !ok || diagnostic["stage"] != stage || diagnostic["status"] != "failed" {
		t.Fatalf("%s probe diagnostic did not retain the observed failure stage: %#v", label, diagnostic)
	}
	data, ok := diagnostic["data"].(map[string]any)
	if !ok {
		t.Fatalf("%s probe diagnostic lost structured failure data: %#v", label, diagnostic)
	}
	dataCompleted, ok := data["completed"].([]any)
	if !ok || len(dataCompleted) != len(completed) {
		t.Fatalf("%s probe diagnostic lost observed completed stages: %#v", label, data)
	}
	for i := range completed {
		if dataCompleted[i] != completed[i] {
			t.Fatalf("%s probe diagnostic changed observed completed stages: probe=%#v data=%#v", label, completed, dataCompleted)
		}
	}
	collective := requiredString(t, data, "collective_diagnostic")
	if !pathExists(collective) || strings.TrimSpace(fmt.Sprint(data["message"])) == "" {
		t.Fatalf("%s probe diagnostic lost collective path or failure message: %#v", label, data)
	}
	members, ok := data["members"].([]any)
	if !ok || len(members) != len(expectedMembers) {
		t.Fatalf("%s probe diagnostic lost member evidence: %#v", label, data)
	}
	seen := map[string]bool{}
	failedMembers := 0
	for _, raw := range members {
		member, ok := raw.(map[string]any)
		if !ok {
			t.Fatalf("%s probe member evidence is not an object: %#v", label, raw)
		}
		workspace := requiredString(t, member, "workspace")
		if seen[workspace] || expectedMembers[workspace] == "" {
			t.Fatalf("%s probe member evidence has unexpected workspace: %#v", label, member)
		}
		seen[workspace] = true
		if member["baseline_kind"] != expectedMembers[workspace] || strings.TrimSpace(fmt.Sprint(member["status"])) == "" {
			t.Fatalf("%s probe member evidence lost baseline/status: %#v", label, member)
		}
		if member["status"] == "failed" {
			failedMembers++
		}
		diagnosticPath := requiredString(t, member, "diagnostic")
		if !pathExists(diagnosticPath) {
			t.Fatalf("%s probe member diagnostic path does not exist: %#v", label, member)
		}
	}
	if len(seen) != len(expectedMembers) || failedMembers == 0 {
		t.Fatalf("%s probe member evidence did not include a failed member: %#v", label, members)
	}
	failure, ok := status["restart_failure"].(map[string]any)
	if !ok || failure["stage"] != "probe" || strings.TrimSpace(fmt.Sprint(failure["message"])) == "" {
		t.Fatalf("%s retained restart failure context: %#v", label, status)
	}
}

func assertPooledMemberIdentity(t *testing.T, status map[string]any, value string) {
	t.Helper()
	if status["state"] != "running" || status["jvm_pool"] != "backend" || status["host_generation_id"] == "" {
		t.Fatalf("%s is not a running pooled member: %#v", value, status)
	}
	for _, key := range []string{"weaver_id", "generation_id", "state_dir", "data_dir", "database_path", "socket_path"} {
		requiredString(t, status, key)
	}
	nreplPort(t, status)
}

func nreplPort(t *testing.T, status map[string]any) float64 {
	t.Helper()
	nrepl, ok := status["nrepl"].(map[string]any)
	if !ok {
		t.Fatalf("status omitted nREPL endpoint: %#v", status)
	}
	port, ok := nrepl["port"].(float64)
	if !ok || port <= 0 {
		t.Fatalf("status omitted positive nREPL port: %#v", status)
	}
	return port
}

func assertIdentityResult(t *testing.T, output, want string, status map[string]any) {
	t.Helper()
	result := decodeObject(t, output)
	if result["member"] != want || result["weaver-id"] != requiredString(t, status, "weaver_id") || result["generation-id"] != requiredString(t, status, "generation_id") || result["database-path"] != status["database_path"] {
		t.Fatalf("selected runtime identity mismatch: want member=%s status=%#v output=%q result=%#v", want, status, output, result)
	}
}

func mustRunStrand(t *testing.T, h *restartProcessHarness, workspace string) string {
	t.Helper()
	out, err := h.runStrand("--workspace", workspace, "pool-identity")
	if err != nil {
		t.Fatalf("strand pool-identity: %v\n%s", err, out)
	}
	return out
}

func assertPendingProjection(t *testing.T, h *restartProcessHarness, selected string, live ...string) {
	t.Helper()
	status := h.status(t, selected)
	if status["state"] != "pending" || status["jvm_pool"] != "backend" {
		t.Fatalf("pending status omitted state/pool: %#v", status)
	}
	for _, key := range []string{"registered_members", "live_members", "pending_members"} {
		if _, ok := status[key].([]any); !ok {
			t.Fatalf("pending status omitted %s: %#v", key, status)
		}
	}
	if !containsString(status["live_members"].([]any), canonicalWorkspace(t, live[0])) || !containsString(status["live_members"].([]any), canonicalWorkspace(t, live[1])) || !containsString(status["pending_members"].([]any), canonicalWorkspace(t, live[2])) {
		t.Fatalf("pending projection was not canonical and complete: %#v", status)
	}
}

func canonicalWorkspace(t *testing.T, path string) string {
	t.Helper()
	canonical, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatalf("canonicalize workspace %s: %v", path, err)
	}
	return canonical
}

func readPIDFile(t *testing.T, path string) int {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read pid file %s: %v", path, err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(raw)))
	if err != nil || pid <= 0 {
		t.Fatalf("invalid pid file %s: %q (%v)", path, raw, err)
	}
	return pid
}

func containsString(values []any, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}

func assertExactPIDIsLive(t *testing.T, pid int) {
	t.Helper()
	cmd := exec.Command("ps", "-o", "pid=,ppid=,command=", "-p", strconv.Itoa(pid))
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("ps exact pid %d: %v\n%s", pid, err, output)
	}
	if !bytes.Contains(output, []byte(strconv.Itoa(pid))) {
		t.Fatalf("ps did not report exact owned pid %d: %s", pid, output)
	}
	t.Logf("owned pooled host process: %s", strings.TrimSpace(string(output)))
}

func restartMillForPoolAcceptance(t *testing.T, h *restartProcessHarness) {
	t.Helper()
	if h.mill == nil || h.mill.Process == nil {
		t.Fatal("pool acceptance Mill process is missing")
	}
	oldPID := h.mill.Process.Pid
	if err := h.mill.Process.Signal(os.Interrupt); err != nil {
		t.Fatalf("stop Mill pid %d for restart: %v", oldPID, err)
	}
	if err := h.mill.Wait(); err != nil {
		t.Fatalf("reap Mill pid %d: %v", oldPID, err)
	}
	start := exec.Command(h.millBin, "start")
	start.Dir = h.source
	start.Stderr = os.Stderr
	stdout, err := start.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := start.Start(); err != nil {
		t.Fatalf("restart Mill: %v", err)
	}
	h.mill = start
	h.pids = append(h.pids, start.Process.Pid)
	scanner := make([]byte, 0, 128)
	buffer := make([]byte, 128)
	for {
		n, readErr := stdout.Read(buffer)
		scanner = append(scanner, buffer[:n]...)
		if bytes.Contains(scanner, []byte("Mill ready")) {
			return
		}
		if readErr != nil {
			t.Fatalf("Mill pid %d exited before readiness: %v (%s)", start.Process.Pid, readErr, scanner)
		}
	}
}
