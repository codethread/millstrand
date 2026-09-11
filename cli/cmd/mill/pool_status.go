package main

import (
	"fmt"
	"strings"

	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
)

func (s *server) poolStatusForMember(host *weaverHost, configDir string) map[string]any {
	for _, member := range host.Members {
		if member.World.ConfigDir != configDir {
			continue
		}
		status, stale := readRuntimeStatus(member.World)
		if status == nil || stale {
			status = baseStatus(member.World, "running")
			status["pid"] = host.PID
			status["weaver_id"] = member.WeaverID
			status["generation_id"] = member.GenerationID
			status["socket_path"] = member.Identity.Socket
			status["started_at"] = member.Identity.StartedAt
		} else {
			status = cloneStatus(status)
		}
		status["jvm_pool"] = host.Pool
		status["host_id"] = host.HostID
		status["host_generation_id"] = host.HostGenerationID
		status["registered_members"] = poolConfigDirs(host.Members)
		live := poolConfigDirs(host.Members)
		status["live_members"] = live
		status["pending_members"] = []string{}
		status["restart_required"] = false
		status["member_basis_fingerprint"] = member.MemberBasis
		status["pool_restart_path"] = host.PoolRestartPath
		return status
	}
	return nil
}

func poolStatusForRegistered(world config.World, pool string, snapshot jvmpool.PoolSnapshot) (map[string]any, error) {
	status := baseStatus(world, "stopped")
	registered := make([]string, 0, len(snapshot.Members))
	for _, member := range snapshot.Members {
		registered = append(registered, member.ConfigDir)
	}
	status["jvm_pool"] = pool
	status["registered_members"] = registered
	status["live_members"] = []string{}
	status["pending_members"] = []string{}
	status["restart_required"] = false
	path, err := poolRestartRecordPathForPool(pool)
	if err != nil {
		return nil, fmt.Errorf("resolve JVM pool %q restart record: %w", pool, err)
	}
	status["pool_restart_path"] = path
	return status, nil
}

func (s *server) poolStatusForWorld(world config.World) (map[string]any, bool, error) {
	return s.poolStatusForWorldWithDetails(world, false)
}

func (s *server) poolStatusForWorldWithDetails(world config.World, details bool) (map[string]any, bool, error) {
	status, pooled, err := s.poolStatusForWorldRaw(world)
	if err != nil || !pooled || status == nil {
		return status, pooled, err
	}
	if details {
		return s.mergePooledDetailedRestartStatus(world, status), true, nil
	}
	return s.mergePooledCompactRestartStatus(world, status), true, nil
}

func (s *server) poolStatusForWorldRaw(world config.World) (map[string]any, bool, error) {
	s.mu.Lock()
	host := poolHostForConfigLocked(s, world.ConfigDir)
	s.mu.Unlock()
	if host != nil && host.Live {
		snapshot, err := s.poolRegisteredSnapshot(host.Pool)
		if err != nil {
			return nil, true, err
		}
		status := s.poolStatusForMember(host, world.ConfigDir)
		addPoolPending(status, host, snapshot)
		return status, true, nil
	}
	// A desired config edit is not allowed to erase the recorded live
	// placement. Rehydrate from durable membership before consulting the
	// effective config, so JVMPool:null still reports the serving host.
	recordedPool, recorded, err := s.registeredPoolForConfig(world.ConfigDir)
	if err != nil {
		return nil, true, err
	}
	if recorded {
		snapshot, snapshotErr := s.poolRegisteredSnapshot(recordedPool)
		if snapshotErr != nil {
			return nil, true, snapshotErr
		}
		s.mu.Lock()
		host = s.poolHosts[recordedPool]
		s.mu.Unlock()
		if host == nil {
			host, err = s.discoverPoolHost(recordedPool)
			if err != nil {
				return nil, true, err
			}
		}
		if host != nil && host.Live {
			if poolHostHasMember(host, world.ConfigDir) {
				status := s.poolStatusForMember(host, world.ConfigDir)
				addPoolPending(status, host, snapshot)
				return status, true, nil
			}
			status := baseStatus(world, "pending")
			status["jvm_pool"] = recordedPool
			status["pool_restart_path"] = host.PoolRestartPath
			status["live_members"] = poolConfigDirs(host.Members)
			addPoolPending(status, host, snapshot)
			return status, true, nil
		}
	}
	pool, err := configuredPool(world)
	if err != nil {
		return nil, false, err
	}
	if pool == "" {
		return nil, false, nil
	}
	snapshot, err := s.poolSnapshot(pool)
	if err != nil {
		return nil, true, err
	}
	if _, ok := memberForPool(snapshot, world.ConfigDir); !ok {
		return nil, true, nil
	}
	s.mu.Lock()
	host = s.poolHosts[pool]
	s.mu.Unlock()
	if host == nil {
		host, err = s.discoverPoolHost(pool)
		if err != nil {
			return nil, true, err
		}
	}
	if host != nil && host.Live {
		if poolHostHasMember(host, world.ConfigDir) {
			status := s.poolStatusForMember(host, world.ConfigDir)
			addPoolPending(status, host, snapshot)
			return status, true, nil
		}
		status := baseStatus(world, "pending")
		status["jvm_pool"] = pool
		status["pool_restart_path"] = host.PoolRestartPath
		status["live_members"] = poolConfigDirs(host.Members)
		addPoolPending(status, host, snapshot)
		return status, true, nil
	}
	status, err := poolStatusForRegistered(world, pool, snapshot)
	return status, true, err
}

func (s *server) mergePooledCompactRestartStatus(_ config.World, status map[string]any) map[string]any {
	record, ok, err := s.readPooledRestartRecordSummaryCached(status)
	if err != nil {
		status["state"] = "stale"
		status["stale_reason"] = err.Error()
		return status
	}
	if !ok {
		return status
	}
	mergePoolRestartRecordStatus(status, record, false)
	return status
}

func (s *server) mergePooledDetailedRestartStatus(_ config.World, status map[string]any) map[string]any {
	record, ok, err := s.readPooledRestartRecordDetailed(status)
	if err != nil {
		status["state"] = "stale"
		status["stale_reason"] = err.Error()
		return status
	}
	if !ok {
		return status
	}
	mergePoolRestartRecordStatus(status, record, true)
	return status
}

func poolRestartRecordPathForPool(pool string) (string, error) {
	root, err := config.StateRoot()
	if err != nil {
		return "", err
	}
	canonicalRoot, err := canonicalProbePath(root)
	if err != nil {
		return "", err
	}
	return poolRestartRecordPath(canonicalRoot, pool)
}

func pooledRestartRecordCoordinates(status map[string]any) (string, string, error) {
	pool, poolOK := status["jvm_pool"].(string)
	if !poolOK || strings.TrimSpace(pool) == "" {
		return "", "", fmt.Errorf("pooled status has invalid jvm_pool %#v", status["jvm_pool"])
	}
	path, pathOK := status["pool_restart_path"].(string)
	if !pathOK || strings.TrimSpace(path) == "" {
		return "", "", fmt.Errorf("pooled status has invalid pool_restart_path %#v", status["pool_restart_path"])
	}
	return pool, path, nil
}

func (s *server) readPooledRestartRecordSummaryCached(status map[string]any) (poolRestartRecord, bool, error) {
	pool, path, err := pooledRestartRecordCoordinates(status)
	if err != nil {
		return poolRestartRecord{}, false, err
	}
	return s.readPoolRestartRecordSummaryCached(path, pool)
}

func (s *server) readPooledRestartRecordDetailed(status map[string]any) (poolRestartRecord, bool, error) {
	pool, path, err := pooledRestartRecordCoordinates(status)
	if err != nil {
		return poolRestartRecord{}, false, err
	}
	return readPoolRestartRecord(path, pool)
}

func mergePoolRestartRecordStatus(status map[string]any, record poolRestartRecord, details bool) {
	currentState, _ := status["state"].(string)
	if currentState != "pending" && record.State != restartStateRunning {
		status["state"] = record.State
	}
	status["restart_state"] = record.State
	status["transition_id"] = record.TransitionID
	status["old_generation_stopped"] = record.OldGenerationStopped
	if details && record.Probe != nil {
		status["probe"] = *record.Probe
	}
	if record.Failure != nil {
		status["restart_failure"] = *record.Failure
		status["diagnostics"] = []map[string]any{{
			"stage": record.Failure.Stage, "status": "failed",
			"data": map[string]any{"message": record.Failure.Message, "log_path": record.Failure.LogPath},
		}}
	}
}

func poolConfigDirsFromSnapshot(snapshot jvmpool.PoolSnapshot) []string {
	result := make([]string, 0, len(snapshot.Members))
	for _, member := range snapshot.Members {
		result = append(result, member.ConfigDir)
	}
	return result
}
