package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/client"
)

func TestRunInitAutoStartRequiresRunningMill(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	workspace := t.TempDir()

	err := runInit(workspace, false, true, nil)
	if err == nil || !strings.Contains(err.Error(), "no running mill") {
		t.Fatalf("expected no-running-mill transport error, got %v", err)
	}
	if _, err := os.Stat(filepath.Join(workspace, "config.json")); !os.IsNotExist(err) {
		t.Fatalf("auto-start init should not bootstrap offline config, stat error=%v", err)
	}
}

func TestWorldRequestMakesMillWorkspaceSelectionPathOnly(t *testing.T) {
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	world, err := worldRequest("frontend", "")
	if err != nil {
		t.Fatal(err)
	}
	want, err := filepath.Abs(filepath.Join(cwd, "frontend"))
	if err != nil {
		t.Fatal(err)
	}
	if world.ConfigDir != want {
		t.Fatalf("mill workspace selection = %q, want absolute path %q", world.ConfigDir, want)
	}
}

func TestValidateInitRequestRejectsStealthExplicitWorkspace(t *testing.T) {
	err := validateInitRequest(client.MillWorldRequest{ConfigDir: t.TempDir(), Stealth: true})
	if err == nil {
		t.Fatal("stealth request with explicit workspace was accepted")
	}
	if err := validateInitRequest(client.MillWorldRequest{Stealth: true}); err != nil {
		t.Fatalf("repo-local stealth request rejected: %v", err)
	}
}
