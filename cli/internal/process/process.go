package process

import (
	"errors"
	"os"
	"syscall"
)

// Alive reports whether pid identifies a process. On Unix, EPERM from a
// signal-0 probe means the process exists but this process may not signal it.
func Alive(pid int) bool {
	if pid <= 0 {
		return false
	}
	p, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	return signalProbeAlive(p.Signal(syscall.Signal(0)))
}

func signalProbeAlive(err error) bool {
	return err == nil || errors.Is(err, syscall.EPERM)
}
