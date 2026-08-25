# Brief: Weaver restart continuity

Replacing a Weaver interrupts work that outlives its JVM. Durable graph, workflow, and scheduler state reconstructs, but native children can lose their waiter: agent runs may be killed, retried, or left running; Millhouse shell execution has the same gap. External loops such as Ralph survive outside the Weaver but may stop when a board call fails during replacement.

Make replacement a normal Mill-supervised transition:

- concurrent start and restart callers converge on one ready generation;
- Mill-routed clients wait through planned downtime within their deadline and send each operation once;
- Mill never replays an operation accepted by the previous generation;
- Mill holds custody of native work that requires continuity, including output, cancellation, and retained completion facts;
- spools retain workflow policy and reconcile durable handles after replacement.

Arbitrary JVM work remains interruptible. Callbacks, handlers, REPL connections, streams, and in-memory awaits need explicit restart outcomes. The first contract covers Weaver replacement while Mill remains alive; cross-Mill adoption and crash survival are later work.

## Required design questions

1. What atomic states and failures define `mill weaver restart`, including convergence and readiness?
2. How do client waiting, deadlines, direct socket access, and one-send behavior work?
3. What custody protocol covers idempotent launch, process identity and trees, output, status, cancellation, retained completion, acknowledgement, and cleanup?
4. How do agent runners, Millhouse shell execution, interactive agents, and Ralph use that contract?
5. What interruption or reconciliation result applies to runtime code, scheduled and event work, chime delivery, peer calls, REPLs, streams, and awaits?
6. Which end-to-end tests prove continuity, reconciliation, one-send behavior, startup failure reporting, and clean shutdown?

## Acceptance

Produce and review a proposal covering restart, client, process-custody, and interruption boundaries across Millstrand, Millhouse, agent-harness, and Codethread. Stop for human approval before implementation planning.
