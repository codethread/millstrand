# Brief: Mill weaver autostart

Mill currently starts a selected workspace's Weaver only when a user asks for
it. That makes a set of opted-in workspaces disappear after Mill restarts and
leaves users to repeat the same start commands.

Add an explicit opt-in contract for remembered Weaver starts:

- `config.json` may carry the boolean `autoStart`; `config.local.json` never
  carries it.
- Unknown keys in either config file are ignored for compatibility. Weaver
  startup emits warnings naming the file and keys; ordinary status and invoke
  config reads do not log them. Known wrong types still fail, and
  `config.local.json` still rejects the known misplaced `configFormat` key.
- `mill init --auto-start` requires a running Mill, writes the opt-in, records
  the workspace, and starts the Weaver immediately. A missing Mill or failed
  start is returned as an error; init has no offline registration fallback.
- `mill weaver start` records an explicit true registration, including when
  the Weaver is already running.
- On the next Mill startup, Mill removes registrations for workspaces whose
  `config.json` omits `autoStart` or sets it to `false`, then starts the
  remaining remembered workspaces with at most four starts in flight.
- A failed remembered start is logged and does not prevent other remembered
  workspaces from being attempted.

The registration is Mill-owned state under the existing XDG state root. It is
not a new workspace overlay or a second user-facing configuration file.

## Required design questions

1. Which config reads and bootstrap paths can write the opt-in without
   changing ordinary init behavior?
2. How does running `mill init --auto-start` write config, record the
   registration before starting, and propagate Mill transport or start
   failures?
3. How are false or omitted config values pruned before startup, including
   entries left by an earlier explicit start?
4. How is the four-start concurrency cap enforced while one failure remains
   isolated and visible in Mill logs?
5. Which CLI, config, lifecycle, and integration tests lock these transitions?

## Acceptance

Document and implement the exact transitions above. Cover running-Mill init,
unavailable-Mill failure, explicit start, already-running start, pruning, the
four-start cap, per-workspace failure logging, continuation, and startup-only
unknown-key warnings for both config files. Run the repository docs gate and
the focused Go tests before the coordinator hands the feature to landing.
