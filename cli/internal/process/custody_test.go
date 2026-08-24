package process

import (
	"os"
	"path/filepath"
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

func TestShutdownCancelsOwnedTrees(t *testing.T) {
	custody, err := NewCustody(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	row, err := custody.Launch("owner/shutdown", "run", testSpec(t, []string{"sleep", "30"}))
	if err != nil {
		t.Fatal(err)
	}
	custody.Shutdown()
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
