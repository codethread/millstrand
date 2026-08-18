package main

import (
	"fmt"
	"os"
	"path/filepath"
)

func renderMillstrandPrime() (string, error) {
	cwd, err := os.Getwd()
	if err != nil {
		return "", fmt.Errorf("mill prime millstrand cannot determine the cwd used to resolve the Millstrand source: %w", err)
	}
	source, err := resolveLaunchSource(cwd)
	if err != nil {
		return "", fmt.Errorf("mill prime millstrand cannot resolve the Millstrand source from cwd %s: %w", cwd, err)
	}
	reference := filepath.Join(source, "docs", "reference.md")
	info, err := os.Stat(reference)
	if err != nil {
		return "", fmt.Errorf("mill prime millstrand: canonical reference %s is unavailable: %w", reference, err)
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("mill prime millstrand: canonical reference is not a regular file: %s", reference)
	}
	return fmt.Sprintf("Millstrand source: %s\nMillstrand reference: %s\n", source, reference), nil
}

func runMillstrandPrime() error {
	out, err := renderMillstrandPrime()
	if err != nil {
		return err
	}
	if _, err := fmt.Fprint(os.Stdout, out); err != nil {
		return fmt.Errorf("writing prime output: %w", err)
	}
	return nil
}
