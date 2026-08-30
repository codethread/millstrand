package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"millstrand-strand-cli/internal/config"
)

type dependencyCoordinate struct {
	Lib   string `json:"lib"`
	Value any    `json:"value"`
}

type dependencyDiagnostic struct {
	Status     string                `json:"status"`
	Stage      string                `json:"stage"`
	SourcePath string                `json:"source_path"`
	Message    string                `json:"message"`
	Cause      string                `json:"cause"`
	Coordinate *dependencyCoordinate `json:"coordinate"`
}

type dependencyLaunchError struct {
	diagnostic dependencyDiagnostic
	err        error
}

func (e *dependencyLaunchError) Error() string {
	return e.diagnostic.Message + ": " + e.diagnostic.Cause
}
func (e *dependencyLaunchError) Unwrap() error { return e.err }

func dependencyDiagnosticPath(world config.World) string {
	return filepath.Join(world.StateDir, "dependency-diagnostic.json")
}

func readDependencyDiagnostic(world config.World) (*dependencyDiagnostic, error) {
	path := dependencyDiagnosticPath(world)
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	_ = os.Remove(path)
	var diagnostic dependencyDiagnostic
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&diagnostic); err != nil {
		return nil, fmt.Errorf("malformed dependency diagnostic: %w", err)
	}
	if err := validateDependencyDiagnostic(diagnostic); err != nil {
		return nil, err
	}
	return &diagnostic, nil
}

func validateDependencyDiagnostic(diagnostic dependencyDiagnostic) error {
	if diagnostic.Status != "invalid-dependency-config" {
		return errors.New("dependency diagnostic status is invalid")
	}
	if diagnostic.Stage != "deps-read" && diagnostic.Stage != "deps-resolve" {
		return errors.New("dependency diagnostic stage is invalid")
	}
	if strings.TrimSpace(diagnostic.SourcePath) == "" || strings.TrimSpace(diagnostic.Message) == "" || strings.TrimSpace(diagnostic.Cause) == "" {
		return errors.New("dependency diagnostic contains a blank required field")
	}
	if diagnostic.Coordinate != nil && (strings.TrimSpace(diagnostic.Coordinate.Lib) == "" || diagnostic.Coordinate.Value == nil) {
		return errors.New("dependency diagnostic coordinate is invalid")
	}
	return nil
}
