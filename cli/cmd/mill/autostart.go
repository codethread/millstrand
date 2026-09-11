package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

const (
	autostartDirectory = "autostart"
	autostartSlots     = 4
	autostartFailure   = "startup-failure.json"
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
	millLogf("Automatic startup enabled for %s (from %s)", world.ConfigDir, absoluteCWD)
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
	millLogf("Automatic startup disabled for %s", configDir)
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
	var failures []error
	for _, path := range matches {
		if filepath.Base(path) == autostartFailure {
			continue
		}
		b, err := os.ReadFile(path)
		if err != nil {
			failures = append(failures, fmt.Errorf("read automatic startup registration %s: %w", path, err))
			continue
		}
		var entry autoStartRegistration
		if err := json.Unmarshal(b, &entry); err != nil {
			failures = append(failures, fmt.Errorf("decode automatic startup registration %s: %w", path, err))
			continue
		}
		if !entry.Enabled || strings.TrimSpace(entry.ConfigDir) == "" || strings.TrimSpace(entry.CWD) == "" {
			failures = append(failures, fmt.Errorf("invalid automatic startup registration %s: missing enabled, config_dir or cwd", path))
			continue
		}
		entries = append(entries, entry)
	}
	return entries, errors.Join(failures...)
}

type autostartFailureRecord struct {
	State  string   `json:"state"`
	Errors []string `json:"errors"`
}

func autostartFailurePath() (string, error) {
	root, err := config.StateRoot()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, autostartDirectory, autostartFailure), nil
}

func writeAutostartFailure(failures []error) error {
	if len(failures) == 0 {
		return clearAutostartFailure()
	}
	path, err := autostartFailurePath()
	if err != nil {
		return err
	}
	record := autostartFailureRecord{State: "failed", Errors: make([]string, 0, len(failures))}
	for _, failure := range failures {
		if failure != nil {
			record.Errors = append(record.Errors, failure.Error())
		}
	}
	b, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return err
	}
	return atomicWriteAutoStart(path, append(b, '\n'))
}

func clearAutostartFailure() error {
	path, err := autostartFailurePath()
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("remove automatic startup failure %s: %w", path, err)
	}
	return nil
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
	failures := make([]error, 0, 1)
	if err != nil {
		failures = append(failures, err)
		millLogf("Automatic startup registry contains failures: %v", err)
	}
	if len(entries) == 0 {
		if err := writeAutostartFailure(failures); err != nil {
			millLogf("Could not persist automatic startup failure: %v", err)
		}
		return
	}
	// Automatic startup owns host processes, not logical members. Keep the
	// first eligible remembered member for each effective pool and retain every
	// isolated workspace as its own host job.
	grouped := make(map[string]autoStartRegistration, len(entries))
	for _, entry := range entries {
		cfg, _, err := config.Load(entry.ConfigDir)
		if err != nil {
			failure := fmt.Errorf("read startup config for registered workspace %s: %w", entry.ConfigDir, err)
			failures = append(failures, failure)
			millLogf("Automatic startup configuration failure: %v", failure)
			continue
		}
		if !cfg.AutoStart {
			if err := removeAutoStart(entry.ConfigDir); err != nil {
				failure := fmt.Errorf("remove disabled automatic startup registration for %s: %w", entry.ConfigDir, err)
				failures = append(failures, failure)
				millLogf("Automatic startup cleanup failure: %v", failure)
			}
			continue
		}
		key := "world:" + entry.ConfigDir
		if cfg.JVMPool != nil {
			key = "pool:" + *cfg.JVMPool
		}
		if _, exists := grouped[key]; !exists {
			grouped[key] = entry
		}
	}
	if len(grouped) == 0 {
		if err := writeAutostartFailure(failures); err != nil {
			millLogf("Could not persist automatic startup failure: %v", err)
		}
		return
	}
	jobsToStart := make([]autoStartRegistration, 0, len(grouped))
	for _, entry := range grouped {
		jobsToStart = append(jobsToStart, entry)
	}
	sort.Slice(jobsToStart, func(i, j int) bool { return jobsToStart[i].ConfigDir < jobsToStart[j].ConfigDir })
	s.autostartWG.Add(1)
	go func() {
		defer s.autostartWG.Done()
		sem := make(chan struct{}, autostartSlots)
		var jobs sync.WaitGroup
		var failureMu sync.Mutex
		defer jobs.Wait()
	launch:
		for _, entry := range jobsToStart {
			if s.shuttingDown() {
				break
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
				millLogf("Starting weaver automatically for %s…", entry.ConfigDir)
				status, err := s.startWeaverWithShutdown(client.MillWorldRequest{CWD: entry.CWD, ConfigDir: entry.ConfigDir, Name: entry.Name}, s.shutdown)
				if err != nil {
					millLogf("Automatic startup failed for %s: %v", entry.ConfigDir, err)
					if !s.shuttingDown() {
						failureMu.Lock()
						failures = append(failures, fmt.Errorf("automatic startup failed for %s: %w", entry.ConfigDir, err))
						failureMu.Unlock()
					}
					return
				}
				if state, _ := status["state"].(string); state == restartStateFailed {
					millLogf("Automatic startup failed for %s; previous startup failure needs attention", entry.ConfigDir)
					failureMu.Lock()
					failures = append(failures, fmt.Errorf("automatic startup for %s returned retained failed state", entry.ConfigDir))
					failureMu.Unlock()
					return
				}
			}(entry)
		}
		jobs.Wait()
		// A shutdown may have cancelled the queue before all eligible jobs were
		// admitted, or while admitted jobs were waiting for their own startup
		// boundary. Preserve the previous failure evidence until a later,
		// non-cancelled pass can account for the complete eligible set.
		if s.shuttingDown() {
			return
		}
		failureMu.Lock()
		deferredFailures := append([]error(nil), failures...)
		failureMu.Unlock()
		if err := writeAutostartFailure(deferredFailures); err != nil {
			millLogf("Could not persist automatic startup failure: %v", err)
		}
	}()
}

func (s *server) stopAutostart() {
	s.signalShutdown()
	s.autostartWG.Wait()
}
