# Millstrand Chime Spool — Cookbook

Composition recipes for `millstrand.spools.chime`: how to turn graph mutations into a real attention surface, and *why* each shape is the right one.

This is the **how/why** half of the chime docs. The other two halves are:

- [`chime/README.md`](./chime/README.md) — the **contract**: what chime watches,
  the notifier binding shape, the rule context map, and the deduplication
  guarantees. Read it for what the engine promises.
- [`chime.api.md`](./chime.api.md) — the **generated reference**: every public
  fn's signature, arity, and docstring, produced from source.

Division of truth: signatures and argument lists live in the generated API doc; narrative and composition live here and in the contract. This cookbook never restates a fn signature — it links to them. When a recipe needs an exact arity, follow the link.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which pieces combine, and how.
3. **Snippet** — a complete, runnable form (assume
   `(require '[millstrand.spools.chime :as chime])`).
4. **Why this shape** — the reasoning: why chime is built this way, and what the
   alternative would cost.

Each recipe cites the honest source it was distilled from — this repo's own config, or the chime test suite — so you can read the load-bearing version.

The one idea under all of it: **chime owns the plumbing, your config owns the judgement.** The engine ships no rules and no notifier. A workspace's trusted config decides what deserves attention (rules); each developer decides how they are told (notifier). Everything below is one of those two halves.

---

## Recipe: Bind how you are told, per developer

**Situation.** The repo already agrees on *what* is worth a notification, but you personally want those notices to reach you your way — a desktop banner, a terminal bell, whatever notifier daemon you run — without editing shared config.

**Composition.** One `set-notifier!` call with an `{:argv [..]}` binding. Chime spawns that argv per notification with the **title appended as the last argument and the body written to stdin**, so any command shaped like `my-notify <title>` (body on stdin) works. Put the call in your gitignored `init.local.clj`, which the weaver loads after the shared startup files on every start and reload.

```clojure
(require '[millstrand.spools.chime :as chime])

;; A notification helper that takes the title as an argument, body on stdin:
(chime/set-notifier! {:argv ["cc-notify"]})

;; macOS with no helper installed — wrap osascript in a one-line script and
;; point argv at it, since osascript wants its own flag shape:
;;   #!/bin/sh
;;   title=$1; body=$(cat)
;;   osascript -e "display notification \"$body\" with title \"$title\""
(chime/set-notifier! {:argv ["/Users/me/.local/bin/millstrand-notify"]})

;; Confirm the binding, then prove the path end to end without waiting for a rule:
(chime/notifier)
(chime/notify! {:title "Chime wired" :body "This is the notifier path."})
```

**Why this shape.**

- **The notifier is personal, so it lives in personal config.** Rules are a repo
  decision and belong in shared startup; the notifier is a per-machine choice and
  belongs in gitignored `init.local.clj`. Keeping them apart means a teammate's
  notifier preference never lands in a commit, and the shared rules stay the
  single source of "what matters here."
- **`{:argv [..]}` is plain data, not a callback.** Chime just spawns a local
  process with your authority — which is exactly why it is an approved local-root
  spool, not a shipped classpath one. The title-as-last-arg / body-on-stdin
  contract is fixed, so any notifier that fits that shape drops in, including a
  three-line wrapper script around your platform's tool.
- **Notifier state is weaver-lifetime.** It is not persisted; you rebind on every
  startup and reload. Putting the call in `init.local.clj` makes that automatic —
  the file re-runs on both.

Honest source: this repo's [`.millstrand/init.clj`](../.millstrand/init.clj) chime block and [CLAUDE.md](../CLAUDE.md)'s chime note (`cc-notify` / `osascript` per-developer binding in `init.local.clj`); the argv-and-stdin behaviour is exercised by `notifier-binding-and-manual-notify` in [`test/millstrand/chime_test.clj`](../test/millstrand/chime_test.clj), which binds a script that appends the title and stdin body to a file and asserts both arrive.

---

## Recipe: Fire on an attribute transition

**Situation.** A strand crosses into a state you want to hear about — a delegated run flips to `failed`, a workflow checkpoint becomes a human's to decide — and you want one notification the moment it happens.

**Composition.** A named rule: a fully-qualified fn that receives the rule context and returns `nil` for "no notification" or `{:title .. :body ..}` to send one. Register it with `register!` from shared config. The rule reads the candidate strand's attributes and matches only the transition you care about.

```clojure
(ns my.rules
  "Workspace attention rules."
  (:require [millstrand.spools.chime :as chime]))

(defn agent-failed
  "Notify when a delegated run has failed or exhausted its attempts."
  [{:keys [strand]}]
  (let [phase (get-in strand [:attributes "agent-run/phase"])]
    (when (contains? #{"failed" "exhausted"} phase)
      {:title (str "Agent run " phase ": " (:title strand))
       :body  (str "Strand " (:id strand) " entered agent-run/phase " phase
                   (when-let [err (get-in strand [:attributes "agent-run/error"])]
                     (str "\n\n" err)))})))

;; register once from shared startup config
(chime/register! :agent-failure 'my.rules/agent-failed)
(chime/rules)
```

**Why this shape.**

- **A rule is just a predicate that also writes the message.** Return `nil` and
  nothing happens; return a map and chime sends it. There is no separate "when"
  and "what" — the same fn that decides the transition matters also phrases the
  alert, so the two never drift.
- **Match on the attribute, not the event.** The rule reads `agent-run/phase` off
  the strand rather than trying to catch a specific mutation. Attributes are the
  durable truth; an event is just what woke the scan. This is why the same rule
  fires whether the phase was set on create or on a later update.
- **Chime deduplicates per `[rule strand]` while the rule keeps matching**, so a
  run that stays `failed` across many later mutations still notifies once. The
  mark clears when the rule stops matching, so a genuine recurrence alerts again.
  Registration seeds currently matching strands as the initial seen baseline,
  so a weaver restart does not replay old failures. This also suppresses a
  never-notified condition that became true while the weaver was down. A
  concurrent mutation is ordered after registration and still notifies. You
  write the plain predicate; the engine handles "only once."

Honest source: this repo's `hitl-checkpoint-ready` and `kanban-completed` rules in [`.millstrand/notifications/attention.clj`](../.millstrand/notifications/attention.clj), registered together in `register-chime-rules!`; the fire-once-per-transition behaviour is pinned by `registered-rules-fire-end-to-end` and `dedup-and-reset-seen` in [`test/millstrand/chime_test.clj`](../test/millstrand/chime_test.clj).

---

## Recipe: Notify when an interactive session is waiting

**Situation.** An `agent delegate --interactive` run starts a live multiplexer session. The attach command is available from `strand agent ps`, but the human should not have to poll for it. You want one notification when an interactive agent-run run enters `running`, with the same attach hint that `ps` shows.

**Composition.** Follow the tracking-task contract from `strand about agent`, then keep notification policy in userland. Agent-run exposes durable run attributes and the `ps` summary includes `attach`; chime only needs a normal rule that recognises a running interactive run. Put the rule in trusted workspace code and register it from startup config after chime is active.

```clojure
(ns my.rules
  "Workspace attention rules."
  (:require [clojure.string :as str]
            [millstrand.spools.chime :as chime]
            [ct.spools.agent-run :as agent-run]))

(defn interactive-session-running
  "Notify when an interactive agent-run session is ready for the human."
  [{:keys [strand]}]
  (let [attrs (:attributes strand)]
    (when (and (= "true" (get attrs "agent-run/run"))
               (= "interactive" (get attrs "agent-run/mode"))
               (= "running" (get attrs "agent-run/phase")))
      (let [summary (some #(when (= (:id %) (:id strand)) %) (agent-run/runs {:active true}))
            attach (:attach summary)]
        {:title (str "Interactive session ready: " (:title strand))
         :body  (str "Run " (:id strand) " is waiting for a human."
                     (when-let [served (:for summary)]
                       (str "\nServes: " served))
                     (if (str/blank? attach)
                       "\nAttach: no backend attach hint is configured for this run."
                       (str "\nAttach: " attach)))}))))

(chime/register! :interactive-session-running 'my.rules/interactive-session-running)
```

**Why this shape.**

- **Agent-run stays decoupled from chime.** The rule lives in workspace code.
  Agent-run does not learn what notification engine a user has, and chime does not
  gain agent-run-specific branching.
- **The match is durable.** `agent-run/mode=interactive` and
  `agent-run/phase=running` are run attributes, so the rule still explains itself
  after a restart. A session already running when the rule is registered becomes
  part of the initial seen baseline; a session that starts afterward notifies
  once while it remains running.
- **The attach text comes from the same surface humans already use.**
  `agent-run/runs` is the Clojure side of `strand agent ps`; it performs the
  interactive liveness check and renders the backend's display-only `:attach`
  argv over the stored handle. If a backend has no attach template yet, the rule
  says no attach hint is configured instead of inventing a command.

Honest source: agent-run's `run-summary` / `runs` implementation in
[`agent_run.clj`][agent-run-source] renders `:attach` from the backend's display-only `:attach` op,
and the external [`delegation/README.md`][delegation-contract] documents that `strand agent ps`
carries `mode`, `backend`, `session`, and `attach` for interactive summaries.

[agent-run-source]: https://github.com/codethread/agent-harness.spool/blob/d28bfb35b5fc1891a7a318e06886aa446722241d/agent-run/src/ct/spools/agent_run.clj
[delegation-contract]: https://github.com/codethread/agent-harness.spool/blob/d28bfb35b5fc1891a7a318e06886aa446722241d/delegation/README.md

---

## Recipe: Notify about a different strand than the one that changed

**Situation.** The strand worth telling a human about is not the one that mutated. Closing a blocker is what makes a *dependent* ready; a run does not become "silently parked" by changing — it becomes parked by *not* changing while staying ready and unclaimed. You need readiness, not the raw event alone.

**Composition.** Read `:ready-ids` from the rule context — the set of ready strand ids, computed once per scan. Chime scans the whole graph on every mutation, so a rule may fire on a strand the triggering event never touched. Combine readiness with the strand's own attributes and age to catch a stuck condition.

```clojure
(defn checkpoint-ready
  "Notify when a human checkpoint becomes ready to decide."
  [{:keys [strand ready-ids]}]
  (when (and (= "active" (:state strand))
             (= "checkpoint" (get-in strand [:attributes "workflow/role"]))
             (= "human" (get-in strand [:attributes "workflow/checkpoint-kind"]))
             (contains? ready-ids (:id strand)))       ; ready *now*, not merely active
    {:title (str "HITL checkpoint ready: " (:title strand))
     :body  (str "Checkpoint " (:id strand) " is ready for human attention.")}))

(chime/register! :hitl-checkpoint-ready 'my.rules/checkpoint-ready)
```

**Why this shape.**

- **Whole-graph scans are the point, not overhead.** The event that fires a scan
  is often not the strand you care about — closing strand A is what makes strand B
  ready. Because chime re-scans the whole graph and hands each rule the same
  `:ready-ids` set, a "B just became ready" rule needs no extra query and no
  memory of what changed.
- **`:ready-ids` is computed once per scan and shared across rules.** A readiness
  check is a set membership test, not a per-rule graph traversal, so a readiness
  rule stays cheap even with many rules registered.
- **Readiness plus age catches *silence*.** The repo's parked-run detector fires
  on a run that is ready, still `pending`, not in-flight, and older than a
  threshold — a condition no single mutation announces. Reading `:ready-ids` and
  the strand's `updated_at` age lets a rule notice the absence of progress, which
  is the failure mode a mutation-only trigger would miss entirely.

Honest source: this repo's `hitl-checkpoint-ready-rule` and `parked-run-rule` in [`.millstrand/notifications/attention.clj`](../.millstrand/notifications/attention.clj); readiness firing on both born-ready and later-unblocked strands is covered by `ready-rule-fires-born-ready-and-when-unblocked` in [`test/millstrand/chime_test.clj`](../test/millstrand/chime_test.clj).

---

## Recipe: When the notifications go quiet

**Situation.** You expected a chime and heard nothing. Before suspecting the rule logic, you need to know whether the notifier ever ran, threw, or was simply never bound.

**Composition.** Read `(chime/recent-failures)`. Chime records notifier, process, and rule failures for the weaver lifetime instead of swallowing them — a fired rule with no notifier bound is a loud `:notifier-missing` failure, not a dropped event. A rule that throws is recorded and skipped rather than crashing the scan.

```clojure
;; What has failed this weaver lifetime?
(chime/recent-failures)
;; e.g. {:kind :notifier-missing :title "Agent run failed: run a" ...}
;;      {:kind :rule :rule :my-rule :message "..."}

;; Is a notifier even bound right now?
(chime/notifier)   ; => nil means every fired rule is recording :notifier-missing

;; Clearing dedup memory so a still-matching rule notifies again (tests/config):
(chime/reset-seen!)
```

**Why this shape.**

- **A missing notifier fails loudly, on purpose.** Chime marks a strand seen only
  *after* the notifier process starts, so a missing or failing notifier never
  swallows the alert — the event stays un-acknowledged and lands in
  `recent-failures`. When things go quiet, `recent-failures` tells you whether
  the gap is "no notifier bound" or "rule never matched."
- **Keep rules cheap and loud.** A rule runs on every scan, so heavy work inside
  one taxes every mutation; and a rule that throws is recorded and skipped, so a
  buggy rule silently stops notifying while the rest keep working. Check
  `recent-failures` for `:rule` entries when one rule alone goes dark, and keep rule
  bodies to attribute reads and set lookups (the `:ready-ids` pattern above)
  rather than fresh graph queries.
- **`reset-seen!` clears dedup, not rules.** If a rule is matching but not
  re-notifying, its `[rule strand]` pair is still marked seen. `reset-seen!`
  clears that memory without touching registrations — the right tool when testing
  a rule interactively, the wrong reflex for a genuinely-once notification.

Honest source: `missing-notifier-is-recorded-loudly`, `rule-failures-are-recorded`, and `dedup-and-reset-seen` in [`test/millstrand/chime_test.clj`](../test/millstrand/chime_test.clj); the loud-failure discipline is the same one this repo relies on in [`.millstrand/init.clj`](../.millstrand/init.clj) ("Unbound chime records loud notifier-missing failures").

---

## See also

- [`chime/README.md`](./chime/README.md) — the contract: notifier binding, the
  rule context map, batch-scan and deduplication semantics.
- [`chime.api.md`](./chime.api.md) — generated signatures and docstrings for
  every fn referenced above.
- [`cron.cookbook.md`](./cron.cookbook.md) — the sibling engine's recipes; cron
  and chime share the local-root layout, runtime-owned state, and loud-failure
  discipline.
- [`workflow.cookbook.md`](./workflow.cookbook.md) — the cookbook template these
  recipes follow.
