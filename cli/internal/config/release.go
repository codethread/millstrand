package config

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
)

const VersionFileName = "VERSION"

var (
	releaseVersionPattern     = regexp.MustCompile(`^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$`)
	releaseVersionFilePattern = regexp.MustCompile(`^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\n$`)
)

// ValidVersion reports whether value is one canonical product version.
func ValidVersion(value string) bool {
	return releaseVersionPattern.MatchString(value)
}

// SourceVersion reads the product version retained with a Millstrand source.
func SourceVersion(source string) (string, error) {
	path := filepath.Join(source, VersionFileName)
	info, err := os.Stat(path)
	if err != nil {
		return "", fmt.Errorf("millstrand version file %s is unavailable: %w", path, err)
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("millstrand version file is not a regular file: %s", path)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read Millstrand version file %s: %w", path, err)
	}
	if !releaseVersionFilePattern.Match(content) {
		return "", fmt.Errorf("millstrand version file %s must contain one MAJOR.MINOR.PATCH version", path)
	}
	content = content[:len(content)-1]
	return string(content), nil
}

// ValidateSourceVersion validates source and binary product metadata independently.
// Release differences do not determine transport or bootstrap compatibility.
func ValidateSourceVersion(source, expected string) (string, error) {
	version, err := SourceVersion(source)
	if err != nil {
		return "", err
	}
	if expected == "dev" {
		return version, nil
	}
	if !ValidVersion(expected) {
		return "", fmt.Errorf("millstrand binary product version %q is invalid", expected)
	}
	return version, nil
}
