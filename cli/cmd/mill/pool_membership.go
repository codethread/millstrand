package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
)

func (s *server) jvmPoolRegistry() (*jvmpool.Registry, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.poolRegistry != nil {
		return s.poolRegistry, nil
	}
	root, err := config.StateRoot()
	if err != nil {
		return nil, err
	}
	registry, err := jvmpool.New(root)
	if err != nil {
		return nil, err
	}
	s.poolRegistry = registry
	return registry, nil
}

func configuredPool(world config.World) (string, error) {
	cfg, _, err := config.Load(world.ConfigDir)
	if err != nil {
		return "", err
	}
	if cfg.JVMPool == nil {
		return "", nil
	}
	if strings.TrimSpace(*cfg.JVMPool) == "" {
		return "", fmt.Errorf("effective JVMPool for %s is blank", world.ConfigDir)
	}
	return *cfg.JVMPool, nil
}

func (s *server) poolSnapshot(pool string) (jvmpool.PoolSnapshot, error) {
	registry, err := s.jvmPoolRegistry()
	if err != nil {
		return jvmpool.PoolSnapshot{}, err
	}
	return registry.ValidatePool(pool, func(configDir string) (jvmpool.EffectiveConfig, error) {
		cfg, _, err := config.Load(configDir)
		if err != nil {
			return jvmpool.EffectiveConfig{}, err
		}
		return jvmpool.EffectiveConfig{ConfigDir: configDir, JVMPool: cfg.JVMPool}, nil
	})
}

func (s *server) poolRegisteredSnapshot(pool string) (jvmpool.PoolSnapshot, error) {
	registry, err := s.jvmPoolRegistry()
	if err != nil {
		return jvmpool.PoolSnapshot{}, err
	}
	return registry.Snapshot(pool)
}

func memberForPool(snapshot jvmpool.PoolSnapshot, configDir string) (jvmpool.Member, bool) {
	for _, member := range snapshot.Members {
		if member.ConfigDir == configDir {
			return member, true
		}
	}
	return jvmpool.Member{}, false
}

// registeredPoolForConfig is used by stop/status when the desired config has
// moved since the live host was admitted. It deliberately reads, but never
// repairs, the durable registry.
func (s *server) registeredPoolForConfig(configDir string) (string, bool, error) {
	registry, err := s.jvmPoolRegistry()
	if err != nil {
		return "", false, err
	}
	doc, err := registry.Read()
	if err != nil {
		return "", false, err
	}
	for _, member := range doc.Members {
		if member.ConfigDir == configDir {
			return member.JVMPool, true, nil
		}
	}
	return "", false, nil
}

func (s *server) poolMembershipRecorded(configDir string) (bool, error) {
	_, present, err := s.registeredPoolForConfig(configDir)
	return present, err
}

func poolStopRequiredError(configDir, pool, hostID string) error {
	return &client.ResponseError{
		Type:    "domain",
		Code:    "mill/jvm-pool-stop-required",
		Message: "stop the live JVM pool before restarting with isolated configuration",
		Details: map[string]any{"config_dir": configDir, "jvm_pool": pool, "host_id": hostID},
	}
}

func (s *server) poolMemberRecorded(configDir string) bool {
	if s.poolMembers == nil {
		return false
	}
	s.mu.Lock()
	present := poolHostForConfigLocked(s, configDir) != nil
	s.mu.Unlock()
	return present
}

// livePoolHostForConfig finds the admitted owner before init changes desired
// configuration. The registry remains the durable source after a Mill restart,
// so discovery is also needed for a host this server has not launched.
func (s *server) livePoolHostForConfig(configDir string) (*weaverHost, error) {
	s.mu.Lock()
	host := poolHostForConfigLocked(s, configDir)
	s.mu.Unlock()
	if host != nil && host.Live {
		return host, nil
	}
	pool, recorded, err := s.registeredPoolForConfig(configDir)
	if err != nil || !recorded {
		return nil, err
	}
	return s.discoverPoolHost(pool)
}

func (s *server) livePoolOwnership() jvmpool.LiveOwnership {
	s.mu.Lock()
	defer s.mu.Unlock()
	ownership := jvmpool.LiveOwnership{}
	for pool, host := range s.poolHosts {
		if host == nil || !host.Live {
			continue
		}
		for _, member := range host.Members {
			ownership[member.World.ConfigDir] = jvmpool.LiveOwner{Pool: pool, HostID: host.HostID}
		}
	}
	return ownership
}

func initSourceCWD(cwd string) (string, error) {
	if strings.TrimSpace(cwd) == "" {
		var err error
		cwd, err = os.Getwd()
		if err != nil {
			return "", err
		}
	}
	abs, err := filepath.Abs(cwd)
	if err != nil {
		return "", err
	}
	if real, err := filepath.EvalSymlinks(abs); err == nil {
		abs = real
	}
	return filepath.Clean(abs), nil
}

// reconcileInitPool applies the optional local override and then reconciles
// durable membership. It checks the recorded live owner first so init cannot
// silently move a serving member between isolated and pooled placement.
func (s *server) reconcileInitPool(world config.World, cwd string, override *string) (string, error) {
	currentPool, err := configuredPool(world)
	if err != nil {
		return "", err
	}
	desiredPool := currentPool
	if override != nil {
		desiredPool = *override
	}
	host, err := s.livePoolHostForConfig(world.ConfigDir)
	if err != nil {
		return "", err
	}
	if host != nil && host.Live && host.Pool != desiredPool {
		return "", poolStopRequiredError(world.ConfigDir, host.Pool, host.HostID)
	}
	if override != nil {
		if err := config.SetLocalJVMPool(world.ConfigDir, *override); err != nil {
			return "", err
		}
	}
	// Reload after the local write: this preserves base config values for a
	// plain init and makes the local override the effective configuration.
	effective, _, err := config.Load(world.ConfigDir)
	if err != nil {
		return "", err
	}
	desiredPool = ""
	if effective.JVMPool != nil {
		desiredPool = *effective.JVMPool
	}
	registry, err := s.jvmPoolRegistry()
	if err != nil {
		return "", err
	}
	live := s.livePoolOwnership()
	if desiredPool == "" {
		if _, err := registry.Remove(world.ConfigDir, live); err != nil {
			return "", err
		}
		return "", nil
	}
	sourceCWD, err := initSourceCWD(cwd)
	if err != nil {
		return "", err
	}
	if _, err := registry.Reconcile(jvmpool.Member{ConfigDir: world.ConfigDir, SourceCWD: sourceCWD, JVMPool: desiredPool}, live); err != nil {
		return "", err
	}
	return desiredPool, nil
}
