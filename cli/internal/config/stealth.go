package config

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const (
	stealthExcludeStart = "# mill:millstrand-stealth"
	stealthExcludeEnd   = "# /mill:millstrand-stealth"
	stealthExcludeBlock = stealthExcludeStart + "\n/.millstrand\n/.ms\n/CLAUDE.local.md\n" + stealthExcludeEnd + "\n"

	StealthStatusCreated        = "created"
	StealthStatusUpdated        = "updated"
	StealthStatusUnchanged      = "unchanged"
	StealthStatusSkippedTracked = "skipped-tracked"

	StealthTargetTrackedMillstrand = "tracked-millstrand"
	StealthTargetGitExclude        = "git-exclude"
	StealthTargetClaudeGuidance    = "claude-guidance"
	StealthStateTracked            = "tracked"
	StealthStateStartOnly          = "start-only"
	StealthStateEndOnly            = "end-only"
	StealthStateDuplicateStart     = "duplicate-start"
	StealthStateDuplicateEnd       = "duplicate-end"
	StealthStateReversed           = "reversed"
	StealthStateEdited             = "edited"
	StealthCodexManualRequired     = "manual-required"
)

const stealthCodexSuggestedText = "This repository uses a local, gitignored .millstrand workspace. Run `mill millstrand prime` and `mill strand prime` before working."

type StealthFileAction struct {
	Path   string `json:"path"`
	Status string `json:"status"`
}

type StealthCodexGuidance struct {
	Status        string `json:"status"`
	SuggestedText string `json:"suggested_text"`
}

type StealthReport struct {
	GitExclude     StealthFileAction    `json:"git_exclude"`
	ClaudeGuidance StealthFileAction    `json:"claude_guidance"`
	CodexGuidance  StealthCodexGuidance `json:"codex_guidance"`
}

type StealthInitResult struct {
	ConfigDir  string        `json:"config_dir"`
	ConfigFile string        `json:"config_file"`
	Stealth    StealthReport `json:"stealth"`
}

func (r StealthInitResult) Validate() error {
	if strings.TrimSpace(r.ConfigDir) == "" || strings.TrimSpace(r.ConfigFile) == "" {
		return fmt.Errorf("invalid stealth init paths: %#v", r)
	}
	return r.Stealth.Validate()
}

func (r StealthReport) Validate() error {
	if strings.TrimSpace(r.GitExclude.Path) == "" || !oneOf(r.GitExclude.Status, StealthStatusCreated, StealthStatusUpdated, StealthStatusUnchanged) {
		return fmt.Errorf("invalid stealth git_exclude report: %#v", r.GitExclude)
	}
	if strings.TrimSpace(r.ClaudeGuidance.Path) == "" || !oneOf(r.ClaudeGuidance.Status, StealthStatusCreated, StealthStatusUpdated, StealthStatusUnchanged, StealthStatusSkippedTracked) {
		return fmt.Errorf("invalid stealth claude_guidance report: %#v", r.ClaudeGuidance)
	}
	if r.CodexGuidance.Status != StealthCodexManualRequired || strings.TrimSpace(r.CodexGuidance.SuggestedText) == "" {
		return fmt.Errorf("invalid stealth codex_guidance report: %#v", r.CodexGuidance)
	}
	return nil
}

type StealthRefusal struct {
	Path        string
	Target      string
	State       string
	Remediation string
}

type StealthRefusalDetails struct {
	Path        string
	Target      string
	State       string
	Remediation string
}

func (d StealthRefusalDetails) Validate() error {
	if strings.TrimSpace(d.Path) == "" || strings.TrimSpace(d.Remediation) == "" {
		return fmt.Errorf("invalid stealth refusal details: %#v", d)
	}
	if !oneOf(d.Target, StealthTargetTrackedMillstrand, StealthTargetGitExclude, StealthTargetClaudeGuidance) {
		return fmt.Errorf("invalid stealth refusal target: %q", d.Target)
	}
	if !oneOf(d.State, StealthStateTracked, StealthStateStartOnly, StealthStateEndOnly, StealthStateDuplicateStart, StealthStateDuplicateEnd, StealthStateReversed, StealthStateEdited) {
		return fmt.Errorf("invalid stealth refusal state: %q", d.State)
	}
	return nil
}

func (e *StealthRefusal) Error() string {
	return fmt.Sprintf("stealth init refused %s at %s (state %s): %s", e.Target, e.Path, e.State, e.Remediation)
}

func (e *StealthRefusal) Details() (map[string]any, error) {
	details := StealthRefusalDetails{Path: e.Path, Target: e.Target, State: e.State, Remediation: e.Remediation}
	if err := details.Validate(); err != nil {
		return nil, err
	}
	return map[string]any{"path": details.Path, "target": details.Target, "state": details.State, "remediation": details.Remediation}, nil
}

type markerSpec struct {
	target string
	start  string
	end    string
	block  string
}

type markedFilePlan struct {
	path    string
	status  string
	content []byte
}

type stealthPlan struct {
	exclude markedFilePlan
	claude  markedFilePlan
}

func BootstrapStealthWorld(cwd string) (World, StealthReport, error) {
	repoRoot, err := GitRoot(cwd)
	if err != nil {
		return World{}, StealthReport{}, err
	}
	plan, err := preflightStealth(repoRoot)
	if err != nil {
		return World{}, StealthReport{}, err
	}
	world, err := bootstrapWorld(cwd, "", "", false)
	if err != nil {
		return World{}, StealthReport{}, err
	}
	if err := applyMarkedFilePlan(plan.exclude); err != nil {
		return World{}, StealthReport{}, err
	}
	if err := applyMarkedFilePlan(plan.claude); err != nil {
		return World{}, StealthReport{}, err
	}
	report := StealthReport{
		GitExclude:     StealthFileAction{Path: plan.exclude.path, Status: plan.exclude.status},
		ClaudeGuidance: StealthFileAction{Path: plan.claude.path, Status: plan.claude.status},
		CodexGuidance:  StealthCodexGuidance{Status: StealthCodexManualRequired, SuggestedText: stealthCodexSuggestedText},
	}
	if err := report.Validate(); err != nil {
		return World{}, StealthReport{}, err
	}
	return world, report, nil
}

func preflightStealth(repoRoot string) (stealthPlan, error) {
	trackedPath, err := trackedWorkspacePath(repoRoot)
	if err != nil {
		return stealthPlan{}, err
	}
	if trackedPath != "" {
		return stealthPlan{}, refuse(trackedPath, StealthTargetTrackedMillstrand, StealthStateTracked, "remove .millstrand or .ms from the Git index or run mill init without --stealth")
	}
	excludePath, err := gitPrivateExcludePath(repoRoot)
	if err != nil {
		return stealthPlan{}, err
	}
	exclude, err := preflightMarkedFile(excludePath, markerSpec{target: StealthTargetGitExclude, start: stealthExcludeStart, end: stealthExcludeEnd, block: stealthExcludeBlock})
	if err != nil {
		return stealthPlan{}, err
	}
	claudePath := filepath.Join(repoRoot, "CLAUDE.local.md")
	tracked, err := gitTracked(repoRoot, "CLAUDE.local.md")
	if err != nil {
		return stealthPlan{}, err
	}
	if tracked {
		return stealthPlan{exclude: exclude, claude: markedFilePlan{path: claudePath, status: StealthStatusSkippedTracked}}, nil
	}
	claude, err := preflightMarkedFile(claudePath, markerSpec{target: StealthTargetClaudeGuidance, start: agentGuidanceMarker, end: agentGuidanceEndMarker, block: agentGuidanceSection})
	if err != nil {
		return stealthPlan{}, err
	}
	return stealthPlan{exclude: exclude, claude: claude}, nil
}

func trackedWorkspacePath(repoRoot string) (string, error) {
	for _, workspace := range []string{DefaultWorkspace, WorkspaceAlias} {
		tracked, err := gitTracked(repoRoot, workspace)
		if err != nil {
			return "", err
		}
		if tracked {
			return filepath.Join(repoRoot, workspace), nil
		}
	}
	return "", nil
}

func gitPrivateExcludePath(repoRoot string) (string, error) {
	cmd := exec.Command("git", "rev-parse", "--path-format=absolute", "--git-path", "info/exclude")
	cmd.Dir = repoRoot
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", gitInspectionError("resolve private exclude path", repoRoot, out, err)
	}
	path := filepath.Clean(strings.TrimSpace(string(out)))
	if !filepath.IsAbs(path) {
		return "", fmt.Errorf("git returned non-absolute private exclude path: %s", path)
	}
	return path, nil
}

func gitTracked(repoRoot, pathspec string) (bool, error) {
	cmd := exec.Command("git", "ls-files", "-z", "--", pathspec)
	cmd.Dir = repoRoot
	out, err := cmd.CombinedOutput()
	if err != nil {
		return false, gitInspectionError("inspect index for "+pathspec, repoRoot, out, err)
	}
	return len(out) > 0, nil
}

func gitInspectionError(operation, repoRoot string, output []byte, err error) error {
	diagnostic := strings.TrimSpace(string(output))
	if diagnostic == "" {
		diagnostic = err.Error()
	}
	return fmt.Errorf("git failed to %s in %s: %s; repair the repository Git metadata and rerun mill init --stealth", operation, repoRoot, diagnostic)
}

func preflightMarkedFile(path string, spec markerSpec) (markedFilePlan, error) {
	existing, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return markedFilePlan{path: path, status: StealthStatusCreated, content: []byte(spec.block)}, nil
	}
	if err != nil {
		return markedFilePlan{}, err
	}
	text := string(existing)
	starts := strings.Count(text, spec.start)
	ends := strings.Count(text, spec.end)
	if starts == 0 && ends == 0 {
		content := append([]byte(nil), existing...)
		if len(content) > 0 && content[len(content)-1] != '\n' {
			content = append(content, '\n')
		}
		if len(content) > 0 {
			content = append(content, '\n')
		}
		content = append(content, spec.block...)
		return markedFilePlan{path: path, status: StealthStatusUpdated, content: content}, nil
	}
	state := markerState(text, spec, starts, ends)
	if state != "exact" {
		return markedFilePlan{}, refuse(path, spec.target, state, "restore the exact mill-owned block or remove both mill markers, then rerun mill init --stealth")
	}
	return markedFilePlan{path: path, status: StealthStatusUnchanged}, nil
}

func markerState(text string, spec markerSpec, starts, ends int) string {
	switch {
	case starts > 1:
		return StealthStateDuplicateStart
	case ends > 1:
		return StealthStateDuplicateEnd
	case starts == 1 && ends == 0:
		return StealthStateStartOnly
	case starts == 0 && ends == 1:
		return StealthStateEndOnly
	}
	start := strings.Index(text, spec.start)
	end := strings.Index(text, spec.end)
	if end < start {
		return StealthStateReversed
	}
	end += len(spec.end)
	if end >= len(text) || text[end] != '\n' {
		return StealthStateEdited
	}
	end++
	if text[start:end] != spec.block {
		return StealthStateEdited
	}
	return "exact"
}

func applyMarkedFilePlan(plan markedFilePlan) error {
	if plan.status == StealthStatusUnchanged || plan.status == StealthStatusSkippedTracked {
		return nil
	}
	return os.WriteFile(plan.path, plan.content, 0o644)
}

func refuse(path, target, state, remediation string) error {
	return &StealthRefusal{Path: path, Target: target, State: state, Remediation: remediation}
}

func oneOf(value string, allowed ...string) bool {
	for _, candidate := range allowed {
		if value == candidate {
			return true
		}
	}
	return false
}
