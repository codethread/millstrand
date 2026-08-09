package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/spf13/cobra"
	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
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
	pathPlan := `{"operation":"bins plan","bin":"x","runnable":false,"exec":{"path":"/tmp/x","env":{"MILLSTRAND_WORKSPACE":"/tmp/ws"}},"build":{"argv":["bun","install"],"cwd":"/tmp"}}`
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
	got := overlayEnvironment([]string{"PATH=/bin", "MILLSTRAND_WORKSPACE=old", "KEEP=yes"}, map[string]string{"MILLSTRAND_WORKSPACE": "/ws", "NEW": "value"})
	want := []string{"PATH=/bin", "MILLSTRAND_WORKSPACE=/ws", "KEEP=yes", "NEW=value"}
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
	if gotEnvelope["is_tty"] != false || gotEnvelope["tty_col"] != nil {
		t.Fatalf("bin plan terminal capabilities = (%#v, %#v), want (false, nil)", gotEnvelope["is_tty"], gotEnvelope["tty_col"])
	}
}

func TestRunBinExecAppendsOpaqueArgumentsAndUsesOverlay(t *testing.T) {
	originalInvoke, originalExec := binInvoke, execBin
	t.Cleanup(func() { binInvoke, execBin = originalInvoke, originalExec })
	binInvoke = fakePlanInvoker(`{"operation":"bins plan","bin":"dashboard","runnable":true,"exec":{"path":"/tmp/dashboard","env":{"MILLSTRAND_WORKSPACE":"/selected"}}}`)
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
	if got, ok := envValue(gotEnv, "MILLSTRAND_WORKSPACE"); !ok || got != "/selected" {
		t.Fatalf("missing workspace overlay: %#v", gotEnv)
	}
	current, err := os.Getwd()
	if err != nil || current != caller {
		t.Fatalf("run changed caller cwd from %q to %q (err=%v)", caller, current, err)
	}
}

func TestRunBinExecResolvesBareCommandThroughCallerPATH(t *testing.T) {
	originalInvoke, originalExec := binInvoke, execBin
	t.Cleanup(func() { binInvoke, execBin = originalInvoke, originalExec })
	pathDir := t.TempDir()
	command := "path-bin"
	commandPath := filepath.Join(pathDir, command)
	if err := os.WriteFile(commandPath, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", pathDir)
	binInvoke = fakePlanInvoker(fmt.Sprintf(`{"operation":"bins plan","bin":"%s","runnable":null,"exec":{"command":"%s","env":{}}}`, command, command))
	var gotPath string
	var gotArgv []string
	execBin = func(path string, argv []string, _ []string) error {
		gotPath = path
		gotArgv = append([]string(nil), argv...)
		return nil
	}

	if err := runBinExec("/selected", command, []string{"--flag"}); err != nil {
		t.Fatal(err)
	}
	if gotPath != commandPath {
		t.Fatalf("exec path = %q, want PATH-resolved %q", gotPath, commandPath)
	}
	if !reflect.DeepEqual(gotArgv, []string{commandPath, "--flag"}) {
		t.Fatalf("exec argv = %#v, want resolved path plus opaque args", gotArgv)
	}
}

func TestRunBinExecFailurePreservesBareCommandAndCause(t *testing.T) {
	originalInvoke, originalExec := binInvoke, execBin
	t.Cleanup(func() { binInvoke, execBin = originalInvoke, originalExec })
	command := "missing-from-path"
	t.Setenv("PATH", t.TempDir())
	binInvoke = fakePlanInvoker(fmt.Sprintf(`{"operation":"bins plan","bin":"%s","runnable":null,"exec":{"command":"%s","env":{}}}`, command, command))
	execBin = func(string, []string, []string) error {
		t.Fatal("exec seam called after PATH lookup failed")
		return nil
	}

	err := runBinExec("/selected", command, nil)
	var binErr *binError
	if !errors.As(err, &binErr) || binErr.Code != "bin/exec-failed" {
		t.Fatalf("error = %v, want bin/exec-failed", err)
	}
	if got := binErr.Details["path"]; got != command {
		t.Fatalf("failure path = %#v, want bare command %q", got, command)
	}
	if cause, ok := binErr.Details["cause"].(string); !ok || cause == "" {
		t.Fatalf("failure cause = %#v, want host lookup cause", binErr.Details["cause"])
	}
}

func TestMillBinFailureUsesStructuredJSONCommandEnvelope(t *testing.T) {
	originalInvoke, originalErrorOut := binInvoke, millErrorOut
	t.Cleanup(func() { binInvoke, millErrorOut = originalInvoke, originalErrorOut })
	binInvoke = fakePlanInvoker(`{"operation":"bins plan","bin":"dashboard","runnable":false,"exec":{"path":"/tmp/dashboard","env":{}},"build":{"argv":["bun","install"],"cwd":"/tmp"}}`)
	var stderr bytes.Buffer
	millErrorOut = &stderr
	cmd := newMillCommand()
	cmd.SetArgs([]string{"bin", "run", "dashboard"})
	err := cmd.Execute()
	if err == nil {
		t.Fatal("expected bin/not-built failure")
	}
	writeMillCommandError(err, nil)
	var envelope map[string]any
	if err := json.Unmarshal(stderr.Bytes(), &envelope); err != nil {
		t.Fatalf("failure is not JSON: %v (%q)", err, stderr.String())
	}
	for key, want := range map[string]any{"operation": "bin run", "error": "bin/not-built", "bin": "dashboard", "remedy": "mill bin build dashboard"} {
		if envelope[key] != want {
			t.Fatalf("envelope[%q] = %#v, want %#v", key, envelope[key], want)
		}
	}
	if _, present := envelope["code"]; present {
		t.Fatalf("failure envelope must use error, not code: %#v", envelope)
	}
	if strings.Contains(stderr.String(), "error:") {
		t.Fatalf("structured failure fell through plain error output: %q", stderr.String())
	}
}

func TestMillBinFailureEnvelopeProtectsCanonicalFields(t *testing.T) {
	envelope := newBinError("bin run", "dashboard", "bin/not-built", "not built", map[string]any{
		"operation": "spoofed operation",
		"error":     "spoofed error",
		"bin":       "spoofed bin",
		"remedy":    "mill bin build dashboard",
	}).binFailureEnvelope()
	if envelope["operation"] != "bin run" || envelope["error"] != "bin/not-built" || envelope["bin"] != "dashboard" {
		t.Fatalf("canonical fields were shadowed: %#v", envelope)
	}
	if envelope["remedy"] != "mill bin build dashboard" {
		t.Fatalf("remote details were lost: %#v", envelope)
	}
}

func TestMillBinListRelayFailureUsesTypedResponseError(t *testing.T) {
	originalInvoke, originalErrorOut := binInvoke, millErrorOut
	t.Cleanup(func() { binInvoke, millErrorOut = originalInvoke, originalErrorOut })
	binInvoke = func(_ client.MillWorldRequest, _ map[string]any, _ io.Writer, _ io.Writer) (int, error) {
		return 1, &client.ResponseError{
			Type:    "domain",
			Code:    "mill/no-selected-weaver",
			Message: "no running weaver for selected workspace",
			Details: map[string]any{"config_dir": "/selected/.millstrand"},
		}
	}
	var stderr bytes.Buffer
	millErrorOut = &stderr
	cmd := newMillCommand()
	cmd.SetArgs([]string{"bin", "list", "--workspace", "/selected/.millstrand"})
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected list relay failure")
	} else {
		writeMillCommandError(err, nil)
	}
	var envelope map[string]any
	if err := json.Unmarshal(stderr.Bytes(), &envelope); err != nil {
		t.Fatalf("list failure is not JSON: %v (%q)", err, stderr.String())
	}
	if envelope["operation"] != "bin list" || envelope["error"] != "mill/no-selected-weaver" {
		t.Fatalf("unexpected list failure envelope: %#v", envelope)
	}
	if envelope["config_dir"] != "/selected/.millstrand" {
		t.Fatalf("typed weaver details were not relayed: %#v", envelope)
	}
	if _, present := envelope["bin"]; present {
		t.Fatalf("list failure must not invent a bin name: %#v", envelope)
	}
}

func TestMillBinPlanFailuresPreserveTypedCodeAndDetails(t *testing.T) {
	tests := []struct {
		name    string
		code    string
		message string
		details map[string]any
	}{
		{name: "unknown", code: "bin/unknown", message: "unknown bin", details: map[string]any{"available": []any{"known"}}},
		{name: "anchor unresolved", code: "bin/anchor-unresolved", message: "bin anchor cannot be resolved", details: map[string]any{"path": "/workspace/bin.clj", "anchor": ":family", "remedy": "use an absolute path"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			originalInvoke, originalErrorOut := binInvoke, millErrorOut
			t.Cleanup(func() { binInvoke, millErrorOut = originalInvoke, originalErrorOut })
			binInvoke = func(_ client.MillWorldRequest, _ map[string]any, _ io.Writer, _ io.Writer) (int, error) {
				return 1, &client.ResponseError{Type: "domain", Code: tt.code, Message: tt.message, Details: tt.details}
			}
			var stderr bytes.Buffer
			millErrorOut = &stderr
			cmd := newMillCommand()
			cmd.SetArgs([]string{"bin", "run", "--workspace", "/selected/.millstrand", "dashboard"})
			if err := cmd.Execute(); err == nil {
				t.Fatal("expected plan failure")
			} else {
				writeMillCommandError(err, nil)
			}
			var envelope map[string]any
			if err := json.Unmarshal(stderr.Bytes(), &envelope); err != nil {
				t.Fatalf("plan failure is not JSON: %v (%q)", err, stderr.String())
			}
			if envelope["operation"] != "bin run" || envelope["error"] != tt.code || envelope["bin"] != "dashboard" {
				t.Fatalf("typed plan failure was not preserved: %#v", envelope)
			}
			for key, want := range tt.details {
				if !reflect.DeepEqual(envelope[key], want) {
					t.Fatalf("typed detail %q = %#v, want %#v", key, envelope[key], want)
				}
			}
			if _, present := envelope["code"]; present {
				t.Fatalf("failure envelope must use error, not code: %#v", envelope)
			}
		})
	}
}

func TestMillBinMalformedPlanFailureDoesNotInventOutcomeCode(t *testing.T) {
	originalInvoke, originalErrorOut := binInvoke, millErrorOut
	t.Cleanup(func() { binInvoke, millErrorOut = originalInvoke, originalErrorOut })
	binInvoke = func(_ client.MillWorldRequest, _ map[string]any, stdout, _ io.Writer) (int, error) {
		_, _ = io.WriteString(stdout, `{"operation":"bins plan","bin":"dashboard","runnable":true,"exec":{"path":"relative","env":{}}}`+"\n")
		return 0, nil
	}
	var stderr bytes.Buffer
	millErrorOut = &stderr
	cmd := newMillCommand()
	cmd.SetArgs([]string{"bin", "run", "--workspace", "/selected/.millstrand", "dashboard"})
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected malformed plan failure")
	} else {
		writeMillCommandError(err, nil)
	}
	var envelope map[string]any
	if err := json.Unmarshal(stderr.Bytes(), &envelope); err != nil {
		t.Fatalf("malformed plan failure is not JSON: %v (%q)", err, stderr.String())
	}
	if envelope["operation"] != "bin run" || envelope["bin"] != "dashboard" {
		t.Fatalf("unexpected malformed plan envelope: %#v", envelope)
	}
	if envelope["error"] == "bin/plan-failed" || envelope["error"] == "" {
		t.Fatalf("malformed plan must not silently become bin/plan-failed: %#v", envelope)
	}
	if envelope["cause"] == nil {
		t.Fatalf("malformed plan cause missing: %#v", envelope)
	}
}

func TestRunBinExecWaitsForMillAndWeaverRelayBeforeExecSeam(t *testing.T) {
	world, cfg := forwardWorld(t)
	serveFakeWeaverStream(t, world, func(req map[string]any) [][]byte {
		return [][]byte{mustFrame(t, map[string]any{"protocol_version": client.ProtocolVersion, "request_id": req["request_id"], "ok": true, "result": map[string]any{"operation": "bins plan", "bin": "dashboard", "runnable": true, "exec": map[string]any{"path": "/tmp/dashboard", "env": map[string]string{}}}})}
	})
	writeWeaverMetadata(t, world, os.Getpid(), "weaver-bin-seam")

	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	millSocket := filepath.Join(root, config.MillSocketFileName)
	listener, err := net.Listen("unix", millSocket)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close(); _ = os.Remove(millSocket) })
	meta := client.MillMetadata{ProtocolVersion: client.MillProtocolVersion, PID: os.Getpid(), MillID: "mill-bin-seam", StateRoot: root, SocketPath: millSocket, StartedAt: time.Now().UTC().Format(time.RFC3339Nano)}
	metadataPath, err := config.MillMetadataPath()
	if err != nil {
		t.Fatal(err)
	}
	b, err := json.Marshal(meta)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(metadataPath, b, 0o644); err != nil {
		t.Fatal(err)
	}

	serverDone := make(chan struct{})
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr == nil {
			s := server{meta: meta, children: map[string]*weaverChild{}}
			s.handle(conn)
		}
		close(serverDone)
	}()

	originalInvoke, originalExec := binInvoke, execBin
	t.Cleanup(func() { binInvoke, execBin = originalInvoke, originalExec })
	binInvoke = client.InvokeThroughMill
	execBin = func(path string, argv []string, _ []string) error {
		const relayCompletionGuard = 2 * time.Second
		select {
		case <-serverDone:
		case <-time.After(relayCompletionGuard):
			t.Fatalf("exec seam reached while mill/weaver relay was still live after %s", relayCompletionGuard)
		}
		if path != "/tmp/dashboard" || !reflect.DeepEqual(argv, []string{"/tmp/dashboard"}) {
			t.Fatalf("exec seam received unexpected command: %q %#v", path, argv)
		}
		return nil
	}

	if err := runBinExec(cfg, "dashboard", nil); err != nil {
		t.Fatal(err)
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
	binInvoke = fakePlanInvoker(fmt.Sprintf(`{"operation":"bins plan","bin":"x","runnable":true,"exec":{"path":"/tmp/x","env":{"MILLSTRAND_WORKSPACE":"/selected","BIN_RESULT":%q}},"build":{"argv":["/bin/sh","-c","printf '%%s|%%s' \"$PWD\" \"$MILLSTRAND_WORKSPACE\" > \"$BIN_RESULT\""],"cwd":%q}}`, marker, buildCWD))
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
		t.Fatalf("build child did not receive MILLSTRAND_WORKSPACE overlay: %q", result)
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
