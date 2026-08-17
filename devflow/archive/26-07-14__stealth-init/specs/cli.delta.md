# CLI delta for stealth local workspace initialization

**Document ID:** `DELTA-Si-001` **Root spec:** [cli.md](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-14

## DELTA-Si-001.P1 Summary

`mill init` gains a repo-only stealth mode for a physical, untracked `.skein` workspace. Workspace selection and runtime identity do not change.

## DELTA-Si-001.P2 Contract changes

- **DELTA-Si-001.CC1:** `mill init --stealth` cannot be combined with `--workspace`. Before any write, one preflight validates Git tracking and both owned marker files. Every semantic refusal exits non-zero with no file change. `.skein` is tracked when `git ls-files -- .skein` returns the path itself or any descendant.
- **DELTA-Si-001.CC2:** Stealth init owns exact, bounded marker blocks in `.git/info/exclude` and an untracked `CLAUDE.local.md`. An absent file is created with the exact block. A non-empty marker-free file keeps its bytes and gains a separating newline plus the block. Exact blocks, including their final newline, are unchanged. Partial, duplicate, reordered, or edited owned blocks fail with the path, detected state, and remediation.
- **DELTA-Si-001.CC3:** A tracked `CLAUDE.local.md` is left unchanged and reported as `skipped-tracked`.
- **DELTA-Si-001.CC4:** The success result retains `config_dir` and `config_file`, then adds `stealth`. Its closed children are `git_exclude` (`path`, status `created|updated|unchanged`), `claude_guidance` (`path`, status `created|updated|unchanged|skipped-tracked`), and `codex_guidance` (status `manual-required`, `suggested_text`).
- **DELTA-Si-001.CC5:** Ordinary `mill init` output and bootstrap behavior remain unchanged.
- **DELTA-Si-001.CC6:** The Go boundary owns typed success structs with closed JSON keys and validates every status enum before placing the result on the mill response. A stealth refusal uses error code `mill/init-stealth-refused` and details with exactly `path`, `target`, `state`, and `remediation`, all required non-blank strings. `target` is `tracked-skein`, `git-exclude`, or `claude-guidance`. Marker `state` is one of `tracked`, `start-only`, `end-only`, `duplicate-start`, `duplicate-end`, `reversed`, or `edited`. The normal `mill/init-failed` envelope remains for non-stealth bootstrap failures.

## DELTA-Si-001.P3 Normative marker blocks

The Git exclude block is exactly these bytes, including its final newline:

```text
# mill:skein-stealth
/.skein
/CLAUDE.local.md
# /mill:skein-stealth
```

The Claude guidance block is exactly these bytes, including its final newline:

```text
<!-- mill:skein-prime -->
## Skein / strand

This repo uses Skein strands to track work. Orientation ships in the `mill` CLI:

- `mill skein prime` — where the Skein source and docs live, and how to extend this repo's `.skein/` config.
- `mill strand prime` — the strand planning/tracking workflow; run it before multi-step work.
<!-- /mill:skein-prime -->
```

For either file, absence creates the file with the block. A non-empty existing file with neither marker keeps its bytes, gains a newline if needed, then one blank line and the block. Exactly one start and end marker in order is valid only when the inclusive bytes equal the normative block. Any other marker count, order, or body maps to the refusal states in CC6. Content outside an exact block is preserved.

## DELTA-Si-001.P4 Design decisions

### DELTA-Si-001.D1 Keep one workspace model

- **Decision:** Stealth mode changes Git visibility and local agent guidance, not workspace layout or resolution.
- **Rationale:** A physical repo-local workspace preserves ordinary filesystem, testing, sandbox, and process-working-directory behavior.
- **Rejected:** A vault, symlink, index, or weaver-backed bootstrap operation.

## DELTA-Si-001.P5 Open questions

None.
