//go:build !darwin && !linux

package main

import (
	"errors"
	"net"
)

func unixPeerPID(net.Conn) (int, error) {
	return 0, errors.New("process control peer PID is unsupported on this platform")
}
