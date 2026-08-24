//go:build darwin

package process

import (
	"fmt"

	"golang.org/x/sys/unix"
)

// observeProcessExit reports exit without consuming the child's wait status.
// kqueue NOTE_EXIT observes the child while leaving it waitable, pinning its
// PID and PGID until cancellation has finished tearing down descendants.
func observeProcessExit(pid int) error {
	kqueue, err := unix.Kqueue()
	if err != nil {
		return fmt.Errorf("create process exit kqueue: %w", err)
	}
	defer func() { _ = unix.Close(kqueue) }()
	change := unix.Kevent_t{Ident: uint64(pid), Filter: unix.EVFILT_PROC, Flags: unix.EV_ADD | unix.EV_ONESHOT, Fflags: unix.NOTE_EXIT}
	if _, err := unix.Kevent(kqueue, []unix.Kevent_t{change}, nil, nil); err != nil {
		return fmt.Errorf("watch process %d exit: %w", pid, err)
	}
	events := make([]unix.Kevent_t, 1)
	for {
		n, err := unix.Kevent(kqueue, nil, events, nil)
		if err == unix.EINTR {
			continue
		}
		if err != nil {
			return fmt.Errorf("observe process %d exit: %w", pid, err)
		}
		if n == 1 {
			return nil
		}
	}
}

func processGroupHasLiveMembers(pgid, leaderPID int) (bool, error) {
	processes, err := unix.SysctlKinfoProcSlice("kern.proc.all")
	if err != nil {
		return false, fmt.Errorf("read process table: %w", err)
	}
	for _, process := range processes {
		// The leader is intentionally left as the sole allowed zombie: it pins
		// the PGID until this cleanup has finished. Descendant zombies still
		// occupy their PIDs and are counted so callers do not report cleanup
		// complete while an orphaned descendant remains observable.
		if int(process.Eproc.Pgid) == pgid && (int(process.Proc.P_pid) != leaderPID || process.Proc.P_stat != 5) {
			return true, nil
		}
	}
	return false, nil
}
