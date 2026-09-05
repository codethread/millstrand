package cli_test

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestMillStartPublishesMetadataAndStatus(t *testing.T) {
	xdg, err := os.MkdirTemp("/tmp", "mill-state-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(xdg) })
	bin := filepath.Join(t.TempDir(), "mill")
	build := exec.Command("go", "build", "-o", bin, "./cmd/mill")
	build.Dir = "."
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build mill: %v\n%s", err, out)
	}
	cmd := exec.Command(bin, "start")
	cmd.Env = append(os.Environ(), "XDG_STATE_HOME="+xdg)
	var serverOut strings.Builder
	cmd.Stdout = &serverOut
	cmd.Stderr = &serverOut
	if err := cmd.Start(); err != nil {
		t.Fatalf("start mill: %v", err)
	}
	t.Cleanup(func() { _ = cmd.Process.Signal(os.Interrupt); _, _ = cmd.Process.Wait() })
	metadataPath := filepath.Join(xdg, "millstrand", "mill.json")
	var meta map[string]any
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		b, err := os.ReadFile(metadataPath)
		if err == nil && json.Unmarshal(b, &meta) == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if meta["mill_id"] == "" || meta["socket_path"] != filepath.Join(xdg, "millstrand", "mill.sock") || meta["state_root"] != filepath.Join(xdg, "millstrand") {
		t.Fatalf("unexpected metadata: %#v\nserver output: %s", meta, serverOut.String())
	}
	for _, want := range []string{"Mill ready", filepath.Join(xdg, "millstrand", "mill.sock"), "PID "} {
		if !strings.Contains(serverOut.String(), want) {
			t.Fatalf("missing mill start log %q in: %s", want, serverOut.String())
		}
	}
	humanStatus := exec.Command(bin, "status")
	humanStatus.Env = append(os.Environ(), "XDG_STATE_HOME="+xdg)
	humanOut, err := humanStatus.CombinedOutput()
	if err != nil || !strings.Contains(string(humanOut), "Mill running (PID ") || strings.Contains(string(humanOut), "\x1b") {
		t.Fatalf("mill human status: %v\n%s", err, humanOut)
	}
	status := exec.Command(bin, "status", "--json")
	status.Env = append(os.Environ(), "XDG_STATE_HOME="+xdg)
	out, err := status.CombinedOutput()
	if err != nil {
		t.Fatalf("mill status: %v\n%s", err, out)
	}
	var result map[string]any
	if err := json.Unmarshal(out, &result); err != nil {
		t.Fatalf("status did not emit JSON: %v\n%s", err, out)
	}
	if result["healthy"] != true || result["mill_id"] != meta["mill_id"] {
		t.Fatalf("unexpected status: %#v", result)
	}
}
