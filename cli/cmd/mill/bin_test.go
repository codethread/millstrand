package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/spf13/cobra"
	"skein-strand-cli/internal/client"
)

func TestBinPlanRejectsMalformedExecShapes(t *testing.T) {
	tests := []struct {
		name string
		json string
		want string
	}{
		{
			name: "both path and command",
			json: `{"operation":"bins plan","bin":"x","runnable":true,"exec":{"path":"/x","command":"x","env":{}}}`,
			want: "exactly one",
		},
		{
			name: "neither path nor command",
			json: `{"operation":"bins plan","bin":"x","runnable":true,"exec":{"env":{}}}`,
			want: "exactly one",
		},
		{
			name: "command with boolean runnable",
			json: `{"operation":"bins plan","bin":"x","runnable":true,"exec":{"command":"x","env":{}}}`,
			want: "null runnable",
		},
		{
			name: "path with null runnable",
			json: `{"operation":"bins plan","bin":"x","runnable":null,"exec":{"path":"/x","env":{}}}`,
			want: "boolean runnable",
		},
		{
			name: "missing environment",
			json: `{"operation":"bins plan","bin":"x","runnable":true,"exec":{"path":"/x"}}`,
			want: "missing exec.env",
		},
		{
			name: "wrong operation",
			json: `{"operation":"bins list","bin":"x","runnable":true,"exec":{"path":"/x","env":{}}}`,
			want: "operation must be",
		},
		{
			name: "empty build argv",
			json: `{"operation":"bins plan","bin":"x","runnable":true,"exec":{"path":"/x","env":{}},"build":{"argv":[],"cwd":"/tmp"}}`,
			want: "build.argv",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var plan BinPlan
			err := json.Unmarshal([]byte(tt.json), &plan)
			if err == nil || !strings.Contains(err.Error(), tt.want) {
				t.Fatalf("expected malformed plan mentioning %q, got %v", tt.want, err)
			}
		})
	}
}

func TestBinPlanDecodesPathAndCommandForms(t *testing.T) {
	pathPlan := `{"operation":"bins plan","bin":"x","runnable":false,"exec":{"path":"/tmp/x","env":{"SKEIN_WORKSPACE":"/tmp/ws"}},"build":{"argv":["bun","install"],"cwd":"/tmp"}}`
	var path BinPlan
	if err := json.Unmarshal([]byte(pathPlan), &path); err != nil {
		t.Fatal(err)
	}
	if path.Exec.Path != "/tmp/x" || path.Exec.Command != "" || path.Runnable == nil || *path.Runnable || path.Build == nil {
		t.Fatalf("unexpected path plan: %#v", path)
	}

	commandPlan := `{"operation":"bins plan","bin":"x","runnable":null,"exec":{"command":"x","env":{}}}`
	var command BinPlan
	if err := json.Unmarshal([]byte(commandPlan), &command); err != nil {
		t.Fatal(err)
	}
	if command.Exec.Command != "x" || command.Exec.Path != "" || command.Runnable != nil {
		t.Fatalf("unexpected command plan: %#v", command)
	}
}

func TestParseBinInvocationCutsFlagsAtBinName(t *testing.T) {
	cmd := &cobra.Command{}
	cmd.Flags().String("workspace", "", "")
	parsed, err := parseBinInvocation(cmd, []string{"--workspace", "/tmp/ws", "dashboard", "--help", "--workspace", "child"}, true)
	if err != nil {
		t.Fatal(err)
	}
	want := parsedBinInvocation{workspace: "/tmp/ws", bin: "dashboard", args: []string{"--help", "--workspace", "child"}}
	if !reflect.DeepEqual(parsed, want) {
		t.Fatalf("parsed invocation = %#v, want %#v", parsed, want)
	}
}

func TestParseBinInvocationHelpBeforeBinAndFlagErrors(t *testing.T) {
	cmd := &cobra.Command{}
	cmd.Flags().String("workspace", "", "")
	parsed, err := parseBinInvocation(cmd, []string{"--help"}, true)
	if err != nil || !parsed.help {
		t.Fatalf("pre-bin help not recognized: %#v %v", parsed, err)
	}
	if _, err := parseBinInvocation(cmd, []string{"--unknown", "x"}, true); err == nil {
		t.Fatal("unknown pre-bin flag was accepted")
	}
}

func TestOverlayEnvironmentReplacesAndPreservesCallerValues(t *testing.T) {
	got := overlayEnvironment([]string{"PATH=/bin", "SKEIN_WORKSPACE=old", "KEEP=yes"}, map[string]string{"SKEIN_WORKSPACE": "/ws", "NEW": "value"})
	want := []string{"PATH=/bin", "SKEIN_WORKSPACE=/ws", "KEEP=yes", "NEW=value"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("environment = %#v, want %#v", got, want)
	}
}

func TestInvokeBinUsesSelectedWorldAndReturnsRelayedJSON(t *testing.T) {
	original := binInvoke
	t.Cleanup(func() { binInvoke = original })
	var gotWorld client.MillWorldRequest
	var gotEnvelope map[string]any
	binInvoke = func(world client.MillWorldRequest, envelope map[string]any, stdout, stderr io.Writer) (int, error) {
		gotWorld = world
		gotEnvelope = envelope
		_, _ = stdout.Write([]byte(`{"operation":"bins list","bins":[]}` + "\n"))
		return 0, nil
	}
	world := client.MillWorldRequest{CWD: "/caller", ConfigDir: "/workspace"}
	result, err := invokeBin(world, []string{"list"})
	if err != nil {
		t.Fatal(err)
	}
	if string(result) != `{"operation":"bins list","bins":[]}`+"\n" {
		t.Fatalf("result = %q", result)
	}
	if gotWorld != world || gotEnvelope["name"] != "bins" || gotEnvelope["workspace"] != "/workspace" || gotEnvelope["cwd"] != "/caller" {
		t.Fatalf("selected world/envelope mismatch: world=%#v envelope=%#v", gotWorld, gotEnvelope)
	}
}

func TestRunBinExecAppendsOpaqueArgumentsAndUsesOverlay(t *testing.T) {
	originalInvoke, originalExec := binInvoke, execBin
	t.Cleanup(func() { binInvoke, execBin = originalInvoke, originalExec })
	binInvoke = fakePlanInvoker(`{"operation":"bins plan","bin":"dashboard","runnable":true,"exec":{"path":"/tmp/dashboard","env":{"SKEIN_WORKSPACE":"/selected"}}}`)
	var gotPath string
	var gotArgv []string
	var gotEnv []string
	execBin = func(path string, argv []string, env []string) error {
		gotPath = path
		gotArgv = append([]string(nil), argv...)
		gotEnv = append([]string(nil), env...)
		return nil
	}
	caller, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := runBinExec("/selected", "dashboard", []string{"--help", "--workspace", "child"}); err != nil {
		t.Fatal(err)
	}
	if gotPath != "/tmp/dashboard" || !reflect.DeepEqual(gotArgv, []string{"/tmp/dashboard", "--help", "--workspace", "child"}) {
		t.Fatalf("exec path/argv = %q %#v", gotPath, gotArgv)
	}
	if got, ok := envValue(gotEnv, "SKEIN_WORKSPACE"); !ok || got != "/selected" {
		t.Fatalf("missing workspace overlay: %#v", gotEnv)
	}
	current, err := os.Getwd()
	if err != nil || current != caller {
		t.Fatalf("run changed caller cwd from %q to %q (err=%v)", caller, current, err)
	}
}

func TestRunBinNamedFailures(t *testing.T) {
	originalInvoke, originalExec := binInvoke, execBin
	t.Cleanup(func() { binInvoke, execBin = originalInvoke, originalExec })
	tests := []struct {
		name string
		plan string
		want string
	}{
		{name: "not built", plan: `{"operation":"bins plan","bin":"x","runnable":false,"exec":{"path":"/tmp/x","env":{}},"build":{"argv":["bun","install"],"cwd":"/tmp"}}`, want: "bin/not-built"},
		{name: "not runnable", plan: `{"operation":"bins plan","bin":"x","runnable":false,"exec":{"path":"/tmp/x","env":{}}}`, want: "bin/not-runnable"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			binInvoke = fakePlanInvoker(tt.plan)
			execBin = func(string, []string, []string) error { return errors.New("must not exec") }
			err := runBinExec("/ws", "x", nil)
			var binErr *binError
			if !errors.As(err, &binErr) || binErr.Code != tt.want {
				t.Fatalf("error = %v, want %s", err, tt.want)
			}
		})
	}
}

func TestRunBinBuildNoRecipeAndStartFailure(t *testing.T) {
	originalInvoke := binInvoke
	t.Cleanup(func() { binInvoke = originalInvoke })
	binInvoke = fakePlanInvoker(`{"operation":"bins plan","bin":"x","runnable":true,"exec":{"path":"/tmp/x","env":{}}}`)
	err := runBinBuild("/ws", "x")
	var binErr *binError
	if !errors.As(err, &binErr) || binErr.Code != "bin/no-build-recipe" {
		t.Fatalf("no recipe error = %v", err)
	}

	binInvoke = fakePlanInvoker(`{"operation":"bins plan","bin":"x","runnable":true,"exec":{"path":"/tmp/x","env":{}},"build":{"argv":["/missing/build-command"],"cwd":"/tmp"}}`)
	err = runBinBuild("/ws", "x")
	if !errors.As(err, &binErr) || binErr.Code != "bin/build-start-failed" || !strings.Contains(err.Error(), "/missing/build-command") {
		t.Fatalf("start failure = %v", err)
	}
}

func TestRunBinBuildReportsChildFailureAndSuccessEnvelope(t *testing.T) {
	originalInvoke, originalStdout := binInvoke, binStdout
	t.Cleanup(func() { binInvoke, binStdout = originalInvoke, originalStdout })
	var out bytes.Buffer
	binStdout = &out
	binInvoke = fakePlanInvoker(`{"operation":"bins plan","bin":"x","runnable":true,"exec":{"path":"/tmp/x","env":{}},"build":{"argv":["/bin/sh","-c","exit 7"],"cwd":"/tmp"}}`)
	err := runBinBuild("/ws", "x")
	var binErr *binError
	if !errors.As(err, &binErr) || binErr.Code != "bin/build-failed" || binErr.Details["exit"] != -1 && binErr.Details["exit"] != 7 {
		t.Fatalf("child failure = %v details=%#v", err, binErr)
	}

	binInvoke = fakePlanInvoker(`{"operation":"bins plan","bin":"x","runnable":true,"exec":{"path":"/tmp/x","env":{}},"build":{"argv":["/bin/sh","-c","exit 0"],"cwd":"/tmp"}}`)
	if err := runBinBuild("/ws", "x"); err != nil {
		t.Fatal(err)
	}
	var result map[string]any
	if err := json.Unmarshal(out.Bytes(), &result); err != nil {
		t.Fatalf("success envelope is not JSON: %v (%q)", err, out.String())
	}
	if result["operation"] != "bin build" || result["exit"] != float64(0) || result["bin"] != "x" {
		t.Fatalf("unexpected success envelope: %#v", result)
	}
}

func TestRunBinBuildUsesPlanCWDAndEnvironmentOverlay(t *testing.T) {
	originalInvoke, originalStdout := binInvoke, binStdout
	t.Cleanup(func() { binInvoke, binStdout = originalInvoke, originalStdout })
	var out bytes.Buffer
	binStdout = &out
	marker := filepath.Join(t.TempDir(), "build-result")
	buildCWD := t.TempDir()
	binInvoke = fakePlanInvoker(fmt.Sprintf(`{"operation":"bins plan","bin":"x","runnable":true,"exec":{"path":"/tmp/x","env":{"SKEIN_WORKSPACE":"/selected","BIN_RESULT":%q}},"build":{"argv":["/bin/sh","-c","printf '%%s|%%s' \"$PWD\" \"$SKEIN_WORKSPACE\" > \"$BIN_RESULT\""],"cwd":%q}}`, marker, buildCWD))
	if err := runBinBuild("/ws", "x"); err != nil {
		t.Fatal(err)
	}
	result, err := os.ReadFile(marker)
	if err != nil {
		t.Fatal(err)
	}
	expectedCWD, err := filepath.EvalSymlinks(buildCWD)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(string(result), expectedCWD+"|") {
		t.Fatalf("build child did not use plan cwd: %q", result)
	}
	if !strings.HasSuffix(string(result), "|/selected") {
		t.Fatalf("build child did not receive SKEIN_WORKSPACE overlay: %q", result)
	}
}

func fakePlanInvoker(plan string) func(client.MillWorldRequest, map[string]any, io.Writer, io.Writer) (int, error) {
	return func(_ client.MillWorldRequest, _ map[string]any, stdout, _ io.Writer) (int, error) {
		_, _ = io.WriteString(stdout, plan+"\n")
		return 0, nil
	}
}

func envValue(env []string, key string) (string, bool) {
	for _, entry := range env {
		if k, value, ok := strings.Cut(entry, "="); ok && k == key {
			return value, true
		}
	}
	return "", false
}
