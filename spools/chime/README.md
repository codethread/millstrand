# Skein Chime Spool

## Overview

`skein.spools.chime` is a local notification engine for Skein graph events. It watches strand mutations, evaluates small user-registered rules, and sends matching notices through a user-bound local notifier command.

Chime knows nothing about any particular workflow or attribute vocabulary: it ships **no rules and no notifier**. A workspace's trusted config decides what deserves attention (rules) and each developer decides how to be told (notifier). It owns only runtime-local weaver-lifetime state: the notifier binding, rules, deduplication memory, batch scan memory, and recent failures are kept on the active runtime and isolated from other runtimes in the same JVM.

Chime spawns a user-configured local process with the user's authority. Its code still loads as an approved spool, through the shipped source-root coordinate rather than the production classpath.

For composition recipes — binding a notifier, writing rules that fire on an attribute transition or on readiness, and debugging when the notifications go quiet — see the [cookbook](../chime.cookbook.md).

## Loading

Approve the shipped root from the selected workspace's `spools.edn`:

```clojure
{:spools {skein.spools/chime {:skein/source-root "spools/chime"}}}
```

Activate it from trusted startup config after syncing approved roots:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/module! runtime :chime
  {:ns 'skein.spools.chime
   :spools ['skein.spools/chime]
   :required? true})
```

The module's `defresource` maintains the graph-event handler, registration barrier, and visible rule view under one shared monitor. A useful setup then layers two things on top:

- shared config (`init.clj` / a workspace spool) authors the workspace's rules with `defrule`, so the repo decides what needs attention;
- each developer binds their notifier in gitignored `init.local.clj` with `set-notifier!`, so how you get told (a notification daemon, `osascript`, a bell) stays a personal choice.

## Notifier binding

Bind the notifier with plain data:

```clojure
(require '[skein.spools.chime :as chime])

(chime/set-notifier! {:argv ["my-notify"]})
(chime/notifier)
```

For each notification, chime spawns `:argv` with the title appended as the final argument and the body written to stdin — any command with the shape `my-notify <title>` (body on stdin) works, including a small wrapper script around whatever your platform uses.

Notifier state is weaver-lifetime: re-bind on every startup/reload (which `init.local.clj` does naturally). With no notifier bound, a fired rule records a loud failure in `(chime/recent-failures)` instead of silently dropping the event.

Manual sends use the same path:

```clojure
(chime/notify! {:title "Build finished" :body "All strands under plan abc are closed."})
```

## Rules

A rule is a named function registered by fully qualified symbol. On every graph mutation chime scans current strands and calls each rule with a context map containing at least:

- `:strand` — the candidate strand being evaluated;
- `:event` — the triggering graph event;
- `:ready-ids` — the set of ready strand ids, computed once per scan, so
  readiness rules need no extra queries.

The rule returns nil for no notification or `{:title "..." :body "..."}` to send one. Scans are whole-graph on purpose — the strand worth notifying about is often not the strand that changed (closing one strand is what makes another ready). Batch events and their per-strand fanout share a `:batch/id` and trigger only one scan.

Worked example — notify when a strand that parents other work is closed:

```clojure
(ns my.rules
  "Workspace notification rules."
  (:require [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.chime :as chime]))

(chime/defrule parent-completed
  "Notify when a strand with parent-of children reaches closed."
  [{:keys [strand]}]
  (when (and (= "closed" (:state strand))
             (seq (weaver/list (current/runtime)
                               [:edge/in "parent-of" [:= :id (:id strand)]] {})))
    {:title (str "Plan complete: " (:title strand))
     :body (str "Strand " (:id strand) " and the work it parents are finished.")}))
```

Loading this module publishes `:parent-completed` under its module owner. Omission retracts it on refresh.
That authoring-form path is the durable source. Trusted REPL code and tests may use Chime's runtime-local `register!` seam for a live rule, and `unregister!` removes only that direct entry; neither function changes the rule Var or the module-owned declaration. The rule registry remains local to the active runtime.

When a rule is registered, chime treats its currently matching strands as an
initial seen baseline. Restarting a weaver therefore does not replay every
durable condition on the next graph mutation. This suppresses every condition
already true at registration, including one that first became true while the
weaver was down and has never produced a notification. Conditions that start
after registration notify normally. Chime's pre-commit mutation barrier orders
concurrent graph changes after registration, so they are not absorbed into the
baseline.

Chime then deduplicates notifications per `[rule strand]` while the rule keeps
matching: a strand is marked seen only after the notifier process starts, so a
missing or failing notifier does not swallow the alert, and the mark clears when
the rule stops matching so a recurrence notifies again. Use
`(chime/reset-seen!)` from tests or config to clear that memory.

Rule, notifier, and process failures are recorded by `(chime/recent-failures)` and event handler failures remain visible through the Skein event failure surface.

## See also

- [`../README.md`](../README.md) — the spool index.
- [`agent-harness.spool/agent-run/README.md`][agent-run-contract] — external coordinate,
  activation, and local-override pattern.
- [`../../docs/spools/customisation.md#promoting-config-to-a-local-spool`](../../docs/spools/customisation.md#promoting-config-to-a-local-spool) — authoring and loading local spools.

[agent-run-contract]: https://github.com/codethread/agent-harness.spool/blob/d28bfb35b5fc1891a7a318e06886aa446722241d/agent-run/README.md
