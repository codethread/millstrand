package config

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
)

const (
	MillMetadataFileName = "mill.json"
	MillSocketFileName   = "mill.sock"
)

// StateRoot returns Millstrand's XDG state root. When XDG_STATE_HOME is unset,
// it uses the XDG fallback under the current user's home directory.
func StateRoot() (string, error) {
	base := os.Getenv("XDG_STATE_HOME")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		base = filepath.Join(home, ".local", "state")
	}
	if !filepath.IsAbs(base) {
		return "", fmt.Errorf("XDG_STATE_HOME must be an absolute path: %s", base)
	}
	return filepath.Join(filepath.Clean(base), "millstrand"), nil
}

func CanonicalConfigIdentity(configDir string) (string, error) {
	if configDir == "" {
		return "", fmt.Errorf("config dir is required")
	}
	abs, err := filepath.Abs(configDir)
	if err != nil {
		return "", err
	}
	if real, err := filepath.EvalSymlinks(abs); err == nil {
		abs = real
	}
	return filepath.Clean(abs), nil
}

func WorldHash(canonicalConfigIdentity string) string {
	sum := sha256.Sum256([]byte(canonicalConfigIdentity))
	return hex.EncodeToString(sum[:])[:32]
}

func RuntimeWorld(configDir string) (World, error) {
	canonicalConfigDir, err := CanonicalConfigIdentity(configDir)
	if err != nil {
		return World{}, err
	}
	identity := markerNeutralIdentity(canonicalConfigDir)
	root, err := StateRoot()
	if err != nil {
		return World{}, err
	}
	runtimeDir := filepath.Join(root, "weavers", WorldHash(identity))
	return world(canonicalConfigDir, runtimeDir, filepath.Join(runtimeDir, "data")), nil
}

func markerNeutralIdentity(identity string) string {
	base := filepath.Base(identity)
	if base == WorkspaceAlias || base == DefaultWorkspace {
		return filepath.Join(filepath.Dir(identity), DefaultWorkspace)
	}
	return identity
}

func MillMetadataPath() (string, error) {
	root, err := StateRoot()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, MillMetadataFileName), nil
}
