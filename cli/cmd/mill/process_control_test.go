package main

import (
	"encoding/json"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/process"
)

// startingChild registers one supervised child that mill launched and that has
// published no identity yet, which is the state a weaver is in while its config
// is still evaluating.
func startingChild(t *testing.T, token string) (*server, config.World) {
	t.Helper()
	root := t.TempDir()
	world := config.World{
		ConfigDir: filepath.Join(root, "config"),
		StateDir:  filepath.Join(root, "state"),
		DataDir:   filepath.Join(root, "data"),
	}
	cmd := exec.Command("sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	})
	s := &server{children: map[string]*weaverChild{
		world.ConfigDir: {cmd: cmd, world: world, name: "starting-weaver", launchToken: token},
	}}
	s.controlPeerPID = func(net.Conn) (int, error) { return cmd.Process.Pid, nil }
	return s, world
}

func TestAdmitStartingWeaverByLaunchToken(t *testing.T) {
	s, world := startingChild(t, "launch-token-one")

	admitted, err := s.admitControlCaller("weaver-nonce-one", "launch-token-one", s.children[world.ConfigDir].cmd.Process.Pid)
	if err != nil {
		t.Fatalf("starting weaver was refused its own custody: %v", err)
	}
	if admitted.ConfigDir != world.ConfigDir {
		t.Fatalf("admitted the wrong world: got %q want %q", admitted.ConfigDir, world.ConfigDir)
	}

	// The token speaks for exactly one weaver identity for the rest of startup.
	if _, err := s.admitControlCaller("weaver-nonce-one", "launch-token-one", s.children[world.ConfigDir].cmd.Process.Pid); err != nil {
		t.Fatalf("bound weaver was refused on a repeat request: %v", err)
	}
	if _, err := s.admitControlCaller("weaver-nonce-two", "launch-token-one", s.children[world.ConfigDir].cmd.Process.Pid); err == nil {
		t.Fatal("a second weaver identity reused the launch token")
	}
}

func TestAdmitControlCallerRejectsUnprovenCallers(t *testing.T) {
	s, world := startingChild(t, "launch-token-one")

	if _, err := s.admitControlCaller("weaver-nonce-one", "", s.children[world.ConfigDir].cmd.Process.Pid); err == nil {
		t.Fatal("a caller with no identity and no launch token was admitted")
	}
	if _, err := s.admitControlCaller("weaver-nonce-one", "some-other-token", s.children[world.ConfigDir].cmd.Process.Pid); err == nil {
		t.Fatal("a caller presenting an unrelated launch token was admitted")
	}

	// A weaver that has published its identity is admitted by that identity, and
	// its spent launch token no longer speaks for anyone else.
	s.children[world.ConfigDir].identity = weaverIdentity{WeaverID: "weaver-nonce-one", PID: s.children[world.ConfigDir].cmd.Process.Pid}
	if _, err := s.admitControlCaller("weaver-nonce-one", "", s.children[world.ConfigDir].cmd.Process.Pid); err != nil {
		t.Fatalf("ready weaver was refused by identity: %v", err)
	}
	if _, err := s.admitControlCaller("stale-weaver-nonce", "launch-token-one", s.children[world.ConfigDir].cmd.Process.Pid); err == nil {
		t.Fatal("a stale caller was admitted with the token of a now-ready weaver")
	}
}

func TestAdmitControlCallerRejectsDeadAndDiscoveredChildren(t *testing.T) {
	s, world := startingChild(t, "launch-token-one")
	child := s.children[world.ConfigDir]

	child.unsupervised = true
	if _, err := s.admitControlCaller("weaver-nonce-one", "launch-token-one", child.cmd.Process.Pid); err == nil {
		t.Fatal("a discovered child was admitted through a launch token")
	}
	child.unsupervised = false

	if err := child.cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = child.cmd.Wait()
	if _, err := s.admitControlCaller("weaver-nonce-one", "launch-token-one", child.cmd.Process.Pid); err == nil {
		t.Fatal("a launch token outlived the process it names")
	}
}

func TestInheritedChildCannotUseLaunchToken(t *testing.T) {
	s, world := startingChild(t, "launch-token-inherited")
	parent := s.children[world.ConfigDir]
	// A descendant can inherit the launch environment, including the token. Use
	// a live adversarial process so the rejection rests on peer identity, not on
	// a dead-PID check.
	adversary := exec.Command("sleep", "60")
	adversary.Env = append(os.Environ(), launchTokenEnvVar+"=launch-token-inherited")
	if err := adversary.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = adversary.Process.Kill()
		_ = adversary.Wait()
	})
	if adversary.Process.Pid == parent.cmd.Process.Pid {
		t.Fatal("adversarial process unexpectedly reused the Weaver pid")
	}
	s.controlPeerPID = func(net.Conn) (int, error) { return adversary.Process.Pid, nil }
	response := callProcessControl(t, s, "launch-token-inherited", "process.list-owned", map[string]any{"owner": "harness"})
	if response.OK {
		t.Fatal("a descendant that inherited the launch token reached custody")
	}
	if response.Error == nil || response.Error.Code != "process/stale-weaver" {
		t.Fatalf("inherited-child rejection = %+v", response.Error)
	}
}

func TestUnixPeerPIDUsesKernelPeerIdentity(t *testing.T) {
	root, err := os.MkdirTemp("/tmp", "mill-pid-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(root) })
	listener, err := net.Listen("unix", filepath.Join(root, "control.sock"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	peer := make(chan int, 1)
	peerErr := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			peerErr <- err
			return
		}
		defer func() { _ = conn.Close() }()
		pid, err := unixPeerPID(conn)
		if err != nil {
			peerErr <- err
			return
		}
		peer <- pid
	}()
	conn, err := net.Dial("unix", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = conn.Close() }()
	select {
	case err := <-peerErr:
		if strings.Contains(err.Error(), "unsupported") {
			t.Skip(err)
		}
		t.Fatal(err)
	case pid := <-peer:
		if pid != os.Getpid() {
			t.Fatalf("kernel peer pid = %d, want test pid %d", pid, os.Getpid())
		}
	case <-time.After(time.Second):
		t.Fatal("timed out reading Unix peer PID")
	}
}

// A starting weaver must be able to run real custody operations, because config
// evaluation recovers earlier runs and schedules pending ones before readiness.
func TestProcessControlServesStartingWeaver(t *testing.T) {
	s, world := startingChild(t, "launch-token-one")
	s.custodies = map[string]*process.Custody{}

	launched := callProcessControl(t, s, "launch-token-one", "process.launch", map[string]any{
		"owner": "harness",
		"key":   "run-one",
		"launch_spec": map[string]any{
			"argv": []any{"sleep", "60"},
			"cwd":  t.TempDir(),
		},
	})
	if !launched.OK {
		t.Fatalf("starting weaver could not launch its own process: %+v", launched.Error)
	}
	t.Cleanup(func() {
		if custody := s.custodies[world.ConfigDir]; custody != nil {
			_ = custody.Shutdown()
		}
	})

	listed := callProcessControl(t, s, "launch-token-one", "process.list-owned", map[string]any{"owner": "harness"})
	if !listed.OK {
		t.Fatalf("starting weaver could not list its own custody: %+v", listed.Error)
	}
	records, ok := listed.Result.([]any)
	if !ok || len(records) != 1 {
		t.Fatalf("expected one owned record, got %#v", listed.Result)
	}

	stale := callProcessControl(t, s, "stale-token", "process.list-owned", map[string]any{"owner": "harness"})
	if stale.OK {
		t.Fatal("a stale caller reached custody for a starting weaver")
	}
	if stale.Error.Code != "process/stale-weaver" {
		t.Fatalf("unexpected rejection code %q", stale.Error.Code)
	}
}

func TestStartWeaverHandsLaunchTokenToItsChild(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)

	orig := launchWeaver
	var launchedEnv []string
	launchWeaver = func(_ string, _ []string, env []string, register func(*exec.Cmd) error, _, _ io.Writer) (*exec.Cmd, error) {
		launchedEnv = append([]string(nil), env...)
		cmd := exec.Command("sleep", "60")
		if err := register(cmd); err != nil {
			return nil, err
		}
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		world, err := config.RuntimeWorld(cfg)
		if err != nil {
			t.Fatal(err)
		}
		writeWeaverMetadata(t, world, cmd.Process.Pid, "token-weaver")
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = orig })

	s := server{children: map[string]*weaverChild{}}
	if _, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.stopAll() })

	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	child := s.children[world.ConfigDir]
	if child == nil || child.launchToken == "" {
		t.Fatal("started child has no launch token to admit its own pre-ready custody calls")
	}
	want := launchTokenEnvVar + "=" + child.launchToken
	if len(launchedEnv) != 1 || launchedEnv[0] != want {
		t.Fatalf("launch env %#v does not carry %q", launchedEnv, want)
	}
}

func callProcessControl(t *testing.T, s *server, launchToken, operation string, arguments map[string]any) client.MillResponse {
	t.Helper()
	frame := map[string]any{
		"protocol_version": client.MillProtocolVersion,
		"request_id":       "control-" + operation,
		"weaver_id":        "weaver-nonce-one",
		"operation":        operation,
		"arguments":        arguments,
	}
	if launchToken != "" {
		frame["launch_token"] = launchToken
	}
	encoded, err := json.Marshal(frame)
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &raw); err != nil {
		t.Fatal(err)
	}
	client_, server_ := net.Pipe()
	go func() {
		s.handleProcessControl(server_, raw)
		_ = server_.Close()
	}()
	var response client.MillResponse
	if err := json.NewDecoder(client_).Decode(&response); err != nil {
		t.Fatal(err)
	}
	_ = client_.Close()
	return response
}
