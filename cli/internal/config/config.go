package config

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const (
	ConfigFileName      = "config.json"
	LocalConfigFileName = "config.local.json"
	DefaultDBFileName   = "millstrand.sqlite"
	DefaultWorkspace    = ".millstrand"
	WorkspaceAlias      = ".ms"
	LegacyWorkspace     = ".skein"
)

var InstalledSource string

// Version is the shared Millstrand product version stamped into both shipped
// binaries from the source checkout's VERSION file.
var Version = "dev"

// BuildID identifies the source revision of both shipped binaries; local build
// tooling appends "-dirty" when tracked or untracked checkout content differs.
var BuildID = "dev"

type World struct {
	ConfigDir  string
	StateDir   string
	DataDir    string
	ConfigFile string
	DBPath     string
}

// UnknownKeyWarning records an ignored compatibility field. Warnings are
// retained during parsing so callers that merely read config do not emit
// diagnostics; the mill startup path decides when to present them.
type UnknownKeyWarning struct {
	File string
	Keys []string
}

type Config struct {
	ConfigFormat string              `json:"configFormat"`
	Name         string              `json:"name,omitempty"`
	AutoStart    bool                `json:"autoStart,omitempty"`
	Source       string              `json:"-"`
	Warnings     []UnknownKeyWarning `json:"-"`
}

func RepoWorld() (World, error) {
	root, err := GitRoot("")
	if err != nil {
		return World{}, err
	}
	configDir, err := repoWorkspace(root)
	if err != nil {
		return World{}, err
	}
	return isolatedWorld(configDir)
}

func SelectedWorld(configDir string) (World, error) {
	if configDir != "" {
		return isolatedWorld(configDir)
	}
	return RepoWorld()
}

func InitWorld(configDir string) (World, error) {
	if configDir != "" {
		return isolatedWorld(configDir)
	}
	return RepoWorld()
}

func isolatedWorld(configDir string) (World, error) {
	return RuntimeWorld(configDir)
}

func world(configDir, stateDir, dataDir string) World {
	return World{ConfigDir: configDir, StateDir: stateDir, DataDir: dataDir, ConfigFile: filepath.Join(configDir, ConfigFileName), DBPath: filepath.Join(dataDir, DefaultDBFileName)}
}

func Load(configDir string) (Config, World, error) {
	w, err := SelectedWorld(configDir)
	if err != nil {
		return Config{}, World{}, err
	}
	b, err := os.ReadFile(w.ConfigFile)
	if os.IsNotExist(err) {
		return Config{}, World{}, fmt.Errorf("client config %s is required; run mill init for the selected world", w.ConfigFile)
	}
	if err != nil {
		return Config{}, World{}, err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(b, &raw); err != nil {
		return Config{}, World{}, fmt.Errorf("malformed client config: %w", err)
	}
	c := Config{Warnings: unknownKeyWarnings(w.ConfigFile, raw, map[string]bool{
		"configFormat": true,
		"name":         true,
		"autoStart":    true,
	})}
	if v, ok := raw["configFormat"]; ok {
		if err := json.Unmarshal(v, &c.ConfigFormat); err != nil {
			return Config{}, World{}, fmt.Errorf("client config configFormat must be a string")
		}
	} else {
		return Config{}, World{}, fmt.Errorf("client config configFormat is required")
	}
	if c.ConfigFormat != "alpha" {
		return Config{}, World{}, fmt.Errorf("unsupported client config configFormat: %s", c.ConfigFormat)
	}
	if v, ok := raw["name"]; ok {
		name, err := parseConfigName("client config name", v)
		if err != nil {
			return Config{}, World{}, err
		}
		c.Name = name
	}
	if v, ok := raw["autoStart"]; ok {
		if bytes.Equal(bytes.TrimSpace(v), []byte("null")) {
			return Config{}, World{}, fmt.Errorf("client config autoStart must be a boolean")
		}
		if err := json.Unmarshal(v, &c.AutoStart); err != nil {
			return Config{}, World{}, fmt.Errorf("client config autoStart must be a boolean")
		}
	}
	if err := applyLocalOverlay(&c, filepath.Join(w.ConfigDir, LocalConfigFileName)); err != nil {
		return Config{}, World{}, err
	}
	return c, w, nil
}

// SetAutoStart updates only the top-level autoStart setting in an existing
// client config.  The caller has already bootstrapped and validated the
// workspace; this boundary parser keeps all other config fields intact.
func SetAutoStart(configDir string, enabled bool) error {
	path := filepath.Join(configDir, ConfigFileName)
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(b, &raw); err != nil {
		return fmt.Errorf("malformed client config: %w", err)
	}
	value, err := json.Marshal(enabled)
	if err != nil {
		return err
	}
	raw["autoStart"] = value
	updated, err := json.MarshalIndent(raw, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(configDir, ".config.json.autostart-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if err := tmp.Chmod(0o644); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(append(updated, '\n')); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

func parseConfigName(label string, raw json.RawMessage) (string, error) {
	var name string
	if err := json.Unmarshal(raw, &name); err != nil {
		return "", fmt.Errorf("%s must be a non-blank string", label)
	}
	if strings.TrimSpace(name) == "" {
		return "", fmt.Errorf("%s must be a non-blank string", label)
	}
	return name, nil
}

func unknownKeyWarnings(file string, raw map[string]json.RawMessage, known map[string]bool) []UnknownKeyWarning {
	unknown := make([]string, 0)
	for key := range raw {
		if !known[key] {
			unknown = append(unknown, key)
		}
	}
	if len(unknown) == 0 {
		return nil
	}
	sort.Strings(unknown)
	return []UnknownKeyWarning{{File: file, Keys: unknown}}
}

func applyLocalOverlay(c *Config, path string) error {
	b, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(b, &raw); err != nil {
		return fmt.Errorf("malformed local client config: %w", err)
	}
	if _, ok := raw["configFormat"]; ok {
		return fmt.Errorf("local client config must not declare configFormat")
	}
	c.Warnings = append(c.Warnings, unknownKeyWarnings(path, raw, map[string]bool{"name": true})...)
	if v, ok := raw["name"]; ok {
		name, err := parseConfigName("local client config name", v)
		if err != nil {
			return err
		}
		c.Name = name
	}
	return nil
}

func ResolveSource(source string) (string, error) {
	if source == "" {
		return "", fmt.Errorf("client config source is required for weaver lifecycle commands; set source in %s", ConfigFileName)
	}
	return ValidateSource("client config source", source)
}

func ValidateSource(label, source string) (string, error) {
	resolvedSource := source
	if source == "~" || strings.HasPrefix(source, "~/") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		if source == "~" {
			resolvedSource = home
		} else {
			resolvedSource = filepath.Join(home, source[2:])
		}
	}
	if !filepath.IsAbs(resolvedSource) {
		return "", fmt.Errorf("%s must be an absolute path: %s", label, resolvedSource)
	}
	if st, err := os.Stat(resolvedSource); err != nil || !st.IsDir() {
		return "", fmt.Errorf("%s must be an existing directory: %s", label, resolvedSource)
	}
	if st, err := os.Stat(filepath.Join(resolvedSource, "deps.edn")); err != nil || st.IsDir() {
		return "", fmt.Errorf("%s must contain deps.edn: %s", label, resolvedSource)
	}
	return resolvedSource, nil
}
