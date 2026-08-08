package process

import (
	"errors"
	"syscall"
	"testing"
)

func TestSignalProbeAliveTreatsEPERMAsExisting(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want bool
	}{
		{name: "success", want: true},
		{name: "permission denied", err: syscall.EPERM, want: true},
		{name: "wrapped permission denied", err: errors.Join(errors.New("probe failed"), syscall.EPERM), want: true},
		{name: "missing process", err: syscall.ESRCH, want: false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := signalProbeAlive(tt.err); got != tt.want {
				t.Fatalf("signalProbeAlive(%v) = %t, want %t", tt.err, got, tt.want)
			}
		})
	}
}
