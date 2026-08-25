package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"time"

	"golang.org/x/term"

	"millstrand-strand-cli/internal/client"
)

// worldRequest builds the routing selector for a mill client subcommand: the
// process cwd feeds repo-local .millstrand/.ms discovery and --workspace pins an explicit
// selection (same precedence as the strand dispatcher, SPEC-002-D004.C9).
func worldRequest(workspace, name string) (client.MillWorldRequest, error) {
	cwd, err := os.Getwd()
	if err != nil {
		return client.MillWorldRequest{}, err
	}
	return client.MillWorldRequest{CWD: cwd, ConfigDir: workspace, Name: name}, nil
}

func parseReadyTimeout(value string) (int64, error) {
	if value == "" {
		return 0, nil
	}
	d, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("invalid --ready-timeout %q: %w", value, err)
	}
	if d <= 0 {
		return 0, fmt.Errorf("invalid --ready-timeout %q: must be a positive duration", value)
	}
	if d < time.Millisecond {
		return 0, fmt.Errorf("invalid --ready-timeout %q: must be at least 1ms", value)
	}
	return d.Milliseconds(), nil
}

func emitJSON(v any) error {
	return json.NewEncoder(os.Stdout).Encode(v)
}

func runInit(workspace string, stealth bool) error {
	if stealth && workspace != "" {
		return errors.New("--stealth cannot be combined with --workspace; stealth init creates the repo-local .millstrand workspace")
	}
	world, err := worldRequest(workspace, "")
	if err != nil {
		return err
	}
	world.Stealth = stealth
	result, err := client.MillCall("init", world)
	if err != nil {
		return err
	}
	return emitJSON(result)
}

func runWeaverLifecycle(operation, workspace, name string) error {
	return runWeaverLifecycleWithReadyTimeout(operation, workspace, name, "")
}

func runWeaverLifecycleWithReadyTimeout(operation, workspace, name, readyTimeout string) error {
	world, err := worldRequest(workspace, name)
	if err != nil {
		return err
	}
	ms, err := parseReadyTimeout(readyTimeout)
	if err != nil {
		return err
	}
	world.ReadyTimeoutMs = ms
	if operation != "weaver-restart" || !term.IsTerminal(int(os.Stderr.Fd())) {
		result, err := client.MillCall(operation, world)
		if err != nil {
			return err
		}
		return emitJSON(result)
	}

	type restartOutcome struct {
		result any
		err    error
	}
	completed := make(chan restartOutcome, 1)
	fmt.Fprintln(os.Stderr, "verifying new weaver")
	fmt.Fprintln(os.Stderr, "  weaver init")
	go func() {
		result, err := client.MillCall(operation, world)
		completed <- restartOutcome{result: result, err: err}
	}()

	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	cutoverReported := false
	for {
		select {
		case outcome := <-completed:
			if outcome.err != nil {
				return outcome.err
			}
			return emitJSON(outcome.result)
		case <-ticker.C:
			if cutoverReported {
				continue
			}
			status, statusErr := client.MillCall("weaver-status", world)
			if statusErr != nil {
				return fmt.Errorf("read weaver restart progress: %w", statusErr)
			}
			fields, ok := status.(map[string]any)
			if !ok {
				return fmt.Errorf("read weaver restart progress: expected status object, got %T", status)
			}
			state, ok := fields["state"].(string)
			if !ok {
				return fmt.Errorf("read weaver restart progress: status has invalid state: %v", status)
			}
			switch state {
			case "restarting":
				fmt.Fprintln(os.Stderr, "closing open handles")
				fmt.Fprintln(os.Stderr, "starting new weaver")
				cutoverReported = true
			case "probing", "running", "failed":
			case "":
				return fmt.Errorf("read weaver restart progress: status has empty state: %v", status)
			default:
				return fmt.Errorf("read weaver restart progress: unexpected state %q in %v", state, status)
			}
		}
	}
}

// runWeaverRepl resolves the live weaver's nREPL endpoint and launch source
// through the mill, then execs the Clojure repl attach process (SPEC-002.C20
// retained). The child inherits stdio; a non-zero child exit propagates as this
// process's exit code so weaver eval failures surface faithfully.
func runWeaverRepl(workspace string, stdin bool) error {
	world, err := worldRequest(workspace, "")
	if err != nil {
		return err
	}
	result, err := client.MillCall("weaver-repl-context", world)
	if err != nil {
		return err
	}
	status, ok := result.(map[string]any)
	if !ok {
		return errors.New("malformed mill weaver-repl-context response: expected object")
	}
	state, ok := status["state"].(string)
	if !ok || state == "" {
		return errors.New("malformed mill weaver-repl-context response: missing state")
	}
	if state != "running" {
		return fmt.Errorf("no running weaver for selected workspace (state: %s); start one with: mill weaver start", state)
	}
	source, ok := status["source"].(string)
	if !ok || source == "" {
		return errors.New("malformed mill weaver-repl-context response: missing source")
	}
	host, port, err := nreplEndpoint(status["nrepl"])
	if err != nil {
		return err
	}
	args := []string{"-M", "-m", "millstrand.repl"}
	if stdin {
		args = append(args, "--attach-stdin", host, port)
	} else {
		args = append(args, "--attach", host, port)
	}
	cmd := exec.Command("clojure", args...)
	cmd.Dir = source
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		if exit, ok := err.(*exec.ExitError); ok {
			os.Exit(exit.ExitCode())
		}
		return err
	}
	return nil
}

func nreplEndpoint(v any) (string, string, error) {
	nrepl, ok := v.(map[string]any)
	if !ok {
		return "", "", errors.New("malformed mill weaver-repl-context response: missing nrepl")
	}
	host, ok := nrepl["host"].(string)
	if !ok || host == "" {
		return "", "", errors.New("malformed mill weaver-repl-context response: missing nrepl.host")
	}
	port, err := parseNreplPort(nrepl["port"])
	if err != nil {
		return "", "", err
	}
	return host, port, nil
}

func parseNreplPort(v any) (string, error) {
	var port int
	switch p := v.(type) {
	case string:
		parsed, err := strconv.Atoi(p)
		if err != nil {
			return "", errors.New("malformed mill weaver-repl-context response: missing nrepl.port")
		}
		port = parsed
	case float64:
		if p != float64(int(p)) {
			return "", errors.New("malformed mill weaver-repl-context response: missing nrepl.port")
		}
		port = int(p)
	default:
		return "", errors.New("malformed mill weaver-repl-context response: missing nrepl.port")
	}
	if port <= 0 || port > 65535 {
		return "", errors.New("malformed mill weaver-repl-context response: missing nrepl.port")
	}
	return strconv.Itoa(port), nil
}
