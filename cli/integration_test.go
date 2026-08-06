package cli_test

import (
	"bytes"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// harness holds the locally built strand + mill binaries and a running mill
// daemon for one test. Lifecycle/bootstrap commands run through the mill bin
// (mill init, mill weaver *); op invocations run through the strand dispatcher.
type harness struct {
	strand string
	mill   string
}

func newHarness(t *testing.T) harness {
	t.Helper()
	t.Setenv("XDG_STATE_HOME", filepath.Join(shortTempDir(t), "state"))
	h := harness{strand: buildStrand(t), mill: buildMill(t)}
	h.startMill(t)
	return h
}

func (h harness) startMill(t *testing.T) {
	t.Helper()
	mill := exec.Command(h.mill, "start")
	var out bytes.Buffer
	mill.Stdout = &out
	mill.Stderr = &out
	if err := mill.Start(); err != nil {
		t.Fatalf("start mill: %v", err)
	}
	t.Cleanup(func() { _ = mill.Process.Signal(os.Interrupt); _, _ = mill.Process.Wait() })
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(out.String(), "error:") {
			t.Fatalf("mill failed: %s", out.String())
		}
		root := filepath.Join(os.Getenv("XDG_STATE_HOME"), "millstrand")
		if _, err := os.Stat(filepath.Join(root, "mill.json")); err == nil {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("mill did not publish metadata: %s", out.String())
}

// millCmd runs a mill client subcommand (init, weaver *), appending --workspace
// (a subcommand flag) when an explicit workspace is selected.
func (h harness) millCmd(workspace, cwd, stdin string, args ...string) (string, error) {
	full := append([]string{}, args...)
	if workspace != "" {
		full = append(full, "--workspace", workspace)
	}
	return runBin(h.mill, cwd, stdin, full...)
}

// strandCmd invokes a registered op through the strand dispatcher; --workspace
// is a dispatcher flag and precedes the op name.
func (h harness) strandCmd(workspace, cwd, stdin string, args ...string) (string, error) {
	full := []string{}
	if workspace != "" {
		full = append(full, "--workspace", workspace)
	}
	full = append(full, args...)
	return runBin(h.strand, cwd, stdin, full...)
}

func runBin(bin, cwd, stdin string, args ...string) (string, error) {
	cmd := exec.Command(bin, args...)
	cmd.Dir = cwd
	if stdin != "" {
		cmd.Stdin = strings.NewReader(stdin)
	}
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		return out.String(), err
	}
	return out.String(), nil
}

func TestInitBootstrapsConfigDirWorkspaceThroughMill(t *testing.T) {
	cfg := shortTempDir(t)
	h := newHarness(t)
	if out, err := h.millCmd(cfg, sourceRoot(t), "", "init"); err != nil {
		t.Fatalf("init failed: %v\n%s", err, out)
	}
	configPath := filepath.Join(cfg, "config.json")
	if _, err := os.Stat(configPath); err != nil {
		t.Fatalf("expected config bootstrap: %v", err)
	}
	var cfgFile map[string]any
	b, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, &cfgFile); err != nil {
		t.Fatal(err)
	}
	if _, ok := cfgFile["source"]; ok || cfgFile["configFormat"] != "alpha" {
		t.Fatalf("unexpected bootstrap config: %#v", cfgFile)
	}
	spoolsPath := filepath.Join(cfg, "spools.edn")
	if _, err := os.Stat(spoolsPath); err != nil {
		t.Fatalf("expected spools.edn bootstrap: %v", err)
	}
	spools := string(mustReadFile(t, spoolsPath))
	if want := `millstrand.spools/batteries {:millstrand/source-root "spools/batteries"}`; !strings.Contains(spools, want) {
		t.Fatalf("spools.edn missing %q, got:\n%s", want, spools)
	}
	if strings.Contains(spools, "{:spools {}}") {
		t.Fatalf("spools.edn retained the empty-spools seed, got:\n%s", spools)
	}
	initPath := filepath.Join(cfg, "init.clj")
	got := string(mustReadFile(t, initPath))
	// The seeded declaration carries a source target and world policy only: the
	// module's contribution is the declaration data its authoring forms collect,
	// so the removed entry-point keys must appear nowhere in the file.
	declaration := "(runtime/module! runtime :millstrand/spools-batteries\n                 {:ns 'millstrand.spools.batteries\n                  :spools ['millstrand.spools/batteries]})"
	if !strings.Contains(got, declaration) {
		t.Fatalf("init.clj missing the source/world-policy declaration %q, got:\n%s", declaration, got)
	}
	for _, removed := range []string{":contribute", ":reconcile"} {
		if strings.Contains(got, removed) {
			t.Fatalf("init.clj seeds removed entry-point key %q, got:\n%s", removed, got)
		}
	}
	if strings.Contains(got, "(require 'millstrand.spools.batteries)") {
		t.Fatalf("init.clj retained the bare batteries require, got:\n%s", got)
	}
	if _, err := os.Stat(filepath.Join(cfg, ".git")); !os.IsNotExist(err) {
		t.Fatalf("explicit --workspace init must not run git init, stat err=%v", err)
	}
}

func TestInitRequiresRunningMill(t *testing.T) {
	cfg := shortTempDir(t)
	t.Setenv("XDG_STATE_HOME", filepath.Join(shortTempDir(t), "state"))
	mill := buildMill(t)
	out, err := runBin(mill, sourceRoot(t), "", "init", "--workspace", cfg)
	if err == nil || !strings.Contains(out, "mill start") {
		t.Fatalf("expected mill remediation, got out=%q err=%v", out, err)
	}
	if _, err := os.Stat(filepath.Join(cfg, "config.json")); !os.IsNotExist(err) {
		t.Fatalf("init without mill should create no config, stat err=%v", err)
	}
}

func TestGoWeaverLifecycleCommands(t *testing.T) {
	dir := shortTempDir(t)
	writeClientConfig(t, dir)
	t.Setenv("MILLSTRAND_SOURCE", sourceRoot(t))
	h := newHarness(t)
	runDir := shortTempDir(t)
	if out, err := h.millCmd(dir, runDir, "", "weaver", "start"); err != nil {
		t.Fatalf("start weaver: %v\n%s", err, out)
	}
	h.waitForStatus(t, dir, runDir)
	out, err := h.millCmd(dir, runDir, "", "weaver", "status")
	if err != nil {
		t.Fatal(err)
	}
	var status map[string]any
	if err := json.Unmarshal([]byte(out), &status); err != nil {
		t.Fatalf("status is not json: %v\n%s", err, out)
	}
	realDir, err := filepath.EvalSymlinks(dir)
	if err != nil {
		t.Fatal(err)
	}
	if status["state"] != "running" || status["database_path"] == "" || status["config_dir"] != realDir || status["data_dir"] == "" || status["weaver_id"] == "" || status["socket_path"] == filepath.Join(realDir, "state", "weaver.sock") || status["pid"].(float64) <= 0 {
		t.Fatalf("unexpected status payload: %#v", status)
	}
	if out, err := h.millCmd(dir, runDir, "", "weaver", "stop"); err != nil {
		t.Fatalf("weaver stop failed: %v\n%s", err, out)
	}
	h.waitForNotRunning(t, dir, runDir)
}

func TestWeaverReplStdinAttachesThroughMillMetadata(t *testing.T) {
	dir := shortTempDir(t)
	writeClientConfig(t, dir)
	t.Setenv("MILLSTRAND_SOURCE", sourceRoot(t))
	h := newHarness(t)
	runDir := shortTempDir(t)
	if out, err := h.millCmd(dir, runDir, "", "weaver", "start"); err != nil {
		t.Fatalf("weaver start failed: %v\n%s", err, out)
	}
	h.waitForStatus(t, dir, runDir)
	out, err := h.millCmd(dir, runDir, "@millstrand.core.weaver.runtime/current-runtime\n", "weaver", "repl", "--stdin")
	if err != nil {
		t.Fatalf("repl stdin failed: %v\n%s", err, out)
	}
	if !strings.Contains(out, ":metadata") || !strings.Contains(out, ":query-store") {
		t.Fatalf("unexpected repl output: %q", out)
	}
	out, err = h.millCmd(dir, runDir, "(throw (ex-info \"boom from weaver\" {}))\n", "weaver", "repl", "--stdin")
	if err == nil || !strings.Contains(out, "boom from weaver") {
		t.Fatalf("expected weaver eval failure, err=%v out=%q", err, out)
	}
	if out, err := h.millCmd(dir, runDir, "", "weaver", "stop"); err != nil {
		t.Fatalf("weaver stop failed: %v\n%s", err, out)
	}
}

func TestRepoLocalDiscoveryAndInitLocalOverlay(t *testing.T) {
	repo := shortTempDir(t)
	if err := exec.Command("git", "init", repo).Run(); err != nil {
		t.Fatalf("git init repo: %v", err)
	}
	subdir := filepath.Join(repo, "work", "nested")
	if err := os.MkdirAll(subdir, 0o755); err != nil {
		t.Fatal(err)
	}
	h := newHarness(t)
	if out, err := h.millCmd("", subdir, "", "init"); err != nil {
		t.Fatalf("init failed: %v\n%s", err, out)
	}
	configDir := filepath.Join(repo, ".millstrand")
	if _, err := os.Stat(filepath.Join(configDir, "config.json")); err != nil {
		t.Fatalf("expected repo .millstrand config.json: %v", err)
	}
	if _, err := os.Stat(filepath.Join(subdir, ".millstrand")); !os.IsNotExist(err) {
		t.Fatalf("did not expect nested .millstrand, stat err=%v", err)
	}
}

func TestStealthInitIsIdempotentAndLeavesNoTrackedChanges(t *testing.T) {
	repo := shortTempDir(t)
	if err := exec.Command("git", "init", "-q", repo).Run(); err != nil {
		t.Fatalf("git init repo: %v", err)
	}
	h := newHarness(t)
	realRepo, err := filepath.EvalSymlinks(repo)
	if err != nil {
		t.Fatal(err)
	}
	wantConfig := filepath.Join(realRepo, ".millstrand")
	var first map[string]any
	for attempt := 0; attempt < 2; attempt++ {
		out, err := h.millCmd("", repo, "", "init", "--stealth")
		if err != nil {
			t.Fatalf("stealth init attempt %d failed: %v\n%s", attempt+1, err, out)
		}
		var result map[string]any
		if err := json.Unmarshal([]byte(out), &result); err != nil {
			t.Fatalf("stealth result is not JSON: %v\n%s", err, out)
		}
		if len(result) != 3 || result["config_dir"] != wantConfig || result["config_file"] != filepath.Join(wantConfig, "config.json") {
			t.Fatalf("unexpected result keys/paths: %#v", result)
		}
		stealth, ok := result["stealth"].(map[string]any)
		if !ok || len(stealth) != 3 {
			t.Fatalf("unexpected stealth shape: %#v", result["stealth"])
		}
		gitExclude := stealth["git_exclude"].(map[string]any)
		claude := stealth["claude_guidance"].(map[string]any)
		codex := stealth["codex_guidance"].(map[string]any)
		if len(gitExclude) != 2 || len(claude) != 2 || len(codex) != 2 || codex["status"] != "manual-required" || codex["suggested_text"] == "" {
			t.Fatalf("unexpected action shapes: %#v", stealth)
		}
		if attempt == 0 {
			first = stealth
		} else if gitExclude["status"] != "unchanged" || claude["status"] != "unchanged" {
			t.Fatalf("second init not unchanged: first=%#v second=%#v", first, stealth)
		}
	}
	if _, err := os.Stat(filepath.Join(repo, "AGENTS.md")); !os.IsNotExist(err) {
		t.Fatalf("stealth init created AGENTS.md: %v", err)
	}
	status := exec.Command("git", "status", "--short", "--untracked-files=all")
	status.Dir = repo
	if out, err := status.CombinedOutput(); err != nil || len(out) != 0 {
		t.Fatalf("stealth init left visible changes: err=%v out=%q", err, out)
	}
}

func TestStealthInitRejectsExplicitWorkspaceBeforeContactingMill(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(shortTempDir(t), "state"))
	mill := buildMill(t)
	out, err := runBin(mill, sourceRoot(t), "", "init", "--stealth", "--workspace", shortTempDir(t))
	if err == nil || !strings.Contains(out, "--stealth cannot be combined with --workspace") || strings.Contains(out, "mill start") {
		t.Fatalf("unexpected flag conflict: err=%v out=%q", err, out)
	}
}

func TestMillRoutedStrandOpsAddListHelpAndStream(t *testing.T) {
	repo := shortTempDir(t)
	if err := exec.Command("git", "init", repo).Run(); err != nil {
		t.Fatalf("git init repo: %v", err)
	}
	t.Setenv("MILLSTRAND_SOURCE", sourceRoot(t))
	h := newHarness(t)
	if out, err := h.millCmd("", repo, "", "init"); err != nil {
		t.Fatalf("init failed: %v\n%s", err, out)
	}
	// Load the pinned streaming-op fixture from the workspace init.clj so the
	// weaver registers `test-stream` alongside the batteries ops.
	appendFixtureLoad(t, filepath.Join(repo, ".millstrand", "init.clj"))
	if out, err := h.millCmd("", repo, "", "weaver", "start"); err != nil {
		t.Fatalf("weaver start failed: %v\n%s", err, out)
	}
	h.waitForStatus(t, "", repo)

	out, err := h.strandCmd("", repo, "", "add", "hello")
	if err != nil || !strings.Contains(out, `"title":"hello"`) {
		t.Fatalf("add output/error = %q/%v", out, err)
	}
	out, err = h.strandCmd("", repo, "", "list")
	if err != nil || !strings.Contains(out, `"title":"hello"`) {
		t.Fatalf("list output/error = %q/%v", out, err)
	}

	// Live op discovery through the core help op.
	out, err = h.strandCmd("", repo, "", "help")
	if err != nil || !strings.Contains(out, `"add"`) || !strings.Contains(out, `"test-stream"`) {
		t.Fatalf("help output/error = %q/%v", out, err)
	}
	out, err = h.strandCmd("", repo, "", "help", "add")
	if err != nil || !strings.Contains(out, `"add"`) {
		t.Fatalf("help add output/error = %q/%v", out, err)
	}

	// A streaming op relayed through the full strand -> mill -> weaver chain:
	// each emitted line is relayed verbatim on stdout, the terminator is not.
	out, err = h.strandCmd("", repo, "", "test-stream", "--count", "5")
	if err != nil {
		t.Fatalf("stream op error: %v\n%s", err, out)
	}
	emitted := 0
	for _, line := range strings.Split(strings.TrimSpace(out), "\n") {
		if strings.Contains(line, `"i"`) {
			emitted++
		}
		if strings.Contains(line, "emitted") {
			t.Fatalf("terminator result leaked onto stdout: %q", out)
		}
	}
	if emitted != 5 {
		t.Fatalf("expected 5 emitted stream lines, got %d:\n%s", emitted, out)
	}

	// Unknown op fails non-zero with the weaver's available-names domain error.
	out, err = h.strandCmd("", repo, "", "no-such-op")
	if err == nil || !strings.Contains(out, "Operation not found") {
		t.Fatalf("expected unknown-op failure, err=%v out=%q", err, out)
	}

	// The same failure in JSON mode, over the whole strand -> mill -> weaver
	// chain: the envelope the weaver built reaches a CLI-over-CLI consumer as
	// structure, affordance and all (SPEC-002.C4e).
	t.Setenv("MILLSTRAND_ERROR_FORMAT", "json")
	out, err = h.strandCmd("", repo, "", "no-such-op")
	if err == nil {
		t.Fatalf("expected unknown-op failure, out=%q", out)
	}
	var envelope struct {
		Type    string         `json:"type"`
		Code    string         `json:"code"`
		Message string         `json:"message"`
		Details map[string]any `json:"details"`
	}
	if decodeErr := json.Unmarshal([]byte(strings.TrimSpace(out)), &envelope); decodeErr != nil {
		t.Fatalf("decoding %q: %v", out, decodeErr)
	}
	if envelope.Type != "domain" || !strings.Contains(envelope.Message, "Operation not found") {
		t.Fatalf("weaver-origin envelope = %#v", envelope)
	}
	if len(envelope.Details["available"].([]any)) == 0 {
		t.Fatalf("the available affordance must arrive structured: %#v", envelope.Details)
	}
	t.Setenv("MILLSTRAND_ERROR_FORMAT", "")

	if out, err := h.millCmd("", repo, "", "weaver", "stop"); err != nil {
		t.Fatalf("weaver stop failed: %v\n%s", err, out)
	}
}

func TestLinkedGitWorktreesShareDefaultWorldAndExplicitConfigDirIsolated(t *testing.T) {
	repo := shortTempDir(t)
	runGit(t, repo, "init")
	runGit(t, repo, "config", "user.email", "test@example.invalid")
	runGit(t, repo, "config", "user.name", "Test User")
	if err := os.WriteFile(filepath.Join(repo, "README.md"), []byte("test\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	runGit(t, repo, "add", "README.md")
	runGit(t, repo, "commit", "-m", "init")
	linked := filepath.Join(shortTempDir(t), "linked")
	runGit(t, repo, "worktree", "add", linked)

	t.Setenv("MILLSTRAND_SOURCE", sourceRoot(t))
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(shortTempDir(t), "config"))
	h := newHarness(t)
	if out, err := h.millCmd("", repo, "", "init"); err != nil {
		t.Fatalf("init failed: %v\n%s", err, out)
	}
	if out, err := h.millCmd("", repo, "", "weaver", "start"); err != nil {
		t.Fatalf("weaver start failed: %v\n%s", err, out)
	}
	h.waitForStatus(t, "", repo)

	mainStatus := h.weaverStatusMap(t, "", repo)
	linkedStatus := h.weaverStatusMap(t, "", linked)
	for _, key := range []string{"config_dir", "state_dir", "data_dir", "weaver_id"} {
		if mainStatus[key] == "" || mainStatus[key] != linkedStatus[key] {
			t.Fatalf("linked worktree default status mismatch for %s: main=%#v linked=%#v", key, mainStatus, linkedStatus)
		}
	}
	wantConfig, err := filepath.EvalSymlinks(filepath.Join(repo, ".millstrand"))
	if err != nil {
		t.Fatal(err)
	}
	if mainStatus["config_dir"] != wantConfig {
		t.Fatalf("default config_dir = %q, want canonical repo world %q", mainStatus["config_dir"], wantConfig)
	}

	created := h.addJSON(t, "", repo, "shared from main", "owner=linked-worktree-test")
	out, err := h.strandCmd("", linked, "", "list")
	if err != nil || !strings.Contains(out, created) || !strings.Contains(out, `"title":"shared from main"`) {
		t.Fatalf("linked list output/error = %q/%v", out, err)
	}

	explicit := filepath.Join(shortTempDir(t), "explicit-world")
	if out, err := h.millCmd(explicit, linked, "", "init"); err != nil {
		t.Fatalf("explicit init failed: %v\n%s", err, out)
	}
	if out, err := h.millCmd(explicit, linked, "", "weaver", "start"); err != nil {
		t.Fatalf("explicit weaver start failed: %v\n%s", err, out)
	}
	explicitStatus := h.weaverStatusMap(t, explicit, linked)
	for _, key := range []string{"config_dir", "state_dir", "data_dir", "weaver_id"} {
		if explicitStatus[key] == "" || explicitStatus[key] == mainStatus[key] {
			t.Fatalf("explicit --workspace was not isolated for %s: default=%#v explicit=%#v", key, mainStatus, explicitStatus)
		}
	}
	out, err = h.strandCmd(explicit, linked, "", "list")
	if err != nil {
		t.Fatalf("explicit list failed: %v\n%s", err, out)
	}
	if strings.Contains(out, created) || strings.Contains(out, "shared from main") {
		t.Fatalf("explicit world unexpectedly saw default-world strand: %s", out)
	}

	if out, err := h.millCmd(explicit, linked, "", "weaver", "stop"); err != nil {
		t.Fatalf("explicit weaver stop failed: %v\n%s", err, out)
	}
	if out, err := h.millCmd("", linked, "", "weaver", "stop"); err != nil {
		t.Fatalf("default weaver stop failed: %v\n%s", err, out)
	}
}

func shortTempDir(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("/tmp", "td-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	return dir
}

func buildStrand(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(shortTempDir(t), "strand")
	cmd := exec.Command("go", "build", "-o", bin, "./cmd/strand")
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		t.Fatalf("build strand: %v\n%s", err, out.String())
	}
	return bin
}

func buildMill(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(shortTempDir(t), "mill")
	cmd := exec.Command("go", "build", "-o", bin, "./cmd/mill")
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		t.Fatalf("build mill: %v\n%s", err, out.String())
	}
	return bin
}

func mustReadFile(t *testing.T, path string) []byte {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

// appendFixtureLoad appends a load-file of the pinned streaming-op fixture to an
// existing workspace init.clj so the started weaver registers `test-stream`.
func appendFixtureLoad(t *testing.T, initPath string) {
	t.Helper()
	fixture, err := filepath.Abs(filepath.Join("..", "test", "fixtures", "stream-op-init.clj"))
	if err != nil {
		t.Fatal(err)
	}
	f, err := os.OpenFile(initPath, os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	if _, err := f.WriteString("\n(load-file \"" + fixture + "\")\n"); err != nil {
		t.Fatal(err)
	}
}

func (h harness) addJSON(t *testing.T, workspace, cwd, title, attr string) string {
	t.Helper()
	out, err := h.strandCmd(workspace, cwd, "", "add", title, "--attr", attr)
	if err != nil {
		t.Fatalf("add %s: %v\n%s", title, err, out)
	}
	var row map[string]any
	if err := json.Unmarshal([]byte(out), &row); err != nil {
		t.Fatalf("add output is not json: %v\n%s", err, out)
	}
	id, ok := row["id"].(string)
	if !ok || id == "" {
		t.Fatalf("add output missing id: %#v", row)
	}
	return id
}

func writeClientConfig(t *testing.T, dir string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
		t.Fatal(err)
	}
}

func (h harness) weaverStatusMap(t *testing.T, workspace, cwd string) map[string]any {
	t.Helper()
	out, err := h.millCmd(workspace, cwd, "", "weaver", "status")
	if err != nil {
		t.Fatalf("weaver status failed: %v\n%s", err, out)
	}
	var raw map[string]any
	if err := json.Unmarshal([]byte(out), &raw); err != nil {
		t.Fatalf("status is not json: %v\n%s", err, out)
	}
	return raw
}

func runGit(t *testing.T, dir string, args ...string) {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v failed: %v\n%s", args, err, out)
	}
}

func sourceRoot(t *testing.T) string {
	t.Helper()
	source, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	return source
}

func (h harness) waitForStatus(t *testing.T, workspace, cwd string) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	var lastErr error
	var lastOut string
	for time.Now().Before(deadline) {
		out, err := h.millCmd(workspace, cwd, "", "weaver", "status")
		if err == nil {
			var status map[string]any
			if json.Unmarshal([]byte(out), &status) == nil && status["state"] == "running" {
				return
			}
			lastOut = out
		} else {
			lastErr = err
			lastOut = out
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("weaver did not become ready: %v\nlast status: %s", lastErr, lastOut)
}

func (h harness) waitForNotRunning(t *testing.T, workspace, cwd string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	var lastOut string
	for time.Now().Before(deadline) {
		out, err := h.millCmd(workspace, cwd, "", "weaver", "status")
		lastOut = out
		if err == nil {
			var status map[string]any
			if json.Unmarshal([]byte(out), &status) == nil && status["state"] != "running" {
				return
			}
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("weaver still running after stop; last status: %s", lastOut)
}
