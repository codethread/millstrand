package jvmpool

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

func testMember(t *testing.T, name, pool string) Member {
	t.Helper()
	root := filepath.Join(t.TempDir(), name)
	return Member{ConfigDir: filepath.Join(root, ".millstrand"), SourceCWD: root, JVMPool: pool}
}

func TestRegistryReopenDurabilityAndCanonicalOrdering(t *testing.T) {
	state := t.TempDir()
	registry, err := Open(state)
	if err != nil {
		t.Fatal(err)
	}
	b := testMember(t, "b", "backend")
	a := testMember(t, "a", "backend")
	if _, err := registry.Reconcile(b, nil); err != nil {
		t.Fatal(err)
	}
	first, err := registry.Reconcile(a, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !first.Changed || len(first.Members) != 2 || first.Members[0].ConfigDir >= first.Members[1].ConfigDir {
		t.Fatalf("unexpected canonical mutation: %#v", first)
	}

	reopened, err := Open(state)
	if err != nil {
		t.Fatal(err)
	}
	doc, err := reopened.Read()
	if err != nil {
		t.Fatal(err)
	}
	expected := []Member{a, b}
	sort.Slice(expected, func(i, j int) bool { return expected[i].ConfigDir < expected[j].ConfigDir })
	if doc.Revision != first.Revision || len(doc.Members) != 2 || doc.Members[0] != expected[0] || doc.Members[1] != expected[1] {
		t.Fatalf("reopened document = %#v, want revision %q and canonical rows", doc, first.Revision)
	}
	snapshot, err := reopened.Snapshot("backend")
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Revision != first.Revision || len(snapshot.Members) != 2 {
		t.Fatalf("snapshot = %#v", snapshot)
	}
}

func TestReconcileNoChangeLeavesBytesAndRevisionUnchanged(t *testing.T) {
	registry, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	member := testMember(t, "one", "exact-name")
	created, err := registry.Reconcile(member, nil)
	if err != nil {
		t.Fatal(err)
	}
	path := registry.Path()
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	unchanged, err := registry.Reconcile(member, nil)
	if err != nil {
		t.Fatal(err)
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if unchanged.Changed || unchanged.Revision != created.Revision || string(before) != string(after) {
		t.Fatalf("no-change mutation = %#v, bytes changed=%t", unchanged, string(before) != string(after))
	}
}

func TestMoveAndRemovalAreSingleReplacements(t *testing.T) {
	registry, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	member := testMember(t, "workspace", "one")
	if _, err := registry.Reconcile(member, nil); err != nil {
		t.Fatal(err)
	}
	moved := member
	moved.JVMPool = "two"
	movedResult, err := registry.Reconcile(moved, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !movedResult.Changed || len(movedResult.Members) != 1 || movedResult.Members[0].JVMPool != "two" {
		t.Fatalf("move result = %#v", movedResult)
	}
	removed, err := registry.Remove(member.ConfigDir, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !removed.Changed || len(removed.Members) != 0 {
		t.Fatalf("removal result = %#v", removed)
	}
	doc, err := registry.Read()
	if err != nil {
		t.Fatal(err)
	}
	if len(doc.Members) != 0 || doc.Revision != removed.Revision {
		t.Fatalf("after removal = %#v", doc)
	}
}

func TestLiveMoveAndRemovalRefuseWithoutChangingBytes(t *testing.T) {
	registry, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	member := testMember(t, "workspace", "one")
	if _, err := registry.Reconcile(member, nil); err != nil {
		t.Fatal(err)
	}
	path := registry.Path()
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	live := LiveOwnership{member.ConfigDir: {Pool: "one", HostID: "host-1"}}
	moved := member
	moved.JVMPool = "two"
	_, err = registry.Reconcile(moved, live)
	assertStopRequired(t, err, member.ConfigDir)
	_, err = registry.Remove(member.ConfigDir, live)
	assertStopRequired(t, err, member.ConfigDir)
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(before) != string(after) {
		t.Fatal("live move/removal changed registry bytes")
	}
}

func TestNewcomerMayJoinLiveHostWhenPoolMatches(t *testing.T) {
	registry, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	first := testMember(t, "first", "backend")
	second := testMember(t, "second", "backend")
	if _, err := registry.Reconcile(first, nil); err != nil {
		t.Fatal(err)
	}
	result, err := registry.Reconcile(second, LiveOwnership{first.ConfigDir: {Pool: "backend", HostID: "host-1"}})
	if err != nil || !result.Changed {
		t.Fatalf("same-pool newcomer result=%#v err=%v", result, err)
	}
}

func TestReadRejectsMalformedDuplicateAndUnsortedRecords(t *testing.T) {
	root := t.TempDir()
	registry, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	member := testMember(t, "same", "backend")
	if err := os.MkdirAll(filepath.Dir(registry.Path()), 0o755); err != nil {
		t.Fatal(err)
	}
	row := `{"config_dir":"` + member.ConfigDir + `","source_cwd":"` + member.SourceCWD + `","jvm_pool":"backend"}`
	cases := map[string]string{
		"malformed":     `{`,
		"duplicate":     `{"format":"` + MembershipFormat + `","revision":"membership-r","members":[` + row + `,` + row + `]}`,
		"unsorted":      `{"format":"` + MembershipFormat + `","revision":"membership-r","members":[` + row + `]}`, // replaced below
		"unknown field": `{"format":"` + MembershipFormat + `","revision":"membership-r","members":[],"extra":true}`,
	}
	other := testMember(t, "other", "backend")
	row2 := `{"config_dir":"` + other.ConfigDir + `","source_cwd":"` + other.SourceCWD + `","jvm_pool":"backend"}`
	cases["unsorted"] = `{"format":"` + MembershipFormat + `","revision":"membership-r","members":[` + row2 + `,` + row + `]}`
	for name, data := range cases {
		t.Run(name, func(t *testing.T) {
			if err := os.WriteFile(registry.Path(), []byte(data), 0o644); err != nil {
				t.Fatal(err)
			}
			if _, err := registry.Read(); err == nil {
				t.Fatal("invalid membership unexpectedly loaded")
			}
		})
	}
}

func TestValidatePoolReloadsAllSelectedMembersAndRejectsMismatch(t *testing.T) {
	registry, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	a := testMember(t, "a", "backend")
	b := testMember(t, "b", "backend")
	if _, err := registry.Reconcile(a, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := registry.Reconcile(b, nil); err != nil {
		t.Fatal(err)
	}
	calls := make([]string, 0, 2)
	valid := "backend"
	snapshot, err := registry.ValidatePool("backend", func(configDir string) (EffectiveConfig, error) {
		calls = append(calls, configDir)
		return EffectiveConfig{ConfigDir: configDir, JVMPool: &valid}, nil
	})
	if err != nil || len(snapshot.Members) != 2 || len(calls) != 2 {
		t.Fatalf("valid snapshot=%#v calls=%v err=%v", snapshot, calls, err)
	}

	calls = nil
	_, err = registry.ValidatePool("backend", func(configDir string) (EffectiveConfig, error) {
		calls = append(calls, configDir)
		if configDir == a.ConfigDir {
			return EffectiveConfig{}, errors.New("config is unreadable")
		}
		wrong := "other"
		return EffectiveConfig{ConfigDir: configDir, JVMPool: &wrong}, nil
	})
	if err == nil || len(calls) != 2 || !strings.Contains(err.Error(), a.ConfigDir) || !strings.Contains(err.Error(), b.ConfigDir) {
		t.Fatalf("validation error=%v calls=%v", err, calls)
	}
}

func TestMembershipIgnoresAutomaticStartData(t *testing.T) {
	state := t.TempDir()
	registry, err := Open(state)
	if err != nil {
		t.Fatal(err)
	}
	a := testMember(t, "a", "backend")
	if _, err := registry.Reconcile(a, nil); err != nil {
		t.Fatal(err)
	}
	autostart := filepath.Join(state, "autostart.json")
	if err := os.WriteFile(autostart, []byte(`{"enabled":true}`), 0o644); err != nil {
		t.Fatal(err)
	}
	snapshot, err := registry.Snapshot("backend")
	if err != nil || len(snapshot.Members) != 1 {
		t.Fatalf("snapshot=%#v err=%v", snapshot, err)
	}
	contents, err := os.ReadFile(autostart)
	if err != nil || string(contents) != `{"enabled":true}` {
		t.Fatalf("automatic-start data changed: %q err=%v", contents, err)
	}
}

func TestArtifactKeyUsesExactPoolName(t *testing.T) {
	pool := " backend/blue "
	input := append([]byte("millstrand-jvm-pool"), 0)
	input = append(input, []byte(pool)...)
	sum := sha256.Sum256(input)
	want := hex.EncodeToString(sum[:])[:32]
	if got := ArtifactKey(pool); got != want {
		t.Fatalf("artifact key=%q want=%q", got, want)
	}
	if ArtifactKey("backend") == ArtifactKey("Backend") {
		t.Fatal("artifact key unexpectedly case-folded pool name")
	}
	if err := ValidateArtifactKey(pool, want); err != nil {
		t.Fatal(err)
	}
	if err := ValidateArtifactKey(pool, ArtifactKey("other")); err == nil {
		t.Fatal("mismatched artifact key accepted")
	}
}

func assertStopRequired(t *testing.T, err error, configDir string) {
	t.Helper()
	var refusal *StopRequiredError
	if err == nil || !errors.Is(err, ErrStopRequired) || !errors.As(err, &refusal) || refusal.ConfigDir != configDir || !strings.Contains(err.Error(), "mill/jvm-pool-stop-required") {
		t.Fatalf("expected stop-required for %s, got %v", configDir, err)
	}
}
