package dispatch

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"millstrand-strand-cli/internal/client"
)

type capture struct {
	called   bool
	world    client.MillWorldRequest
	envelope map[string]any
	code     int
	err      error
}

// harness stubs the git-context and transport seams. By default derivation
// yields deterministic values and the transport succeeds with exit 0.
func harness(t *testing.T, c *capture) {
	t.Helper()
	origGetwd, origDerive, origSend := getwd, deriveGitContext, sendInvoke
	getwd = func() (string, error) { return "/proc/cwd", nil }
	deriveGitContext = func(cwd string) (string, string, error) {
		return "/wt/" + filepath.Base(cwd), "/wt/.git", nil
	}
	sendInvoke = func(world client.MillWorldRequest, env map[string]any, stdout, stderr io.Writer) (int, error) {
		c.called = true
		c.world = world
		c.envelope = env
		return c.code, c.err
	}
	t.Cleanup(func() { getwd, deriveGitContext, sendInvoke = origGetwd, origDerive, origSend })
}

func runDispatch(stdin string, args ...string) (string, string, int) {
	var out, er bytes.Buffer
	code := Run(args, strings.NewReader(stdin), &out, &er)
	return out.String(), er.String(), code
}

func TestFlagParsingStopsAtOpName(t *testing.T) {
	var c capture
	harness(t, &c)
	_, er, code := runDispatch("", "--workspace", "ws", "add", "x", "--workspace", "foo", "--attr", "k=v")
	if code != 0 {
		t.Fatalf("exit %d stderr=%q", code, er)
	}
	if c.world.ConfigDir != "ws" {
		t.Fatalf("workspace flag before op name should select workspace, got %#v", c.world)
	}
	if c.envelope["name"] != "add" {
		t.Fatalf("op name = %v", c.envelope["name"])
	}
	// Everything after the op name ships verbatim, including flag-looking tokens.
	wantArgv := []any{"x", "--workspace", "foo", "--attr", "k=v"}
	if !reflect.DeepEqual(toAnySlice(c.envelope["argv"]), wantArgv) {
		t.Fatalf("argv = %#v, want %#v", c.envelope["argv"], wantArgv)
	}
	if c.envelope["workspace"] != "ws" {
		t.Fatalf("envelope workspace = %v", c.envelope["workspace"])
	}
}

func TestBareOpAssemblesEnvelopeWithClientAndPayloads(t *testing.T) {
	var c capture
	harness(t, &c)
	_, er, code := runDispatch("", "list")
	if code != 0 {
		t.Fatalf("exit %d stderr=%q", code, er)
	}
	if c.envelope["cwd"] != "/proc/cwd" {
		t.Fatalf("cwd = %v", c.envelope["cwd"])
	}
	if _, ok := c.envelope["payloads"].(map[string]string); !ok {
		t.Fatalf("payloads must be a (possibly empty) map: %#v", c.envelope["payloads"])
	}
	cl, ok := c.envelope["client"].(map[string]any)
	if !ok || cl["pid"] == nil || cl["version"] != Version {
		t.Fatalf("client identity malformed: %#v", c.envelope["client"])
	}
	if c.envelope["worktree_root"] != "/wt/cwd" || c.envelope["git_common_dir"] != "/wt/.git" {
		t.Fatalf("derived git context missing: %#v", c.envelope)
	}
}

func TestWorkspaceWinsWhenDerivationFails(t *testing.T) {
	var c capture
	harness(t, &c)
	deriveGitContext = func(string) (string, string, error) { return "", "", errors.New("not a git repo") }
	_, er, code := runDispatch("", "--workspace", "/tmp/ws", "list")
	if code != 0 {
		t.Fatalf("workspace should pin selection despite derivation failure; exit %d stderr=%q", code, er)
	}
	if _, ok := c.envelope["worktree_root"]; ok {
		t.Fatalf("underivable worktree_root must be omitted: %#v", c.envelope)
	}
	if c.envelope["workspace"] != "/tmp/ws" {
		t.Fatalf("workspace = %v", c.envelope["workspace"])
	}
}

func TestExplicitGitFlagsOverrideDerivation(t *testing.T) {
	var c capture
	harness(t, &c)
	deriveGitContext = func(string) (string, string, error) {
		t.Fatal("derivation must not run when both git flags are pinned")
		return "", "", nil
	}
	_, _, code := runDispatch("", "--worktree-root", "/x/wt", "--git-common-dir", "/x/.git", "list")
	if code != 0 {
		t.Fatalf("exit %d", code)
	}
	if c.envelope["worktree_root"] != "/x/wt" || c.envelope["git_common_dir"] != "/x/.git" {
		t.Fatalf("explicit git flags not honored: %#v", c.envelope)
	}
}

func TestDerivationRunsFromCwdFlag(t *testing.T) {
	var c capture
	harness(t, &c)
	_, _, code := runDispatch("", "--cwd", "/somewhere/repo", "list")
	if code != 0 {
		t.Fatalf("exit %d", code)
	}
	if c.envelope["cwd"] != "/somewhere/repo" || c.envelope["worktree_root"] != "/wt/repo" {
		t.Fatalf("cwd/derivation mismatch: %#v", c.envelope)
	}
}

func TestFailedDerivationWithNothingPinnedFailsLoudly(t *testing.T) {
	var c capture
	harness(t, &c)
	deriveGitContext = func(string) (string, string, error) {
		return "", "", errors.New("requires cwd inside a supported non-bare Git worktree")
	}
	_, er, code := runDispatch("", "list")
	if code == 0 {
		t.Fatalf("expected loud failure when nothing pins the workspace")
	}
	if c.called {
		t.Fatalf("transport must not run after failed context resolution")
	}
	if !strings.Contains(er, "non-bare Git worktree") {
		t.Fatalf("remediation missing from stderr: %q", er)
	}
}

func TestPayloadSlotCollisions(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "body")
	if err := os.WriteFile(f, []byte("hi"), 0o644); err != nil {
		t.Fatal(err)
	}
	var c capture
	harness(t, &c)
	cases := [][]string{
		{"--stdin", "--payload", "stdin=" + f, "add", "x"},
		{"--payload", "a=" + f, "--payload", "a=" + f, "add", "x"},
	}
	for _, args := range cases {
		_, er, code := runDispatch("body", args...)
		if code == 0 {
			t.Fatalf("expected collision error for %v", args)
		}
		if !strings.Contains(er, "duplicate payload slot") {
			t.Fatalf("stderr missing collision message for %v: %q", args, er)
		}
	}
}

func TestPayloadsAttached(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "plan.md")
	if err := os.WriteFile(f, []byte("# Plan\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	var c capture
	harness(t, &c)
	_, _, code := runDispatch("body-text", "--stdin", "--payload", "plan="+f, "add", "y")
	if code != 0 {
		t.Fatalf("exit %d", code)
	}
	payloads := c.envelope["payloads"].(map[string]string)
	if payloads["stdin"] != "body-text" || payloads["plan"] != "# Plan\n" {
		t.Fatalf("payloads not attached: %#v", payloads)
	}
}

func TestEmptyStdinIsEmptyPayloadNotError(t *testing.T) {
	var c capture
	harness(t, &c)
	_, er, code := runDispatch("", "--stdin", "add", "y")
	if code != 0 {
		t.Fatalf("empty stdin with --stdin must not error; exit %d stderr=%q", code, er)
	}
	payloads := c.envelope["payloads"].(map[string]string)
	if v, ok := payloads["stdin"]; !ok || v != "" {
		t.Fatalf("empty stdin should attach empty-string payload: %#v", payloads)
	}
}

func TestDryRunPrintsEnvelopeWithoutTransport(t *testing.T) {
	var c capture
	harness(t, &c)
	out, _, code := runDispatch("", "--dry-run", "add", "x", "--attr", "k=v")
	if code != 0 {
		t.Fatalf("exit %d", code)
	}
	if c.called {
		t.Fatalf("--dry-run must not contact the mill")
	}
	var frame map[string]any
	if err := json.Unmarshal([]byte(strings.TrimSpace(out)), &frame); err != nil {
		t.Fatalf("dry-run output is not one JSON object: %q", out)
	}
	if frame["operation"] != "invoke" || frame["weaver_id"] != "<dry-run>" || frame["request_id"] != "<dry-run>" {
		t.Fatalf("dry-run frame identity wrong: %#v", frame)
	}
	args, ok := frame["arguments"].(map[string]any)
	if !ok || args["name"] != "add" {
		t.Fatalf("dry-run arguments missing envelope: %#v", frame["arguments"])
	}
	if !reflect.DeepEqual(args["argv"], []any{"x", "--attr", "k=v"}) {
		t.Fatalf("dry-run argv = %#v", args["argv"])
	}
	if args["is_tty"] != false || args["tty_col"] != nil {
		t.Fatalf("non-terminal capabilities = (%#v, %#v)", args["is_tty"], args["tty_col"])
	}
}

func TestDryRunWithStdinPayload(t *testing.T) {
	harness(t, &capture{})
	out, _, code := runDispatch("body\n", "--stdin", "--dry-run", "add", "y", "--attr", "body=:stdin")
	if code != 0 {
		t.Fatalf("exit %d", code)
	}
	var frame map[string]any
	if err := json.Unmarshal([]byte(strings.TrimSpace(out)), &frame); err != nil {
		t.Fatalf("not JSON: %q", out)
	}
	args := frame["arguments"].(map[string]any)
	payloads := args["payloads"].(map[string]any)
	if payloads["stdin"] != "body\n" {
		t.Fatalf("stdin payload not attached in dry-run: %#v", payloads)
	}
}

func TestTimeoutParsedToMilliseconds(t *testing.T) {
	var c capture
	harness(t, &c)
	if _, _, code := runDispatch("", "--timeout", "1500ms", "list"); code != 0 {
		t.Fatalf("exit %d", code)
	}
	if c.envelope["timeout"].(int64) != 1500 {
		t.Fatalf("timeout ms = %v", c.envelope["timeout"])
	}
	if _, er, code := runDispatch("", "--timeout", "nope", "list"); code == 0 || !strings.Contains(er, "invalid --timeout") {
		t.Fatalf("expected timeout parse error, got exit %d stderr=%q", code, er)
	}
}

func TestErrorExitCodesPropagate(t *testing.T) {
	var c capture
	harness(t, &c)
	c.code = 3
	if _, _, code := runDispatch("", "list"); code != 3 {
		t.Fatalf("transport exit code must propagate, got %d", code)
	}

	c2 := capture{code: 0, err: errors.New("boom")}
	harness(t, &c2)
	_, er, code := runDispatch("", "list")
	if code != 1 || !strings.Contains(er, "boom") {
		t.Fatalf("transport error should exit 1 with message, got %d %q", code, er)
	}
}

func TestUnknownFlagFailsBeforeOp(t *testing.T) {
	var c capture
	harness(t, &c)
	_, er, code := runDispatch("", "--bogus", "list")
	if code == 0 || !strings.Contains(er, "unknown flag: --bogus") {
		t.Fatalf("expected unknown-flag error, got %d %q", code, er)
	}
	if c.called {
		t.Fatalf("must not transport on unknown flag")
	}
}

func TestVersionAndHelpAreLocal(t *testing.T) {
	var c capture
	harness(t, &c)
	out, _, code := runDispatch("", "--version")
	if code != 0 {
		t.Fatalf("exit %d", code)
	}
	var v map[string]any
	if err := json.Unmarshal([]byte(strings.TrimSpace(out)), &v); err != nil {
		t.Fatalf("--version not JSON: %q", out)
	}
	if v["bin_version"] != Version || v["protocol_version"] != float64(client.ProtocolVersion) {
		t.Fatalf("unexpected version payload: %#v", v)
	}

	for _, args := range [][]string{{"--help"}, {}} {
		out, _, code := runDispatch("", args...)
		if code != 0 {
			t.Fatalf("help exit %d for %v", code, args)
		}
		for _, want := range []string{"strand [dispatcher-flags]", "--dry-run", "strand help", "mill start", "invoke envelope"} {
			if !strings.Contains(out, want) {
				t.Fatalf("help missing %q for %v:\n%s", want, args, out)
			}
		}
	}
	if c.called {
		t.Fatalf("version/help must not transport")
	}
}

func TestNoOpHelpStaysUsage(t *testing.T) {
	var c capture
	harness(t, &c)
	// With no op named, --help/-h and bare strand all print dispatcher usage.
	for _, args := range [][]string{{"--help"}, {"-h"}, {}} {
		out, _, code := runDispatch("", args...)
		if code != 0 {
			t.Fatalf("no-op help exit %d for %v", code, args)
		}
		if !strings.Contains(out, "strand [dispatcher-flags]") {
			t.Fatalf("no-op help should print usage for %v:\n%s", args, out)
		}
	}
	if c.called {
		t.Fatalf("no-op help must not transport")
	}
}

func TestPreOpHelpWithOpIsError(t *testing.T) {
	var c capture
	harness(t, &c)
	// Once an op is named, a pre-op --help is an error (was: static usage),
	// redirecting to `strand help <op>` or a trailing --help (DELTA-Dtf-001.CC6).
	for _, args := range [][]string{
		{"--help", "agent"},
		{"-h", "agent"},
		{"--workspace", "ws", "--help", "list"},
	} {
		out, er, code := runDispatch("", args...)
		if code == 0 {
			t.Fatalf("pre-op --help must fail for %v (stdout=%q)", args, out)
		}
		if !strings.Contains(er, "--help must follow") || !strings.Contains(er, "strand help") {
			t.Fatalf("pre-op --help should redirect for %v: %q", args, er)
		}
	}
	if c.called {
		t.Fatalf("pre-op --help error must not transport")
	}
}

func TestTrailingHelpShipsVerbatim(t *testing.T) {
	var c capture
	harness(t, &c)
	// A --help trailing the op is opaque argv the weaver rewrites; the dispatcher
	// transports it verbatim rather than intercepting it.
	_, er, code := runDispatch("", "agent", "--help")
	if code != 0 {
		t.Fatalf("trailing --help exit %d stderr=%q", code, er)
	}
	if !c.called {
		t.Fatalf("trailing --help must transport to the weaver")
	}
	if c.envelope["name"] != "agent" {
		t.Fatalf("op name = %v", c.envelope["name"])
	}
	if !reflect.DeepEqual(toAnySlice(c.envelope["argv"]), []any{"--help"}) {
		t.Fatalf("argv = %#v, want [--help]", c.envelope["argv"])
	}
}

func TestJSONFlagInHelpSurfaceShipsVerbatim(t *testing.T) {
	var c capture
	harness(t, &c)
	// --json is a help-op flag parsed weaver-side (leading-only there). The thin
	// dispatcher parses no arg-spec, so it ships everything after the op verbatim.
	_, er, code := runDispatch("", "help", "custom", "--json")
	if code != 0 {
		t.Fatalf("exit %d stderr=%q", code, er)
	}
	if c.envelope["name"] != "help" {
		t.Fatalf("op name = %v", c.envelope["name"])
	}
	if !reflect.DeepEqual(toAnySlice(c.envelope["argv"]), []any{"custom", "--json"}) {
		t.Fatalf("argv = %#v, want [custom --json]", c.envelope["argv"])
	}
}

func toAnySlice(v any) []any {
	switch s := v.(type) {
	case []any:
		return s
	case []string:
		out := make([]any, len(s))
		for i, x := range s {
			out[i] = x
		}
		return out
	}
	return nil
}
