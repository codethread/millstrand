package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"golang.org/x/term"
)

// Presentation belongs to the caller's stdout. The wire results and weaver
// log files retain their full detail regardless of terminal capabilities.
type statusOutput struct {
	out   io.Writer
	color bool
}

func newStatusOutput(out io.Writer) statusOutput {
	file, ok := out.(*os.File)
	_, noColor := os.LookupEnv("NO_COLOR")
	return statusOutput{out: out, color: ok && term.IsTerminal(int(file.Fd())) && !noColor && os.Getenv("TERM") != "dumb"}
}

func (o statusOutput) paint(code, text string) string {
	if o.color {
		return "\x1b[" + code + "m" + text + "\x1b[0m"
	}
	return text
}

func (o statusOutput) event(at time.Time, message string) {
	fmt.Fprintf(o.out, "%s  %s\n", o.paint("2", at.Format("15:04:05")), message)
}

func (o statusOutput) detail(label, value string) {
	if value != "" {
		fmt.Fprintf(o.out, "  %-10s %s\n", label, value)
	}
}

func statusText(fields map[string]any, key string) string {
	if value := fields[key]; value != nil {
		return fmt.Sprint(value)
	}
	return ""
}

func (o statusOutput) result(operation string, result any, elapsed time.Duration) error {
	fields, ok := result.(map[string]any)
	if !ok {
		return fmt.Errorf("malformed %s response: expected status object", operation)
	}
	state := statusText(fields, "state")
	subject := "Weaver"
	if operation == "status" {
		subject = "Mill"
		if healthy, ok := fields["healthy"].(bool); !ok {
			return fmt.Errorf("malformed mill status response: missing healthy")
		} else if healthy {
			state = "running"
		} else {
			state = "unhealthy"
		}
	}
	color := "33"
	label := state
	switch state {
	case "running":
		color = "32"
	case "failed", "stale", "unhealthy":
		color = "31"
	case "none":
		label = "not running"
	case "starting", "stopped", "probing", "restarting":
	default:
		return fmt.Errorf("malformed %s response: unknown state %q", operation, state)
	}
	if name := statusText(fields, "name"); name != "" {
		subject += " " + name
	}
	if operation == "weaver-restart" {
		subject = "Weaver restart"
		if state == "running" {
			label = "complete"
		}
	}
	heading := subject + " " + o.paint(color, label)
	var facts []string
	if pid := statusText(fields, "pid"); pid != "" && state != "stopped" {
		facts = append(facts, "PID "+pid)
	}
	if version := statusText(fields, "version"); version != "" {
		facts = append(facts, "v"+version)
	}
	if elapsed > 0 {
		facts = append(facts, elapsed.Round(time.Millisecond).String())
	}
	if len(facts) > 0 {
		heading += " (" + strings.Join(facts, ", ") + ")"
	}
	if elapsed > 0 {
		o.event(time.Now(), heading)
	} else {
		fmt.Fprintln(o.out, heading)
	}
	workspace := statusText(fields, "config_dir")
	if workspace == "" {
		workspace = statusText(fields, "workspace")
	}
	o.detail("Workspace", workspace)
	if started := statusText(fields, "started_at"); started != "" {
		at, err := time.Parse(time.RFC3339Nano, started)
		if err == nil {
			started = at.Local().Format("2006-01-02 15:04:05 MST")
		}
		o.detail("Since", started)
	}
	if operation == "status" {
		o.detail("Socket", statusText(fields, "socket_path"))
	}
	o.detail("Logs", statusText(fields, "log_path"))
	o.detail("Reason", statusText(fields, "stale_reason"))
	if restart := statusText(fields, "restart_state"); restart != "" && restart != state {
		o.detail("Restart", o.paint("31", restart))
	}
	if failure, ok := fields["restart_failure"].(map[string]any); ok {
		o.detail("Restart", o.paint("31", "last attempt failed; current weaver is still running"))
		o.detail("Reason", statusText(failure, "message"))
		o.detail("Logs", statusText(failure, "log_path"))
	}
	if rows, ok := restartDiagnosticRows(fields["diagnostics"]); ok {
		for _, row := range rows {
			if row["status"] != "failed" {
				continue
			}
			o.detail("Failed", statusText(row, "stage"))
			if data, ok := row["data"].(map[string]any); ok {
				o.detail("Reason", statusText(data, "message"))
				o.detail("Logs", statusText(data, "log_path"))
			}
			if row["stage"] == "probe" && fields["generation_id"] != nil {
				fmt.Fprintln(o.out, "  Current weaver is still running.")
			}
		}
		fmt.Fprintln(o.out, "  Use --json for full restart diagnostics.")
	}
	if state == "none" || state == "stopped" {
		fmt.Fprintln(o.out, "  Start with: mill weaver start --workspace "+fmt.Sprintf("%q", workspace))
	}
	return nil
}

func writeStatusResult(out io.Writer, jsonOutput bool, operation string, result any, elapsed time.Duration) error {
	if jsonOutput {
		return json.NewEncoder(out).Encode(result)
	}
	return newStatusOutput(out).result(operation, result, elapsed)
}
