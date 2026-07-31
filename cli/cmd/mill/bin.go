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
	"sort"
	"strings"
	"syscall"
	"time"

	"github.com/spf13/cobra"
	"skein-strand-cli/internal/client"
	"skein-strand-cli/internal/config"
)

// binInvoke is deliberately the existing selected-world invoke path. The
// mill client does not open a second weaver connection for bins, and it has
// returned (and closed its mill connection) before a bin is executed.
var binInvoke = client.InvokeThroughMill

// binStdout is kept as a narrow output seam for the machine-readable list and
// build envelopes. Child processes still receive the real os.Stdout below.
var binStdout io.Writer = os.Stdout

// millErrorOut is the command surface's error stream. It is a seam so the
// structured bin failure contract can be tested without starting a process.
var millErrorOut io.Writer = os.Stderr

// execBin is a seam for tests. A successful syscall.Exec never returns; a
// returned error is the only path on which mill can report bin/exec-failed.
var execBin = syscall.Exec

type binError struct {
	Operation string
	Bin       string
	Code      string
	Message   string
	Details   map[string]any
}

func (e *binError) Error() string {
	if e == nil {
		return "bin error"
	}
	if e.Code == "" {
		return e.Message
	}
	if e.Message == "" {
		return e.Code
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Message)
}

func newBinError(operation, bin, code, message string, details map[string]any) *binError {
	return &binError{Operation: operation, Bin: bin, Code: code, Message: message, Details: details}
}

// binFailureEnvelope is the machine-readable failure shape for mill bin
// commands. The human message remains available through Error for callers
// that use the Go surface directly, while the process surface emits only this
// JSON object instead of Cobra's generic "error:" line.
func (e *binError) binFailureEnvelope() map[string]any {
	envelope := map[string]any{
		"operation": e.Operation,
		"code":      e.Code,
		"bin":       e.Bin,
	}
	for key, value := range e.Details {
		envelope[key] = value
	}
	return envelope
}

func writeMillCommandError(err error) {
	var binErr *binError
	if errors.As(err, &binErr) {
		_ = json.NewEncoder(millErrorOut).Encode(binErr.binFailureEnvelope())
		return
	}
	_, _ = fmt.Fprintln(millErrorOut, "error:", err)
}

// BinPlan is the typed wire contract returned by bins plan. The custom
// decoder rejects ambiguous exec objects (both path and command, or neither)
// before any process is started.
type BinPlan struct {
	Operation string    `json:"operation"`
	Bin       string    `json:"bin"`
	Runnable  *bool     `json:"runnable"`
	Exec      BinExec   `json:"exec"`
	Build     *BinBuild `json:"build,omitempty"`
}

type BinExec struct {
	Path    string            `json:"path,omitempty"`
	Command string            `json:"command,omitempty"`
	Env     map[string]string `json:"env"`
}

type BinBuild struct {
	Argv []string `json:"argv"`
	CWD  string   `json:"cwd"`
}

func (p *BinPlan) UnmarshalJSON(data []byte) error {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return fmt.Errorf("malformed bins plan: %w", err)
	}
	if len(raw) == 0 {
		return errors.New("malformed bins plan: expected object")
	}
	decodeString := func(key string, required bool) (string, error) {
		value, ok := raw[key]
		if !ok {
			if required {
				return "", fmt.Errorf("malformed bins plan: missing %s", key)
			}
			return "", nil
		}
		var result string
		if err := json.Unmarshal(value, &result); err != nil || strings.TrimSpace(result) == "" {
			return "", fmt.Errorf("malformed bins plan: %s must be a non-empty string", key)
		}
		return result, nil
	}

	operation, err := decodeString("operation", true)
	if err != nil {
		return err
	}
	if operation != "bins plan" {
		return fmt.Errorf("malformed bins plan: operation must be %q", "bins plan")
	}
	bin, err := decodeString("bin", true)
	if err != nil {
		return err
	}
	runnableRaw, ok := raw["runnable"]
	if !ok {
		return errors.New("malformed bins plan: missing runnable")
	}
	var runnable *bool
	if string(runnableRaw) != "null" {
		var value bool
		if err := json.Unmarshal(runnableRaw, &value); err != nil {
			return errors.New("malformed bins plan: runnable must be boolean or null")
		}
		runnable = &value
	}

	execRaw, ok := raw["exec"]
	if !ok {
		return errors.New("malformed bins plan: missing exec")
	}
	var execMap map[string]json.RawMessage
	if err := json.Unmarshal(execRaw, &execMap); err != nil || execMap == nil {
		return errors.New("malformed bins plan: exec must be an object")
	}
	path, pathPresent, err := decodeExecString(execMap, "path")
	if err != nil {
		return err
	}
	command, commandPresent, err := decodeExecString(execMap, "command")
	if err != nil {
		return err
	}
	if pathPresent == commandPresent {
		return errors.New("malformed bins plan: exec must carry exactly one of path or command")
	}
	envRaw, ok := execMap["env"]
	if !ok {
		return errors.New("malformed bins plan: missing exec.env")
	}
	var env map[string]string
	if string(envRaw) == "null" || json.Unmarshal(envRaw, &env) != nil || env == nil {
		return errors.New("malformed bins plan: exec.env must be an object of strings")
	}
	for key := range env {
		if key == "" || strings.Contains(key, "=") {
			return fmt.Errorf("malformed bins plan: invalid environment key %q", key)
		}
	}

	var build *BinBuild
	if buildRaw, present := raw["build"]; present {
		if string(buildRaw) == "null" {
			return errors.New("malformed bins plan: build must be an object when present")
		}
		var candidate struct {
			Argv []string `json:"argv"`
			CWD  string   `json:"cwd"`
		}
		if err := json.Unmarshal(buildRaw, &candidate); err != nil || candidate.Argv == nil {
			return errors.New("malformed bins plan: build.argv must be a non-empty string array")
		}
		if len(candidate.Argv) == 0 {
			return errors.New("malformed bins plan: build.argv must be a non-empty string array")
		}
		for _, arg := range candidate.Argv {
			if arg == "" {
				return errors.New("malformed bins plan: build.argv entries must be non-empty strings")
			}
		}
		if strings.TrimSpace(candidate.CWD) == "" || !filepath.IsAbs(candidate.CWD) {
			return errors.New("malformed bins plan: build.cwd must be an absolute non-empty string")
		}
		build = &BinBuild{Argv: candidate.Argv, CWD: candidate.CWD}
	}
	if commandPresent && runnable != nil {
		return errors.New("malformed bins plan: command exec must have null runnable")
	}
	if pathPresent && runnable == nil {
		return errors.New("malformed bins plan: path exec must have boolean runnable")
	}
	if pathPresent && !filepath.IsAbs(path) {
		return errors.New("malformed bins plan: exec.path must be absolute")
	}
	if commandPresent && strings.ContainsAny(command, `/\\`) {
		return errors.New("malformed bins plan: exec.command must be a bare name")
	}
	*p = BinPlan{Operation: operation, Bin: bin, Runnable: runnable, Exec: BinExec{Path: path, Command: command, Env: env}, Build: build}
	return nil
}

func decodeExecString(values map[string]json.RawMessage, key string) (string, bool, error) {
	raw, present := values[key]
	if !present {
		return "", false, nil
	}
	var value string
	if err := json.Unmarshal(raw, &value); err != nil || value == "" {
		return "", false, fmt.Errorf("malformed bins plan: exec.%s must be a non-empty string", key)
	}
	return value, true, nil
}

func newBinCommand() *cobra.Command {
	bin := &cobra.Command{Use: "bin", Short: "List and run spool-shipped executables"}
	bin.PersistentFlags().String("workspace", "", "explicit workspace selection (defaults to repo-local .skein)")

	list := &cobra.Command{Use: "list", Short: "List declared bins", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, err := cmd.Flags().GetString("workspace")
		if err != nil {
			return err
		}
		return runBinList(workspace)
	}}

	build := &cobra.Command{Use: "build <bin>", Short: "Run a bin's declared build recipe", DisableFlagParsing: true, RunE: func(cmd *cobra.Command, args []string) error {
		parsed, err := parseBinInvocation(cmd, args, false)
		if err != nil {
			return err
		}
		if parsed.help {
			return cmd.Help()
		}
		return runBinBuild(parsed.workspace, parsed.bin)
	}}

	run := &cobra.Command{Use: "run <bin> [args...]", Short: "Exec a declared bin", DisableFlagParsing: true, RunE: func(cmd *cobra.Command, args []string) error {
		parsed, err := parseBinInvocation(cmd, args, true)
		if err != nil {
			return err
		}
		if parsed.help {
			return cmd.Help()
		}
		return runBinExec(parsed.workspace, parsed.bin, parsed.args)
	}}

	bin.AddCommand(list, build, run)
	return bin
}

type parsedBinInvocation struct {
	workspace string
	bin       string
	args      []string
	help      bool
}

// parseBinInvocation consumes mill flags only until the bin name. Everything
// after that name is opaque, including flag-looking arguments for the bin.
func parseBinInvocation(cmd *cobra.Command, args []string, allowArgs bool) (parsedBinInvocation, error) {
	workspace, err := cmd.Flags().GetString("workspace")
	if err != nil {
		return parsedBinInvocation{}, err
	}
	var name string
	var trailing []string
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if name != "" {
			trailing = append(trailing, args[i:]...)
			break
		}
		switch {
		case arg == "--help" || arg == "-h":
			return parsedBinInvocation{workspace: workspace, help: true}, nil
		case arg == "--":
			if i+1 >= len(args) {
				return parsedBinInvocation{}, errors.New("bin name is required")
			}
			name = args[i+1]
			i++
		case arg == "--workspace":
			if i+1 >= len(args) {
				return parsedBinInvocation{}, errors.New("flag needs an argument: --workspace")
			}
			workspace = args[i+1]
			i++
		case strings.HasPrefix(arg, "--workspace="):
			workspace = strings.TrimPrefix(arg, "--workspace=")
			if workspace == "" {
				return parsedBinInvocation{}, errors.New("flag needs a non-empty argument: --workspace")
			}
		default:
			if strings.HasPrefix(arg, "-") {
				return parsedBinInvocation{}, fmt.Errorf("unknown flag before bin name: %s", arg)
			}
			name = arg
		}
	}
	if name == "" {
		return parsedBinInvocation{}, errors.New("bin name is required")
	}
	if !allowArgs && len(trailing) > 0 {
		return parsedBinInvocation{}, fmt.Errorf("build accepts only a bin name; unexpected argument %q", trailing[0])
	}
	return parsedBinInvocation{workspace: workspace, bin: name, args: trailing}, nil
}

func binWorld(workspace string) (client.MillWorldRequest, error) {
	return worldRequest(workspace, "")
}

func binEnvelope(world client.MillWorldRequest, argv []string) map[string]any {
	envelope := map[string]any{
		"name":     "bins",
		"argv":     argv,
		"payloads": map[string]any{},
		"cwd":      world.CWD,
		"client":   map[string]any{"pid": os.Getpid(), "version": config.BuildID},
	}
	if world.ConfigDir != "" {
		envelope["workspace"] = world.ConfigDir
	}
	return envelope
}

func invokeBin(world client.MillWorldRequest, argv []string) ([]byte, error) {
	var stdout, stderr bytes.Buffer
	code, err := binInvoke(world, binEnvelope(world, argv), &stdout, &stderr)
	if err != nil {
		return nil, err
	}
	if code != 0 {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = fmt.Sprintf("bins operation exited with status %d", code)
		}
		return nil, errors.New(message)
	}
	if stdout.Len() == 0 {
		return nil, errors.New("malformed bins response: empty result")
	}
	return stdout.Bytes(), nil
}

func runBinList(workspace string) error {
	world, err := binWorld(workspace)
	if err != nil {
		return err
	}
	result, err := invokeBin(world, []string{"list"})
	if err != nil {
		return err
	}
	_, err = binStdout.Write(result)
	return err
}

func fetchBinPlan(workspace, name string) (BinPlan, error) {
	world, err := binWorld(workspace)
	if err != nil {
		return BinPlan{}, err
	}
	result, err := invokeBin(world, []string{"plan", name})
	if err != nil {
		return BinPlan{}, err
	}
	var plan BinPlan
	if err := json.Unmarshal(result, &plan); err != nil {
		return BinPlan{}, err
	}
	return plan, nil
}

func binPlanFailureCode(err error) string {
	message := err.Error()
	for _, prefix := range []string{"bin/", "mill/"} {
		if start := strings.Index(message, prefix); start >= 0 {
			end := start + len(prefix)
			for end < len(message) {
				ch := message[end]
				if (ch < 'a' || ch > 'z') && ch != '-' && ch != '/' {
					break
				}
				end++
			}
			if end > start+len(prefix) {
				return message[start:end]
			}
		}
	}
	return "bin/plan-failed"
}

func runBinBuild(workspace, name string) error {
	plan, err := fetchBinPlan(workspace, name)
	if err != nil {
		return newBinError("bin build", name, binPlanFailureCode(err), "could not resolve bin plan", map[string]any{"cause": err.Error()})
	}
	if plan.Build == nil {
		return newBinError("bin build", name, "bin/no-build-recipe", fmt.Sprintf("bin %q declares no build recipe", name), nil)
	}
	started := time.Now()
	cmd := exec.Command(plan.Build.Argv[0], plan.Build.Argv[1:]...)
	cmd.Dir = plan.Build.CWD
	cmd.Env = overlayEnvironment(os.Environ(), plan.Exec.Env)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return newBinError("bin build", name, "bin/build-start-failed", fmt.Sprintf("could not start build %q: %v", strings.Join(plan.Build.Argv, " "), err), map[string]any{"argv": plan.Build.Argv, "cause": err.Error()})
	}
	err = cmd.Wait()
	if err != nil {
		exitCode := -1
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) {
			exitCode = exitErr.ExitCode()
		}
		return newBinError("bin build", name, "bin/build-failed", fmt.Sprintf("build exited with status %d", exitCode), map[string]any{"argv": plan.Build.Argv, "exit": exitCode})
	}
	result := map[string]any{"operation": "bin build", "bin": name, "exit": 0, "elapsed-ms": time.Since(started).Milliseconds()}
	return json.NewEncoder(binStdout).Encode(result)
}

func runBinExec(workspace, name string, args []string) error {
	plan, err := fetchBinPlan(workspace, name)
	if err != nil {
		return newBinError("bin run", name, binPlanFailureCode(err), "could not resolve bin plan", map[string]any{"cause": err.Error()})
	}
	if plan.Runnable != nil && !*plan.Runnable {
		if plan.Build != nil {
			return newBinError("bin run", name, "bin/not-built", fmt.Sprintf("bin %q is not runnable; build it with: mill bin build %s", name, name), map[string]any{"remedy": "mill bin build " + name})
		}
		return newBinError("bin run", name, "bin/not-runnable", fmt.Sprintf("bin %q is not runnable at %s", name, plan.Exec.Path), map[string]any{"path": plan.Exec.Path})
	}
	executable := plan.Exec.Path
	command := ""
	if executable == "" {
		command = plan.Exec.Command
		resolved, lookupErr := exec.LookPath(command)
		if lookupErr != nil && !errors.Is(lookupErr, exec.ErrDot) {
			// Keep the declaration's bare command in the failure details. The
			// resolved path is an implementation detail, while the command is
			// what the operator must repair in PATH.
			return newBinError("bin run", name, "bin/exec-failed", fmt.Sprintf("could not find %s in PATH: %v", command, lookupErr), map[string]any{"path": command, "cause": lookupErr.Error()})
		}
		executable = resolved
	}
	argv := make([]string, 1, len(args)+1)
	argv[0] = executable
	argv = append(argv, args...)
	if err := execBin(executable, argv, overlayEnvironment(os.Environ(), plan.Exec.Env)); err != nil {
		failurePath := executable
		if command != "" {
			failurePath = command
		}
		return newBinError("bin run", name, "bin/exec-failed", fmt.Sprintf("could not exec %s: %v", failurePath, err), map[string]any{"path": failurePath, "cause": err.Error()})
	}
	return nil
}

func overlayEnvironment(base []string, overlay map[string]string) []string {
	result := append([]string(nil), base...)
	positions := make(map[string]int, len(base))
	for i, entry := range result {
		key, _, ok := strings.Cut(entry, "=")
		if ok {
			positions[key] = i
		}
	}
	keys := make([]string, 0, len(overlay))
	for key := range overlay {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		entry := key + "=" + overlay[key]
		if i, ok := positions[key]; ok {
			result[i] = entry
		} else {
			positions[key] = len(result)
			result = append(result, entry)
		}
	}
	return result
}
