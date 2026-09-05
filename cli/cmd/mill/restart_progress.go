package main

import (
	"fmt"

	"millstrand-strand-cli/internal/client"
)

// Progress describes only the in-memory transition. Idle means no transition
// is registered, not that the weaver is healthy or a restart succeeded.
func (s *server) weaverRestartProgress(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	state := "idle"
	if transition := s.lifecycleTransition(world.ConfigDir); transition != nil {
		state = transition.state()
	}
	return map[string]any{"state": state}, nil
}

func restartProgressState(result any) (string, error) {
	fields, ok := result.(map[string]any)
	if !ok || len(fields) != 1 {
		return "", fmt.Errorf("restart progress must be an object containing only state")
	}
	state, ok := fields["state"].(string)
	if ok {
		switch state {
		case "idle", restartStateProbing, restartStateRestarting, restartStateRunning, restartStateFailed:
			return state, nil
		}
	}
	return "", fmt.Errorf("restart progress has invalid state %v", fields["state"])
}
