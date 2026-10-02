package main

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
	"millstrand-strand-cli/internal/process"
)

type temporaryFakeLauncher struct {
	t             *testing.T
	launches      int
	pids          []int
	fail          bool
	failedOutput  string
	launchEntered chan<- string
	launchRelease <-chan struct{}
}

func installTemporaryFakeLauncher(t *testing.T, fail bool) *temporaryFakeLauncher {
	t.Helper()
	fake := &temporaryFakeLauncher{t: t, fail: fail, failedOutput: "temporary startup diagnostic"}
	original := launchWeaver
	launchWeaver = fake.launch
	t.Cleanup(func() { launchWeaver = original })
	return fake
}

func (f *temporaryFakeLauncher) launch(_ string, args []string, _ []string, register func(*exec.Cmd) error, out, errOut io.Writer) (*exec.Cmd, error) {
	f.launches++
	world, err := temporaryLaunchWorld(args)
	if err != nil {
		return nil, err
	}
	var cmd *exec.Cmd
	if f.fail {
		cmd = exec.Command("sh", "-c", fmt.Sprintf("printf '%s\\n' >&2; exit 1", f.failedOutput))
	} else {
		cmd = exec.Command("sleep", "60")
	}
	cmd.Stdout = out
	cmd.Stderr = errOut
	if err := register(cmd); err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	f.pids = append(f.pids, cmd.Process.Pid)
	if f.fail {
		return cmd, nil
	}
	writeWeaverMetadata(f.t, world, cmd.Process.Pid, fmt.Sprintf("temporary-test-%d", f.launches))
	if f.launchEntered != nil {
		f.launchEntered <- world.StateDir
		<-f.launchRelease
	}
	return cmd, nil
}

func temporaryLaunchWorld(args []string) (config.World, error) {
	configDir := configDirArg(args)
	world, err := config.RuntimeWorld(configDir)
	if err != nil {
		return config.World{}, err
	}
	stateDir, err := temporaryLaunchArg(args, "--state-dir")
	if err != nil {
		return config.World{}, err
	}
	dataDir, err := temporaryLaunchArg(args, "--data-dir")
	if err != nil {
		return config.World{}, err
	}
	world.StateDir = stateDir
	world.DataDir = dataDir
	world.DBPath = filepath.Join(dataDir, config.DefaultDBFileName)
	return world, nil
}

func temporaryLaunchArg(args []string, flag string) (string, error) {
	for i, arg := range args {
		if arg == flag && i+1 < len(args) && args[i+1] != "" {
			return args[i+1], nil
		}
	}
	return "", fmt.Errorf("fake launch args omitted %s: %#v", flag, args)
}

func newTemporaryTestServer(t *testing.T) (*server, client.MillWorldRequest, string) {
	t.Helper()
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	configDir := tempConfig(t, source)
	s := &server{
		children:    map[string]*weaverChild{},
		custodies:   map[string]*process.Custody{},
		transitions: map[string]*weaverTransition{},
		startClaims: map[string]chan struct{}{},
		shutdown:    make(chan struct{}),
	}
	t.Cleanup(func() { _ = s.stopAll() })
	return s, client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: configDir}, configDir
}

func TestTemporaryWeaverModeConflictsAndPreservesPersistentState(t *testing.T) {
	s, req, configDir := newTemporaryTestServer(t)
	fake := installTemporaryFakeLauncher(t, false)

	persistent, err := s.startWeaver(req)
	if err != nil {
		t.Fatal(err)
	}
	persistentPID := temporaryStatusPID(t, persistent)
	persistentWorld, err := config.RuntimeWorld(configDir)
	if err != nil {
		t.Fatal(err)
	}
	persistentSentinel := filepath.Join(persistentWorld.StateDir, "persistent-sentinel")
	if err := os.WriteFile(persistentSentinel, []byte("keep\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := s.startTemporaryWeaver(req); err == nil || !strings.Contains(err.Error(), "temp") {
		t.Fatalf("persistent-to-temporary mode change was accepted: %v", err)
	}
	if fake.launches != 1 || !processAlive(persistentPID) || !temporaryPathExists(persistentSentinel) {
		t.Fatalf("rejected temporary start changed persistent runtime: launches=%d pid=%d sentinel=%v", fake.launches, persistentPID, temporaryPathExists(persistentSentinel))
	}
	if _, err := s.stopWeaver(req); err != nil {
		t.Fatal(err)
	}

	first, err := s.startTemporaryWeaver(req)
	if err != nil {
		t.Fatal(err)
	}
	firstPID := temporaryStatusPID(t, first)
	if first["temporary"] != true {
		t.Fatalf("temporary status omitted temporary=true: %#v", first)
	}
	repeated, err := s.startTemporaryWeaver(req)
	if err != nil {
		t.Fatal(err)
	}
	if repeated["temporary"] != true || temporaryStatusPID(t, repeated) != firstPID || fake.launches != 2 {
		t.Fatalf("repeated temporary start was not idempotent: first=%#v repeated=%#v launches=%d", first, repeated, fake.launches)
	}

	persistentAgain, err := s.startWeaver(req)
	if err == nil || !strings.Contains(err.Error(), "temp") {
		t.Fatalf("temporary-to-persistent mode change was accepted: status=%#v err=%v", persistentAgain, err)
	}
	if fake.launches != 2 || !processAlive(firstPID) {
		t.Fatalf("rejected persistent start changed temporary runtime: launches=%d pid=%d", fake.launches, firstPID)
	}
	if _, err := s.stopWeaver(req); err != nil {
		t.Fatal(err)
	}
	if !temporaryPathExists(persistentSentinel) {
		t.Fatal("temporary lifecycle removed existing persistent state")
	}
}

func TestTemporaryWeaverRejectsConfiguredAndRegisteredPools(t *testing.T) {
	t.Run("configured pool", func(t *testing.T) {
		s, req, configDir := newTemporaryTestServer(t)
		if err := os.WriteFile(filepath.Join(configDir, config.LocalConfigFileName), []byte(`{"JVMPool":"configured-pool"}`), 0o644); err != nil {
			t.Fatal(err)
		}
		fake := installTemporaryFakeLauncher(t, false)
		if _, err := s.startTemporaryWeaver(req); err == nil || !strings.Contains(err.Error(), "configured-pool") {
			t.Fatalf("configured JVM pool did not reject temporary start: %v", err)
		}
		if fake.launches != 0 {
			t.Fatalf("configured-pool rejection launched a child: %d", fake.launches)
		}
		assertNoTemporaryRuntimeDirs(t)
	})

	t.Run("stopped registered pool", func(t *testing.T) {
		s, req, configDir := newTemporaryTestServer(t)
		canonical, err := config.CanonicalConfigIdentity(configDir)
		if err != nil {
			t.Fatal(err)
		}
		registry, err := jvmpool.New(temporaryTestStateRoot(t))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := registry.Reconcile(jvmpool.Member{
			ConfigDir: canonical,
			SourceCWD: req.CWD,
			JVMPool:   "stopped-registered-pool",
		}, jvmpool.LiveOwnership{}); err != nil {
			t.Fatal(err)
		}
		fake := installTemporaryFakeLauncher(t, false)
		if _, err := s.startTemporaryWeaver(req); err == nil || !strings.Contains(err.Error(), "registered JVM pool") {
			t.Fatalf("stopped registered JVM pool did not reject temporary start: %v", err)
		}
		if fake.launches != 0 {
			t.Fatalf("stopped registered-pool rejection launched a child: %d", fake.launches)
		}
		assertNoTemporaryRuntimeDirs(t)
	})
}

func TestTemporaryWeaverStartupFailureCleansPrivateRoot(t *testing.T) {
	s, req, _ := newTemporaryTestServer(t)
	fake := installTemporaryFakeLauncher(t, true)
	var privateState string
	original := launchWeaver
	launchWeaver = func(source string, args []string, env []string, register func(*exec.Cmd) error, out, errOut io.Writer) (*exec.Cmd, error) {
		privateState, _ = temporaryLaunchArg(args, "--state-dir")
		return fake.launch(source, args, env, register, out, errOut)
	}
	t.Cleanup(func() { launchWeaver = original })

	status, err := s.startTemporaryWeaver(req)
	if status != nil || err == nil || !strings.Contains(err.Error(), fake.failedOutput) {
		t.Fatalf("temporary startup lost diagnostic string: status=%#v err=%v", status, err)
	}
	if privateState == "" || temporaryPathExists(privateState) {
		t.Fatalf("failed temporary startup retained private directory %q", privateState)
	}
	assertNoTemporaryRuntimeDirs(t)
}

func TestTemporaryWeaverDirectChildExitCleansPrivateRoot(t *testing.T) {
	s, req, _ := newTemporaryTestServer(t)
	installTemporaryFakeLauncher(t, false)
	status, err := s.startTemporaryWeaver(req)
	if err != nil {
		t.Fatal(err)
	}
	stateDir := temporaryStatusString(t, status, "state_dir")
	child := temporarySelectedChild(t, s, req.ConfigDir)
	pid := child.cmd.Process.Pid
	if err := child.cmd.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	waitForTemporaryCondition(t, func() bool {
		return !temporaryPathExists(stateDir) && temporarySelectedChild(t, s, req.ConfigDir) == nil
	})
	if processAlive(pid) || temporaryPathExists(stateDir) {
		t.Fatalf("direct child exit retained temporary runtime: pid=%d state=%q", pid, stateDir)
	}
	assertNoTemporaryRuntimeDirs(t)
}

func TestTemporaryWeaverStopAllCancelsInFlightStart(t *testing.T) {
	s, req, _ := newTemporaryTestServer(t)
	entered := make(chan string, 1)
	release := make(chan struct{})
	fake := installTemporaryFakeLauncher(t, false)
	fake.launchEntered = entered
	fake.launchRelease = release

	startDone := make(chan struct {
		status map[string]any
		err    error
	}, 1)
	go func() {
		status, err := s.startTemporaryWeaver(req)
		startDone <- struct {
			status map[string]any
			err    error
		}{status, err}
	}()
	var privateState string
	select {
	case privateState = <-entered:
	case <-time.After(time.Second):
		t.Fatal("temporary start did not reach fake launch")
	}
	if !temporaryPathExists(privateState) {
		t.Fatalf("in-flight temporary start did not allocate private directory %q", privateState)
	}

	stopDone := make(chan error, 1)
	go func() { stopDone <- s.stopAll() }()
	select {
	case err := <-stopDone:
		t.Fatalf("stopAll returned before joining in-flight temporary start: %v", err)
	case <-time.After(100 * time.Millisecond):
	}
	close(release)

	select {
	case result := <-startDone:
		if result.status != nil || result.err == nil {
			t.Fatalf("in-flight temporary start was not cancelled: status=%#v err=%v", result.status, result.err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("temporary start did not join after stopAll")
	}
	select {
	case err := <-stopDone:
		if err != nil {
			t.Fatalf("stopAll failed: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("stopAll did not join in-flight temporary start")
	}
	if processAlive(fake.pids[0]) || temporaryPathExists(privateState) {
		t.Fatalf("stopAll left temporary process or private state: pid=%d state=%q", fake.pids[0], privateState)
	}
	assertNoTemporaryRuntimeDirs(t)
}

func temporaryTestStateRoot(t *testing.T) string {
	t.Helper()
	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	return root
}

func temporaryStatusPID(t *testing.T, status map[string]any) int {
	t.Helper()
	pid, ok := status["pid"].(int)
	if !ok || pid <= 0 {
		t.Fatalf("status omitted positive pid: %#v", status)
	}
	return pid
}

func temporaryStatusString(t *testing.T, status map[string]any, key string) string {
	t.Helper()
	value, ok := status[key].(string)
	if !ok || value == "" {
		t.Fatalf("status omitted non-empty %s: %#v", key, status)
	}
	return value
}

func temporarySelectedChild(t *testing.T, s *server, configDir string) *weaverChild {
	t.Helper()
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.children[configDir]
}

func assertNoTemporaryRuntimeDirs(t *testing.T) {
	t.Helper()
	root := filepath.Join(temporaryTestStateRoot(t), "weavers")
	entries, err := os.ReadDir(root)
	if os.IsNotExist(err) {
		return
	}
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), "temp-") {
			t.Fatalf("temporary runtime directory remains: %s", filepath.Join(root, entry.Name()))
		}
	}
}

func temporaryPathExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func waitForTemporaryCondition(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.NewTimer(5 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(2 * time.Millisecond)
	defer tick.Stop()
	for {
		if condition() {
			return
		}
		select {
		case <-deadline.C:
			t.Fatal("temporary lifecycle condition did not converge")
		case <-tick.C:
		}
	}
}
