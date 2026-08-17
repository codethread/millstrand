# Task 3: Land merge-local-verify extension in .skein/workflows.clj

**Document ID:** `TASK-ssc-003` **Configuration identification:** Document IDs must be ordered as document type, short name, sequential id, then optional version: `TASK-ssc-003` for v1 and `TASK-ssc-003@2` for v2. Omit `@1`; append `@2`, `@3`, etc. only when a new version supersedes an externally referenced document. Prefix every nested point ID with the full document ID, for example `TASK-ssc-003.P1`, so references are globally grepable and do not clash across documents. If the next number or version is unclear, ask before creating the document.

## TASK-ssc-003.P1 Scope

Type: AFK

Extend the land `:merge-local-verify` local verification gate to run the spool-suite gate before pushing main. Edit the step's `workflow/instruction` string in `.skein/workflows.clj` to add `make spool-suite-gate` alongside the existing gates (full suite, go tests, fmt/lint/reflect/docs, smoke) — PLAN-ssc-001.A6, PH3. Instruction-string edit only; not a new workflow op.

## TASK-ssc-003.P2 Must implement exactly

- **TASK-ssc-003.MI1 — Add the gate to the instruction string.** In `.skein/workflows.clj`, the `:merge-local-verify` step's `"workflow/instruction"` fn currently lists the local verification gate commands (`flock … clojure -M:test`, `(cd cli && go test ./...)`, `make fmt-check lint reflect-check docs-check`, `clojure -M:smoke`). Add `make spool-suite-gate` to that gate list so the coordinator runs the spool suites on merged local main before pushing.
- **TASK-ssc-003.MI2 — Instruction-string edit only (PLAN-ssc-001.CM1/A6).** Do NOT add a new workflow op, decision point, or step, and do not change any workflow engine contract; this is a prose edit to the existing step's instruction. `.skein/workflows.clj` is repo coordination config, not a root-spec domain.
- **TASK-ssc-003.MI3 — Keep the existing gate semantics.** Preserve the surrounding instruction (squash-merge semantics, `api-docs` regeneration note, the `git reset --hard origin/main` failure recovery, "record every gate result in notes", "do NOT push in this step"). Only insert the new gate command into the verification list; keep the `PATH="/opt/homebrew/opt/openjdk/bin:$PATH"` guard style consistent with the existing full-suite command in that string.

## TASK-ssc-003.P3 Done when

- **TASK-ssc-003.DW1 — Rendered step lists the new gate (disposable-world smoke test).** Smoke-test the config in a throwaway `mktemp -d` `--workspace` world (NEVER the canonical `.skein`): load the workflow config there, render the `:merge-local-verify` step's instruction, and confirm the rendered text lists `make spool-suite-gate` among the local verification gates. Guard every workspace expansion with `${ws:?}`.
- **TASK-ssc-003.DW2 — Config pickup discipline recorded, weaver untouched.** State in the task notes that `.skein/workflows.clj` config changes are picked up at runtime via `runtime/reload!` on a selected world, and that this slice MUST NOT restart the canonical weaver (PLAN-ssc-001.TC4/V4). Do not restart or reload the canonical `.skein` weaver as part of this task.
- **TASK-ssc-003.DW3 — Clojure gate green for the changed file.** `.skein/workflows.clj` is a Clojure config file; run `make fmt-check lint reflect-check` and confirm they pass. No shipped src/test namespace changes, so there is no `clojure -M:test <ns...>` cold gate for this slice (PLAN-ssc-001.V2); the disposable-world render smoke (DW1) is the behavioral gate.
- **TASK-ssc-003.DW4 — Working tree clean.** `git status --short` shows only the intended `.skein/workflows.clj` change; no disposable-world artifacts, generated SQLite, or runtime metadata left behind.

## TASK-ssc-003.P4 Out of scope

- **TASK-ssc-003.OS1:** The make target itself (TASK-ssc-001) and the CI job (TASK-ssc-002).
- **TASK-ssc-003.OS2:** Any new workflow op, step, or decision point, or any change to the workflow engine contract (SPEC-004) — this is an instruction-string edit only.
- **TASK-ssc-003.OS3:** Restarting or reloading the canonical `.skein` weaver.

## TASK-ssc-003.P5 References

- **TASK-ssc-003.REF1:** Plan PLAN-ssc-001.A6, CM1, V4, AA3, TC4 — [../spool-suite-ci-gate.plan.md](../spool-suite-ci-gate.plan.md).
- **TASK-ssc-003.REF2:** Proposal PROP-ssc-001.S6 (land-gate decision: include) — [../proposal.md](../proposal.md).
- **TASK-ssc-003.REF3:** `.skein/workflows.clj` — the `:merge-local-verify` step (~356–373), its `"workflow/instruction"` fn listing the local verification gate commands.
- **TASK-ssc-003.REF4:** Root `AGENTS.md`/`CLAUDE.md` "Coordination" and "Hard rules" — the `runtime/reload!` pickup ladder and the never-restart-the-canonical-weaver discipline; the disposable `--workspace` and `${ws:?}` guard rules.
