package main

import (
	"io"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

func TestRestartConvergesAndReplacesExactlyOnce(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var launches int
	var pids []int
	origLaunch, origProbe := launchWeaver, probeRuntime
	t.Cleanup(func() {
		launchWeaver, probeRuntime = origLaunch, origProbe
		for _, pid := range pids {
			if processAlive(pid) {
				terminatePID(pid)
				waitForPIDExit(pid, time.Second)
			}
		}
	})
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		mu.Lock()
		launches++
		pids = append(pids, cmd.Process.Pid)
		mu.Unlock()
		writeWeaverMetadata(t, world, cmd.Process.Pid, "weaver-"+intString(cmd.Process.Pid))
		return cmd, nil
	}
	probeRuntime = func(source string, world config.World) (restartProbeResult, error) {
		return restartProbeResult{Success: true, Stage: "probe/complete", ProbeWorkspace: filepath.Join(t.TempDir(), "probe"), SourceWorkspace: world.ConfigDir, Log: "probe.log"}, nil
	}
	s := server{children: map[string]*weaverChild{}, transitions: map[string]*weaverTransition{}}
	start, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg})
	if err != nil {
		t.Fatal(err)
	}
	oldGeneration := start["generation_id"]
	var results [2]map[string]any
	var errs [2]error
	var wg sync.WaitGroup
	for i := range results {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			results[i], errs[i] = s.restartWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg, ReadyTimeoutMs: 2_000})
		}(i)
	}
	wg.Wait()
	for i, err := range errs {
		if err != nil {
			t.Fatalf("restart caller %d failed: %v", i, err)
		}
		if results[i]["state"] != "running" {
			t.Fatalf("restart caller %d got %#v", i, results[i])
		}
	}
	mu.Lock()
	gotLaunches := launches
	mu.Unlock()
	if gotLaunches != 2 {
		t.Fatalf("expected one initial and one replacement launch, got %d", gotLaunches)
	}
	if results[0]["generation_id"] == oldGeneration || results[1]["generation_id"] == oldGeneration || results[0]["generation_id"] != results[1]["generation_id"] {
		t.Fatalf("replacement generation did not converge: old=%v results=%#v", oldGeneration, results)
	}
}

func TestRestartCallerTimeoutDoesNotCancelSharedProbe(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	origLaunch, origProbe := launchWeaver, probeRuntime
	t.Cleanup(func() { launchWeaver, probeRuntime = origLaunch, origProbe })
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		writeWeaverMetadata(t, world, cmd.Process.Pid, "weaver")
		return cmd, nil
	}
	probeStarted := make(chan struct{})
	allowProbe := make(chan struct{})
	probeRuntime = func(source string, world config.World) (restartProbeResult, error) {
		close(probeStarted)
		<-allowProbe
		return restartProbeResult{Success: true, Stage: "probe/complete", ProbeWorkspace: "probe", SourceWorkspace: world.ConfigDir, Log: "probe.log"}, nil
	}
	s := server{children: map[string]*weaverChild{}, transitions: map[string]*weaverTransition{}}
	if _, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}); err != nil {
		t.Fatal(err)
	}
	result, err := s.restartWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg, ReadyTimeoutMs: 10})
	if err == nil || !strings.Contains(err.Error(), "before timeout") || result != nil {
		t.Fatalf("expected caller-only timeout, result=%#v err=%v", result, err)
	}
	<-probeStarted
	close(allowProbe)
	transition := s.lifecycleTransition(world.ConfigDir)
	if transition == nil {
		t.Fatal("shared transition disappeared after caller timeout")
	}
	result, err = waitForLifecycleTransition(transition, 2*time.Second)
	if err != nil || result["state"] != "running" {
		t.Fatalf("shared restart did not continue after caller timeout: result=%#v err=%v", result, err)
	}
}

func TestFailedProbeRetainsOldGenerationAndDiagnostics(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	origLaunch, origProbe := launchWeaver, probeRuntime
	t.Cleanup(func() { launchWeaver, probeRuntime = origLaunch, origProbe })
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		writeWeaverMetadata(t, world, cmd.Process.Pid, "weaver")
		return cmd, nil
	}
	probeRuntime = func(source string, world config.World) (restartProbeResult, error) {
		return restartProbeResult{Success: false, Stage: "probe/failure", ProbeWorkspace: filepath.Join(t.TempDir(), "retained"), SourceWorkspace: world.ConfigDir, Log: "probe.log"}, nil
	}
	s := server{children: map[string]*weaverChild{}, transitions: map[string]*weaverTransition{}}
	if _, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}); err != nil {
		t.Fatal(err)
	}
	result, err := s.restartWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg, ReadyTimeoutMs: 2_000})
	if err != nil || result["state"] != "running" || result["probe_error"] == nil {
		t.Fatalf("failed probe should retain running old generation: result=%#v err=%v", result, err)
	}
	if !processAlive(s.children[world.ConfigDir].cmd.Process.Pid) {
		t.Fatal("failed probe stopped the admitted old generation")
	}
	record, ok := readRestartRecord(world)
	if !ok || record.State != restartStateRunning || record.Probe == nil {
		t.Fatalf("probe diagnostics were not retained: %#v ok=%v", record, ok)
	}
}
