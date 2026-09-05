package main

import (
	"bytes"
	"encoding/json"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestHumanLifecycleStatus(t *testing.T) {
	for _, tt := range []struct {
		name   string
		op     string
		result map[string]any
		want   []string
	}{
		{"running", "weaver-status", map[string]any{"state": "running", "name": "demo", "pid": 42, "version": "0.5.2", "config_dir": "/tmp/demo", "log_path": "/tmp/weaver.log", "generation_id": "opaque"}, []string{"Weaver demo running (PID 42, v0.5.2)", "Workspace  /tmp/demo", "Logs       /tmp/weaver.log"}},
		{"absent", "weaver-status", map[string]any{"state": "none", "config_dir": "/tmp/demo"}, []string{"Weaver not running", "Start with: mill weaver start"}},
		{"stale", "weaver-status", map[string]any{"state": "stale", "stale_reason": "process exited"}, []string{"Weaver stale", "Reason     process exited"}},
		{"starting", "weaver-status", map[string]any{"state": "starting", "pid": 42}, []string{"Weaver starting (PID 42)"}},
		{"probing", "weaver-status", map[string]any{"state": "probing"}, []string{"Weaver probing"}},
		{"cutover", "weaver-status", map[string]any{"state": "restarting"}, []string{"Weaver restarting"}},
		{"stopped", "weaver-stop", map[string]any{"state": "stopped", "pid": 42}, []string{"Weaver stopped\n"}},
		{"restart", "weaver-restart", map[string]any{"state": "running", "workspace": "/tmp/demo", "generation_id": "new"}, []string{"Weaver restart complete", "Workspace  /tmp/demo"}},
		{"failed probe", "weaver-restart", map[string]any{"state": "failed", "generation_id": "old", "diagnostics": []any{map[string]any{"stage": "probe", "status": "failed", "data": map[string]any{"message": "init failed", "log_path": "/tmp/probe.log"}}}}, []string{"Weaver restart failed", "Reason     init failed", "/tmp/probe.log", "Current weaver is still running.", "--json"}},
		{"retained probe failure", "weaver-status", map[string]any{"state": "running", "restart_failure": map[string]any{"message": "init failed"}}, []string{"Weaver running", "last attempt failed", "Reason     init failed"}},
		{"mill", "status", map[string]any{"healthy": true, "pid": 42, "socket_path": "/tmp/mill.sock"}, []string{"Mill running (PID 42)", "Socket     /tmp/mill.sock"}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			var out bytes.Buffer
			if err := writeStatusResult(&out, false, tt.op, tt.result, 0); err != nil {
				t.Fatal(err)
			}
			for _, want := range tt.want {
				if !strings.Contains(out.String(), want) {
					t.Fatalf("missing %q in:\n%s", want, &out)
				}
			}
			if strings.ContainsAny(out.String(), "\x1b{") || strings.Contains(out.String(), "opaque") {
				t.Fatalf("plain status contains ANSI or raw metadata: %q", &out)
			}
		})
	}
}

func TestStatusJSONRetainsFullResult(t *testing.T) {
	result := map[string]any{"state": "running", "pid": float64(42), "generation_id": "opaque", "nrepl": map[string]any{"host": "127.0.0.1", "port": float64(1234)}}
	var out bytes.Buffer
	if err := writeStatusResult(&out, true, "weaver-start", result, time.Second); err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(out.Bytes(), &decoded); err != nil || !reflect.DeepEqual(decoded, result) {
		t.Fatalf("JSON changed or contains progress: %s (%v)", &out, err)
	}
}

func TestStatusColorAndTimestamp(t *testing.T) {
	var out bytes.Buffer
	ui := statusOutput{out: &out, color: true}
	ui.event(time.Date(2026, 9, 5, 14, 3, 2, 0, time.UTC), "Starting weaver…")
	if got, want := out.String(), "\x1b[2m14:03:02\x1b[0m  Starting weaver…\n"; got != want {
		t.Fatalf("timestamp = %q, want %q", got, want)
	}
	out.Reset()
	if err := ui.result("weaver-status", map[string]any{"state": "running"}, 0); err != nil {
		t.Fatal(err)
	}
	if got, want := out.String(), "Weaver \x1b[32mrunning\x1b[0m\n"; got != want {
		t.Fatalf("status color = %q, want %q", got, want)
	}
}

func TestStatusRejectsMalformedResponse(t *testing.T) {
	for _, result := range []any{nil, "running", map[string]any{}, map[string]any{"state": "surprise"}} {
		var out bytes.Buffer
		if err := writeStatusResult(&out, false, "weaver-status", result, 0); err == nil {
			t.Fatalf("accepted malformed response %#v", result)
		}
	}
}
