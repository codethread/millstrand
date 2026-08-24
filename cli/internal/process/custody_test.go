package process

import (
	"errors"
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
			os.Exit(2)
		}
		time.Sleep(24 * time.Hour)
	}
	if role != "leader" {
		os.Exit(2)
	}
	child := exec.Command(os.Args[0], "-test.run=TestCustodyTERMIgnoringChildHelper", "--")
	child.Env = append(os.Environ(), "CUSTODY_HELPER_ROLE=child")
	if err := child.Start(); err != nil {
		os.Exit(2)
	}
	if err := os.WriteFile(os.Getenv("CUSTODY_HELPER_LEADER_PID_FILE"), []byte(strconv.Itoa(os.Getpid())), 0o600); err != nil {
		os.Exit(2)
	}
	time.Sleep(24 * time.Hour)
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

func terminatePID(t *testing.T, pid int) {
	t.Helper()
	if pid > 0 && Alive(pid) {
		if err := syscall.Kill(pid, syscall.SIGKILL); err != nil && !errors.Is(err, syscall.ESRCH) {
			t.Errorf("PID-specific cleanup for %d failed: %v", pid, err)
		}
	}
}

func termIgnoringChildSpec(t *testing.T, root, childPIDFile, leaderPIDFile string) LaunchSpec {
	t.Helper()
	return LaunchSpec{
		Argv: []string{os.Args[0], "-test.run=TestCustodyTERMIgnoringChildHelper", "--"},
		CWD:  root,
		Env: map[string]string{
			"CUSTODY_HELPER_ROLE":            "leader",
			"CUSTODY_HELPER_PID_FILE":        childPIDFile,
			"CUSTODY_HELPER_LEADER_PID_FILE": leaderPIDFile,
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
	row, err := custody.Launch("owner/tree", "cancel", termIgnoringChildSpec(t, root, childPIDFile, leaderPIDFile))
	if err != nil {
		t.Fatal(err)
	}
	leaderPID := waitPIDFile(t, leaderPIDFile)
	childPID := waitPIDFile(t, childPIDFile)
	t.Cleanup(func() {
		terminatePID(t, childPID)
		terminatePID(t, leaderPID)
	})
	if _, err := custody.Cancel("owner/tree", row.Handle); err != nil {
		t.Fatal(err)
	}
	if Alive(childPID) {
		t.Fatalf("TERM-ignoring descendant %d survived Cancel", childPID)
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
	row, err := custody.Launch("owner/tree", "shutdown", termIgnoringChildSpec(t, root, childPIDFile, leaderPIDFile))
	if err != nil {
		t.Fatal(err)
	}
	leaderPID := waitPIDFile(t, leaderPIDFile)
	childPID := waitPIDFile(t, childPIDFile)
	t.Cleanup(func() {
		terminatePID(t, childPID)
		terminatePID(t, leaderPID)
	})
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
