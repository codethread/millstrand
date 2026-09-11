package main

import (
	"os"
	"os/exec"
	"testing"

	"millstrand-strand-cli/internal/config"
)

func TestPoolCustodyRequiresExactMemberIdentityAndToken(t *testing.T) {
	world := config.World{ConfigDir: "/tmp/A/.millstrand", StateDir: "/tmp/state/A", DataDir: "/tmp/state/A/data"}
	process, err := os.FindProcess(os.Getpid())
	if err != nil {
		t.Fatal(err)
	}
	host := &weaverHost{Pool: "backend", HostID: "host-1", HostGenerationID: "host-generation-1", PID: os.Getpid(), LaunchToken: "token-1", cmd: &exec.Cmd{Process: process}, Allowances: map[string]config.World{"weaver-1": world}}
	s := &server{poolHosts: map[string]*weaverHost{"backend": host}}
	if got, err := s.admitControlCaller("weaver-1", "token-1", os.Getpid()); err != nil || got.ConfigDir != world.ConfigDir {
		t.Fatalf("expected exact pre-ready custody admission, got world=%#v err=%v", got, err)
	}
	for name, tc := range map[string]struct {
		id    string
		token string
		pid   int
	}{"unknown member": {"weaver-2", "token-1", os.Getpid()}, "wrong token": {"weaver-1", "wrong", os.Getpid()}, "wrong pid": {"weaver-1", "token-1", os.Getpid() + 1}} {
		t.Run(name, func(t *testing.T) {
			if _, err := s.admitControlCaller(tc.id, tc.token, tc.pid); err == nil {
				t.Fatal("invalid pool custody identity was accepted")
			}
		})
	}
}
