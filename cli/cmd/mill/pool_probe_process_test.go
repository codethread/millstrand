//go:build integration

package main

import (
	"fmt"
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

func TestRunPooledProbeProcessActualClojureChildMissingDeps(t *testing.T) {
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
	t.Cleanup(func() { _ = os.RemoveAll(probeRoot) })
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

func TestRunPooledProbeProcessActualClojureChildSuccess(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	workingRoot, err := canonicalProbePath(t.TempDir())
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
	probeRoot := filepath.Join(workingRoot, "probe")
	t.Cleanup(func() { _ = os.RemoveAll(probeRoot) })
	manifest := poolProbeManifest{
		Format:               poolProbeFormat,
		JVMPool:              "backend",
		ProbeID:              "probe-child-success",
		CandidateHostID:      "probe-host-child-success",
		CandidateGeneration:  "probe-generation-child-success",
		ProbeRoot:            probeRoot,
		MillstrandSource:     source,
		Result:               filepath.Join(probeRoot, "result.json"),
		CollectiveDiagnostic: filepath.Join(probeRoot, "collective.jsonl"),
	}

	fixtureFiles := func(value string) map[string][]byte {
		return map[string][]byte{
			config.ConfigFileName: []byte(`{"configFormat":"alpha","name":"` + value + `"}`),
			"deps.edn":            []byte("{:paths [\".\"] :deps {}}\n"),
			"init.clj": []byte(`(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(spit (str *file* ".started") "` + value + `")
(runtime/module! runtime :pool-probe-sentinel
                 {:file "sentinel.clj"
                  :required? true})
`),
			"sentinel.clj": []byte(`(ns pool-probe-sentinel
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(def ^:private sentinel-arg-spec
  {:op "pool-probe-sentinel"
   :doc "` + value + `"
   :hook-class :read
   :deadline-class :standard})

(millstrand/defop! pool-probe-sentinel
  "` + value + `"
  {:arg-spec sentinel-arg-spec}
  [_]
  {:sentinel "` + value + `"})
`),
		}
	}
	originalContents := map[string]map[string][]byte{}
	for _, fixture := range []struct {
		name  string
		value string
	}{
		{name: "A", value: "probe-sentinel-A"},
		{name: "B", value: "probe-sentinel-B"},
	} {
		originalConfig := filepath.Join(workingRoot, fixture.name, config.DefaultWorkspace)
		if err := os.MkdirAll(originalConfig, 0o755); err != nil {
			t.Fatal(err)
		}
		files := fixtureFiles(fixture.value)
		originalContents[originalConfig] = files
		for name, contents := range files {
			if err := os.WriteFile(filepath.Join(originalConfig, name), contents, 0o644); err != nil {
				t.Fatal(err)
			}
		}
		canonicalConfig, err := canonicalProbePath(originalConfig)
		if err != nil {
			t.Fatal(err)
		}
		manifest.Members = append(manifest.Members, poolProbeMember{
			OriginalConfigDir:     canonicalConfig,
			OriginalSourceCWD:     workingRoot,
			ProbeConfigDir:        filepath.Join(probeRoot, "members", fixture.name, "config"),
			ProbeStateDir:         filepath.Join(probeRoot, "members", fixture.name, "state"),
			ProbeDataDir:          filepath.Join(probeRoot, "members", fixture.name, "data"),
			MemberDiagnostic:      filepath.Join(probeRoot, "members", fixture.name, "diagnostic.jsonl"),
			Name:                  fixture.name,
			CandidateWeaverID:     "probe-weaver-" + fixture.name,
			CandidateGenerationID: "probe-generation-" + fixture.name,
		})
	}
	if err := validatePoolProbeManifest(manifest); err != nil {
		t.Fatal(err)
	}
	result, err := runPooledProbeProcess(manifest)
	if err != nil {
		diagnostic, _ := os.ReadFile(manifest.CollectiveDiagnostic)
		t.Fatalf("actual child success probe failed: %v\n%s", err, diagnostic)
	}
	if !result.Success || result.Stage != "probe/complete" {
		t.Fatalf("actual child returned invalid success result: %+v", result)
	}
	if len(result.Members) != 2 || len(result.Completed) != 4 {
		t.Fatalf("actual child did not complete the basis and both members: %#v", result)
	}
	if result.Completed[1] != "member/"+manifest.Members[0].OriginalConfigDir || result.Completed[2] != "member/"+manifest.Members[1].OriginalConfigDir {
		t.Fatalf("actual child completion order does not cover both members: %#v", result.Completed)
	}
	seenValues := map[string]bool{}
	for i, member := range result.Members {
		if member.Status != "validated" || member.BaselineKind != "newcomer" {
			t.Fatalf("member %d was not validated as a newcomer: %#v", i, member)
		}
		ops, ok := member.RegistryProjection["ops"].(map[string]any)
		if !ok {
			t.Fatalf("member %d has no ops registry projection: %#v", i, member.RegistryProjection)
		}
		effective, ok := ops["effective"].(map[string]any)
		if !ok {
			t.Fatalf("member %d has no effective ops projection: %#v", i, ops)
		}
		effectiveEntries, ok := effective["ops"].(map[string]any)
		if !ok {
			t.Fatalf("member %d has no effective ops entries: %#v", i, effective)
		}
		sentinel, ok := effectiveEntries["pool-probe-sentinel"].(map[string]any)
		if !ok {
			t.Fatalf("member %d has no sentinel registration: %#v", i, effectiveEntries)
		}
		value, ok := sentinel["value"].(map[string]any)
		if !ok || value["doc"] == nil {
			t.Fatalf("member %d sentinel projection has no value: %#v", i, sentinel)
		}
		seenValues[fmt.Sprint(value["doc"])] = true
		if member.ProbeConfigDir != manifest.Members[i].ProbeConfigDir || member.CandidateWeaverID != manifest.Members[i].CandidateWeaverID || member.CandidateGenerationID != manifest.Members[i].CandidateGenerationID {
			t.Fatalf("member %d result identity does not match manifest: %#v", i, member)
		}
		if _, err := os.Stat(filepath.Join(member.ProbeConfigDir, "init.clj.started")); err != nil {
			t.Fatalf("member %d startup effect did not stay in private config: %v", i, err)
		}
		for _, artifact := range []string{
			filepath.Join(manifest.Members[i].ProbeStateDir, "weaver.json"),
			filepath.Join(manifest.Members[i].ProbeStateDir, "weaver.sock"),
			filepath.Join(manifest.Members[i].ProbeDataDir, "millstrand.sqlite"),
		} {
			if _, err := os.Stat(artifact); !os.IsNotExist(err) {
				t.Fatalf("member %d probe left serving artifact %s: %v", i, artifact, err)
			}
		}
	}
	if len(seenValues) != 2 || !seenValues["probe-sentinel-A"] || !seenValues["probe-sentinel-B"] {
		t.Fatalf("sentinel registrations did not retain distinct per-member values: %#v", seenValues)
	}
	for originalConfig, files := range originalContents {
		for name, want := range files {
			got, err := os.ReadFile(filepath.Join(originalConfig, name))
			if err != nil {
				t.Fatal(err)
			}
			if string(got) != string(want) {
				t.Fatalf("source config %s changed", filepath.Join(originalConfig, name))
			}
		}
		for _, artifact := range []string{"weaver.json", "weaver.sock"} {
			if _, err := os.Stat(filepath.Join(originalConfig, artifact)); !os.IsNotExist(err) {
				t.Fatalf("source config has serving artifact %s: %v", artifact, err)
			}
		}
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
