package config

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadRequiresConfigFile(t *testing.T) {
	d := t.TempDir()
	_, _, err := Load(d)
	if err == nil || !strings.Contains(err.Error(), "client config") || !strings.Contains(err.Error(), "is required") {
		t.Fatalf("expected missing config error, got %v", err)
	}
}

func TestLoadMalformedJSON(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "malformed client config") {
		t.Fatalf("expected malformed error, got %v", err)
	}
}

func TestLoadRequiresConfigFormat(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "configFormat is required") {
		t.Fatalf("expected configFormat required error, got %v", err)
	}
}

func TestLoadIgnoresUnknownKeysAndRetainsWarnings(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha","where":"x","another":true}`), 0o644); err != nil {
		t.Fatal(err)
	}
	c, _, err := Load(d)
	if err != nil {
		t.Fatalf("unknown keys should be ignored, got %v", err)
	}
	if len(c.Warnings) != 1 || len(c.Warnings[0].Keys) != 2 || c.Warnings[0].Keys[0] != "another" || c.Warnings[0].Keys[1] != "where" {
		t.Fatalf("unexpected unknown-key warnings: %#v", c.Warnings)
	}

	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha","source":"/tmp/source"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err != nil {
		t.Fatalf("unknown source key should be ignored, got %v", err)
	}

	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"old"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "unsupported client config configFormat") {
		t.Fatalf("expected configFormat value error, got %v", err)
	}

	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":123}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "client config configFormat must be a string") {
		t.Fatalf("expected configFormat type error, got %v", err)
	}
}

func TestLoadAcceptsValidAlphaConfig(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	c, world, err := Load(d)
	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}
	if c.ConfigFormat != "alpha" {
		t.Fatalf("unexpected config: %#v", c)
	}
	tDir, err := filepath.EvalSymlinks(d)
	if err != nil {
		t.Fatal(err)
	}
	if world.ConfigDir != tDir {
		t.Fatalf("unexpected world config dir: %#v", world)
	}
}

func TestLoadAcceptsJVMPoolFromBaseConfig(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha","name":"shop-fe","autoStart":true,"JVMPool":" backend "}`), 0o644); err != nil {
		t.Fatal(err)
	}

	c, _, err := Load(d)
	if err != nil {
		t.Fatalf("expected JVMPool to load, got %v", err)
	}
	if c.JVMPool == nil || *c.JVMPool != " backend " {
		t.Fatalf("unexpected JVMPool: %#v", c.JVMPool)
	}
	if c.ConfigFormat != "alpha" || c.Name != "shop-fe" || !c.AutoStart {
		t.Fatalf("existing config fields changed: %#v", c)
	}
}

func TestLoadAcceptsJVMPoolFromLocalOverlay(t *testing.T) {
	d := t.TempDir()
	base := []byte(`{"configFormat":"alpha","JVMPool":"base"}`)
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), base, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(d, LocalConfigFileName), []byte(`{"JVMPool":"local"}`), 0o644); err != nil {
		t.Fatal(err)
	}

	c, _, err := Load(d)
	if err != nil {
		t.Fatalf("expected local JVMPool overlay to load, got %v", err)
	}
	if c.JVMPool == nil || *c.JVMPool != "local" {
		t.Fatalf("local JVMPool did not win: %#v", c.JVMPool)
	}
	if err := os.WriteFile(filepath.Join(d, LocalConfigFileName), []byte(`{"JVMPool":null}`), 0o644); err != nil {
		t.Fatal(err)
	}
	c, _, err = Load(d)
	if err != nil {
		t.Fatalf("expected local null JVMPool opt-out to load, got %v", err)
	}
	if c.JVMPool != nil {
		t.Fatalf("local null did not clear base JVMPool: %#v", c.JVMPool)
	}
}

func TestLoadRejectsInvalidJVMPool(t *testing.T) {
	for _, tc := range []struct {
		name  string
		value string
	}{
		{name: "number", value: `123`},
		{name: "boolean", value: `false`},
		{name: "blank", value: `""`},
		{name: "whitespace", value: `" \t "`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			d := t.TempDir()
			data := []byte(`{"configFormat":"alpha","JVMPool":` + tc.value + `}`)
			if err := os.WriteFile(filepath.Join(d, ConfigFileName), data, 0o644); err != nil {
				t.Fatal(err)
			}
			if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "client config JVMPool must be a non-blank string or null") {
				t.Fatalf("expected JVMPool validation error, got %v", err)
			}
		})
	}
}

func TestLoadRetainsUnknownConfigWarningsWithJVMPool(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha","JVMPool":"backend","future":true}`), 0o644); err != nil {
		t.Fatal(err)
	}

	c, _, err := Load(d)
	if err != nil {
		t.Fatalf("expected config to load, got %v", err)
	}
	if c.JVMPool == nil || *c.JVMPool != "backend" {
		t.Fatalf("unexpected JVMPool: %#v", c.JVMPool)
	}
	if len(c.Warnings) != 1 || len(c.Warnings[0].Keys) != 1 || c.Warnings[0].Keys[0] != "future" {
		t.Fatalf("unexpected unknown-key warnings: %#v", c.Warnings)
	}
}

func TestSetLocalJVMPoolPreservesLocalKeysAndBaseBytes(t *testing.T) {
	d := t.TempDir()
	base := []byte("{\n  \"configFormat\": \"alpha\",\n  \"JVMPool\": \"base\"\n}\n")
	local := []byte("{\"name\":\"local\",\"future\":{\"keep\":true}}\n")
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), base, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(d, LocalConfigFileName), local, 0o644); err != nil {
		t.Fatal(err)
	}

	if err := SetLocalJVMPool(d, " backend "); err != nil {
		t.Fatalf("expected local JVMPool write to succeed, got %v", err)
	}
	if got, err := os.ReadFile(filepath.Join(d, ConfigFileName)); err != nil {
		t.Fatal(err)
	} else if string(got) != string(base) {
		t.Fatalf("base config changed: got %q want %q", got, base)
	}
	var persisted map[string]json.RawMessage
	b, err := os.ReadFile(filepath.Join(d, LocalConfigFileName))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, &persisted); err != nil {
		t.Fatal(err)
	}
	var future map[string]bool
	if err := json.Unmarshal(persisted["future"], &future); err != nil {
		t.Fatal(err)
	}
	if string(persisted["JVMPool"]) != `" backend "` || string(persisted["name"]) != `"local"` || !future["keep"] {
		t.Fatalf("local keys were not preserved: %s", b)
	}
}

func TestSetLocalJVMPoolRejectsInvalidInputWithoutReplacement(t *testing.T) {
	d := t.TempDir()
	path := filepath.Join(d, LocalConfigFileName)
	original := []byte(`{"name":"keep"}`)
	if err := os.WriteFile(path, original, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := SetLocalJVMPool(d, " \t "); err == nil || !strings.Contains(err.Error(), "non-blank string") {
		t.Fatalf("expected blank JVMPool rejection, got %v", err)
	}
	if got, err := os.ReadFile(path); err != nil {
		t.Fatal(err)
	} else if string(got) != string(original) {
		t.Fatalf("blank input replaced local config: got %q want %q", got, original)
	}

	malformed := []byte(`{"name":`)
	if err := os.WriteFile(path, malformed, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := SetLocalJVMPool(d, "backend"); err == nil || !strings.Contains(err.Error(), "malformed local client config") {
		t.Fatalf("expected malformed local config rejection, got %v", err)
	}
	if got, err := os.ReadFile(path); err != nil {
		t.Fatal(err)
	} else if string(got) != string(malformed) {
		t.Fatalf("malformed local config was replaced: got %q want %q", got, malformed)
	}
}

func TestLoadAcceptsConfigName(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha","name":"shop-fe"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	c, _, err := Load(d)
	if err != nil {
		t.Fatalf("expected name to load, got %v", err)
	}
	if c.Name != "shop-fe" {
		t.Fatalf("unexpected name: %#v", c)
	}
}

func TestLoadRejectsNullAutoStart(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha","autoStart":null}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "client config autoStart must be a boolean") {
		t.Fatalf("expected autoStart boolean validation error, got %v", err)
	}
}

func TestLoadRejectsNonBooleanAutoStart(t *testing.T) {
	for _, value := range []string{`"yes"`, `1`} {
		t.Run(value, func(t *testing.T) {
			d := t.TempDir()
			data := []byte(`{"configFormat":"alpha","autoStart":` + value + `}`)
			if err := os.WriteFile(filepath.Join(d, ConfigFileName), data, 0o644); err != nil {
				t.Fatal(err)
			}
			if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "client config autoStart must be a boolean") {
				t.Fatalf("expected autoStart boolean validation error, got %v", err)
			}
		})
	}
}

func TestLoadAppliesLocalNameOverlay(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha","name":"shared"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(d, LocalConfigFileName), []byte(`{"name":"local"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	c, _, err := Load(d)
	if err != nil {
		t.Fatalf("expected overlay to load, got %v", err)
	}
	if c.Name != "local" {
		t.Fatalf("overlay did not win: %#v", c)
	}
}

func TestLoadRejectsInvalidConfigNames(t *testing.T) {
	cases := []struct {
		name string
		json string
	}{
		{"blank", `{"configFormat":"alpha","name":" \t"}`},
		{"non-string", `{"configFormat":"alpha","name":123}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d := t.TempDir()
			if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(tc.json), 0o644); err != nil {
				t.Fatal(err)
			}
			if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "client config name must be a non-blank string") {
				t.Fatalf("expected name validation error, got %v", err)
			}
		})
	}
}

func TestLoadRejectsInvalidLocalOverlay(t *testing.T) {
	cases := []struct {
		name string
		json string
		want string
		key  string
	}{
		{"config-format", `{"configFormat":"alpha"}`, "local client config must not declare configFormat", ""},
		{"unknown-key", `{"where":"x"}`, "", "where"},
		{"unknown-auto-start", `{"autoStart":true}`, "", "autoStart"},
		{"blank-name", `{"name":""}`, "local client config name must be a non-blank string", ""},
		{"non-string-name", `{"name":false}`, "local client config name must be a non-blank string", ""},
		{"blank-jvm-pool", `{"JVMPool":""}`, "local client config JVMPool must be a non-blank string or null", ""},
		{"non-string-jvm-pool", `{"JVMPool":false}`, "local client config JVMPool must be a non-blank string or null", ""},
		{"malformed", `{"name":`, "malformed local client config", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d := t.TempDir()
			if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(d, LocalConfigFileName), []byte(tc.json), 0o644); err != nil {
				t.Fatal(err)
			}
			c, _, err := Load(d)
			if tc.want != "" {
				if err == nil || !strings.Contains(err.Error(), tc.want) {
					t.Fatalf("expected %q error, got %v", tc.want, err)
				}
				return
			}
			if err != nil {
				t.Fatalf("unknown local key should be ignored, got %v", err)
			}
			if len(c.Warnings) != 1 || filepath.Base(c.Warnings[0].File) != LocalConfigFileName {
				t.Fatalf("expected local unknown-key warning, got %#v", c.Warnings)
			}
			if len(c.Warnings[0].Keys) != 1 || c.Warnings[0].Keys[0] != tc.key {
				t.Fatalf("unexpected local warning: %#v", c.Warnings[0])
			}
		})
	}
}

func TestResolveSourceSupportsLeadingHomeExpansion(t *testing.T) {
	home := t.TempDir()
	homeSource := filepath.Join(home, "millstrand")
	if err := os.MkdirAll(homeSource, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(home, "deps.edn"), []byte(`{}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(homeSource, "deps.edn"), []byte(`{}`), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", home)

	resolved, err := ResolveSource("~")
	if err != nil {
		t.Fatalf("expected leading ~ to resolve, got %v", err)
	}
	if resolved != home {
		t.Fatalf("unexpected resolved source: %q", resolved)
	}

	resolved, err = ResolveSource("~/millstrand")
	if err != nil {
		t.Fatalf("expected leading ~/ to resolve, got %v", err)
	}
	if resolved != homeSource {
		t.Fatalf("unexpected resolved source: %q", resolved)
	}
}

func TestResolveSourceRejectsRelativePath(t *testing.T) {
	if _, err := ResolveSource("relative"); err == nil || !strings.Contains(err.Error(), "source must be an absolute path") {
		t.Fatalf("expected absolute path error, got %v", err)
	}
}

func TestBootstrapTargetWorldResolvesRelativeConfigDirAgainstCallerCWD(t *testing.T) {
	cwd := t.TempDir()
	world, err := BootstrapTargetWorld(cwd, "custom-workspace")
	if err != nil {
		t.Fatal(err)
	}
	want, err := filepath.EvalSymlinks(cwd)
	if err != nil {
		want = filepath.Clean(cwd)
	}
	want = filepath.Join(want, "custom-workspace")
	if world.ConfigDir != want {
		t.Fatalf("relative config dir resolved against wrong cwd: got %q want %q", world.ConfigDir, want)
	}
}

func TestDefaultRepoWorldCanonicalAcrossLinkedWorktrees(t *testing.T) {
	repo := t.TempDir()
	runGit(t, repo, "init")
	runGit(t, repo, "config", "user.email", "test@example.invalid")
	runGit(t, repo, "config", "user.name", "Test User")
	if err := os.WriteFile(filepath.Join(repo, "README.md"), []byte("test\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	runGit(t, repo, "add", "README.md")
	runGit(t, repo, "commit", "-m", "init")

	linked := filepath.Join(t.TempDir(), "linked")
	runGit(t, repo, "worktree", "add", linked)

	mainWorld, err := BootstrapTargetWorld(repo, "")
	if err != nil {
		t.Fatal(err)
	}
	linkedWorld, err := BootstrapTargetWorld(linked, "")
	if err != nil {
		t.Fatal(err)
	}
	realRepo, err := filepath.EvalSymlinks(repo)
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(realRepo, ".millstrand")
	if mainWorld.ConfigDir != want || linkedWorld.ConfigDir != want {
		t.Fatalf("default worlds did not use canonical repo .millstrand: main=%q linked=%q want=%q", mainWorld.ConfigDir, linkedWorld.ConfigDir, want)
	}
	if mainWorld.StateDir != linkedWorld.StateDir || mainWorld.DataDir != linkedWorld.DataDir || mainWorld.DBPath != linkedWorld.DBPath {
		t.Fatalf("linked worktree did not share runtime identity: main=%#v linked=%#v", mainWorld, linkedWorld)
	}
}

func TestRepoWorkspaceAcceptsMsAndKeepsRuntimeIdentityAcrossRename(t *testing.T) {
	repo := t.TempDir()
	runGit(t, repo, "init")
	if err := os.Mkdir(filepath.Join(repo, ".ms"), 0o755); err != nil {
		t.Fatal(err)
	}
	alias, err := BootstrapTargetWorld(repo, "")
	if err != nil {
		t.Fatal(err)
	}
	realRepo, err := filepath.EvalSymlinks(repo)
	if err != nil {
		t.Fatal(err)
	}
	if alias.ConfigDir != filepath.Join(realRepo, ".ms") {
		t.Fatalf("selected workspace = %q, want .ms", alias.ConfigDir)
	}
	if err := os.Rename(filepath.Join(repo, ".ms"), filepath.Join(repo, ".millstrand")); err != nil {
		t.Fatal(err)
	}
	full, err := BootstrapTargetWorld(repo, "")
	if err != nil {
		t.Fatal(err)
	}
	if alias.StateDir != full.StateDir || alias.DataDir != full.DataDir || alias.DBPath != full.DBPath {
		t.Fatalf("marker rename changed runtime identity: alias=%#v full=%#v", alias, full)
	}
}

func TestRepoWorkspaceRejectsConflictingLegacyAndInvalidMarkers(t *testing.T) {
	cases := []struct {
		name  string
		setup func(string) error
		want  string
	}{
		{"both accepted markers", func(repo string) error {
			if err := os.Mkdir(filepath.Join(repo, ".millstrand"), 0o755); err != nil {
				return err
			}
			return os.Mkdir(filepath.Join(repo, ".ms"), 0o755)
		}, "conflicting Millstrand workspaces"},
		{"legacy marker", func(repo string) error {
			return os.Mkdir(filepath.Join(repo, ".skein"), 0o755)
		}, "legacy Millstrand workspace marker"},
		{"file marker", func(repo string) error {
			return os.WriteFile(filepath.Join(repo, ".millstrand"), []byte("not a directory"), 0o644)
		}, "it must be a directory"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			repo := t.TempDir()
			runGit(t, repo, "init")
			if err := tc.setup(repo); err != nil {
				t.Fatal(err)
			}
			if _, err := BootstrapTargetWorld(repo, ""); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("expected %q error, got %v", tc.want, err)
			}
		})
	}
}

func TestDefaultRepoWorldRejectsNoGit(t *testing.T) {
	_, err := BootstrapTargetWorld(t.TempDir(), "")
	if err == nil || !strings.Contains(err.Error(), "requires cwd inside a supported non-bare Git worktree") {
		t.Fatalf("expected no-Git default world error, got %v", err)
	}
}

func runGit(t *testing.T, dir string, args ...string) {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v failed: %v\n%s", args, err, out)
	}
}
