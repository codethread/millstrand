//go:build integration

package cli_test

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// TestTemporaryWeaverAcceptance exercises the temporary Weaver contract through
// the built binaries and an isolated Mill. In particular, the workspace is
// reused across temporary and persistent lifetimes so accidental persistence or
// cleanup of the selected workspace is observable.
func TestTemporaryWeaverAcceptance(t *testing.T) {
	h := newRestartProcessHarness(t)
	workspace := shortTempDir(t)
	h.initWorld(t, workspace)
	writeTemporaryConfig(t, workspace)
	sourceBefore := snapshotWorkspaceFiles(t, workspace)

	// A temporary start is disposable even when the selected config asks for
	// remembered startup. There must be no registration before or after it.
	autostartBefore := snapshotAutostartFiles(t)
	first := startTemporaryWeaver(t, h, workspace, "temporary-weaver")
	if got := snapshotAutostartFiles(t); !sameBytesMap(autostartBefore, got) {
		t.Fatalf("temporary start changed remembered-start state: before=%v after=%v", mapKeys(autostartBefore), mapKeys(got))
	}
	assertTemporaryStatus(t, first, workspace, "temporary-weaver")
	firstStateDir := requiredString(t, first, "state_dir")
	firstDataDir := requiredString(t, first, "data_dir")
	firstDB := requiredString(t, first, "database_path")
	if !pathExists(firstDB) {
		t.Fatalf("temporary database was not created: %s", firstDB)
	}
	assertListRow(t, h, workspace, "temporary-weaver", firstDB)

	// Both the selected path and the published name must discover this same
	// temporary runtime through the ordinary strand boundary.
	for _, selector := range []string{workspace, "temporary-weaver"} {
		out, err := h.runStrand("--workspace", selector, "help", "--json")
		if err != nil || !strings.Contains(out, "schema-version") {
			t.Fatalf("strand help through temporary selector %q failed: %v\n%s", selector, err, out)
		}
	}

	// A temporary runtime cannot be replaced in place. Stop is the explicit
	// fresh-database boundary.
	out, err := h.run("weaver", "restart", "--workspace", workspace)
	if err == nil || !strings.Contains(strings.ToLower(out), "temp") {
		t.Fatalf("temporary restart was not rejected: err=%v out=%q", err, out)
	}
	if out, err := h.run("weaver", "stop", "--workspace", workspace); err != nil {
		t.Fatalf("temporary stop failed: %v\n%s", err, out)
	}
	if err := waitProcessExit(requiredPID(t, first), 20*time.Second); err != nil {
		t.Fatal(err)
	}
	waitForPathGone(t, firstStateDir, 20*time.Second)
	if pathExists(firstDataDir) || pathExists(firstDB) {
		t.Fatalf("temporary stop retained runtime state: state=%q data=%q db=%q", firstStateDir, firstDataDir, firstDB)
	}
	if got := snapshotAutostartFiles(t); !sameBytesMap(autostartBefore, got) {
		t.Fatalf("temporary stop changed remembered-start state: before=%v after=%v", mapKeys(autostartBefore), mapKeys(got))
	}

	// Create durable state after the first disposable lifetime. A later
	// temporary lifetime must use a different database and leave this sentinel
	// and its persistent registration untouched.
	persistent := h.startExistingWorld(t, workspace)
	persistentPID := requiredPID(t, persistent)
	persistentStateDir := requiredString(t, persistent, "state_dir")
	persistentDB := requiredString(t, persistent, "database_path")
	if out, err := h.run("weaver", "stop", "--workspace", workspace); err != nil {
		t.Fatalf("persistent stop failed: %v\n%s", err, out)
	}
	if err := waitProcessExit(persistentPID, 20*time.Second); err != nil {
		t.Fatal(err)
	}
	persistentSentinel := filepath.Join(persistentStateDir, "persistent-sentinel")
	if err := os.WriteFile(persistentSentinel, []byte("must survive temporary cleanup\n"), 0o600); err != nil {
		t.Fatalf("write persistent sentinel: %v", err)
	}
	persistentRegistration := snapshotAutostartFiles(t)
	if len(persistentRegistration) == 0 {
		t.Fatal("persistent autoStart configuration did not create a remembered-start registration")
	}

	second := startTemporaryWeaver(t, h, workspace, "temporary-weaver")
	assertTemporaryStatus(t, second, workspace, "temporary-weaver")
	if requiredString(t, second, "database_path") == persistentDB || requiredString(t, second, "state_dir") == persistentStateDir {
		t.Fatalf("temporary runtime reused persistent state: persistent=%#v temporary=%#v", persistent, second)
	}
	if got := snapshotAutostartFiles(t); !sameBytesMap(persistentRegistration, got) {
		t.Fatalf("temporary start changed pre-existing remembered-start state: before=%v after=%v", mapKeys(persistentRegistration), mapKeys(got))
	}
	secondStateDir := requiredString(t, second, "state_dir")
	secondPID := requiredPID(t, second)
	if err := syscall.Kill(secondPID, syscall.SIGTERM); err != nil {
		t.Fatalf("signal temporary child pid %d: %v", secondPID, err)
	}
	if err := waitProcessExit(secondPID, 20*time.Second); err != nil {
		t.Fatal(err)
	}
	waitForPathGone(t, secondStateDir, 20*time.Second)
	if !pathExists(persistentSentinel) || !pathExists(persistentDB) {
		t.Fatalf("direct child exit damaged persistent state: sentinel=%v db=%v", pathExists(persistentSentinel), pathExists(persistentDB))
	}
	if got := snapshotAutostartFiles(t); !sameBytesMap(persistentRegistration, got) {
		t.Fatalf("direct child exit changed remembered-start state: before=%v after=%v", mapKeys(persistentRegistration), mapKeys(got))
	}

	// Startup failure is synchronous and must not leave a temporary runtime
	// directory behind. Keep this separate from the healthy workspace so the
	// failure cannot be masked by stale metadata.
	failureWorkspace := shortTempDir(t)
	h.initWorld(t, failureWorkspace)
	if err := os.WriteFile(filepath.Join(failureWorkspace, "init.clj"), []byte("(throw (ex-info \"temporary startup failure\" {}))\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	failureRoots := snapshotWeaverRoots(t)
	out, err = h.run("weaver", "start", "--temp", "--workspace", failureWorkspace)
	if err == nil || !strings.Contains(strings.ToLower(out), "weaver start failed") {
		t.Fatalf("temporary startup failure lost its returned error: err=%v out=%q", err, out)
	}
	if got := snapshotWeaverRoots(t); !sameStringSet(failureRoots, got) {
		t.Fatalf("temporary startup failure leaked runtime directories: before=%v after=%v", mapKeys(failureRoots), mapKeys(got))
	}

	// Temporary startup refuses a configured JVM pool rather than silently
	// changing the process topology.
	poolWorkspace := shortTempDir(t)
	h.initWorld(t, poolWorkspace)
	if err := os.WriteFile(filepath.Join(poolWorkspace, "config.local.json"), []byte(`{"JVMPool":"temporary-pool"}
`), 0o644); err != nil {
		t.Fatal(err)
	}
	poolRoots := snapshotWeaverRoots(t)
	out, err = h.run("weaver", "start", "--temp", "--workspace", poolWorkspace)
	if err == nil || !strings.Contains(strings.ToLower(out), "jvm") {
		t.Fatalf("temporary JVM-pool start was not rejected: err=%v out=%q", err, out)
	}
	if got := snapshotWeaverRoots(t); !sameStringSet(poolRoots, got) {
		t.Fatalf("temporary JVM-pool rejection leaked runtime directories: before=%v after=%v", mapKeys(poolRoots), mapKeys(got))
	}

	// Mill shutdown is itself a cleanup boundary for a running temporary child.
	third := startTemporaryWeaver(t, h, workspace, "temporary-weaver")
	thirdStateDir := requiredString(t, third, "state_dir")
	thirdPID := requiredPID(t, third)
	millPID := h.mill.Process.Pid
	if err := h.mill.Process.Signal(os.Interrupt); err != nil {
		t.Fatalf("signal fixture Mill pid %d: %v", millPID, err)
	}
	if err := h.mill.Wait(); err != nil {
		t.Fatalf("fixture Mill pid %d did not shut down gracefully: %v", millPID, err)
	}
	if err := waitProcessExit(thirdPID, 20*time.Second); err != nil {
		t.Fatal(err)
	}
	waitForPathGone(t, thirdStateDir, 20*time.Second)
	if !pathExists(persistentSentinel) || !pathExists(persistentDB) {
		t.Fatalf("Mill shutdown damaged persistent state: sentinel=%v db=%v", pathExists(persistentSentinel), pathExists(persistentDB))
	}
	if got := snapshotAutostartFiles(t); !sameBytesMap(persistentRegistration, got) {
		t.Fatalf("Mill shutdown changed remembered-start state: before=%v after=%v", mapKeys(persistentRegistration), mapKeys(got))
	}
	assertWorkspaceFiles(t, workspace, sourceBefore)
}

func writeTemporaryConfig(t *testing.T, workspace string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(workspace, "config.json"), []byte(`{"configFormat":"alpha","autoStart":true,"name":"persistent-name"}
`), 0o644); err != nil {
		t.Fatalf("write temporary acceptance config: %v", err)
	}
}

func startTemporaryWeaver(t *testing.T, h *restartProcessHarness, workspace, name string) map[string]any {
	t.Helper()
	out, err := h.run("weaver", "start", "--temp", "--workspace", workspace, "--name", name)
	if err != nil {
		t.Fatalf("temporary weaver start: %v\n%s", err, out)
	}
	status := decodeObject(t, out)
	h.pids = append(h.pids, requiredPID(t, status))
	return status
}

func assertTemporaryStatus(t *testing.T, status map[string]any, workspace, name string) {
	t.Helper()
	if status["state"] != "running" || status["config_dir"] != canonicalWorkspace(t, workspace) || status["name"] != name {
		t.Fatalf("unexpected temporary status identity: %#v", status)
	}
	for _, key := range []string{"state_dir", "data_dir", "database_path", "socket_path"} {
		requiredString(t, status, key)
	}
}

func assertListRow(t *testing.T, h *restartProcessHarness, workspace, name, database string) {
	t.Helper()
	wantWorkspace := canonicalWorkspace(t, workspace)
	for _, row := range h.list(t) {
		if row["config_dir"] == wantWorkspace && row["state"] == "running" {
			if row["name"] != name || row["database_path"] != database {
				t.Fatalf("temporary list row lost name/database: %#v", row)
			}
			return
		}
	}
	t.Fatalf("temporary runtime missing from mill weaver list: workspace=%q", wantWorkspace)
}

func snapshotWorkspaceFiles(t *testing.T, workspace string) map[string][]byte {
	t.Helper()
	files := map[string][]byte{}
	for _, name := range []string{"config.json", "deps.edn", "init.clj"} {
		path := filepath.Join(workspace, name)
		contents, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("snapshot workspace file %s: %v", path, err)
		}
		files[name] = contents
	}
	return files
}

func assertWorkspaceFiles(t *testing.T, workspace string, before map[string][]byte) {
	t.Helper()
	if !pathExists(workspace) {
		t.Fatalf("temporary lifecycle removed workspace source %s", workspace)
	}
	for name, want := range before {
		got, err := os.ReadFile(filepath.Join(workspace, name))
		if err != nil {
			t.Fatalf("workspace source file %s was removed: %v", name, err)
		}
		if !bytes.Equal(want, got) {
			t.Fatalf("workspace source file %s changed: before=%q after=%q", name, want, got)
		}
	}
}

func snapshotAutostartFiles(t *testing.T) map[string][]byte {
	t.Helper()
	root := filepath.Join(os.Getenv("XDG_STATE_HOME"), "millstrand", "autostart")
	entries, err := os.ReadDir(root)
	if os.IsNotExist(err) {
		return map[string][]byte{}
	}
	if err != nil {
		t.Fatalf("read autostart directory: %v", err)
	}
	files := map[string][]byte{}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		path := filepath.Join(root, entry.Name())
		contents, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read autostart file %s: %v", path, err)
		}
		files[path] = contents
	}
	return files
}

func snapshotWeaverRoots(t *testing.T) map[string]struct{} {
	t.Helper()
	root := filepath.Join(os.Getenv("XDG_STATE_HOME"), "millstrand", "weavers")
	entries, err := os.ReadDir(root)
	if os.IsNotExist(err) {
		return map[string]struct{}{}
	}
	if err != nil {
		t.Fatalf("read weaver runtime root: %v", err)
	}
	got := map[string]struct{}{}
	for _, entry := range entries {
		got[entry.Name()] = struct{}{}
	}
	return got
}

func waitForPathGone(t *testing.T, path string, timeout time.Duration) {
	t.Helper()
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	tick := time.NewTicker(5 * time.Millisecond)
	defer tick.Stop()
	for {
		if !pathExists(path) {
			return
		}
		select {
		case <-deadline.C:
			t.Fatalf("path %s still exists after %s", path, timeout)
		case <-tick.C:
		}
	}
}

func sameBytesMap(a, b map[string][]byte) bool {
	if len(a) != len(b) {
		return false
	}
	for path, want := range a {
		if !bytes.Equal(want, b[path]) {
			return false
		}
	}
	return true
}

func sameStringSet(a, b map[string]struct{}) bool {
	if len(a) != len(b) {
		return false
	}
	for value := range a {
		if _, ok := b[value]; !ok {
			return false
		}
	}
	return true
}

func mapKeys[T any](values map[string]T) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	return keys
}
