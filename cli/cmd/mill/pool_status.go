package main

import (
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/jvmpool"
)

func (s *server) poolStatusForMember(host *weaverHost, configDir string) map[string]any {
	for _, member := range host.Members {
		if member.World.ConfigDir != configDir {
			continue
		}
		status, stale := readStatus(member.World)
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
		return status
	}
	return nil
}

func poolStatusForRegistered(world config.World, pool string, snapshot jvmpool.PoolSnapshot) map[string]any {
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
	return status
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
		status["live_members"] = poolConfigDirs(host.Members)
		addPoolPending(status, host, snapshot)
		return status, true, nil
	}
	return poolStatusForRegistered(world, pool, snapshot), true, nil
}

func (s *server) mergePooledCompactRestartStatus(world config.World, status map[string]any) map[string]any {
	record, ok, err := s.readRestartRecordSummaryCached(world)
	if err != nil {
		status["state"] = "stale"
		status["stale_reason"] = err.Error()
		return status
	}
	if !ok || record.State == restartStateRunning {
		if ok && record.State == restartStateRunning {
			mergeRestartRecordCompactStatus(status, record)
		}
		return status
	}
	compact := record.compactStatus(world)
	if status["state"] != "pending" {
		status["state"] = compact["state"]
	}
	for _, key := range []string{"generation_id", "previous_generation_id", "transition_id", "old_generation_stopped", "restart_failure"} {
		if value, present := compact[key]; present {
			status[key] = value
		} else {
			delete(status, key)
		}
	}
	return status
}

func (s *server) mergePooledDetailedRestartStatus(world config.World, status map[string]any) map[string]any {
	record, ok, err := readRestartRecordDetailed(world)
	if err != nil {
		status["state"] = "stale"
		status["stale_reason"] = err.Error()
		return status
	}
	if !ok {
		return status
	}
	if status["state"] != "pending" {
		status["state"] = record.State
	}
	mergeRestartRecordStatus(status, record)
	return status
}

func poolConfigDirsFromSnapshot(snapshot jvmpool.PoolSnapshot) []string {
	result := make([]string, 0, len(snapshot.Members))
	for _, member := range snapshot.Members {
		result = append(result, member.ConfigDir)
	}
	return result
}
