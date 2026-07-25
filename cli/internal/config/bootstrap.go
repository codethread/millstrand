package config

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const (
	DefaultInitCLJ        = "(require '[skein.api.current.alpha :as current]\n         '[skein.api.runtime.alpha :as runtime])\n\n(def runtime (current/runtime))\n\n;; batteries is approved as a shipped source-root spool by default. The module\n;; guard keeps source loading behind that visible approval. The coordinator\n;; resolves the entry points from the namespace's public `spool` var, so this\n;; declaration carries only a source target and world policy: contribute\n;; publishes the CLI ops and reconcile seeds the glossary outcomes they\n;; reference.\n(runtime/module! runtime :skein/spools-batteries\n                 {:ns 'skein.spools.batteries\n                  :spools ['skein.spools/batteries]})\n"
	DefaultSkeinGitignore = "config.local.json\ninit.local.clj\nspools.local.edn\nstate/\ndata/\nweaver.*\n*.sqlite\n*.sqlite-*\n"
)

func BootstrapWorld(cwd, configDir, source string) (World, error) {
	return bootstrapWorld(cwd, configDir, source, true)
}

func bootstrapWorld(cwd, configDir, source string, injectGuidance bool) (World, error) {
	world, err := BootstrapTargetWorld(cwd, configDir)
	if err != nil {
		return World{}, err
	}
	if err := rejectLegacySpoolConfig(world.ConfigDir); err != nil {
		return World{}, err
	}
	if err := os.MkdirAll(filepath.Join(world.ConfigDir, "spools"), 0o755); err != nil {
		return World{}, err
	}
	if _, err := os.Stat(world.ConfigFile); os.IsNotExist(err) {
		data, err := json.Marshal(Config{ConfigFormat: "alpha"})
		if err != nil {
			return World{}, err
		}
		if err := os.WriteFile(world.ConfigFile, append(data, '\n'), 0o644); err != nil {
			return World{}, err
		}
	} else if err != nil {
		return World{}, err
	}
	if err := writeMissing(filepath.Join(world.ConfigDir, "spools.edn"), "{:spools {skein.spools/batteries {:skein/source-root \"spools/batteries\"}}}\n"); err != nil {
		return World{}, err
	}
	if err := writeMissing(filepath.Join(world.ConfigDir, "init.clj"), DefaultInitCLJ); err != nil {
		return World{}, err
	}
	if err := writeMissing(filepath.Join(world.ConfigDir, ".gitignore"), DefaultSkeinGitignore); err != nil {
		return World{}, err
	}
	if _, _, err := Load(world.ConfigDir); err != nil {
		return World{}, err
	}
	if configDir == "" && injectGuidance {
		if err := ensureAgentGuidance(filepath.Dir(world.ConfigDir)); err != nil {
			return World{}, err
		}
	}
	return world, nil
}

// agentGuidanceMarker guards idempotent injection: the section is appended only
// when the marker is absent, so repeated `mill init` runs never duplicate it.
// agentGuidanceEndMarker bounds the block so tooling can locate and replace it.
const (
	agentGuidanceMarker    = "<!-- mill:skein-prime -->"
	agentGuidanceEndMarker = "<!-- /mill:skein-prime -->"
)

const agentGuidanceSection = agentGuidanceMarker + `
## Skein / strand

This repo uses Skein strands to track work. Orientation ships in the ` + "`mill`" + ` CLI:

- ` + "`mill strand prime`" + ` — the day-to-day strand workflow; run it before multi-step work.
- ` + "`mill skein prime`" + ` — read on demand, only when building on this repo's ` + "`.skein/`" + ` config or spools.
` + agentGuidanceEndMarker + `
`

// ensureAgentGuidance appends the Skein/strand pointer section to the repo-root
// agent guidance file so a fresh agent is routed at the `mill *prime` commands.
// It is idempotent (marker-guarded), appends to whichever of AGENTS.md/CLAUDE.md
// exist, and creates AGENTS.md when neither does. CLAUDE.md commonly symlinks to
// AGENTS.md; sequential marker checks make the shared target safe.
func ensureAgentGuidance(repoRoot string) error {
	wrote := false
	for _, name := range []string{"AGENTS.md", "CLAUDE.md"} {
		path := filepath.Join(repoRoot, name)
		existing, err := os.ReadFile(path)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return err
		}
		wrote = true
		if strings.Contains(string(existing), agentGuidanceMarker) {
			continue
		}
		if err := appendAgentGuidance(path, existing); err != nil {
			return err
		}
	}
	if !wrote {
		doc := "# Agents\n\n" + agentGuidanceSection
		return os.WriteFile(filepath.Join(repoRoot, "AGENTS.md"), []byte(doc), 0o644)
	}
	return nil
}

func appendAgentGuidance(path string, existing []byte) error {
	out := existing
	if len(out) > 0 && !strings.HasSuffix(string(out), "\n") {
		out = append(out, '\n')
	}
	out = append(out, '\n')
	out = append(out, []byte(agentGuidanceSection)...)
	return os.WriteFile(path, out, 0o644)
}

func BootstrapTargetWorld(cwd, configDir string) (World, error) {
	if configDir != "" {
		if !filepath.IsAbs(configDir) {
			if cwd == "" {
				var err error
				cwd, err = os.Getwd()
				if err != nil {
					return World{}, err
				}
			}
			if realCWD, err := filepath.EvalSymlinks(cwd); err == nil {
				cwd = realCWD
			}
			configDir = filepath.Join(cwd, configDir)
		}
		return isolatedWorld(configDir)
	}
	root, err := GitRoot(cwd)
	if err != nil {
		return World{}, err
	}
	return isolatedWorld(filepath.Join(root, ".skein"))
}

func GitRoot(cwd string) (string, error) {
	if cwd == "" {
		var err error
		cwd, err = os.Getwd()
		if err != nil {
			return "", err
		}
	}
	cmd := exec.Command("git", "rev-parse", "--path-format=absolute", "--git-common-dir")
	cmd.Dir = cwd
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("default Skein workspace requires cwd inside a supported non-bare Git worktree; run `git init` or pass --workspace: %w", err)
	}
	commonDir := filepath.Clean(strings.TrimSpace(string(out)))
	if !filepath.IsAbs(commonDir) {
		return "", fmt.Errorf("git returned non-absolute common dir for default Skein workspace: %s", commonDir)
	}
	if filepath.Base(commonDir) != ".git" {
		return "", fmt.Errorf("unsupported Git layout for default Skein workspace: common Git dir must be a repository .git directory, got %s", commonDir)
	}
	return filepath.Dir(commonDir), nil
}

// DeriveGitContext resolves the worktree root and git common dir for the
// dispatcher envelope from an effective cwd. Unlike GitRoot (which locates the
// repo root that hosts .skein), this returns the actual worktree toplevel and
// common dir so linked worktrees report their own root while still sharing a
// workspace identity through the common dir (RFC-019.D2).
func DeriveGitContext(cwd string) (worktreeRoot, gitCommonDir string, err error) {
	if cwd == "" {
		cwd, err = os.Getwd()
		if err != nil {
			return "", "", err
		}
	}
	remediation := "requires cwd inside a supported non-bare Git worktree; run `git init`, pass --workspace, or pin --worktree-root/--git-common-dir"
	top, err := gitRevParse(cwd, "--show-toplevel")
	if err != nil {
		return "", "", fmt.Errorf("git context derivation %s: %w", remediation, err)
	}
	common, err := gitRevParse(cwd, "--path-format=absolute", "--git-common-dir")
	if err != nil {
		return "", "", fmt.Errorf("git context derivation %s: %w", remediation, err)
	}
	worktreeRoot = filepath.Clean(top)
	gitCommonDir = filepath.Clean(common)
	if !filepath.IsAbs(worktreeRoot) || !filepath.IsAbs(gitCommonDir) {
		return "", "", fmt.Errorf("git returned non-absolute paths for worktree context: root=%s common=%s", worktreeRoot, gitCommonDir)
	}
	return worktreeRoot, gitCommonDir, nil
}

func gitRevParse(cwd string, args ...string) (string, error) {
	cmd := exec.Command("git", append([]string{"rev-parse"}, args...)...)
	cmd.Dir = cwd
	out, err := cmd.Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

func rejectLegacySpoolConfig(configDir string) error {
	var legacy []string
	for _, name := range []string{"libs.edn", "libs.local.edn"} {
		path := filepath.Join(configDir, name)
		if _, err := os.Lstat(path); err == nil {
			legacy = append(legacy, path)
		} else if !os.IsNotExist(err) {
			return err
		}
	}
	if len(legacy) > 0 {
		return fmt.Errorf("legacy runtime library config files are no longer supported; rename libs.edn/libs.local.edn to spools.edn/spools.local.edn and change top-level :libs to :spools: %s", strings.Join(legacy, ", "))
	}
	return nil
}

func writeMissing(path, content string) error {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return os.WriteFile(path, []byte(content), 0o644)
	} else {
		return err
	}
}
