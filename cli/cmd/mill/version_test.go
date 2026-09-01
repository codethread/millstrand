package main

import (
	"bytes"
	"encoding/json"
	"testing"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

func TestMillVersionIsLocalAndStructured(t *testing.T) {
	root := newMillCommand()
	var stdout bytes.Buffer
	root.SetOut(&stdout)
	root.SetArgs([]string{"--version"})
	if _, err := root.ExecuteC(); err != nil {
		t.Fatal(err)
	}

	var got map[string]any
	if err := json.Unmarshal(stdout.Bytes(), &got); err != nil {
		t.Fatalf("mill --version output is not JSON: %v (%q)", err, stdout.String())
	}
	want := map[string]any{
		"version":          config.Version,
		"build_id":         config.BuildID,
		"protocol_version": float64(client.ProtocolVersion),
	}
	if len(got) != len(want) {
		t.Fatalf("version output = %#v, want %#v", got, want)
	}
	for key, value := range want {
		if got[key] != value {
			t.Fatalf("version output[%q] = %#v, want %#v", key, got[key], value)
		}
	}
}
