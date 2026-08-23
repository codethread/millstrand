package main

import (
	"bytes"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

func TestWeaverLifecycleWithFakeLauncher(t *testing.T) {
	var logs bytes.Buffer
	origLogOut := millLogOut
	millLogOut = &logs
	t.Cleanup(func() { millLogOut = origLogOut })
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	req := client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}

	orig := launchWeaver
	var launches int
	var launchedSource string
	var launchedArgs []string
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		launches++
		launchedSource = source
		launchedArgs = append([]string(nil), args...)
		if out == io.Discard || errOut == io.Discard {
			t.Fatalf("weaver child output should be captured in the weaver log, not discarded")
		}
		_, _ = io.WriteString(out, "probe: child stdout line\n")
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		world, err := config.RuntimeWorld(cfg)
		if err != nil {
			t.Fatal(err)
		}
		writeWeaverMetadata(t, world, cmd.Process.Pid, "weaver-one")
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = orig })

	s := server{children: map[string]*weaverChild{}}
	status, err := s.startWeaver(req)
	if err != nil {
		t.Fatal(err)
	}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if launchedSource != source || !reflect.DeepEqual(launchedArgs, weaverArgs(world, filepath.Base(world.ConfigDir))) {
		t.Fatalf("unexpected launch source/args: source=%q args=%#v", launchedSource, launchedArgs)
	}
	if status["state"] != "running" || status["pid"] == nil || status["weaver_id"] != "weaver-one" || status["socket_path"] == nil || status["nrepl"] == nil {
		t.Fatalf("running status missing required fields: %#v", status)
	}
	if status["config_dir"] == "" || status["state_dir"] == "" || status["data_dir"] == "" || status["database_path"] == "" {
		t.Fatalf("running status missing identity/path fields: %#v", status)
	}
	logPath := filepath.Join(world.StateDir, "weaver.log")
	if status["log_path"] != logPath {
		t.Fatalf("running status should carry the weaver log path, got %#v", status["log_path"])
	}
	if b, err := os.ReadFile(logPath); err != nil || !strings.Contains(string(b), "probe: child stdout line") {
		t.Fatalf("weaver log should capture child output, err=%v content=%q", err, b)
	}
	status, err = s.startWeaver(req)
	if err != nil {
		t.Fatal(err)
	}
	if launches != 1 || status["weaver_id"] != "weaver-one" {
		t.Fatalf("second start should be idempotent after identity check launches=%d status=%#v", launches, status)
	}
	status, err = s.weaverStatus(req)
	if err != nil || status["state"] != "running" {
		t.Fatalf("bad status %#v err=%v", status, err)
	}
	stoppedPID := status["pid"].(int)
	status, err = s.stopWeaver(req)
	if err != nil || status["state"] != "stopped" {
		t.Fatalf("bad stop %#v err=%v", status, err)
	}
	if processAlive(stoppedPID) {
		t.Fatalf("stop should terminate selected child pid %d", stoppedPID)
	}
	status, err = s.weaverStatus(req)
	if err != nil || status["state"] == "running" || status["state"] == "stale" {
		t.Fatalf("bad post-stop status %#v err=%v", status, err)
	}
	if _, err := os.Stat(filepath.Join(world.StateDir, "weaver.json")); !os.IsNotExist(err) {
		t.Fatalf("stop should remove weaver.json, stat err=%v", err)
	}
	logText := logs.String()
	for _, want := range []string{"weaver started config_dir=" + world.ConfigDir, "weaver stopped config_dir=" + world.ConfigDir, "pid=" + intString(stoppedPID)} {
		if !strings.Contains(logText, want) {
			t.Fatalf("missing log %q in:\n%s", want, logText)
		}
	}
	if strings.Contains(logText, "weaver running config_dir=") {
		t.Fatalf("idempotent start should not log a lifecycle transition:\n%s", logText)
	}
	if strings.Contains(logText, "probe: child stdout line") {
		t.Fatalf("weaver child output should not be forwarded to mill logs:\n%s", logText)
	}
}

func TestWeaverListIncludesSupervisedAndMetadataDiscovered(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfgA := tempConfig(t, source)
	cfgB := tempConfig(t, source)
	worldA, err := config.RuntimeWorld(cfgA)
	if err != nil {
		t.Fatal(err)
	}
	worldB, err := config.RuntimeWorld(cfgB)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _, _ = cmd.Process.Wait() })
	writeWeaverMetadata(t, worldA, cmd.Process.Pid, "weaver-a")
	writeWeaverMetadata(t, worldB, os.Getpid(), "weaver-b")
	s := server{children: map[string]*weaverChild{worldA.ConfigDir: {cmd: cmd, world: worldA, done: make(chan error, 1)}}}
	rows, err := s.weaverList()
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 {
		t.Fatalf("expected two weavers, got %#v", rows)
	}
	byID := map[any]map[string]any{}
	for _, row := range rows {
		byID[row["weaver_id"]] = row
	}
	for _, id := range []string{"weaver-a", "weaver-b"} {
		row := byID[id]
		if row == nil || row["state"] != "running" || row["name"] == "" || row["config_dir"] == "" || row["state_dir"] == "" || row["data_dir"] == "" || row["database_path"] == "" || row["socket_path"] == "" || row["nrepl"] == nil || row["started_at"] == "" {
			t.Fatalf("bad list row for %s: %#v", id, row)
		}
	}
}

func TestFriendlyNameUsesConfigNameAndLocalOverlay(t *testing.T) {
	source := tempSource(t)
	cfg := tempConfig(t, source)
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha","name":"shared"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	name, err := friendlyName(world, "")
	if err != nil || name != "shared" {
		t.Fatalf("config name should win over basename, got name=%q err=%v", name, err)
	}
	if err := os.WriteFile(filepath.Join(cfg, "config.local.json"), []byte(`{"name":"local"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	name, err = friendlyName(world, "")
	if err != nil || name != "local" {
		t.Fatalf("local overlay should win, got name=%q err=%v", name, err)
	}
	name, err = friendlyName(world, "explicit")
	if err != nil || name != "explicit" {
		t.Fatalf("explicit name should win, got name=%q err=%v", name, err)
	}
}

func TestFriendlyNameFallsBackToBasename(t *testing.T) {
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	name, err := friendlyName(world, "")
	if err != nil || name != filepath.Base(world.ConfigDir) {
		t.Fatalf("basename fallback failed, got name=%q err=%v", name, err)
	}
}

func TestFriendlyNameRejectsWhitespaceOnly(t *testing.T) {
	world := config.World{ConfigDir: filepath.Join(t.TempDir(), "cfg")}
	if _, err := friendlyName(world, " \t\n"); err == nil || !strings.Contains(err.Error(), "must not be blank") {
		t.Fatalf("expected blank name error, got %v", err)
	}
}

func TestStartPassesConfiguredNameToWeaverMetadata(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha","name":"shop-fe"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	orig := launchWeaver
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		world, err := config.RuntimeWorld(configDirArg(args))
		if err != nil {
			t.Fatal(err)
		}
		writeWeaverMetadataWithName(t, world, cmd.Process.Pid, "weaver-shop-fe", nameArg(args))
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	status, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg})
	if err != nil {
		t.Fatal(err)
	}
	if status["name"] != "shop-fe" {
		t.Fatalf("configured name not published in metadata: %#v", status)
	}
	s.stopAll()
}

func TestValidateMetadataRejectsWhitespaceOnlyName(t *testing.T) {
	world := config.World{ConfigDir: filepath.Join(t.TempDir(), "cfg")}
	world.StateDir = filepath.Join(t.TempDir(), "state")
	world.DataDir = filepath.Join(t.TempDir(), "data")
	world.DBPath = filepath.Join(world.DataDir, "millstrand.sqlite")
	m := client.Metadata{ProtocolVersion: client.ProtocolVersion, PID: os.Getpid(), DatabaseKind: "sqlite-file", DatabaseLabel: world.DBPath, DatabasePath: &world.DBPath, DaemonID: "weaver", ConfigDir: world.ConfigDir, StateDir: world.StateDir, DataDir: world.DataDir, Name: "   ", SocketPath: filepath.Join(world.StateDir, "weaver.sock"), StartedAt: "now"}
	m.NREPL.Host = "127.0.0.1"
	m.NREPL.Port = 5555
	if got := validateMetadata(world, m); got != "malformed weaver metadata: missing required fields" {
		t.Fatalf("expected malformed metadata for blank name, got %q", got)
	}
}

func TestResolveLaunchSourcePrecedence(t *testing.T) {
	envSource := tempSource(t)
	installedSource := tempSource(t)
	cwdSource := tempMillstrandCheckout(t)
	origInstalled := config.InstalledSource
	config.InstalledSource = installedSource
	t.Cleanup(func() { config.InstalledSource = origInstalled })
	t.Setenv("MILLSTRAND_SOURCE", envSource)
	resolved, err := resolveLaunchSource(cwdSource)
	if err != nil || resolved != envSource {
		t.Fatalf("MILLSTRAND_SOURCE should win, got source=%q err=%v", resolved, err)
	}
	t.Setenv("MILLSTRAND_SOURCE", "")
	resolved, err = resolveLaunchSource(cwdSource)
	if err != nil || resolved != installedSource {
		t.Fatalf("installed source should win, got source=%q err=%v", resolved, err)
	}
	missingInstalled := filepath.Join(t.TempDir(), "missing")
	config.InstalledSource = missingInstalled
	var diag bytes.Buffer
	origDiag := sourceDiagOut
	sourceDiagOut = &diag
	resolved, err = resolveLaunchSource(cwdSource)
	sourceDiagOut = origDiag
	if err != nil || resolved != cwdSource {
		t.Fatalf("missing installed source should fall back to cwd, got source=%q err=%v", resolved, err)
	}
	// The bypass of an unusable installed source must not be silent: the operator
	// needs to see which checkout actually launched.
	warn := diag.String()
	if !strings.Contains(warn, missingInstalled) || !strings.Contains(warn, cwdSource) || !strings.Contains(warn, "warning") {
		t.Fatalf("expected bypass warning naming configured source and cwd fallback, got %q", warn)
	}

	config.InstalledSource = ""
	resolved, err = resolveLaunchSource(cwdSource)
	if err != nil || resolved != cwdSource {
		t.Fatalf("Millstrand checkout cwd should win, got source=%q err=%v", resolved, err)
	}
}

func TestResolveLaunchSourceUnusableInstalledAndNoCheckoutFailsLoud(t *testing.T) {
	origInstalled := config.InstalledSource
	config.InstalledSource = filepath.Join(t.TempDir(), "missing-install")
	t.Cleanup(func() { config.InstalledSource = origInstalled })
	t.Setenv("MILLSTRAND_SOURCE", "")
	// A cwd that is not a Millstrand checkout: neither source resolves, so the loud
	// combined error must still cite the dropped installed-source failure.
	_, err := resolveLaunchSource(t.TempDir())
	if err == nil || !strings.Contains(err.Error(), "installed source is unusable") || !strings.Contains(err.Error(), "not a canonical Millstrand checkout") {
		t.Fatalf("expected combined failure preserving installed-source error, got %v", err)
	}
}

func TestStartFailsBeforeLaunchWhenSourceCannotResolve(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	t.Setenv("MILLSTRAND_SOURCE", "")
	origInstalled := config.InstalledSource
	config.InstalledSource = ""
	t.Cleanup(func() { config.InstalledSource = origInstalled })
	cfg := tempConfigWithoutSource(t)
	orig := launchWeaver
	launched := false
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		launched = true
		return orig(source, args, out, errOut)
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	_, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg})
	if err == nil || !strings.Contains(err.Error(), "MILLSTRAND_SOURCE") || !strings.Contains(err.Error(), "install-time source") || !strings.Contains(err.Error(), "canonical Millstrand checkout cwd") {
		t.Fatalf("expected actionable source failure, got %v", err)
	}
	if launched {
		t.Fatal("start should not launch without a resolved source")
	}
}

func TestWeaverReplContextAddsSourceWithoutPublicStatusIdentity(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	req := client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _, _ = cmd.Process.Wait() })
	writeWeaverMetadata(t, world, cmd.Process.Pid, "weaver-repl")
	s := server{children: map[string]*weaverChild{}}
	status, err := s.weaverStatus(req)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := status["source"]; ok {
		t.Fatalf("public status must not expose source: %#v", status)
	}
	context, err := s.weaverReplContext(req)
	if err != nil {
		t.Fatal(err)
	}
	if context["state"] != "running" || context["source"] != source || context["state_dir"] == "" {
		t.Fatalf("unexpected repl context: %#v", context)
	}
}

func TestWeaverStatusDistinguishesStaleMetadata(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	req := client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{bad`), 0o644); err != nil {
		t.Fatal(err)
	}
	s := server{children: map[string]*weaverChild{}}
	status, err := s.weaverStatus(req)
	if err != nil || status["state"] != "stale" || status["stale_reason"] == nil {
		t.Fatalf("expected malformed stale status, got %#v err=%v", status, err)
	}
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{"protocol_version":3,"pid":1,"database_kind":"sqlite-file","database_label":"/wrong","database_path":"/wrong","weaver_id":"wrong","config_dir":"/wrong","state_dir":"/wrong","data_dir":"/wrong","name":"wrong","socket_path":"/wrong","started_at":"now","nrepl":{"host":"127.0.0.1","port":1}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	status, err = s.weaverStatus(req)
	if err != nil || status["state"] != "stale" || status["stale_reason"] != "weaver metadata identity mismatch" {
		t.Fatalf("expected identity stale status, got %#v err=%v", status, err)
	}
	writeWeaverMetadata(t, world, 999999, "dead-weaver")
	status, err = s.weaverStatus(req)
	if err != nil || status["state"] != "stale" || !strings.Contains(status["stale_reason"].(string), "not alive") {
		t.Fatalf("expected dead-pid stale status, got %#v err=%v", status, err)
	}
}

func TestStartFailsLoudlyOnStaleMetadata(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{bad`), 0o644); err != nil {
		t.Fatal(err)
	}
	orig := launchWeaver
	launched := false
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		launched = true
		return orig(source, args, out, errOut)
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	if _, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}); err == nil || !strings.Contains(err.Error(), "stale weaver metadata") {
		t.Fatalf("expected stale metadata start failure, got %v", err)
	}
	if launched {
		t.Fatal("start should not launch through stale metadata")
	}
}

func TestStopCleansStaleMetadata(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	req := client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{bad`), 0o644); err != nil {
		t.Fatal(err)
	}
	s := server{children: map[string]*weaverChild{}}
	status, err := s.stopWeaver(req)
	if err != nil || status["state"] != "stopped" {
		t.Fatalf("bad stale stop %#v err=%v", status, err)
	}
	if _, err := os.Stat(filepath.Join(world.StateDir, "weaver.json")); !os.IsNotExist(err) {
		t.Fatalf("stale stop should remove weaver.json, stat err=%v", err)
	}
}

func TestStopSignalsNonSupervisedWeaverByPID(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", shortStateHome(t))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	// A real metadata-discovered weaver is not this mill's child, so the OS
	// reaps it on exit. Mirror that by reaping here; otherwise the killed sleep
	// lingers as an unreaped zombie and kill(0) still reports it alive.
	reaped := make(chan struct{})
	go func() { _, _ = cmd.Process.Wait(); close(reaped) }()
	t.Cleanup(func() { _ = cmd.Process.Kill(); <-reaped })
	identity := weaverIdentity{
		PID: cmd.Process.Pid, WeaverID: "unsupervised-weaver", StartedAt: "2026-08-23T15:00:00Z",
		Socket: filepath.Join(world.StateDir, "weaver.sock"), ConfigDir: world.ConfigDir,
		StateDir: world.StateDir, DataDir: world.DataDir,
	}
	serveIdentityEndpoint(t, identity, nil)
	writeWeaverMetadataForIdentity(t, world, identity, "unsupervised-weaver")

	// No child handle: the weaver is metadata-discovered, so stop must signal it
	// by pid (the socket stop op no longer exists) and then clean its metadata.
	s := server{children: map[string]*weaverChild{}}
	status, err := s.stopWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg})
	if err != nil || status["state"] != "stopped" || status["pid"].(int) != cmd.Process.Pid {
		t.Fatalf("bad unsupervised stop %#v err=%v", status, err)
	}
	if processAlive(cmd.Process.Pid) {
		t.Fatalf("unsupervised stop should terminate pid %d", cmd.Process.Pid)
	}
	if _, err := os.Stat(filepath.Join(world.StateDir, "weaver.json")); !os.IsNotExist(err) {
		t.Fatalf("unsupervised stop should remove weaver.json, stat err=%v", err)
	}
}

func TestStopRefusesUnsupervisedWeaverWithoutEndpointProof(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", shortStateHome(t))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	reaped := make(chan struct{})
	go func() { _, _ = cmd.Process.Wait(); close(reaped) }()
	t.Cleanup(func() { _ = cmd.Process.Kill(); <-reaped })
	identity := weaverIdentity{
		PID: cmd.Process.Pid, WeaverID: "unsupervised-no-endpoint", StartedAt: "2026-08-23T15:01:00Z",
		Socket: filepath.Join(world.StateDir, "weaver.sock"), ConfigDir: world.ConfigDir,
		StateDir: world.StateDir, DataDir: world.DataDir,
	}
	writeWeaverMetadataForIdentity(t, world, identity, "unsupervised-no-endpoint")

	s := server{children: map[string]*weaverChild{}}
	if _, err := s.stopWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}); err == nil || !strings.Contains(err.Error(), "endpoint identity failed") {
		t.Fatalf("expected endpoint-proof failure, got %v", err)
	}
	if !processAlive(cmd.Process.Pid) {
		t.Fatal("missing endpoint proof signalled the unsupervised process")
	}
}

func TestStopRefusesUnsupervisedWeaverWithMismatchedEndpointProof(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", shortStateHome(t))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	reaped := make(chan struct{})
	go func() { _, _ = cmd.Process.Wait(); close(reaped) }()
	t.Cleanup(func() { _ = cmd.Process.Kill(); <-reaped })
	identity := weaverIdentity{
		PID: cmd.Process.Pid, WeaverID: "unsupervised-mismatch", StartedAt: "2026-08-23T15:02:00Z",
		Socket: filepath.Join(world.StateDir, "weaver.sock"), ConfigDir: world.ConfigDir,
		StateDir: world.StateDir, DataDir: world.DataDir,
	}
	serveIdentityEndpoint(t, identity, map[string]any{"pid": identity.PID + 1})
	writeWeaverMetadataForIdentity(t, world, identity, "unsupervised-mismatch")

	s := server{children: map[string]*weaverChild{}}
	if _, err := s.stopWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}); err == nil || !strings.Contains(err.Error(), "pid mismatch") {
		t.Fatalf("expected endpoint identity mismatch, got %v", err)
	}
	if !processAlive(cmd.Process.Pid) {
		t.Fatal("mismatched endpoint proof signalled the unsupervised process")
	}
}

func TestDifferentReposHaveDistinctRuntimeDirsAndStopSelectedOnly(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfgA := tempConfig(t, source)
	cfgB := tempConfig(t, source)
	orig := launchWeaver
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		world, err := config.RuntimeWorld(configDirArg(args))
		if err != nil {
			t.Fatal(err)
		}
		writeWeaverMetadata(t, world, cmd.Process.Pid, "weaver-"+filepath.Base(world.ConfigDir))
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	stA, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfgA})
	if err != nil {
		t.Fatal(err)
	}
	stB, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfgB})
	if err != nil {
		t.Fatal(err)
	}
	if stA["state_dir"] == stB["state_dir"] || stA["data_dir"] == stB["data_dir"] {
		t.Fatalf("different config worlds share dirs: A=%#v B=%#v", stA, stB)
	}
	if _, err := s.stopWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfgA}); err != nil {
		t.Fatal(err)
	}
	worldB, err := config.RuntimeWorld(cfgB)
	if err != nil {
		t.Fatal(err)
	}
	if child := s.children[worldB.ConfigDir]; child == nil || !processAlive(child.cmd.Process.Pid) {
		t.Fatalf("stopping A should not stop B: %#v", s.children)
	}
	s.stopAll()
}

func TestCleanupPreviousMillStateRemovesStaleSocketsAndWeaverMetadata(t *testing.T) {
	root := t.TempDir()
	socketPath := filepath.Join(root, config.MillSocketFileName)
	metadataPath := filepath.Join(root, config.MillMetadataFileName)
	if err := os.WriteFile(socketPath, []byte("stale"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(metadataPath, []byte(`{"protocol_version":3,"pid":999999,"mill_id":"old","state_root":"`+root+`","socket_path":"`+socketPath+`","started_at":"now"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	stateDir := filepath.Join(root, "weavers", "abc")
	if err := os.MkdirAll(stateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stateDir, "weaver.json"), []byte(`{"protocol_version":3,"pid":999999}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stateDir, "weaver.sock"), []byte("stale"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := cleanupPreviousMillState(root, socketPath, metadataPath); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{socketPath, metadataPath, filepath.Join(stateDir, "weaver.json"), filepath.Join(stateDir, "weaver.sock")} {
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("expected stale artifact removed: %s stat=%v", path, err)
		}
	}
}

func TestStartFailsWhenWeaverExitsBeforeReadyMetadata(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	orig := launchWeaver
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("false")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	_, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg})
	if err == nil || !strings.Contains(err.Error(), "before publishing ready metadata") {
		t.Fatalf("expected ready metadata failure, got %v", err)
	}
	if !strings.Contains(err.Error(), "weaver log") {
		t.Fatalf("startup failure should point at the weaver log, got %v", err)
	}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if child := s.children[world.ConfigDir]; child != nil {
		t.Fatalf("failed startup should remove child handle: %#v", child)
	}
}

func TestReadyTimeoutParsing(t *testing.T) {
	ms, err := parseReadyTimeout("1500ms")
	if err != nil || ms != 1500 {
		t.Fatalf("expected 1500ms, got ms=%d err=%v", ms, err)
	}
	if ms, err := parseReadyTimeout(""); err != nil || ms != 0 {
		t.Fatalf("empty ready timeout should use default, got ms=%d err=%v", ms, err)
	}
	if _, err := parseReadyTimeout("nope"); err == nil || !strings.Contains(err.Error(), "invalid --ready-timeout") {
		t.Fatalf("expected invalid duration error, got %v", err)
	}
	if _, err := parseReadyTimeout("0s"); err == nil || !strings.Contains(err.Error(), "positive duration") {
		t.Fatalf("expected positive duration error, got %v", err)
	}
	if _, err := parseReadyTimeout("500us"); err == nil || !strings.Contains(err.Error(), "at least 1ms") {
		t.Fatalf("expected minimum duration error, got %v", err)
	}
}

func TestStartUsesConfiguredReadyTimeout(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	started := make(chan int, 1)
	orig := launchWeaver
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		started <- cmd.Process.Pid
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	_, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg, ReadyTimeoutMs: 25})
	if err == nil || !strings.Contains(err.Error(), "before timeout") {
		t.Fatalf("expected ready timeout error, got %v", err)
	}
	pid := <-started
	if processAlive(pid) {
		waitForPIDExit(pid, time.Second)
		if processAlive(pid) {
			t.Fatalf("timed-out weaver pid %d still alive", pid)
		}
	}
}

func TestStartRejectsNegativeReadyTimeout(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	cfg := tempConfig(t, tempSource(t))
	s := server{children: map[string]*weaverChild{}}
	_, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg, ReadyTimeoutMs: -1})
	if err == nil || !strings.Contains(err.Error(), "invalid ready_timeout_ms") {
		t.Fatalf("expected invalid ready_timeout_ms error, got %v", err)
	}
}

func TestReleaseChildOnlyRemovesOwner(t *testing.T) {
	owner := &weaverChild{}
	replacement := &weaverChild{}
	s := server{children: map[string]*weaverChild{"world": replacement}}
	if s.releaseChild("world", owner) {
		t.Fatal("release by a superseded start must not claim its successor's entry")
	}
	if s.children["world"] != replacement {
		t.Fatal("successor supervision entry was removed")
	}
	if !s.releaseChild("world", replacement) {
		t.Fatal("owner release should remove the entry and report ownership")
	}
	if _, ok := s.children["world"]; ok {
		t.Fatal("owner release left the entry behind")
	}
}

func configDirArg(args []string) string {
	for i, arg := range args {
		if arg == "--workspace" && i+1 < len(args) {
			return args[i+1]
		}
	}
	return ""
}

func nameArg(args []string) string {
	for i, arg := range args {
		if arg == "--name" && i+1 < len(args) {
			return args[i+1]
		}
	}
	return ""
}

func tempSource(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "deps.edn"), []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

func tempMillstrandCheckout(t *testing.T) string {
	t.Helper()
	dir := tempSource(t)
	cmd := exec.Command("git", "init")
	cmd.Dir = dir
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git init failed: %v\n%s", err, out)
	}
	real, err := filepath.EvalSymlinks(dir)
	if err != nil {
		t.Fatal(err)
	}
	return real
}

func tempConfig(t *testing.T, source string) string {
	t.Helper()
	t.Setenv("MILLSTRAND_SOURCE", source)
	return tempConfigWithoutSource(t)
}

func tempConfigWithoutSource(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	real, err := filepath.EvalSymlinks(dir)
	if err != nil {
		t.Fatal(err)
	}
	return real
}

func writeWeaverMetadata(t *testing.T, world config.World, pid int, id string) {
	t.Helper()
	writeWeaverMetadataWithName(t, world, pid, id, filepath.Base(world.ConfigDir))
}

func writeWeaverMetadataWithName(t *testing.T, world config.World, pid int, id string, name string) {
	t.Helper()
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	meta := `{"protocol_version":3,"pid":` + intString(pid) + `,"database_kind":"sqlite-file","database_label":"` + world.DBPath + `","database_path":"` + world.DBPath + `","weaver_id":"` + id + `","config_dir":"` + world.ConfigDir + `","state_dir":"` + world.StateDir + `","data_dir":"` + world.DataDir + `","name":"` + name + `","socket_path":"` + filepath.Join(world.StateDir, "weaver.sock") + `","started_at":"` + time.Now().UTC().Format(time.RFC3339Nano) + `","nrepl":{"host":"127.0.0.1","port":5555}}`
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(meta), 0o644); err != nil {
		t.Fatal(err)
	}
}

func intString(i int) string { return strconv.Itoa(i) }
