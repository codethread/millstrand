# Strand tracking runbook

Use the `strand` CLI to plan and track multi-step work as a small explicit DAG of strands. If `strand` fails because it cannot reach a weaver, run `mill weaver start`.

This is the batteries tracking loop. Commands are workspace-registered; this page assumes the batteries surface (`add`, `update`, `list`, `ready`, `show`) plus whatever else the workspace elected. Verify with `strand help`.

## When to use strands

- Skip strands for one small direct action.
- Create a strand plan for multi-step work, dependencies, validation, review, or when the user explicitly asks to use or manage strands.
- Keep plans small: usually 2–6 strands. Do not create a strand for every tiny sub-step.

## Discover the surface

Start from `strand --help`, then load help, then prime, then about:

```sh
strand help              # list commands
strand help <op>         # what you can type
strand prime <op>        # runbook for that command
strand about <op>        # detailed explanation
```

Repo conventions name the ops and queries to lead with, and they win over anything generic here.

## Body attribute convention

Use `body` for useful issue-style context: problem, scope, acceptance criteria, constraints, relevant files, and validation expectations.

- Titles alone are acceptable for personal or short-lived to-do tracking.
- Any strand another agent may pick up needs a clear descriptive `body`.
- For multi-line bodies, attach the text as a payload and reference it:

```sh
strand --payload body=<path> add "<title>" --attr body=:payload/body
printf '%s' "<text>" | strand --stdin add "<title>" --attr body=:stdin
```

## Standard loop

1. Check the surface first. If `strand help` lists a `pattern` op, prefer a registered weave pattern over hand-authored graphs: `strand pattern list`, then `strand pattern explain <name>` for the live contract.
2. Create or update the plan graph with the best-fitting pattern, or minimal raw commands.
3. Add `body` context to any strand another agent may pick up.
4. Run `strand ready` and pick from ready work, not the full list. Prefer a repo-curated ready query when one is registered: `strand ready --query <name>`.
5. Respect attribute conventions the repo's spools declare — for example, a ready strand carrying `hitl=true` means stop and ask the user before doing it. `strand about agent` documents how interactive tasks use that convention.
6. Complete one ready strand or one tightly related pair, then run relevant validation.
7. If new work is discovered, add and wire it before closing the current loop.
8. Close completed strands: `strand update <id> --state closed`.
9. Repeat `strand ready` until done or blocked.

## Workspaces

Without a flag, `strand` targets the repo's `.millstrand` or `.ms` world. Select another explicitly for disposable or non-default worlds:

```sh
strand --workspace <dir> ready
strand --workspace <dir> update <id> --state closed
```

## Validation and finish

Before reporting success:

- `strand ready` matches the expected next work, or is empty because all planned work is closed.
- Completed strands are closed; newly discovered work is represented by active strands; dependencies reflect actual blocking relationships.
- Relevant checks pass.

Branching, delegation, review, and landing discipline are repo policy, not batteries: take them from repo conventions and from the `prime`/`about` surfaces of the ops the repo registers. Building on `.millstrand` itself — config, spools, the source docs — is covered by `mill prime millstrand`.
