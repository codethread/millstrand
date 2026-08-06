package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
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

var allowedKeys = map[string]bool{"configFormat": true, "name": true}

var InstalledSource string

// BuildID identifies the build of both shipped binaries; ldflags stamp it with
// the git short sha so metadata written by one build is attributable from the
// other when their mill protocol versions diverge.
var BuildID = "dev"

type World struct {
	ConfigDir  string
	StateDir   string
	DataDir    string
	ConfigFile string
	DBPath     string
}

type Config struct {
	ConfigFormat string `json:"configFormat"`
	Name         string `json:"name,omitempty"`
	Source       string `json:"-"`
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
	for k := range raw {
		if !allowedKeys[k] {
			return Config{}, World{}, fmt.Errorf("unsupported client config key: %s", k)
		}
	}
	var c Config
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
	if err := applyLocalOverlay(&c, filepath.Join(w.ConfigDir, LocalConfigFileName)); err != nil {
		return Config{}, World{}, err
	}
	return c, w, nil
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
	for k := range raw {
		switch k {
		case "name":
		case "configFormat":
			return fmt.Errorf("local client config must not declare configFormat")
		default:
			return fmt.Errorf("unsupported local client config key: %s", k)
		}
	}
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
