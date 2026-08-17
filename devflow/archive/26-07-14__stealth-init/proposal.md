# Stealth local workspace initialization

**Feature spec:** [specs/cli.delta.md](./specs/cli.delta.md) **Plan:** [stealth-init.plan.md](./stealth-init.plan.md)

## Problem

`mill init` assumes the repository will carry shared `.skein` config. Some users want Skein for personal work without asking the repository to adopt it. They can ignore `.skein` themselves, but the setup is easy to miss and fresh agents do not receive the usual orientation.

A separate workspace vault would add path, sandbox, testing, and Git ownership problems. The existing repo-local workspace already has the required runtime and local-spool seams.

## Change

Add `mill init --stealth` for a repository-local workspace with no tracked file changes. It preflights every stealth-owned path, performs the ordinary `.skein` bootstrap, then:

- owns a marked block in `.git/info/exclude` covering `/.skein` and `/CLAUDE.local.md`;
- owns a marked Skein orientation block in `CLAUDE.local.md`, which Claude Code loads as private project guidance;
- returns structured JSON describing each local action;
- returns a manual Codex instruction because Codex has no additive project-local companion to `AGENTS.md`.

The success result keeps the existing top-level keys and adds this closed object:

```json
{
	"config_dir": "/repo/.skein",
	"config_file": "/repo/.skein/config.json",
	"stealth": {
		"git_exclude": {
			"path": "/repo/.git/info/exclude",
			"status": "created|updated|unchanged"
		},
		"claude_guidance": {
			"path": "/repo/CLAUDE.local.md",
			"status": "created|updated|unchanged|skipped-tracked"
		},
		"codex_guidance": {
			"status": "manual-required",
			"suggested_text": "..."
		}
	}
}
```

`devflow/specs/cli.md` owns this public shape and the CLI behavior. Go tests assert the exact keys, types, and status values.

Each marked file has three valid states. An absent file is created with the exact owned block. A non-empty marker-free file keeps its bytes and gains a separator plus the exact block. One complete, byte-exact block, including its final newline, is unchanged. Any half marker, duplicate marker, or edited owned block fails and names the path, detected state, and remediation. Stealth init rejects a repository that already tracks `.skein` before bootstrap writes anything. All semantic rejection happens during preflight, before any file changes. It reports a tracked `CLAUDE.local.md` as `skipped-tracked` and leaves it untouched. The CLI delta defines the normative marker bytes, state enum, and error details.

`--stealth` is repo-only and cannot be combined with `--workspace`. Ordinary `mill init` remains unchanged.

## Convention

Stealth users keep the generated `init.clj` small. Personal activation belongs in `init.local.clj`, approvals belong in `spools.local.edn`, and substantive behavior belongs in a local spool. The spool can use its own Git repository when history matters.

## Validation

Go unit tests cover marker insertion, repeat runs, malformed markers, and tracked guidance. The existing CLI integration harness covers the public command, ordinary workspace bootstrap, JSON output, and absence of tracked changes.
