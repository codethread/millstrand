package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

const (
	autostartDirectory = "autostart"
	autostartSlots     = 4
)

// autoStartRegistration is intentionally small: the config remains the
// source of truth for whether a workspace is enabled, while this hidden
// record remembers that the user explicitly opted into registration and the
// cwd needed by resolveLaunchSource on a later mill startup.
type autoStartRegistration struct {
	ConfigDir string `json:"config_dir"`
	CWD       string `json:"cwd"`
	Name      string `json:"name,omitempty"`
	Enabled   bool   `json:"enabled"`
}

func autostartPath(configDir string) (string, error) {
	identity, err := config.CanonicalWorldIdentity(configDir)
	if err != nil {
		return "", err
	}
	root, err := config.StateRoot()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, autostartDirectory, config.WorldHash(identity)+".json"), nil
}

func registerAutoStart(world config.World, cwd, name string) error {
	if strings.TrimSpace(cwd) == "" {
		return errors.New("autostart registration requires the launch cwd")
	}
	absoluteCWD, err := filepath.Abs(cwd)
	if err != nil {
		return err
	}
	path, err := autostartPath(world.ConfigDir)
	if err != nil {
		return err
	}
	entry := autoStartRegistration{ConfigDir: world.ConfigDir, CWD: absoluteCWD, Name: name, Enabled: true}
	b, err := json.MarshalIndent(entry, "", "  ")
	if err != nil {
		return err
	}
	if err := atomicWriteAutoStart(path, append(b, '\n')); err != nil {
		return fmt.Errorf("write autostart registration for %s: %w", world.ConfigDir, err)
	}
	millLogf("autostart registered config_dir=%s cwd=%s", world.ConfigDir, absoluteCWD)
	return nil
}

func removeAutoStart(configDir string) error {
	path, err := autostartPath(configDir)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	millLogf("autostart registration removed config_dir=%s", configDir)
	return nil
}

func atomicWriteAutoStart(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".autostart-*")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	defer func() { _ = os.Remove(tmpPath) }()
	if err := tmp.Chmod(0o644); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
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
	return os.Rename(tmpPath, path)
}

func readAutoStartRegistrations() ([]autoStartRegistration, error) {
	root, err := config.StateRoot()
	if err != nil {
		return nil, err
	}
	matches, err := filepath.Glob(filepath.Join(root, autostartDirectory, "*.json"))
	if err != nil {
		return nil, err
	}
	entries := make([]autoStartRegistration, 0, len(matches))
	for _, path := range matches {
		b, err := os.ReadFile(path)
		if err != nil {
			millLogf("autostart registration read failed path=%s: %v", path, err)
			continue
		}
		var entry autoStartRegistration
		if err := json.Unmarshal(b, &entry); err != nil {
			millLogf("autostart registration malformed path=%s: %v", path, err)
			continue
		}
		if !entry.Enabled || strings.TrimSpace(entry.ConfigDir) == "" || strings.TrimSpace(entry.CWD) == "" {
			millLogf("autostart registration malformed path=%s: missing enabled config_dir or cwd", path)
			continue
		}
		entries = append(entries, entry)
	}
	return entries, nil
}

func (s *server) signalShutdown() {
	if s.shutdown != nil {
		s.shutdownOnce.Do(func() { close(s.shutdown) })
	}
}

func (s *server) shuttingDown() bool {
	if s.shutdown == nil {
		return false
	}
	select {
	case <-s.shutdown:
		return true
	default:
		return false
	}
}

func (s *server) startAutostart() {
	entries, err := readAutoStartRegistrations()
	if err != nil {
		millLogf("autostart registry read failed: %v", err)
		return
	}
	if len(entries) == 0 {
		return
	}
	s.autostartWG.Add(1)
	go func() {
		defer s.autostartWG.Done()
		sem := make(chan struct{}, autostartSlots)
		var jobs sync.WaitGroup
		defer jobs.Wait()
	launch:
		for _, entry := range entries {
			if s.shuttingDown() {
				break
			}
			cfg, _, err := config.Load(entry.ConfigDir)
			if err != nil {
				millLogf("autostart config read failed config_dir=%s: %v", entry.ConfigDir, err)
				continue
			}
			if !cfg.AutoStart {
				if err := removeAutoStart(entry.ConfigDir); err != nil {
					millLogf("autostart registration removal failed config_dir=%s: %v", entry.ConfigDir, err)
				}
				continue
			}
			select {
			case sem <- struct{}{}:
			case <-s.shutdown:
				break launch
			}
			jobs.Add(1)
			go func(entry autoStartRegistration) {
				defer jobs.Done()
				defer func() { <-sem }()
				if s.shuttingDown() {
					return
				}
				millLogf("autostart starting config_dir=%s", entry.ConfigDir)
				status, err := s.startWeaverWithShutdown(client.MillWorldRequest{CWD: entry.CWD, ConfigDir: entry.ConfigDir, Name: entry.Name}, s.shutdown)
				if err != nil {
					millLogf("autostart failed config_dir=%s: %v", entry.ConfigDir, err)
					return
				}
				if state, _ := status["state"].(string); state == restartStateFailed {
					millLogf("autostart failed config_dir=%s: retained failed startup state", entry.ConfigDir)
					return
				}
				millLogf("autostart started config_dir=%s", entry.ConfigDir)
			}(entry)
		}
	}()
}

func (s *server) stopAutostart() {
	s.signalShutdown()
	s.autostartWG.Wait()
}
