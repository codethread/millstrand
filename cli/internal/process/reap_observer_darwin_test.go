//go:build darwin

package process

import (
	"errors"
	"strings"
	"testing"

	"golang.org/x/sys/unix"
)

func TestObserveProcessExitAcceptsWaitableRegistrationRace(t *testing.T) {
	registerProcessExit := func(int, unix.Kevent_t) error { return unix.ESRCH }
	processExitWaitable := func(pid int) (bool, error) {
		if pid != 4242 {
			t.Fatalf("waitability probe pid = %d, want 4242", pid)
		}
		return true, nil
	}

	if err := observeProcessExitWith(4242, registerProcessExit, processExitWaitable); err != nil {
		t.Fatalf("waitable registration race returned error: %v", err)
	}
}

func TestObserveProcessExitKeepsUnprovenRegistrationFailureVisible(t *testing.T) {
	registerProcessExit := func(int, unix.Kevent_t) error { return unix.ESRCH }
	processExitWaitable := func(int) (bool, error) { return false, nil }
	if err := observeProcessExitWith(4242, registerProcessExit, processExitWaitable); err == nil || !errors.Is(err, unix.ESRCH) {
		t.Fatalf("unproven registration failure = %v, want ESRCH", err)
	}
	probeErr := errors.New("waitability probe failed")
	processExitWaitable = func(int) (bool, error) { return false, probeErr }
	err := observeProcessExitWith(4242, registerProcessExit, processExitWaitable)
	if err == nil || !errors.Is(err, unix.ESRCH) || !errors.Is(err, probeErr) {
		t.Fatalf("waitability probe failure = %v, want registration and probe errors", err)
	}

	registerProcessExit = func(int, unix.Kevent_t) error { return unix.EINVAL }
	processExitWaitable = func(int) (bool, error) {
		t.Fatal("unrelated registration failure should not invoke waitability probe")
		return false, nil
	}
	err = observeProcessExitWith(4242, registerProcessExit, processExitWaitable)
	if err == nil || !errors.Is(err, unix.EINVAL) || !strings.Contains(err.Error(), "watch process 4242 exit") {
		t.Fatalf("unrelated registration failure = %v", err)
	}
}
