//go:build integration

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
)

func TestRunPooledProbeProcessActualClojureChild(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	workingRoot, err := canonicalProbePath(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	originalConfig := filepath.Join(workingRoot, "member", config.DefaultWorkspace)
	if err := os.MkdirAll(originalConfig, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(originalConfig, config.ConfigFileName), []byte(`{"configFormat":"alpha","name":"probe-child"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	source, err := filepath.Abs(filepath.Join("..", "..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	source, err = canonicalProbePath(source)
	if err != nil {
		t.Fatal(err)
	}
	probeRoot := filepath.Join(workingRoot, "probe")
	manifest := poolProbeManifest{
		Format:               poolProbeFormat,
		JVMPool:              "backend",
		ProbeID:              "probe-child",
		CandidateHostID:      "probe-host-child",
		CandidateGeneration:  "probe-generation-child",
		ProbeRoot:            probeRoot,
		MillstrandSource:     source,
		Result:               filepath.Join(probeRoot, "result.json"),
		CollectiveDiagnostic: filepath.Join(probeRoot, "collective.jsonl"),
		Members: []poolProbeMember{{
			OriginalConfigDir:     originalConfig,
			OriginalSourceCWD:     filepath.Dir(filepath.Dir(originalConfig)),
			ProbeConfigDir:        filepath.Join(probeRoot, "members", "member", "config"),
			ProbeStateDir:         filepath.Join(probeRoot, "members", "member", "state"),
			ProbeDataDir:          filepath.Join(probeRoot, "members", "member", "data"),
			MemberDiagnostic:      filepath.Join(probeRoot, "members", "member", "diagnostic.jsonl"),
			Name:                  "probe-child",
			CandidateWeaverID:     "probe-weaver-child",
			CandidateGenerationID: "probe-generation-child",
		}},
	}
	if err := validatePoolProbeManifest(manifest); err != nil {
		t.Fatal(err)
	}
	result, err := runPooledProbeProcess(manifest)
	if err == nil {
		t.Fatalf("expected the dependency-free child fixture to fail explicitly, got success %+v", result)
	}
	if !strings.Contains(err.Error(), "reported failure") {
		t.Fatalf("actual child failure was not decoded as a probe result: %v", err)
	}
	if result.Success || result.Stage != "probe/failure" {
		t.Fatalf("actual child returned invalid failure result: %+v", result)
	}
	if _, err := os.Stat(filepath.Join(manifest.Members[0].ProbeConfigDir, config.ConfigFileName)); err != nil {
		t.Fatalf("child boundary did not receive copied config: %v", err)
	}
}

func TestRunPooledServingProcessActualClojureChild(t *testing.T) {
	stateRoot, err := os.MkdirTemp("/tmp", "jvm-pool-state-")
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("XDG_STATE_HOME", stateRoot)
	t.Cleanup(func() { _ = os.RemoveAll(stateRoot) })
	workingRoot, err := os.MkdirTemp("/tmp", "jvm-pool-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(workingRoot) })
	workingRoot, err = canonicalProbePath(workingRoot)
	if err != nil {
		t.Fatal(err)
	}
	source, err := filepath.Abs(filepath.Join("..", "..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	source, err = canonicalProbePath(source)
	if err != nil {
		t.Fatal(err)
	}

	memberRoot := filepath.Join(workingRoot, "members")
	snapshot := jvmpool.PoolSnapshot{Pool: "backend", Revision: newOpaqueID("membership")}
	for _, member := range []struct {
		name     string
		sentinel string
	}{
		{name: "A", sentinel: "A-started"},
		{name: "B", sentinel: "B-started"},
	} {
		configDir := filepath.Join(memberRoot, member.name, config.DefaultWorkspace)
		if err := os.MkdirAll(configDir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(configDir, config.ConfigFileName), []byte(`{"configFormat":"alpha","name":"`+member.name+`"}`), 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(configDir, "deps.edn"), []byte("{:paths []}\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(configDir, "init.clj"), []byte("(spit "+
			strconv.Quote(filepath.Join(configDir, "startup.sentinel"))+" "+
			strconv.Quote(member.sentinel)+")\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		canonicalConfig, err := canonicalProbePath(configDir)
		if err != nil {
			t.Fatal(err)
		}
		canonicalCWD, err := canonicalProbePath(filepath.Dir(filepath.Dir(configDir)))
		if err != nil {
			t.Fatal(err)
		}
		snapshot.Members = append(snapshot.Members, jvmpool.Member{
			ConfigDir: canonicalConfig,
			SourceCWD: canonicalCWD,
			JVMPool:   snapshot.Pool,
		})
	}

	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	manifest, host, err := poolHostFromSnapshot(snapshot, source, root)
	if err != nil {
		t.Fatal(err)
	}
	if err := writePoolLaunchManifest(host.ManifestPath, manifest); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(host.LogPath), 0o755); err != nil {
		t.Fatal(err)
	}
	logFile, err := os.OpenFile(host.LogPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer logFile.Close()

	cmd := exec.Command("clojure", poolWeaverArgs(host.ManifestPath, source)...)
	cmd.Dir = source
	cmd.Env = append(os.Environ(), launchTokenEnv(host.LaunchToken)...)
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	host.PID = cmd.Process.Pid
	done := make(chan error, 1)
	waitDone := make(chan struct{})
	go func() {
		defer close(waitDone)
		done <- cmd.Wait()
	}()
	t.Cleanup(func() {
		if processAlive(host.PID) {
			_ = cmd.Process.Signal(syscall.SIGTERM)
		}
		select {
		case <-waitDone:
		case <-time.After(10 * time.Second):
			_ = cmd.Process.Kill()
			<-waitDone
		}
	})

	statuses, err := waitForPoolReady(host, done, waitDone, 45*time.Second, nil)
	if err != nil {
		logBytes, _ := os.ReadFile(host.LogPath)
		t.Fatalf("real pooled host failed before readiness: %v\n%s", err, logBytes)
	}
	if len(statuses) != 2 {
		t.Fatalf("ready statuses = %d, want two members: %#v", len(statuses), statuses)
	}
	identities := make([]weaverIdentity, 0, len(host.Members))
	for _, member := range host.Members {
		status := statuses[member.World.ConfigDir]
		identity, err := identityFromStatus(status)
		if err != nil {
			t.Fatal(err)
		}
		identities = append(identities, identity)
		if identity.PID != host.PID {
			t.Fatalf("member %s pid = %d, want shared host pid %d", member.Name, identity.PID, host.PID)
		}
		sentinel, err := os.ReadFile(filepath.Join(member.World.ConfigDir, "startup.sentinel"))
		if err != nil {
			t.Fatalf("member %s startup sentinel missing: %v", member.Name, err)
		}
		want := member.Name + "-started"
		if string(sentinel) != want {
			t.Fatalf("member %s startup sentinel = %q, want %q", member.Name, sentinel, want)
		}
		endpointStatus, err := runtimeStatus(identity)
		if err != nil {
			t.Fatalf("member %s endpoint status failed: %v", member.Name, err)
		}
		if endpointStatus["weaver_id"] != identity.WeaverID || endpointStatus["generation_id"] != identity.GenerationID {
			t.Fatalf("member %s endpoint returned wrong identity: %#v", member.Name, endpointStatus)
		}
	}
	if identities[0].WeaverID == identities[1].WeaverID || identities[0].GenerationID == identities[1].GenerationID || identities[0].Socket == identities[1].Socket || identities[0].StateDir == identities[1].StateDir || identities[0].DataDir == identities[1].DataDir {
		t.Fatalf("pooled members did not retain distinct identities and private paths: %#v", identities)
	}
	if statuses[host.Members[0].World.ConfigDir]["database_path"] == statuses[host.Members[1].World.ConfigDir]["database_path"] {
		t.Fatalf("pooled members share database path: %#v", statuses)
	}
}
