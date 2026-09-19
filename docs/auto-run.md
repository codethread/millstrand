# Automatic delivery

This repository can admit eligible feature cards into the repository-owned `auto-full-land` workflow. The dispatcher is activated by `.millstrand/init.clj`. It starts at most two workers and uses the `sol-high` seat at high effort.

## Opt in a card

Automatic delivery only admits an active, graph-ready feature in the `pending` lane with the `auto-run` label. The card must be unclaimed and must not already have an automatic-delivery receipt. Dependencies remain the readiness rule.

The dispatcher accepts these optional card attributes:

- `auto-run/seat`
- `auto-run/effort`
- `auto-run/workflow`

The repository allows only `auto-full-land`. Invalid overrides are recorded as a card error; they do not fall back to the defaults.

For the default receipt, make the card pending and label it last:

```text
strand update FEATURE_ID --attr kanban/lane=pending
strand kanban label add FEATURE_ID auto-run
```

For a non-default receipt, set valid overrides before making the card eligible:

```text
strand update FEATURE_ID --attr auto-run/seat=SEAT --attr auto-run/effort=EFFORT
strand update FEATURE_ID --attr kanban/lane=pending
strand kanban label add FEATURE_ID auto-run
strand workflow show auto-full-land
```

## Inspect and control admission

```text
strand auto-run status
strand auto-run scan --by-identity YOUR_IDENTITY
```

`status` shows the repository configuration and durable receipts. An `assigned` receipt means that Harnesses accepted a worker. Inspect the worker and its workflow frontier with:

```text
strand agent show RUN_ID
strand workflow ready WORKFLOW_RUN_ID
```

A scan respects the two-worker limit and does not launch a card twice.

Disable the dispatcher through a repository policy change and normal refresh. Disabling stops new admission only. It does not stop an accepted worker, clear a receipt, or rearm a card.

## Delivery boundary

The workflow requires implementation, the repository land-quality contract, a ready PR with its review package, PR checks, and deterministic PR verification before moving the card to review. Verification requires an open, non-draft PR targeting `main`, from the prepared branch at the pushed quality-marked HEAD, with `## Summary`, `## Walkthrough`, and `## Verification` in its body. It then calls Millhouse's autonomous landing contract. The delivery worker prepares a separate finisher target; the finisher waits for successful worker settlement and owns shared-land sign-off, FIFO merge, cleanup, and final card completion.

On an observed delivery or landing failure, the worker adds `auto-run-failure`, records the evidence and retained resources, and leaves the card open. The workflow never retries a failed gate or replaces an accepted worker on its own.
