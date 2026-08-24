package process

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func testSpec(t *testing.T, argv []string) LaunchSpec {
	t.Helper()
	return LaunchSpec{Argv: argv, CWD: t.TempDir(), Env: map[string]string{"CUSTODY_TEST": "yes"}}
}

func waitTerminal(t *testing.T, custody *Custody, handle string) Record {
	t.Helper()
	deadline := time.NewTimer(5 * time.Second)
	ticker := time.NewTicker(10 * time.Millisecond)
	defer deadline.Stop()
	defer ticker.Stop()
	for {
		select {
		case <-deadline.C:
			t.Fatal("process did not become terminal")
		case <-ticker.C:
			row, err := custody.Get(handle)
			if err != nil {
				t.Fatal(err)
			}
			if row.Phase == "terminal" {
				return row
			}
		}
	}
}

func TestParseLaunchSpecValidatesBoundaryAndPreservesArgv(t *testing.T) {
	cwd := t.TempDir()
	spec, err := ParseLaunchSpec(map[string]any{
		"argv":  []string{"printf", "%s", "hello world"},
		"cwd":   cwd,
		"env":   map[string]string{"A": "B"},
		"stdin": "input",
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(spec.Argv) != 3 || spec.Argv[2] != "hello world" || *spec.Stdin != "input" {
		t.Fatalf("parsed launch spec = %#v", spec)
	}
	if _, err := ParseLaunchSpec(map[string]any{"argv": []any{"sh"}, "cwd": "relative", "env": map[string]any{}}); err == nil {
		t.Fatal("relative cwd should be rejected")
	}
	if _, err := ParseLaunchSpec(map[string]any{"argv": []any{"sh"}, "cwd": cwd, "env": map[string]any{"A": 1}}); err == nil {
		t.Fatal("non-string env should be rejected")
	}
}

func TestLaunchSameKeyConvergesAndConflictIsLoud(t *testing.T) {
	custody, err := NewCustody(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spec := testSpec(t, []string{"sh", "-c", "printf output"})
	const calls = 12
	rows := make(chan Record, calls)
	errs := make(chan error, calls)
	for i := 0; i < calls; i++ {
		go func() {
			row, err := custody.Launch("owner/run", "same", spec)
			rows <- row
			errs <- err
		}()
	}
	var first Record
	for i := 0; i < calls; i++ {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
		row := <-rows
		if first.Handle == "" {
			first = row
		} else if row.Handle != first.Handle {
			t.Fatalf("same-key launch handles diverged: %q and %q", first.Handle, row.Handle)
		}
	}
	conflict := spec
	conflict.Argv = []string{"sh", "-c", "printf different"}
	if _, err := custody.Launch("owner/run", "same", conflict); err == nil {
		t.Fatal("conflicting key reuse should fail")
	}
}

func TestOwnerIsolationAndTerminalRetentionAcknowledgement(t *testing.T) {
	custody, err := NewCustody(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	row, err := custody.Launch("owner/a", "run", testSpec(t, []string{"sh", "-c", "printf retained"}))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := custody.Cancel("owner/b", row.Handle); err == nil {
		t.Fatal("owner mismatch should fail")
	}
	terminal := waitTerminal(t, custody, row.Handle)
	if terminal.Exit == nil || terminal.Phase != "terminal" {
		t.Fatalf("terminal record = %#v", terminal)
	}
	if rows, err := custody.ListOwned("owner/a"); err != nil || len(rows) != 1 {
		t.Fatalf("terminal retention rows = %#v, err=%v", rows, err)
	}
	if err := custody.Acknowledge("owner/a", row.Handle); err != nil {
		t.Fatal(err)
	}
	if rows, err := custody.ListOwned("owner/a"); err != nil || len(rows) != 0 {
		t.Fatalf("acknowledged rows = %#v, err=%v", rows, err)
	}
	if _, err := custody.Launch("owner/a", "run", testSpec(t, []string{"sh", "-c", "printf relaunch"})); err == nil {
		t.Fatal("acknowledged key tombstone should block relaunch")
	}
}

func TestCancelTerminatesProcessTreeAndRetainsCancellation(t *testing.T) {
	root := t.TempDir()
	custody, err := NewCustody(filepath.Join(root, "custody"))
	if err != nil {
		t.Fatal(err)
	}
	pidFile := filepath.Join(root, "child.pid")
	row, err := custody.Launch("owner/tree", "run", LaunchSpec{
		Argv: []string{"sh", "-c", "sleep 30 & echo $! > '" + pidFile + "'; wait"},
		CWD:  root,
		Env:  map[string]string{},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := custody.Cancel("owner/tree", row.Handle); err != nil {
		t.Fatal(err)
	}
	terminal := waitTerminal(t, custody, row.Handle)
	if terminal.Cancellation == nil || terminal.Cancellation.Reason == "" {
		t.Fatalf("cancellation result = %#v", terminal)
	}
}

func TestCustodyTERMIgnoringChildHelper(t *testing.T) {
	role := os.Getenv("CUSTODY_HELPER_ROLE")
	if role == "" {
		return
	}
	if role == "child" {
		signal.Ignore(syscall.SIGTERM)
		if err := os.WriteFile(os.Getenv("CUSTODY_HELPER_PID_FILE"), []byte(strconv.Itoa(os.Getpid())), 0o600); err != nil {
			helperFatalf("write child pid marker: %v", err)
		}
		waitForHelperRelease(os.Getenv("CUSTODY_HELPER_CHILD_RELEASE_PATH"), os.Getenv("CUSTODY_HELPER_CHILD_ACK_PATH"))
	}
	if role != "leader" {
		os.Exit(2)
	}
	child := exec.Command(os.Args[0], "-test.run=TestCustodyTERMIgnoringChildHelper", "--")
	child.Env = append(os.Environ(), "CUSTODY_HELPER_ROLE=child")
	if err := child.Start(); err != nil {
		helperFatalf("start child helper: %v", err)
	}
	if err := os.WriteFile(os.Getenv("CUSTODY_HELPER_LEADER_PID_FILE"), []byte(strconv.Itoa(os.Getpid())), 0o600); err != nil {
		helperFatalf("write leader pid marker: %v", err)
	}
	waitForHelperRelease(os.Getenv("CUSTODY_HELPER_RELEASE_PATH"), os.Getenv("CUSTODY_HELPER_ACK_PATH"))
}

const helperReleaseDeadline = 5 * time.Second

func helperFatalf(format string, args ...any) {
	_, _ = fmt.Fprintf(os.Stderr, "custody test helper: "+format+"\n", args...)
	os.Exit(2)
}

func waitForHelperRelease(releasePath, acknowledgementPath string) {
	if strings.TrimSpace(releasePath) == "" || strings.TrimSpace(acknowledgementPath) == "" {
		helperFatalf("release and acknowledgement marker paths must not be blank")
	}
	deadline := time.Now().Add(helperReleaseDeadline)
	for {
		file, err := os.Open(releasePath)
		if err == nil {
			_ = file.Close()
			if err := os.WriteFile(acknowledgementPath, []byte("consumed\n"), 0o600); err != nil {
				helperFatalf("write acknowledgement marker %s: %v", acknowledgementPath, err)
			}
			return
		}
		if !errors.Is(err, os.ErrNotExist) {
			helperFatalf("read release marker %s: %v", releasePath, err)
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			helperFatalf("release marker %s was not received before the %s deadline", releasePath, helperReleaseDeadline)
		}
		timer := time.NewTimer(minDuration(remaining, 10*time.Millisecond))
		<-timer.C
		timer.Stop()
	}
}

func waitPIDFile(t *testing.T, path string) int {
	t.Helper()
	deadline := time.NewTimer(5 * time.Second)
	ticker := time.NewTicker(10 * time.Millisecond)
	defer deadline.Stop()
	defer ticker.Stop()
	for {
		select {
		case <-deadline.C:
			t.Fatalf("PID file %s was not written", path)
		case <-ticker.C:
			value, err := os.ReadFile(path)
			if err != nil {
				continue
			}
			pid, err := strconv.Atoi(string(value))
			if err == nil && pid > 0 {
				return pid
			}
		}
	}
}

func releaseHelper(t *testing.T, path string) {
	t.Helper()
	if err := os.WriteFile(path, []byte("release\n"), 0o600); err != nil {
		t.Errorf("helper release marker %s could not be written: %v", path, err)
	}
}

type helperMarkers struct {
	releasePath         string
	acknowledgementPath string
	pidPath             string
}

func cleanupHelper(t *testing.T, markers ...helperMarkers) {
	t.Helper()
	for _, marker := range markers {
		releaseHelper(t, marker.releasePath)
	}
	deadline := time.Now().Add(helperReleaseDeadline)
	for _, marker := range markers {
		if err := waitForHelperCompletion(marker, deadline); err != nil {
			t.Errorf("helper cleanup for %s failed: %v", marker.releasePath, err)
		}
	}
}

func waitForHelperCompletion(marker helperMarkers, deadline time.Time) error {
	if strings.TrimSpace(marker.acknowledgementPath) == "" || strings.TrimSpace(marker.pidPath) == "" {
		return errors.New("acknowledgement and pid marker paths must not be blank")
	}
	pid, err := readHelperPID(marker.pidPath, deadline)
	if err != nil {
		return err
	}
	acknowledged := false
	for {
		if !Alive(pid) {
			// Cancellation may have killed the helper before cleanup could
			// publish its release marker. Its exact PID being gone is enough to
			// permit t.TempDir cleanup because it cannot access the root again.
			return nil
		}
		if !acknowledged {
			if _, err := os.Stat(marker.acknowledgementPath); err == nil {
				acknowledged = true
			} else if !errors.Is(err, os.ErrNotExist) {
				return fmt.Errorf("read acknowledgement marker %s: %w", marker.acknowledgementPath, err)
			}
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			if acknowledged {
				return fmt.Errorf("helper pid %d acknowledged its release marker but did not exit before the %s deadline", pid, helperReleaseDeadline)
			}
			return fmt.Errorf("helper pid %d did not acknowledge its release marker and exit before the %s deadline", pid, helperReleaseDeadline)
		}
		timer := time.NewTimer(minDuration(remaining, 10*time.Millisecond))
		<-timer.C
		timer.Stop()
	}
}

func readHelperPID(path string, deadline time.Time) (int, error) {
	for {
		value, err := os.ReadFile(path)
		if err == nil {
			pid, parseErr := strconv.Atoi(strings.TrimSpace(string(value)))
			if parseErr != nil || pid <= 0 {
				return 0, fmt.Errorf("helper pid marker %s is invalid: %q", path, string(value))
			}
			return pid, nil
		}
		if !errors.Is(err, os.ErrNotExist) {
			return 0, fmt.Errorf("read helper pid marker %s: %w", path, err)
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return 0, fmt.Errorf("helper pid marker %s was not written before the %s deadline", path, helperReleaseDeadline)
		}
		timer := time.NewTimer(minDuration(remaining, 10*time.Millisecond))
		<-timer.C
		timer.Stop()
	}
}

func termIgnoringChildSpec(t *testing.T, root, childPIDFile, leaderPIDFile, childReleasePath, leaderReleasePath, childAckPath, leaderAckPath string) LaunchSpec {
	t.Helper()
	return LaunchSpec{
		Argv: []string{os.Args[0], "-test.run=TestCustodyTERMIgnoringChildHelper", "--"},
		CWD:  root,
		Env: map[string]string{
			"CUSTODY_HELPER_ROLE":               "leader",
			"CUSTODY_HELPER_PID_FILE":           childPIDFile,
			"CUSTODY_HELPER_LEADER_PID_FILE":    leaderPIDFile,
			"CUSTODY_HELPER_CHILD_RELEASE_PATH": childReleasePath,
			"CUSTODY_HELPER_RELEASE_PATH":       leaderReleasePath,
			"CUSTODY_HELPER_CHILD_ACK_PATH":     childAckPath,
			"CUSTODY_HELPER_ACK_PATH":           leaderAckPath,
		},
	}
}

func TestCancelKillsTERMIgnoringDescendantAfterLeaderExits(t *testing.T) {
	root := t.TempDir()
	custody, err := NewCustody(filepath.Join(root, "custody"))
	if err != nil {
		t.Fatal(err)
	}
	childPIDFile := filepath.Join(root, "child.pid")
	leaderPIDFile := filepath.Join(root, "leader.pid")
	childReleasePath := filepath.Join(root, "child.release")
	leaderReleasePath := filepath.Join(root, "leader.release")
	childAckPath := filepath.Join(root, "child.ack")
	leaderAckPath := filepath.Join(root, "leader.ack")
	row, err := custody.Launch("owner/tree", "cancel", termIgnoringChildSpec(t, root, childPIDFile, leaderPIDFile, childReleasePath, leaderReleasePath, childAckPath, leaderAckPath))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupHelper(t,
			helperMarkers{releasePath: childReleasePath, acknowledgementPath: childAckPath, pidPath: childPIDFile},
			helperMarkers{releasePath: leaderReleasePath, acknowledgementPath: leaderAckPath, pidPath: leaderPIDFile},
		)
	})
	_ = waitPIDFile(t, leaderPIDFile)
	childPID := waitPIDFile(t, childPIDFile)
	escalated := false
	custody.signalGroup = func(pid int, signal syscall.Signal) error {
		if signal == syscall.SIGKILL {
			escalated = true
			custody.mu.Lock()
			defer custody.mu.Unlock()
			internalRow := custody.byHandle[row.Handle]
			if internalRow.cmd.ProcessState != nil || internalRow.reapClaimed {
				t.Errorf("SIGKILL sent after leader identity was invalidated: process_state=%v reap_claimed=%v", internalRow.cmd.ProcessState, internalRow.reapClaimed)
			}
		}
		return signalProcessGroup(pid, signal)
	}
	if _, err := custody.Cancel("owner/tree", row.Handle); err != nil {
		t.Fatal(err)
	}
	if Alive(childPID) {
		t.Fatalf("TERM-ignoring descendant %d survived Cancel", childPID)
	}
	if !escalated {
		t.Fatal("TERM-ignoring descendant test did not exercise SIGKILL escalation")
	}
	terminal := waitTerminal(t, custody, row.Handle)
	if terminal.Cancellation == nil || terminal.Cancellation.Reason == "" {
		t.Fatalf("cancellation result = %#v", terminal)
	}
}

func TestShutdownKillsTERMIgnoringDescendantAfterLeaderExits(t *testing.T) {
	root := t.TempDir()
	custody, err := NewCustody(filepath.Join(root, "custody"))
	if err != nil {
		t.Fatal(err)
	}
	childPIDFile := filepath.Join(root, "child.pid")
	leaderPIDFile := filepath.Join(root, "leader.pid")
	childReleasePath := filepath.Join(root, "child.release")
	leaderReleasePath := filepath.Join(root, "leader.release")
	childAckPath := filepath.Join(root, "child.ack")
	leaderAckPath := filepath.Join(root, "leader.ack")
	row, err := custody.Launch("owner/tree", "shutdown", termIgnoringChildSpec(t, root, childPIDFile, leaderPIDFile, childReleasePath, leaderReleasePath, childAckPath, leaderAckPath))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupHelper(t,
			helperMarkers{releasePath: childReleasePath, acknowledgementPath: childAckPath, pidPath: childPIDFile},
			helperMarkers{releasePath: leaderReleasePath, acknowledgementPath: leaderAckPath, pidPath: leaderPIDFile},
		)
	})
	_ = waitPIDFile(t, leaderPIDFile)
	childPID := waitPIDFile(t, childPIDFile)
	if err := custody.Shutdown(); err != nil {
		t.Fatal(err)
	}
	if Alive(childPID) {
		t.Fatalf("TERM-ignoring descendant %d survived Shutdown", childPID)
	}
	terminal := waitTerminal(t, custody, row.Handle)
	if terminal.Cancellation == nil || terminal.Cancellation.Reason != "Mill shutdown" {
		t.Fatalf("shutdown result = %#v", terminal)
	}
}

func TestShutdownCancelsOwnedTrees(t *testing.T) {
	custody, err := NewCustody(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	row, err := custody.Launch("owner/shutdown", "run", testSpec(t, []string{"sleep", "30"}))
	if err != nil {
		t.Fatal(err)
	}
	if err := custody.Shutdown(); err != nil {
		t.Fatal(err)
	}
	terminal := waitTerminal(t, custody, row.Handle)
	if terminal.Cancellation == nil || terminal.Cancellation.Reason != "Mill shutdown" {
		t.Fatalf("shutdown result = %#v", terminal)
	}
	if _, err := custody.Launch("owner/shutdown", "another", testSpec(t, []string{"true"})); err == nil {
		t.Fatal("closed custody should reject new launches")
	}
}

func TestAcknowledgementCleansOutputFiles(t *testing.T) {
	custody, err := NewCustody(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	row, err := custody.Launch("owner/log", "run", testSpec(t, []string{"printf", "x"}))
	if err != nil {
		t.Fatal(err)
	}
	waitTerminal(t, custody, row.Handle)
	if _, err := os.Stat(row.Output.StdoutRef); err != nil {
		t.Fatal(err)
	}
	if err := custody.Acknowledge("owner/log", row.Handle); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(row.Output.StdoutRef); !os.IsNotExist(err) {
		t.Fatalf("stdout still exists after acknowledgement: %v", err)
	}
}

func TestAcknowledgementRetainsRecordWhenOutputCleanupFails(t *testing.T) {
	custody, err := NewCustody(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	row, err := custody.Launch("owner/log", "retry", testSpec(t, []string{"printf", "x"}))
	if err != nil {
		t.Fatal(err)
	}
	waitTerminal(t, custody, row.Handle)
	custody.removeAll = func(string) error { return errors.New("injected cleanup failure") }
	if err := custody.Acknowledge("owner/log", row.Handle); err == nil || !strings.Contains(err.Error(), "injected cleanup failure") {
		t.Fatalf("cleanup failure was not visible: %v", err)
	}
	if _, err := custody.Get(row.Handle); err != nil {
		t.Fatalf("terminal fact was deleted after cleanup failure: %v", err)
	}
	custody.removeAll = os.RemoveAll
	if err := custody.Acknowledge("owner/log", row.Handle); err != nil {
		t.Fatal(err)
	}
}
