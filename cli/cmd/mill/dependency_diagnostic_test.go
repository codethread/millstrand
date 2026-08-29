package main

import (
	"encoding/json"
	"os"
	"testing"

	"millstrand-strand-cli/internal/config"
)

func TestDependencyDiagnosticSidecarHasOneClosedShape(t *testing.T) {
	world := config.World{StateDir: t.TempDir()}
	valid := map[string]any{
		"status": "invalid-dependency-config", "stage": "deps-resolve",
		"source_path": "/tmp/deps.edn", "message": "cannot resolve",
		"cause":      "artifact missing",
		"coordinate": map[string]any{"lib": "broken/lib", "value": map[string]any{"mvn/version": "none"}},
	}
	data, err := json.Marshal(valid)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dependencyDiagnosticPath(world), data, 0o644); err != nil {
		t.Fatal(err)
	}
	diagnostic, err := readDependencyDiagnostic(world)
	if err != nil {
		t.Fatal(err)
	}
	if diagnostic == nil || diagnostic.Stage != "deps-resolve" || diagnostic.Coordinate == nil || diagnostic.Coordinate.Lib != "broken/lib" {
		t.Fatalf("dependency diagnostic was not relayed: %#v", diagnostic)
	}
	if _, err := os.Stat(dependencyDiagnosticPath(world)); !os.IsNotExist(err) {
		t.Fatalf("dependency diagnostic sidecar was retained: %v", err)
	}
}

func TestDependencyDiagnosticRejectsUnknownFieldsAndBlankCause(t *testing.T) {
	world := config.World{StateDir: t.TempDir()}
	for name, value := range map[string]string{
		"unknown":     `{"status":"invalid-dependency-config","stage":"deps-read","source_path":"/tmp/deps.edn","message":"bad","cause":"bad","coordinate":null,"extra":true}`,
		"blank cause": `{"status":"invalid-dependency-config","stage":"deps-read","source_path":"/tmp/deps.edn","message":"bad","cause":" ","coordinate":null}`,
	} {
		t.Run(name, func(t *testing.T) {
			if err := os.WriteFile(dependencyDiagnosticPath(world), []byte(value), 0o644); err != nil {
				t.Fatal(err)
			}
			if diagnostic, err := readDependencyDiagnostic(world); err == nil || diagnostic != nil {
				t.Fatalf("malformed diagnostic was accepted: diagnostic=%#v err=%v", diagnostic, err)
			}
		})
	}
}
