package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/process"
)

type lockedLogBuffer struct {
	mu sync.Mutex
	bytes.Buffer
}

func (b *lockedLogBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.Buffer.Write(p)
}

func (b *lockedLogBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.Buffer.String()
}

func newAutostartTestServer() *server {
	return &server{
		meta:        client.MillMetadata{ProtocolVersion: client.MillProtocolVersion, MillID: "mill-test"},
		children:    map[string]*weaverChild{},
		custodies:   map[string]*process.Custody{},
		transitions: map[string]*weaverTransition{},
		startClaims: map[string]chan struct{}{},
		shutdown:    make(chan struct{}),
	}
}

func writeAutoStartConfig(t *testing.T, contents string) (config.World, string) {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, config.ConfigFileName), []byte(contents), 0o644); err != nil {
		t.Fatal(err)
	}
	world, err := config.RuntimeWorld(dir)
	if err != nil {
		t.Fatal(err)
	}
	return world, dir
}

func waitForAutostartLaunch(t *testing.T, launches <-chan autostartLaunch, want int) []autostartLaunch {
	t.Helper()
	got := make([]autostartLaunch, 0, want)
	for len(got) < want {
		select {
		case launch := <-launches:
			got = append(got, launch)
		case <-time.After(5 * time.Second):
			t.Fatalf("timed out waiting for autostart launch %d/%d", len(got)+1, want)
		}
	}
	return got
}

type autostartLaunch struct {
	configDir string
	release   chan struct{}
	cmd       *exec.Cmd
}

func TestAutostartQueueLimitsConcurrentStartsAndContinuesAfterFailure(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	t.Setenv("MILLSTRAND_SOURCE", tempSource(t))

	worlds := make([]config.World, 0, autostartSlots+2)
	for i := 0; i < autostartSlots+2; i++ {
		world, cwd := writeAutoStartConfig(t, `{"configFormat":"alpha","autoStart":true}`)
		if err := registerAutoStart(world, cwd, "remembered-"+intString(i)); err != nil {
			t.Fatal(err)
		}
		worlds = append(worlds, world)
	}

	var logs lockedLogBuffer
	originalLogOut := millLogOut
	millLogOut = &logs
	t.Cleanup(func() { millLogOut = originalLogOut })

	s := newAutostartTestServer()
	t.Cleanup(func() {
		s.signalShutdown()
		s.stopAutostart()
		_ = s.stopAll()
	})
	launches := make(chan autostartLaunch, len(worlds))
	failureConfig := worlds[0].ConfigDir
	var launchMu sync.Mutex
	active, peak := 0, 0
	originalLaunch := launchWeaver
	launchWeaver = func(_ string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		configDir := configDirArg(args)
		release := make(chan struct{})
		launchMu.Lock()
		active++
		if active > peak {
			peak = active
		}
		launchMu.Unlock()
		launches <- autostartLaunch{configDir: configDir, release: release}
		select {
		case <-release:
		case <-s.shutdown:
		}
		launchMu.Lock()
		active--
		launchMu.Unlock()
		if configDir == failureConfig {
			return nil, errors.New("injected autostart launch failure")
		}
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		world, err := config.RuntimeWorld(configDir)
		if err != nil {
			return nil, err
		}
		writeWeaverMetadata(t, world, cmd.Process.Pid, "autostart-"+filepath.Base(configDir))
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = originalLaunch })

	s.startAutostart()
	first := waitForAutostartLaunch(t, launches, autostartSlots)
	for _, launch := range first {
		close(launch.release)
	}
	later := waitForAutostartLaunch(t, launches, len(worlds)-autostartSlots)
	for _, launch := range later {
		close(launch.release)
	}
	s.autostartWG.Wait()

	launchMu.Lock()
	gotPeak := peak
	launchMu.Unlock()
	if gotPeak > autostartSlots {
		t.Fatalf("autostart exceeded concurrency limit: peak=%d limit=%d", gotPeak, autostartSlots)
	}
	seen := make(map[string]bool, len(worlds))
	for _, launch := range append(first, later...) {
		seen[launch.configDir] = true
	}
	for _, world := range worlds {
		if !seen[world.ConfigDir] {
			t.Fatalf("registered world was never attempted: %s", world.ConfigDir)
		}
	}
	if len(s.children) != len(worlds)-1 {
		t.Fatalf("launch failure stopped successful autostarts: children=%d want=%d", len(s.children), len(worlds)-1)
	}
	if !strings.Contains(logs.String(), "Starting weaver automatically for ") {
		t.Fatalf("missing prelaunch autostart log in %q", logs.String())
	}
}

func TestAutostartRegistrationRequiresExplicitOptInAcrossFreshServers(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	t.Setenv("MILLSTRAND_SOURCE", tempSource(t))

	for _, tc := range []struct {
		name   string
		config string
	}{
		{name: "false", config: `{"configFormat":"alpha","autoStart":false}`},
		{name: "omitted", config: `{"configFormat":"alpha"}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			world, cwd := writeAutoStartConfig(t, tc.config)
			if err := registerAutoStart(world, cwd, tc.name); err != nil {
				t.Fatal(err)
			}
			first := newAutostartTestServer()
			first.startAutostart()
			first.autostartWG.Wait()
			first.signalShutdown()
			second := newAutostartTestServer()
			second.startAutostart()
			second.autostartWG.Wait()
			second.signalShutdown()
			path, err := autostartPath(world.ConfigDir)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(path); !os.IsNotExist(err) {
				t.Fatalf("%s registration survived fresh-server pruning: %v", tc.name, err)
			}
		})
	}

	world, cwd := writeAutoStartConfig(t, `{"configFormat":"alpha","autoStart":true}`)
	path, err := autostartPath(world.ConfigDir)
	if err != nil {
		t.Fatal(err)
	}
	first := newAutostartTestServer()
	first.startAutostart()
	first.autostartWG.Wait()
	first.signalShutdown()
	second := newAutostartTestServer()
	second.startAutostart()
	second.autostartWG.Wait()
	second.signalShutdown()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("autoStart=true without explicit registration created a marker: %v", err)
	}

	s := newAutostartTestServer()
	t.Cleanup(func() { _ = s.stopAll() })
	originalLaunch := launchWeaver
	launchWeaver = func(_ string, args []string, _ io.Writer, _ io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		startedWorld, err := config.RuntimeWorld(configDirArg(args))
		if err != nil {
			return nil, err
		}
		writeWeaverMetadata(t, startedWorld, cmd.Process.Pid, "explicit-restored")
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = originalLaunch })
	response := callMillRequest(t, s, client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion,
		RequestID:       "explicit-reregister",
		MillID:          s.meta.MillID,
		Operation:       "weaver-start",
		World:           client.MillWorldRequest{CWD: cwd, ConfigDir: world.ConfigDir, Name: "restored-name"},
		Payload:         map[string]any{},
	})
	if !response.OK {
		t.Fatalf("explicit start failed to restore registration: %#v", response.Error)
	}
	entries, err := readAutoStartRegistrations()
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].ConfigDir != world.ConfigDir || entries[0].Name != "restored-name" {
		t.Fatalf("explicit start did not restore registration: %#v", entries)
	}
}

func TestInitAutoStartRegistersBeforeFailedLaunchAndStealthStartsImmediately(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	t.Setenv("MILLSTRAND_SOURCE", source)

	world, cwd := writeAutoStartConfig(t, `{"configFormat":"alpha","name":"keep-name","autoStart":false,"futureField":{"keep":true}}`)
	s := newAutostartTestServer()
	type prelaunchObservation struct {
		registered bool
		name       string
	}
	prelaunch := make(chan prelaunchObservation, 1)
	originalLaunch := launchWeaver
	launchWeaver = func(_ string, args []string, _ io.Writer, _ io.Writer) (*exec.Cmd, error) {
		entries, err := readAutoStartRegistrations()
		prelaunch <- prelaunchObservation{
			registered: err == nil && len(entries) == 1 && entries[0].ConfigDir == configDirArg(args),
			name:       nameArg(args),
		}
		return nil, errors.New("injected init launch failure")
	}
	t.Cleanup(func() { launchWeaver = originalLaunch; _ = s.stopAll() })
	response := callMillRequest(t, s, client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion,
		RequestID:       "init-autostart-failure",
		MillID:          s.meta.MillID,
		Operation:       "init",
		World:           client.MillWorldRequest{CWD: cwd, ConfigDir: world.ConfigDir, AutoStart: true},
		Payload:         map[string]any{},
	})
	if response.OK {
		t.Fatal("init reported success after injected launch failure")
	}
	observation := <-prelaunch
	if !observation.registered {
		t.Fatal("init launched before registering the workspace")
	}
	if observation.name != "keep-name" {
		t.Fatalf("init did not preserve configured name at launch: %q", observation.name)
	}
	entries, err := readAutoStartRegistrations()
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].ConfigDir != world.ConfigDir {
		t.Fatalf("failed init removed its registration: %#v", entries)
	}
	cfgBytes, err := os.ReadFile(world.ConfigFile)
	if err != nil {
		t.Fatal(err)
	}
	var persisted map[string]any
	if err := json.Unmarshal(cfgBytes, &persisted); err != nil {
		t.Fatal(err)
	}
	future, futureOK := persisted["futureField"].(map[string]any)
	if persisted["name"] != "keep-name" || !futureOK || future["keep"] != true || persisted["autoStart"] != true {
		t.Fatalf("init did not preserve config values: %#v", persisted)
	}

	repo := tempMillstrandCheckout(t)
	stealth := newAutostartTestServer()
	started := make(chan struct{}, 1)
	launchWeaver = func(_ string, args []string, _ io.Writer, _ io.Writer) (*exec.Cmd, error) {
		started <- struct{}{}
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		startedWorld, err := config.RuntimeWorld(configDirArg(args))
		if err != nil {
			return nil, err
		}
		writeWeaverMetadata(t, startedWorld, cmd.Process.Pid, "stealth-autostart")
		return cmd, nil
	}
	t.Cleanup(func() { _ = stealth.stopAll() })
	response = callMillRequest(t, stealth, client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion,
		RequestID:       "stealth-init-autostart",
		MillID:          stealth.meta.MillID,
		Operation:       "init",
		World:           client.MillWorldRequest{CWD: repo, AutoStart: true, Stealth: true},
		Payload:         map[string]any{},
	})
	if !response.OK {
		t.Fatalf("stealth init --auto-start failed: %#v", response.Error)
	}
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("stealth init --auto-start did not start a weaver")
	}
	stealthWorld, err := config.RuntimeWorld(filepath.Join(repo, config.DefaultWorkspace))
	if err != nil {
		t.Fatal(err)
	}
	entries, err = readAutoStartRegistrations()
	if err != nil {
		t.Fatal(err)
	}
	stealthRegistered := false
	for _, entry := range entries {
		if entry.ConfigDir == stealthWorld.ConfigDir {
			stealthRegistered = true
		}
	}
	if len(entries) != 2 || !stealthRegistered {
		t.Fatalf("stealth init did not retain registration: %#v", entries)
	}
	stealthConfig, err := os.ReadFile(stealthWorld.ConfigFile)
	if err != nil {
		t.Fatal(err)
	}
	var stealthPersisted map[string]any
	if err := json.Unmarshal(stealthConfig, &stealthPersisted); err != nil {
		t.Fatal(err)
	}
	if stealthPersisted["autoStart"] != true {
		t.Fatalf("stealth init did not enable autoStart: %s", stealthConfig)
	}
}

func TestAutostartShutdownCancelsReadyWaitJoinsChildrenAndLeavesQueueUnstarted(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	t.Setenv("MILLSTRAND_SOURCE", tempSource(t))
	worlds := make([]config.World, 0, autostartSlots+1)
	for i := 0; i < autostartSlots+1; i++ {
		world, cwd := writeAutoStartConfig(t, `{"configFormat":"alpha","autoStart":true}`)
		if err := registerAutoStart(world, cwd, "shutdown-"+intString(i)); err != nil {
			t.Fatal(err)
		}
		worlds = append(worlds, world)
	}
	s := newAutostartTestServer()
	launches := make(chan autostartLaunch, len(worlds))
	originalLaunch := launchWeaver
	launchWeaver = func(_ string, args []string, _ io.Writer, _ io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		launches <- autostartLaunch{configDir: configDirArg(args), cmd: cmd}
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = originalLaunch; s.signalShutdown(); s.stopAutostart(); _ = s.stopAll() })

	s.startAutostart()
	started := waitForAutostartLaunch(t, launches, autostartSlots)
	s.stopAutostart()
	allLaunches := append([]autostartLaunch(nil), started...)
	for len(launches) > 0 {
		allLaunches = append(allLaunches, <-launches)
	}
	if got := len(allLaunches); got != autostartSlots {
		t.Fatalf("shutdown allowed queued autostart launch: got=%d want=%d", got, autostartSlots)
	}
	for _, launch := range allLaunches {
		if launch.cmd.ProcessState == nil || processAlive(launch.cmd.Process.Pid) {
			t.Fatalf("autostart child was not terminated and joined: %#v", launch.cmd)
		}
	}
	if len(s.children) != 0 {
		t.Fatalf("shutdown left supervised children: %#v", s.children)
	}

	s.startAutostart()
	s.autostartWG.Wait()
	if got := len(allLaunches) + len(launches); got != autostartSlots {
		t.Fatalf("closed-shutdown server launched work after join: got=%d want=%d", got, autostartSlots)
	}
}
