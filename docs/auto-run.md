# Automatic delivery

This repository can admit eligible feature cards into the repository-owned `auto-full-land` workflow. The dispatcher is activated by `.millstrand/init.clj`. It starts at most two workers and uses the `sol-high` seat at high effort.

## Opt in a card

Automatic delivery only admits an active, graph-ready feature in the `pending` lane with the `auto-run` label. The card must be unclaimed and must not already have an automatic-delivery receipt. Dependencies remain the readiness rule.

The dispatcher accepts these optional card attributes:

- `auto-run/seat`
- `auto-run/effort`
- `auto-run/workflow`

The repository allows only `auto-full-land`. Invalid overrides are recorded as a card error; they do not fall back to the defaults.

## Inspect and control admission

```text
strand auto-run status
strand auto-run scan --by-identity YOUR_IDENTITY
```

`status` shows the repository configuration and durable receipts. An `assigned` receipt means that Harnesses accepted a worker; inspect its run and the workflow frontier separately. A scan respects the two-worker limit and does not launch a card twice.

Disable the dispatcher through a repository policy change and normal refresh. Disabling stops new admission only. It does not stop an accepted worker, clear a receipt, or rearm a card.

## Delivery boundary

The workflow requires implementation, the repository land-quality contract, a ready PR with its review package, PR checks, and deterministic PR verification before moving the card to review. It then calls Millhouse's autonomous landing contract. The delivery worker prepares a separate finisher target; the finisher waits for successful worker settlement and owns shared-land sign-off, FIFO merge, cleanup, and final card completion.

On an observed delivery or landing failure, the worker adds `auto-run-failure`, records the evidence and retained resources, and leaves the card open. The workflow never retries a failed gate or replaces an accepted worker on its own.
