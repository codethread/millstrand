# Skein Cron Spool — Cookbook

Composition recipes for `skein.spools.cron`: how to put spool-owned work on a
durable cadence, and why each shape is the right one.

This is the how/why half of the cron docs. The other two halves are:

- [`cron/README.md`](./cron/README.md) — the contract: job shape, durable
  cadence, at-least-once delivery, and status/failure guarantees.
- [`cron.api.md`](./cron.api.md) — the generated reference: every public fn's
  signature, arity, and docstring, produced from source.

Signatures and argument lists live in the generated API doc. Narrative and
composition live here and in the contract.

## How to read a recipe

Every recipe has four parts:

1. **Situation** — the problem shape.
2. **Composition** — which pieces combine, and how.
3. **Snippet** — a complete form, assuming
   `(require '[skein.spools.cron :as cron])`.
4. **Why this shape** — the tradeoff behind the recipe.

The core idea: cron owns recurrence, your job owns the work. Cron stores a
durable scheduler wake for each registered job. When that wake is delivered,
cron persists the next wake and offloads the job body. The scheduler remains the
single timing view.

---

## Recipe: Register a job that fires on an interval

**Situation.** You have periodic work — a report, cleanup, or scan — that should
run every so often for the lifetime of the weaver. You also want spread so many
weavers or many jobs do not fire on the same tick.

**Composition.** Call `register!` with `:interval-ms`, optional `:jitter-ms`, and a `:handler` symbol resolving to `(fn [runtime] ..)`. This is the direct trusted-Clojure seam. Module sources should use the `defjob` recipe below so owner-complete publication and omission drive the job lifecycle.

```clojure
(ns my.jobs
  (:require [skein.spools.cron :as cron]
            [skein.api.current.alpha :as current]))

(defn emit-report
  "cron :handler — one tick of the periodic work. Its return value is recorded as
  the job's :last-result; a throw lands in recent-failures and never stops the
  cadence."
  [runtime]
  (current/with-runtime runtime
    ;; ... do the periodic work ...
    {:outcome :reported}))

;; from startup config, once the cron module is active:
(cron/register! (current/runtime)
  {:id          :nightly-report
   :interval-ms (* 24 60 60 1000)   ; base period between fires
   :jitter-ms   (* 60 60 1000)      ; each fire offset uniformly in [-1h, +1h]
   :handler     'my.jobs/emit-report})
```

**Why this shape.**

- **`:handler` is a symbol, not a function value.** Cron resolves it at fire time,
  so a reload can update the namespace and the next fire sees the current var.
- **Jitter spreads load.** Many weavers firing the same expensive job on the same
  tick can stampede. `:jitter-ms` spreads each fire uniformly across ±jitter.
- **Re-registering unchanged config preserves the pending wake.** Startup config
  can run again on reload without resetting the countdown. If interval, jitter,
  or `:handler` changes, cron replaces the wake so the new cadence takes effect from
  now.

Honest source: the job shape in [`cron/README.md`](./cron/README.md) and
`register-persists-wake-lists-and-unregisters` /
`fires-records-result-and-continues-cadence` in
[`test/skein/cron_test.clj`](../test/skein/cron_test.clj).

---

## Recipe: Give the job its own startup module, out of test-loaded config

**Situation.** Your job's first act is a real side effect, such as a `gh` call or
network read. Your main config file also loads under test, and you do not want
that test to touch the real world.

**Composition.** Give the job a dedicated startup-file module. Cron's module declares the open jobs kind and the scheduling lifecycle effect but contributes no jobs. The job file holds the `:handler` plus the `defjob` declaration. `init.clj` wires it as its own module.

```clojure
;; report_job.clj (ns report-job) — the job's handler and registration live together.
(defn report-tick [runtime]
  ;; ... do the work ...
  {:outcome :reported})

(cron/defjob :nightly-report
  {:interval-ms (* 24 60 60 1000)
   :jitter-ms   (* 60 60 1000)
   :handler     'report-job/report-tick})

;; init.clj — cron owns the registry kind before the job contributes.
(runtime/module! runtime :skein/spools-cron
  {:ns 'skein.spools.cron :spools ['skein.spools/cron]
   :required? true})
(runtime/module! runtime :report-job
  {:file "report_job.clj" :after [:skein/spools-cron]
   :required? true})
```

**Why this shape.**

- **The main config test stops before job scheduling.** Keeping the `defjob` declaration in a separate module leaves broad config tests free to load shared helpers without publishing the job or running Cron's lifecycle effect.
- **Behavior and cadence stay together.** One file holds what the job does and
  when it runs.
- **Reloads keep cadence.** Republishing the same declaration leaves the managed job untouched. Changing its cadence or handler replaces its wake; omitting it cancels the wake.

Honest source: this repo's [`.skein/nvd_scan.clj`](../.skein/nvd_scan.clj), wired
as `:nvd-scan` in [`.skein/init.clj`](../.skein/init.clj).

---

## Recipe: Coordinate many weavers with a best-effort lock, and raise a card

**Situation.** The same job runs on every maintainer's weaver. The work is
expensive enough that you want to avoid common double-runs, but a rare duplicate
is harmless. When the job finds something, a human needs a durable to-do.

**Composition.** Use shared external state as a best-effort lock: check for an
open marker at the top of the tick and skip if another weaver holds it. Otherwise
create the marker, do the work, and release it in a `finally`. Lean on jitter so
weavers rarely reach the check together. On a finding, call kanban `add!` before
any later external call.

```clojure
(defn run-scan!
  "One tick with every side effect injected, so the flow is unit-testable.
  :run-cmd runs the external tool + lock calls; :raise-card! files a kanban card."
  [{:keys [run-cmd raise-card!]}]
  (cond
    (open-lock-held? run-cmd)
    {:outcome :skipped-locked}

    :else
    (let [lock (acquire-lock! run-cmd)]
      (try
        (let [findings (do-the-work run-cmd)]
          (when (seq findings)
            (raise-card! {:title "Scan: findings" :body (report findings)}))
          {:outcome :scanned :findings findings})
        (finally
          (release-lock! run-cmd lock))))))

(defn scan-tick [runtime]
  (run-scan!
    {:run-cmd run-command
     :raise-card! (fn [card]
                    ((requiring-resolve 'ct.spools.kanban/add!)
                     runtime (:title card)
                     {"--body" (:body card) "--priority" "p1"}))}))
```

**Why this shape.**

- **Best-effort is the honest promise.** Cron delivery is at-least-once, and
  external locks can race. The job must tolerate a duplicate tick.
- **Release in a `finally`.** A failed scan should not leave a stale marker that
  blocks future work.
- **The card is the alert of record.** Filing the card before comments or cleanup
  means a later `gh` failure cannot swallow the finding.
- **A real error still fails loudly.** Missing credentials or command failures
  throw; cron records them in `recent-failures` and keeps the cadence.

Honest source: this repo's `:nvd-scan` job in
[`.skein/nvd_scan.clj`](../.skein/nvd_scan.clj) and its lock/finding tests in
[`test/skein/nvd_scan_test.clj`](../test/skein/nvd_scan_test.clj).

---

## Recipe: Test an offloaded cron job without sleeps

**Situation.** A test advances a manual clock and expects a cron job's side
effect. The scheduler event lane can quiesce before the offloaded job body
finishes, so `test-alpha/await-quiescent!` alone is not enough.

**Composition.** Use the deterministic join sequence: advance the manual clock,
wait for the event lane, then wait for cron's execution executor to go idle.

```clojure
(require '[skein.spools.cron :as cron]
         '[skein.test.alpha :as test-alpha])

(test-alpha/advance! runtime java.time.Duration/ofMinutes 10)
(test-alpha/await-quiescent! runtime)
(cron/await-quiescent! runtime)

;; Now assert the job's side effect or cron status.
```

**Why this shape.**

- **Wake delivery and job completion are separate.** `fire-wake` persists the
  next wake and offloads the job body, then returns. The event lane can be idle
  while the job is still running.
- **`await-quiescent!` counts jobs before offload.** Once the event lane has
  quiesced, any job submitted by a delivered wake is already in cron's in-flight
  latch.
- **No sleeps or wall waits.** Manual clock advancement releases the scheduler wake, and cron's await uses that same runtime clock for its timeout and polling.

Honest source: `fires-records-result-and-continues-cadence`, failure cases in
[`test/skein/cron_test.clj`](../test/skein/cron_test.clj), and the restart/lane
checks in [`test/skein/cron_e2e_test.clj`](../test/skein/cron_e2e_test.clj).

---

## Recipe: See job status and recorded failures

**Situation.** A scheduled job is quiet and you want to know whether it has
fired, what it last returned, and whether anything threw.

**Composition.** Read cron for job status and failures. Read scheduler pending
wakes for next-fire timing.

```clojure
(cron/jobs runtime)
;; => [{:id :nvd-scan :interval-ms 518400000 :jitter-ms 3600000
;;      :handler nvd-scan/nvd-scan-tick :last-result {...}}]

(skein.api.scheduler.alpha/pending runtime)
;; => includes {:key "cron/nvd-scan" :wake-at "..." ...}

(cron/recent-failures runtime)
;; => [{:kind :run :job :nvd-scan :message "..." :at "..."}]

(cron/unregister! runtime :nvd-scan)
```

**Why this shape.**

- **Scheduler is the timing surface.** Cron's listing is a job-status projection,
  not a second source of next-fire truth.
- **A throw is recorded, never fatal.** A job whose `:handler` throws stays
  scheduled and records the error in `recent-failures` and on its status.
- **State is runtime-owned.** Two runtimes in one JVM keep independent executors,
  job tables, and failure logs.

Honest source: the status and failure shapes in [`cron/README.md`](./cron/README.md)
and `records-run-failure-without-stopping-cadence` in
[`test/skein/cron_test.clj`](../test/skein/cron_test.clj).

---

## See also

- [`cron/README.md`](./cron/README.md) — the contract: job shape, cadence,
  delivery, status, and failure semantics.
- [`cron.api.md`](./cron.api.md) — generated signatures and docstrings for every
  fn referenced above.
- [`chime.cookbook.md`](./chime.cookbook.md) — the sibling engine's recipes; cron
  and chime share the local-root layout, runtime-owned state, and loud-failure
  discipline.
- [`workflow.cookbook.md`](./workflow.cookbook.md) — the cookbook template these
  recipes follow.
