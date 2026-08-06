(ns millstrand.spools.workflow.internal.guard
  "The per-run mutation guard for the workflow spool.

  Every run-mutating op resolves its ready frontier and then writes based on what
  it found. Those two moments must not be separable, or two workers acting on one
  run can both resolve the same ready checkpoint and both close it. The guard
  makes each op's resolve-then-write one critical section per run id, so a
  concurrent second caller re-resolves against the frontier the first one left
  and fails loudly on a step that is no longer ready instead of writing over it.

  The lock is runtime-owned spool state (SPEC-004.C8a), so it is scoped to one
  weaver's in-process callers — the same scope as the ambient runtime the ops
  resolve. It is reentrant, so an op that delegates to another (`advance!` to
  `complete!`) does not deadlock on itself. Locks are retained for the runtime's
  lifetime: one small object per run id a process has mutated, kept because
  reclaiming an entry another thread is about to acquire would let two callers
  into the same critical section."
  (:require [millstrand.api.runtime.alpha :as runtime])
  (:import [java.util.concurrent.locks ReentrantLock]))

(def ^:private guard-state-version
  "Shape version for the guard's runtime state. Bump when `new-guards` changes
  shape: spool-state survives refresh, so a stale value must reinitialize."
  1)

(defn- new-guards []
  {:locks (atom {})})

(defn- locks [rt]
  (:locks (runtime/spool-state rt ::guards {:version guard-state-version} new-guards)))

(defn- run-lock
  ^ReentrantLock [rt run-id]
  (let [held (locks rt)]
    (get (swap! held (fn [current]
                       (if (contains? current run-id)
                         current
                         (assoc current run-id (ReentrantLock.)))))
         run-id)))

(defn with-run!
  "Call `f` holding `run-id`'s mutation guard on `rt`, returning its result.

  Callers resolve the ready frontier *inside* `f`, never before it: resolving
  outside the guard is what the guard exists to prevent."
  [rt run-id f]
  (let [lock (run-lock rt run-id)]
    (.lock lock)
    (try
      (f)
      (finally
        (.unlock lock)))))
