---
name: groom
description: scan and groom the kanban backlog - board dumps, jq recipes, and the grooming ops
argument-hint: <grooming guidance>
disable-model-invocation: true
---

# Groom the kanban backlog

Groom the board: scan it with the read surface below, then act (or report) per the guidance at the bottom. Run `strand kanban prime` first if you have not this session — it carries the board discipline.

## Knowledge

### Read surface (scanning)

All output is JSON — pipe to `jq`.

- `strand kanban board` — the primary dump: one snapshot grouped by lane (`refinement`, `pending`, `claimed`, `in_review`, `epics`, `needs-review`; closed collapsed to a count). Titles, ids, priorities, epic parents — no bodies.
- `strand kanban card <id>` — one card's resume view (body, tasks, notes). Depth on demand.
- `strand kanban next` — highest-priority (p1 first) oldest pending feature.
- `strand kanban-export <id>` — a card's parent-of subtree with internal depends-on edges; for untangling epics.
- Named queries: `strand ready --query kanban-pending` (unblocked pending cards) and `strand list --query kanban-cards --limit 500` (everything, incl. closed — the read cap demands an explicit `--limit`).

### Scan recipes

```sh
set -o pipefail   # every recipe pipes strand into jq — a masked upstream failure must not read as an empty backlog

# backlog at a glance, priority-sorted
strand kanban board | jq -r '.pending[] | [.priority, .id, (.epic // "-"), .title] | @tsv' | sort

# refinement lane (cards not yet promoted) — same shape; the lane key is always present, so a missing key fails the pipe
strand kanban board | jq -r '.refinement[] | [.priority, .id, .title] | @tsv'

# search titles+bodies across the open board; board supplies ids, then card loads each body on demand
strand kanban board |
  jq -er 'if ([.epics, .refinement, .pending, .claimed, .in_review] | all(.[]; type == "array"))
    then [.epics[], .refinement[], .pending[], .claimed[], .in_review[]] | .[].id
    else error("kanban board response is missing a required card lane")
    end' |
  while IFS= read -r id; do
    strand kanban card "$id" || exit
  done |
  jq -r --arg re '<term>' '.card | select((.title + " " + (.attributes.body // "")) | test($re; "i")) | [.id, .attributes["kanban/lane"], .title] | @tsv'
```

### Grooming ops (mutations)

- `strand kanban priority <id> <p1..p4>` — re-rank (p1 immediate blocker … p4 someday).
- `strand kanban promote <id>` — refinement → pending.
- `strand kanban finish <id> --outcome abandoned` — close dead cards, within the lifecycle: **features close only from claimed/in_review; epics close from refinement/pending** (reversible via `reopen`). A stale pending/refinement feature therefore cannot be abandoned by grooming — note it and flag it in the report instead.
- `strand kanban note <id> ...` — annotate a card with grooming rationale.
- `strand kanban add ... --epic <id>` — file gaps discovered while grooming.

Exact flags: `strand help kanban`.

## Procedures

1. `strand kanban board` — orient: lane sizes, priority spread, orphan features (no `epic`).
2. Scan `pending` (and `refinement`) with the recipes above; pull `strand kanban card <id>` only where a verdict needs the body.
3. For each card form a verdict: keep as-is, re-prioritise, promote, duplicate-of (note both cards; abandonment only where the lifecycle allows it), abandon (epics, or features already claimed/in_review), or flag-for-user (stale pending/refinement features and anything else the ops cannot reach).
4. Apply verdicts with the grooming ops, leaving a `note` on any card whose priority changed or that was abandoned, stating why.
5. Report: a short table of changes made (id, action, reason) plus anything flagged but left for the user.

## Constraints

- Grooming never claims, reviews, or finishes-as-done cards — lane movement beyond `promote` belongs to whoever works the card. In particular, never claim a card just to make it abandonable.
- Mutate nothing until the scan reads have succeeded: run recipes with `set -o pipefail`, and treat a failed or truncated `kanban board`/`kanban card` read (non-zero pipeline exit, jq parse error, missing lane keys) as an abort — report which command failed, never an "empty backlog" verdict.
- Mutate only through `strand kanban ...` ops, never raw `strand update` against card strands.
- If the guidance below is report-only (e.g. "just tell me what's stale"), make no mutations.

## Guidance

$ARGUMENTS
