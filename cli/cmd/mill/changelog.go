package main

import (
	"fmt"
	"os"
	"path/filepath"

	"millstrand-strand-cli/internal/config"
)

const changelogFileName = "CHANGELOG.md"

func renderChangelog() ([]byte, error) {
	cwd, err := os.Getwd()
	if err != nil {
		return nil, fmt.Errorf("mill changelog cannot determine the cwd used to resolve the Millstrand source: %w", err)
	}
	source, err := resolveLaunchSource(cwd)
	if err != nil {
		return nil, fmt.Errorf("mill changelog cannot resolve the Millstrand source from cwd %s: %w", cwd, err)
	}
	if _, err := config.ValidateSourceVersion(source, config.Version); err != nil {
		return nil, fmt.Errorf("mill changelog cannot read the installed release identity: %w", err)
	}
	path := filepath.Join(source, changelogFileName)
	info, err := os.Stat(path)
	if err != nil {
		return nil, fmt.Errorf("mill changelog: changelog %s is unavailable: %w", path, err)
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("mill changelog: changelog is not a regular file: %s", path)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("mill changelog: read %s: %w", path, err)
	}
	if len(content) == 0 {
		return nil, fmt.Errorf("mill changelog: changelog is empty: %s", path)
	}
	return content, nil
}

func runChangelog() error {
	content, err := renderChangelog()
	if err != nil {
		return err
	}
	if _, err := os.Stdout.Write(content); err != nil {
		return fmt.Errorf("writing changelog output: %w", err)
	}
	return nil
}
