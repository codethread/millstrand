//go:build linux

package main

import (
	"errors"
	"fmt"
	"net"
	"syscall"

	"golang.org/x/sys/unix"
)

func unixPeerPID(conn net.Conn) (int, error) {
	syscallConn, ok := conn.(syscall.Conn)
	if !ok {
		return 0, errors.New("process control connection does not expose a Unix file descriptor")
	}
	var pid int
	var peerErr error
	raw, err := syscallConn.SyscallConn()
	if err != nil {
		return 0, fmt.Errorf("inspect process control peer: %w", err)
	}
	if err := raw.Control(func(fd uintptr) {
		cred, err := unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
		if err != nil {
			peerErr = err
			return
		}
		pid = int(cred.Pid)
	}); err != nil {
		return 0, fmt.Errorf("inspect process control peer: %w", err)
	}
	if peerErr != nil {
		return 0, fmt.Errorf("inspect process control peer: %w", peerErr)
	}
	if pid <= 0 {
		return 0, fmt.Errorf("process control peer pid %d is invalid", pid)
	}
	return pid, nil
}
