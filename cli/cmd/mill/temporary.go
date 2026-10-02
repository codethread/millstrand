package main

import (
	"errors"
	"fmt"
	"os"

	"millstrand-strand-cli/internal/config"
)

// checkStartModeLocked preserves the lifetime selected by the first start.
// A dead temporary child still owns its directory until teardown succeeds.
func (s *server) checkStartModeLocked(world config.World, temporary bool) error {
	child := s.children[world.ConfigDir]
	if child == nil || s.startClaims[world.ConfigDir] != nil {
		return nil
	}
	if !child.temporary && (child.cmd == nil || child.cmd.Process == nil || !processAlive(child.cmd.Process.Pid)) {
		return nil
	}
	if child.temporary && child.waitDone != nil {
		select {
		case <-child.waitDone:
			return s.cleanupTemporaryChildLocked(child)
		default:
		}
	}
	if child.temporary != temporary {
		return errors.New("weaver lifetime mode differs; stop it before switching between persistent start and start --temp")
	}
	return nil
}

func decorateTemporaryStatus(status map[string]any, child *weaverChild) {
	if child != nil && child.temporary {
		status["temporary"] = true
	}
}

// cleanupTemporaryChildLocked runs only after the Weaver has been reaped.
// Holding s.mu retains ownership through custody shutdown and directory removal;
// failed teardown stays registered so status reports it and stop can retry it.
func (s *server) cleanupTemporaryChildLocked(child *weaverChild) (err error) {
	defer func() { child.cleanupErr = err }()
	if custody := s.custodies[child.world.StateDir]; custody != nil {
		if err := custody.Shutdown(); err != nil {
			return fmt.Errorf("temporary weaver custody cleanup: %w", err)
		}
	}
	if child.identity.WeaverID != "" {
		if err := cleanupWorldArtifactsOwned(child.world, child.identity); err != nil {
			return err
		}
	}
	if err := removeTemporaryRuntimeWorld(child.world); err != nil {
		return err
	}
	delete(s.custodies, child.world.StateDir)
	delete(s.children, child.world.ConfigDir)
	return nil
}

func removeTemporaryRuntimeWorld(world config.World) error {
	if !config.IsTemporaryRuntimeDir(world.StateDir) {
		return fmt.Errorf("refusing to remove non-temporary runtime directory %s", world.StateDir)
	}
	return os.RemoveAll(world.StateDir)
}
