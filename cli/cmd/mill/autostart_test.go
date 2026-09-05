package main

import (
	"encoding/json"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

func TestWeaverStartRegistersOnlyExplicitSharedAutoStart(t *testing.T) {
	for _, tc := range []struct {
		name       string
		config     string
		registered bool
	}{
		{name: "true", config: `{"configFormat":"alpha","autoStart":true}`, registered: true},
		{name: "false", config: `{"configFormat":"alpha","autoStart":false}`},
		{name: "omitted", config: `{"configFormat":"alpha"}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
			cfg := t.TempDir()
			if err := os.WriteFile(filepath.Join(cfg, config.ConfigFileName), []byte(tc.config), 0o644); err != nil {
				t.Fatal(err)
			}
			world, err := config.RuntimeWorld(cfg)
			if err != nil {
				t.Fatal(err)
			}
			cmd := exec.Command("sleep", "60")
			if err := cmd.Start(); err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				terminatePID(cmd.Process.Pid)
				waitForPIDExit(cmd.Process.Pid, time.Second)
			})
			writeWeaverMetadata(t, world, cmd.Process.Pid, "already-running")
			s := &server{
				meta:     client.MillMetadata{ProtocolVersion: client.MillProtocolVersion, MillID: "mill-test"},
				children: map[string]*weaverChild{world.ConfigDir: {cmd: cmd, world: world, done: make(chan error, 1)}},
			}
			response := callMillRequest(t, s, client.MillRequest{
				ProtocolVersion: client.MillProtocolVersion,
				RequestID:       "request-1",
				MillID:          "mill-test",
				Operation:       "weaver-start",
				World:           client.MillWorldRequest{CWD: cfg, ConfigDir: cfg, Name: "remembered"},
				Payload:         map[string]any{},
			})
			if !response.OK {
				t.Fatalf("already-running start failed: %#v", response.Error)
			}
			path, err := autostartPath(cfg)
			if err != nil {
				t.Fatal(err)
			}
			_, statErr := os.Stat(path)
			if tc.registered && statErr != nil {
				t.Fatalf("explicit true start did not register: %v", statErr)
			}
			if !tc.registered && !os.IsNotExist(statErr) {
				t.Fatalf("%s start registered unexpectedly: %v", tc.name, statErr)
			}
			if tc.registered {
				entries, err := readAutoStartRegistrations()
				if err != nil || len(entries) != 1 || entries[0].Name != "remembered" {
					t.Fatalf("unexpected registration: entries=%#v err=%v", entries, err)
				}
			}
		})
	}
}

func callMillRequest(t *testing.T, s *server, req client.MillRequest) client.MillResponse {
	t.Helper()
	serverConn, clientConn := net.Pipe()
	done := make(chan struct{})
	go func() {
		s.handle(serverConn)
		close(done)
	}()
	t.Cleanup(func() {
		_ = clientConn.Close()
		<-done
	})
	if err := json.NewEncoder(clientConn).Encode(req); err != nil {
		t.Fatal(err)
	}
	var response client.MillResponse
	if err := json.NewDecoder(clientConn).Decode(&response); err != nil {
		t.Fatal(err)
	}
	return response
}

func TestAutoStartRegistrationRoundTripsAndSharesWorkspaceMarkerIdentity(t *testing.T) {
	state := filepath.Join(t.TempDir(), "state")
	t.Setenv("XDG_STATE_HOME", state)
	repo := t.TempDir()
	shared := filepath.Join(repo, config.DefaultWorkspace)
	alias := filepath.Join(repo, config.WorkspaceAlias)
	if err := os.MkdirAll(shared, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(alias, 0o755); err != nil {
		t.Fatal(err)
	}
	world, err := config.RuntimeWorld(alias)
	if err != nil {
		t.Fatal(err)
	}
	if err := registerAutoStart(world, repo, "shop-fe"); err != nil {
		t.Fatal(err)
	}
	entries, err := readAutoStartRegistrations()
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("registration count = %d, want 1", len(entries))
	}
	if entries[0].ConfigDir != world.ConfigDir || entries[0].CWD != repo || entries[0].Name != "shop-fe" || !entries[0].Enabled {
		t.Fatalf("unexpected registration: %#v", entries[0])
	}
	sharedPath, err := autostartPath(shared)
	if err != nil {
		t.Fatal(err)
	}
	aliasPath, err := autostartPath(alias)
	if err != nil {
		t.Fatal(err)
	}
	if sharedPath != aliasPath {
		t.Fatalf("marker aliases have distinct registration paths: shared=%q alias=%q", sharedPath, aliasPath)
	}
	if err := removeAutoStart(shared); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(aliasPath); !os.IsNotExist(err) {
		t.Fatalf("remove through shared marker left alias registration: %v", err)
	}
}

func TestReadAutoStartRegistrationsSkipsMalformedEntry(t *testing.T) {
	state := filepath.Join(t.TempDir(), "state")
	t.Setenv("XDG_STATE_HOME", state)
	cfg := t.TempDir()
	world := config.World{ConfigDir: cfg}
	if err := registerAutoStart(world, cfg, "healthy"); err != nil {
		t.Fatal(err)
	}
	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	badPath := filepath.Join(root, autostartDirectory, "malformed.json")
	if err := os.WriteFile(badPath, []byte("{"), 0o644); err != nil {
		t.Fatal(err)
	}
	entries, err := readAutoStartRegistrations()
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name != "healthy" {
		t.Fatalf("malformed entry prevented healthy registration: %#v", entries)
	}
}

func TestAutoStartPrunesRegistrationWhenConfigDisablesIt(t *testing.T) {
	state := filepath.Join(t.TempDir(), "state")
	t.Setenv("XDG_STATE_HOME", state)
	cfg := t.TempDir()
	if err := os.WriteFile(filepath.Join(cfg, config.ConfigFileName), []byte(`{"configFormat":"alpha","autoStart":false}`), 0o644); err != nil {
		t.Fatal(err)
	}
	world := config.World{ConfigDir: cfg}
	if err := registerAutoStart(world, cfg, "disabled"); err != nil {
		t.Fatal(err)
	}
	s := &server{shutdown: make(chan struct{})}
	s.startAutostart()
	s.autostartWG.Wait()
	s.signalShutdown()
	path, err := autostartPath(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("disabled registration was not pruned: %v", err)
	}
}
