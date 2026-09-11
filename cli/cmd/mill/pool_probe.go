package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
)

const (
	poolProbeFormat       = "millstrand.jvm-pool-probe/v1"
	poolProbeResultFormat = "millstrand.jvm-pool-probe-result/v1"
)

type poolProbeManifest struct {
	Format               string            `json:"format"`
	JVMPool              string            `json:"jvm_pool"`
	ProbeID              string            `json:"probe_id"`
	CandidateHostID      string            `json:"candidate_host_id"`
	CandidateGeneration  string            `json:"candidate_host_generation_id"`
	ProbeRoot            string            `json:"probe_root"`
	MillstrandSource     string            `json:"millstrand_source"`
	Result               string            `json:"result"`
	CollectiveDiagnostic string            `json:"collective_diagnostic"`
	Members              []poolProbeMember `json:"members"`
}

type poolProbeMember struct {
	OriginalConfigDir     string         `json:"original_config_dir"`
	OriginalSourceCWD     string         `json:"original_source_cwd"`
	ProbeConfigDir        string         `json:"probe_config_dir"`
	ProbeStateDir         string         `json:"probe_state_dir"`
	ProbeDataDir          string         `json:"probe_data_dir"`
	MemberDiagnostic      string         `json:"member_diagnostic"`
	Name                  string         `json:"name"`
	CandidateWeaverID     string         `json:"candidate_weaver_id"`
	CandidateGenerationID string         `json:"candidate_generation_id"`
	OldMemberBaseline     map[string]any `json:"old_member_baseline"`
}

type poolProbeResult struct {
	Format               string                  `json:"format"`
	ProbeID              string                  `json:"probe_id"`
	Success              bool                    `json:"success"`
	Stage                string                  `json:"stage"`
	ProbeRoot            string                  `json:"probe_root"`
	SourceWorkspace      string                  `json:"source_workspace"`
	Completed            []string                `json:"completed"`
	Members              []poolProbeResultMember `json:"members"`
	CollectiveDiagnostic string                  `json:"collective_diagnostic"`
	Log                  string                  `json:"log"`
}

type poolProbeResultMember struct {
	OriginalConfigDir     string         `json:"original_config_dir"`
	ProbeConfigDir        string         `json:"probe_config_dir"`
	CandidateWeaverID     string         `json:"candidate_weaver_id"`
	CandidateGenerationID string         `json:"candidate_generation_id"`
	BaselineKind          string         `json:"baseline_kind"`
	Status                string         `json:"status"`
	RegistryProjection    map[string]any `json:"registry_projection"`
	RegistryDiff          map[string]any `json:"registry_diff"`
	MemberDiagnostic      string         `json:"member_diagnostic"`
}

func validatePoolProbeManifest(m poolProbeManifest) error {
	if m.Format != poolProbeFormat || strings.TrimSpace(m.JVMPool) == "" || strings.TrimSpace(m.ProbeID) == "" || strings.TrimSpace(m.CandidateHostID) == "" || strings.TrimSpace(m.CandidateGeneration) == "" || strings.TrimSpace(m.ProbeRoot) == "" || strings.TrimSpace(m.MillstrandSource) == "" || strings.TrimSpace(m.Result) == "" || strings.TrimSpace(m.CollectiveDiagnostic) == "" {
		return errors.New("invalid JVM pool probe manifest identity")
	}
	for label, path := range map[string]string{"probe_root": m.ProbeRoot, "millstrand_source": m.MillstrandSource, "result": m.Result, "collective_diagnostic": m.CollectiveDiagnostic} {
		if !filepath.IsAbs(path) || filepath.Clean(path) != path {
			return fmt.Errorf("probe manifest %s must be a canonical absolute path", label)
		}
	}
	if !pathBelow(m.ProbeRoot, m.Result) || !pathBelow(m.ProbeRoot, m.CollectiveDiagnostic) {
		return errors.New("probe result paths must remain below private probe root")
	}
	if len(m.Members) == 0 {
		return errors.New("JVM pool probe manifest must contain members")
	}
	seen := map[string]bool{}
	privatePaths := make([]string, 0, len(m.Members)*4)
	for i, member := range m.Members {
		if member.OriginalConfigDir == "" || member.OriginalSourceCWD == "" || member.ProbeConfigDir == "" || member.ProbeStateDir == "" || member.ProbeDataDir == "" || member.MemberDiagnostic == "" || member.Name == "" || member.CandidateWeaverID == "" || member.CandidateGenerationID == "" {
			return fmt.Errorf("invalid JVM pool probe member %d", i)
		}
		if seen[member.OriginalConfigDir] {
			return fmt.Errorf("duplicate JVM pool probe member %q", member.OriginalConfigDir)
		}
		seen[member.OriginalConfigDir] = true
		for label, path := range map[string]string{"original_config_dir": member.OriginalConfigDir, "original_source_cwd": member.OriginalSourceCWD, "probe_config_dir": member.ProbeConfigDir, "probe_state_dir": member.ProbeStateDir, "probe_data_dir": member.ProbeDataDir, "member_diagnostic": member.MemberDiagnostic} {
			if !filepath.IsAbs(path) || filepath.Clean(path) != path {
				return fmt.Errorf("probe member %s %s must be a canonical absolute path", member.OriginalConfigDir, label)
			}
		}
		if !pathBelow(m.ProbeRoot, member.ProbeConfigDir) || !pathBelow(m.ProbeRoot, member.ProbeStateDir) || !pathBelow(m.ProbeRoot, member.ProbeDataDir) || !pathBelow(m.ProbeRoot, member.MemberDiagnostic) {
			return fmt.Errorf("probe member %s has a path outside private probe root", member.OriginalConfigDir)
		}
		privatePaths = append(privatePaths, member.ProbeConfigDir, member.ProbeStateDir, member.ProbeDataDir, member.MemberDiagnostic)
		if i > 0 && m.Members[i-1].OriginalConfigDir >= member.OriginalConfigDir {
			return errors.New("JVM pool probe members must be sorted by original_config_dir")
		}
		if member.OldMemberBaseline != nil {
			if err := validateProbeBaseline(member.OldMemberBaseline); err != nil {
				return fmt.Errorf("probe member %s baseline: %w", member.OriginalConfigDir, err)
			}
		}
	}
	for i, path := range privatePaths {
		for _, other := range privatePaths[:i] {
			if pathsOverlap(path, other) {
				return fmt.Errorf("probe member private paths overlap: %s and %s", path, other)
			}
		}
		if pathsOverlap(path, m.Result) || pathsOverlap(path, m.CollectiveDiagnostic) {
			return fmt.Errorf("probe member private path %s overlaps a probe artifact", path)
		}
	}
	return nil
}

func pathBelow(root, path string) bool {
	rel, err := filepath.Rel(root, path)
	return err == nil && rel != "." && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func pathsOverlap(a, b string) bool {
	return a == b || pathBelow(a, b) || pathBelow(b, a)
}

func validateProbeBaseline(value map[string]any) error {
	if len(value) != 2 || value["status"] != "admitted" {
		return errors.New("baseline must contain status admitted and projection")
	}
	projection, ok := value["projection"].(map[string]any)
	if !ok || !validRegistryProjection(projection) {
		return errors.New("baseline projection must be an object")
	}
	return nil
}

func validRegistryProjection(value map[string]any) bool {
	for _, raw := range value {
		row, ok := raw.(map[string]any)
		if !ok || len(row) != 3 || !hasMapKeys(row, "effective", "owners", "provenance") || !validProjectionValue(row["effective"]) || !validProjectionValue(row["owners"]) || !validProjectionValue(row["provenance"]) {
			return false
		}
	}
	return true
}

func validProjectionValue(value any) bool {
	switch value := value.(type) {
	case nil, string, bool, float64, json.Number:
		return true
	case []any:
		for _, item := range value {
			if !validProjectionValue(item) {
				return false
			}
		}
		return true
	case map[string]any:
		if _, callable := value["callable"]; callable || value["class"] != nil {
			return len(value) == 2 && value["callable"] == true && strings.TrimSpace(fmt.Sprint(value["class"])) != ""
		}
		for key, item := range value {
			if key == "" || !validProjectionValue(item) {
				return false
			}
		}
		return true
	default:
		return false
	}
}

func hasMapKeys(value map[string]any, keys ...string) bool {
	if len(value) != len(keys) {
		return false
	}
	for _, key := range keys {
		if _, ok := value[key]; !ok {
			return false
		}
	}
	return true
}

func validatePoolProbeResult(result poolProbeResult, manifest poolProbeManifest) error {
	if err := validatePoolProbeManifest(manifest); err != nil {
		return err
	}
	if result.Format != poolProbeResultFormat || result.ProbeID != manifest.ProbeID || result.ProbeRoot != manifest.ProbeRoot || strings.TrimSpace(result.Stage) == "" || strings.TrimSpace(result.SourceWorkspace) == "" || strings.TrimSpace(result.CollectiveDiagnostic) == "" || strings.TrimSpace(result.Log) == "" || result.Completed == nil {
		return errors.New("invalid JVM pool probe result identity")
	}
	for label, path := range map[string]string{"probe_root": result.ProbeRoot, "source_workspace": result.SourceWorkspace, "collective_diagnostic": result.CollectiveDiagnostic, "log": result.Log} {
		if !filepath.IsAbs(path) || filepath.Clean(path) != path {
			return fmt.Errorf("probe result %s must be a canonical absolute path", label)
		}
	}
	wantStage := "probe/failure"
	if result.Success {
		wantStage = "probe/complete"
	}
	if result.Stage != wantStage {
		return fmt.Errorf("JVM pool probe result stage %q does not match success=%t", result.Stage, result.Success)
	}
	if len(result.Members) != len(manifest.Members) {
		return fmt.Errorf("JVM pool probe result covers %d members, want %d", len(result.Members), len(manifest.Members))
	}
	seen := map[string]bool{}
	for i, actual := range result.Members {
		expected := manifest.Members[i]
		if actual.OriginalConfigDir != expected.OriginalConfigDir || actual.ProbeConfigDir != expected.ProbeConfigDir || actual.CandidateWeaverID != expected.CandidateWeaverID || actual.CandidateGenerationID != expected.CandidateGenerationID || actual.MemberDiagnostic != expected.MemberDiagnostic {
			return fmt.Errorf("JVM pool probe result member %d does not match manifest", i)
		}
		if seen[actual.OriginalConfigDir] {
			return fmt.Errorf("duplicate JVM pool probe result member %q", actual.OriginalConfigDir)
		}
		seen[actual.OriginalConfigDir] = true
		wantBaseline := "newcomer"
		if expected.OldMemberBaseline != nil {
			wantBaseline = "live"
		}
		if actual.BaselineKind != wantBaseline {
			return fmt.Errorf("probe member %s has baseline kind %q, want %q", actual.OriginalConfigDir, actual.BaselineKind, wantBaseline)
		}
		if actual.RegistryProjection == nil || !validRegistryProjection(actual.RegistryProjection) {
			return fmt.Errorf("probe member %s has no registry projection", actual.OriginalConfigDir)
		}
		if wantBaseline == "newcomer" {
			if actual.RegistryDiff != nil {
				return fmt.Errorf("newcomer probe member %s must have registry_diff null", actual.OriginalConfigDir)
			}
		} else if actual.RegistryDiff == nil || !hasMapKeys(actual.RegistryDiff, "added", "removed", "changed") {
			return fmt.Errorf("live probe member %s must have registry_diff", actual.OriginalConfigDir)
		} else {
			for _, key := range []string{"added", "removed", "changed"} {
				projection, ok := actual.RegistryDiff[key].(map[string]any)
				if !ok || !validRegistryProjection(projection) {
					return fmt.Errorf("live probe member %s has invalid registry_diff.%s", actual.OriginalConfigDir, key)
				}
			}
		}
		if result.Success && actual.Status != "validated" {
			return fmt.Errorf("successful JVM pool probe member %s has status %q", actual.OriginalConfigDir, actual.Status)
		}
		if !result.Success && actual.Status != "validated" && actual.Status != "failed" {
			return fmt.Errorf("failed JVM pool probe member %s has invalid status %q", actual.OriginalConfigDir, actual.Status)
		}
	}
	return nil
}

func decodePoolProbeResult(data []byte, manifest poolProbeManifest) (poolProbeResult, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var result poolProbeResult
	if err := decoder.Decode(&result); err != nil {
		return poolProbeResult{}, fmt.Errorf("malformed JVM pool probe result: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return poolProbeResult{}, errors.New("malformed JVM pool probe result: trailing JSON")
	}
	if err := validatePoolProbeResult(result, manifest); err != nil {
		return poolProbeResult{}, err
	}
	return result, nil
}

func canonicalProbePath(path string) (string, error) {
	if strings.TrimSpace(path) == "" {
		return "", errors.New("path is required")
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	abs = filepath.Clean(abs)
	missing := []string{}
	for current := abs; ; current = filepath.Dir(current) {
		real, evalErr := filepath.EvalSymlinks(current)
		if evalErr == nil {
			for i := len(missing) - 1; i >= 0; i-- {
				real = filepath.Join(real, missing[i])
			}
			return filepath.Clean(real), nil
		}
		if !os.IsNotExist(evalErr) {
			return "", evalErr
		}
		parent := filepath.Dir(current)
		if parent == current {
			return "", evalErr
		}
		missing = append(missing, filepath.Base(current))
	}
}

func poolProbeManifestForHost(host *weaverHost, snapshot jvmpool.PoolSnapshot, source, root string) (poolProbeManifest, error) {
	probeID := newOpaqueID("probe")
	canonicalRoot, err := canonicalProbePath(root)
	if err != nil {
		return poolProbeManifest{}, fmt.Errorf("canonicalize probe root %s: %w", root, err)
	}
	canonicalSource, err := canonicalProbePath(source)
	if err != nil {
		return poolProbeManifest{}, fmt.Errorf("canonicalize Millstrand source %s: %w", source, err)
	}
	probeRoot := filepath.Join(canonicalRoot, "jvm-pools", "pool-probes", probeID)
	manifest := poolProbeManifest{Format: poolProbeFormat, JVMPool: host.Pool, ProbeID: probeID, CandidateHostID: newOpaqueID("probe-host"), CandidateGeneration: newOpaqueID("probe-host-generation"), ProbeRoot: probeRoot, MillstrandSource: canonicalSource, Result: filepath.Join(probeRoot, "result.json"), CollectiveDiagnostic: filepath.Join(probeRoot, "collective.jsonl")}
	for _, registered := range snapshot.Members {
		identity, err := config.CanonicalConfigIdentity(registered.ConfigDir)
		if err != nil {
			return poolProbeManifest{}, fmt.Errorf("canonicalize JVM pool member %s: %w", registered.ConfigDir, err)
		}
		memberRoot := filepath.Join(probeRoot, "members", "member-"+config.WorldHash(identity))
		var baseline map[string]any
		for _, member := range host.Members {
			if member.World.ConfigDir != identity {
				continue
			}
			status, err := poolProbeBaselineStatus(member.Identity)
			if err != nil {
				return poolProbeManifest{}, fmt.Errorf("JVM pool member %s baseline status failed: %w", registered.ConfigDir, err)
			}
			projection, ok := status["registry_projection"].(map[string]any)
			if !ok || !validRegistryProjection(projection) {
				return poolProbeManifest{}, fmt.Errorf("JVM pool member %s baseline status omitted a valid registry_projection", registered.ConfigDir)
			}
			baseline = map[string]any{"status": "admitted", "projection": projection}
		}
		world, err := config.RuntimeWorld(identity)
		if err != nil {
			return poolProbeManifest{}, err
		}
		name, err := friendlyName(world, "")
		if err != nil {
			return poolProbeManifest{}, err
		}
		canonicalSourceCWD, err := canonicalProbePath(registered.SourceCWD)
		if err != nil {
			return poolProbeManifest{}, fmt.Errorf("canonicalize JVM pool member source cwd %s: %w", registered.SourceCWD, err)
		}
		manifest.Members = append(manifest.Members, poolProbeMember{OriginalConfigDir: identity, OriginalSourceCWD: canonicalSourceCWD, ProbeConfigDir: filepath.Join(memberRoot, "config"), ProbeStateDir: filepath.Join(memberRoot, "state"), ProbeDataDir: filepath.Join(memberRoot, "data"), MemberDiagnostic: filepath.Join(memberRoot, "diagnostic.jsonl"), Name: name, CandidateWeaverID: newOpaqueID("probe-weaver"), CandidateGenerationID: newOpaqueID("probe-generation"), OldMemberBaseline: baseline})
	}
	return manifest, validatePoolProbeManifest(manifest)
}

// poolProbeBaselineStatus is the endpoint-backed semantic baseline fetch.
// Missing or malformed projections are probe failures, never an empty
// substitute. The seam keeps the failure path deterministic in unit tests.
var poolProbeBaselineStatus = runtimeStatusWithRegistryProjection

var poolProbeRuntime = runPooledProbeProcess

const pooledProbeExpression = `(require 'clojure.data.json 'millstrand.core.weaver.pool 'millstrand.core.weaver.pool-wire) (try (let [manifest (millstrand.core.weaver.pool-wire/read-probe-manifest (System/getenv "MILLSTRAND_POOL_PROBE_MANIFEST"))] (millstrand.core.weaver.pool/probe! manifest {:runtime-coordinate {:local/root (System/getenv "MILLSTRAND_POOL_PROBE_SOURCE")}}) (System/exit 0)) (catch Throwable throwable (binding [*out* *err*] (prn throwable)) (System/exit 1)))`

func runPooledProbeProcess(manifest poolProbeManifest) (poolProbeResult, error) {
	args := []string{"-Srepro", "-Sdeps", fmt.Sprintf("{:deps {org.clojure/clojure {:mvn/version \"1.12.0\"} org.clojure/data.json {:mvn/version \"2.5.1\"} org.clojure/tools.deps {:mvn/version \"0.31.1642\"}} :paths [%q]}", filepath.Join(manifest.MillstrandSource, "src")), "-M", "-e", pooledProbeExpression}
	cmd := exec.Command("clojure", args...)
	cmd.Dir = manifest.MillstrandSource
	cmd.Env = append(withoutLaunchToken(os.Environ()), "MILLSTRAND_POOL_PROBE_MANIFEST="+filepath.Join(manifest.ProbeRoot, "manifest.json"), "MILLSTRAND_POOL_PROBE_SOURCE="+manifest.MillstrandSource)
	if err := atomicPoolJSON(filepath.Join(manifest.ProbeRoot, "manifest.json"), manifest); err != nil {
		return poolProbeResult{}, err
	}
	if err := preparePoolProbeConfigs(manifest); err != nil {
		return poolProbeResult{}, err
	}
	var stderr cappedBuffer
	stderr.limit = maxProbeStderr
	cmd.Stdout = io.Discard
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return poolProbeResult{}, fmt.Errorf("pooled replacement probe process failed: %w: %s", err, stderr.String())
	}
	data, err := os.ReadFile(manifest.Result)
	if err != nil {
		return poolProbeResult{}, fmt.Errorf("pooled replacement probe did not write result: %w", err)
	}
	result, err := decodePoolProbeResult(data, manifest)
	if err != nil {
		return poolProbeResult{}, err
	}
	if !result.Success {
		return result, fmt.Errorf("pooled replacement probe reported failure at %s", result.Stage)
	}
	return result, nil
}

func preparePoolProbeConfigs(manifest poolProbeManifest) error {
	for _, member := range manifest.Members {
		if err := copyPoolProbeConfig(member.OriginalConfigDir, member.ProbeConfigDir); err != nil {
			return fmt.Errorf("copy JVM pool member config %s to %s: %w", member.OriginalConfigDir, member.ProbeConfigDir, err)
		}
	}
	return nil
}

func copyPoolProbeConfig(source, target string) error {
	canonicalSource, err := filepath.EvalSymlinks(source)
	if err != nil {
		return fmt.Errorf("resolve source: %w", err)
	}
	sourceInfo, err := os.Stat(canonicalSource)
	if err != nil {
		return fmt.Errorf("stat source: %w", err)
	}
	if !sourceInfo.IsDir() {
		return errors.New("source is not a directory")
	}
	target = filepath.Clean(target)
	if pathsOverlap(canonicalSource, target) {
		return fmt.Errorf("source and destination overlap")
	}
	if err := os.MkdirAll(target, 0o755); err != nil {
		return fmt.Errorf("create destination: %w", err)
	}
	return filepath.Walk(canonicalSource, func(path string, info os.FileInfo, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		relative, err := filepath.Rel(canonicalSource, path)
		if err != nil {
			return err
		}
		destination := target
		if relative != "." {
			destination = filepath.Join(target, relative)
		}
		if info.IsDir() {
			if err := os.MkdirAll(destination, info.Mode().Perm()); err != nil {
				return err
			}
			return os.Chmod(destination, info.Mode().Perm())
		}
		if info.Mode()&os.ModeSymlink != 0 {
			resolved, err := os.Stat(path)
			if err != nil {
				return fmt.Errorf("resolve symlink: %w", err)
			}
			if resolved.IsDir() {
				return errors.New("symlinked directories are not supported")
			}
			info = resolved
		}
		if !info.Mode().IsRegular() {
			return fmt.Errorf("unsupported source entry mode %s", info.Mode())
		}
		if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
			return err
		}
		input, err := os.Open(path)
		if err != nil {
			return err
		}
		output, err := os.OpenFile(destination, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, info.Mode().Perm())
		if err != nil {
			_ = input.Close()
			return err
		}
		_, copyErr := io.Copy(output, input)
		closeOutputErr := output.Close()
		closeInputErr := input.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeOutputErr != nil {
			return closeOutputErr
		}
		if closeInputErr != nil {
			return closeInputErr
		}
		return os.Chmod(destination, info.Mode().Perm())
	})
}

func withoutLaunchToken(environment []string) []string {
	filtered := make([]string, 0, len(environment))
	for _, entry := range environment {
		if strings.HasPrefix(entry, launchTokenEnvVar+"=") {
			continue
		}
		filtered = append(filtered, entry)
	}
	return filtered
}

func executePooledProbe(manifest poolProbeManifest) (poolProbeResult, error) {
	if err := os.MkdirAll(manifest.ProbeRoot, 0o755); err != nil {
		return poolProbeResult{}, err
	}
	result, err := poolProbeRuntime(manifest)
	if err != nil {
		return poolProbeResult{}, err
	}
	if err := validatePoolProbeResult(result, manifest); err != nil {
		return poolProbeResult{}, err
	}
	if err := atomicPoolJSON(manifest.Result, result); err != nil {
		return poolProbeResult{}, err
	}
	if !result.Success {
		return result, fmt.Errorf("pooled replacement probe reported failure at %s", result.Stage)
	}
	return result, nil
}

func cleanupPoolProbe(manifest poolProbeManifest) error {
	if strings.TrimSpace(manifest.ProbeRoot) == "" {
		return errors.New("probe root is required for cleanup")
	}
	return os.RemoveAll(manifest.ProbeRoot)
}
