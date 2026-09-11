package main

import (
	"fmt"
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
