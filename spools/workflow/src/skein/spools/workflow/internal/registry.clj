(ns skein.spools.workflow.internal.registry
  "Owner-partitioned registries for the workflow spool.

  Workflow definitions and executors are replaceable declarations published
  owner-complete through `skein.api.registry.alpha`: a complete owner partition
  replaces the owner's prior contribution, so a route or executor a refresh
  omits disappears by omission with no global reload (DELTA-OlrDrt-001.CC2/CC4).
  The handle is a single runtime-owned `registry.alpha` value held directly in
  `spool-state` — never nested — so the refresh kernel discovers its kinds
  alongside the core registries.

  Binding time follows DELTA-OlrDrt-001.CC10. Definition entries are qualified
  symbols resolved at each named route transition, so devflow's live route
  re-pointing takes effect on the next transition while a run already between
  stages keeps the value it started with. Executor entries are qualified symbols
  resolved to a function value at each gate evaluation; that resolution is the
  per-evaluation snapshot.

  A raw executor predicate *function value* carries no symbol — the unavoidable
  direct/REPL case (review note `vovp1` finding 3). Rather than store a function
  object as owner-partition declaration data (DELTA-OlrDrt-001.CC8, D1), those
  values live in a separate runtime-owned resource map; the declarative kind
  stays symbols-only."
  (:require [clojure.spec.alpha :as s]
            [skein.api.registry.alpha :as registry]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [fail!]]))

(def definition-kind
  "Kind id for the workflow-name -> definition-symbol registry.

  The id still reads `constructor` because the kind is the same one legacy
  constructor entries have always used: a registered symbol now resolves to
  either a static definition map or a legacy constructor fn, and the resolved
  value decides which (PROP-Wcd-001.S13)."
  :skein.spools.workflow/constructor)

(def constructor-kind
  "Deprecated alias for `definition-kind`, retained so in-flight spool
  contributions naming the old var keep publishing to the same kind."
  definition-kind)

(def executor-kind
  "Kind id for the gate-waiter -> stall-predicate-symbol registry."
  :skein.spools.workflow/executor)

(def ^:private repl-owner
  "Owner keyword for direct/REPL registrations, published in the `:direct` layer
  (DELTA-OlrDrt-001.CC3), matching the core registries' `:skein.owner/repl`."
  :skein.owner/repl)

;; Entry specs the kinds validate against. Definitions and executors are both
;; fully qualified symbols; a raw function value never reaches the kind.
(s/def ::definition-symbol qualified-symbol?)
(s/def ::executor-symbol qualified-symbol?)

(def ^:private registry-state-version
  "Shape version for the workflow registry handle. Bump when the declared kinds
  change: spool-state survives refresh, so a version mismatch reinitializes
  rather than reuse a stale handle."
  2)

(defn- new-registry-handle []
  (doto (registry/registry)
    (registry/declare-kind!
     {:id definition-kind
      :entry-spec ::definition-symbol
      :binding-moment :route-transition
      ;; Whether an entry is publishable depends on the rest of the candidate:
      ;; a checkpoint route or call target names another registered workflow,
      ;; and an owner can delete that target by omitting it. Only the complete
      ;; staged candidate can answer that, so the check runs there.
      :candidate-validator
      'skein.spools.workflow.internal.definitions/validate-candidates!})
    (registry/declare-kind! {:id executor-kind
                             :entry-spec ::executor-symbol
                             :binding-moment :gate-evaluation})))

(defn registry-handle
  "Return `rt`'s workflow registry handle, materializing it on first use.

  The handle is a direct `spool-state` value so the refresh kernel discovers its
  constructor and executor kinds. Realizing it also declares the kinds, so a
  module contribution naming them finds them already declared."
  [rt]
  (runtime/spool-state rt ::registry
                       {:version registry-state-version}
                       new-registry-handle))

(def ^:private executor-fns-version
  "Shape version for the raw executor-function resource map. Bump when
  `new-executor-fns` changes shape."
  1)

(defn- new-executor-fns []
  {:executor-fns (atom {})})

(defn- executor-fns-state [rt]
  (runtime/spool-state rt ::executor-fns
                       {:version executor-fns-version}
                       new-executor-fns))

(defn executor-fns
  "Return `rt`'s raw executor-function map atom (waiter string -> predicate).

  This holds direct/REPL predicates supplied as bare function values, which
  cannot be owner-partition declaration data. It is runtime-owned resource
  state (DELTA-OlrDrt-001.CC8)."
  [rt]
  (:executor-fns (executor-fns-state rt)))

(defn- waiter-key [waiter]
  (name waiter))

(defn- direct-partition [handle kind-id key value]
  ;; Read-modify-write the direct/REPL owner partition, keeping the owner's
  ;; other live entries and restating override intent for every key (safe even
  ;; when a key shadows nothing).
  (let [entries (assoc (get-in (registry/snapshot handle)
                               [:partitions kind-id repl-owner :entries]
                               {})
                       key value)]
    {:layer :direct :entries entries :overrides (set (keys entries))}))

(defn register-definition!
  "Register definition symbol `sym` under keyword `name` at the direct layer.

  Replaces any prior direct entry under `name`; other direct definitions are
  retained. Returns `name`."
  [rt name sym]
  (let [handle (registry-handle rt)]
    (registry/replace-owner! handle definition-kind repl-owner
                             (direct-partition handle definition-kind name sym))
    name))

(defn direct-definitions
  "Return the direct/REPL layer's own workflow name -> definition symbol map.

  Only this layer is a trusted coordinator's to remove: a name published by a
  module owner disappears by omitting its contribution, never by an ad hoc
  unregister."
  [rt]
  (get-in (registry/snapshot (registry-handle rt))
          [:partitions definition-kind repl-owner :entries]
          {}))

(defn unregister-definition!
  "Remove the direct/REPL entry for `name`, returning the remaining direct map.

  Owner-complete publication expresses removal by omission, which a trusted
  coordinator working at the REPL has no way to state; this is that removal.
  A name with no direct entry fails loudly (TEN-003) rather than reporting a
  removal that never happened."
  [rt name]
  (let [handle (registry-handle rt)
        entries (direct-definitions rt)]
    (when-not (contains? entries name)
      (fail! "Workflow name has no direct registration to remove"
             {:name name :direct (vec (keys entries))}))
    (let [remaining (dissoc entries name)]
      (registry/replace-owner! handle definition-kind repl-owner
                               {:layer :direct :entries remaining
                                :overrides (set (keys remaining))})
      remaining)))

(defn register-executor-symbol!
  "Register executor predicate symbol `sym` for `waiter` at the direct layer.

  Replaces any prior direct entry for `waiter`; other direct executors are
  retained. Also drops any raw function value previously held for `waiter` so
  the two sources never disagree. Returns the waiter as a keyword."
  [rt waiter sym]
  (let [handle (registry-handle rt)
        key (waiter-key waiter)]
    (swap! (executor-fns rt) dissoc key)
    (registry/replace-owner! handle executor-kind repl-owner
                             (direct-partition handle executor-kind key sym))
    (keyword key)))

(defn register-executor-fn!
  "Register a raw executor predicate function value for `waiter`.

  The value is runtime-owned resource state, not owner-partition declaration
  data. Also drops any direct symbol previously held for `waiter`. Returns the
  waiter as a keyword."
  [rt waiter pred]
  (let [handle (registry-handle rt)
        key (waiter-key waiter)
        entries (dissoc (get-in (registry/snapshot handle)
                                [:partitions executor-kind repl-owner :entries]
                                {})
                        key)]
    (registry/replace-owner! handle executor-kind repl-owner
                             {:layer :direct :entries entries
                              :overrides (set (keys entries))})
    (swap! (executor-fns rt) assoc key pred)
    (keyword key)))

(defn workflow-definitions
  "Return the effective workflow name (keyword) -> definition symbol map."
  [rt]
  (registry/effective (registry-handle rt) definition-kind))

(defn workflow-definition
  "Return the effective definition symbol registered under keyword `name`,
  failing loudly (TEN-003) when `name` is not registered.

  The lookup reads the current effective snapshot, so a re-pointed route
  resolves the replacement at this named transition (DELTA-OlrDrt-001.CC10)."
  [rt name]
  (let [registry (workflow-definitions rt)]
    (or (get registry name)
        (fail! "Unknown registered workflow"
               {:reason :workflow/definition-unregistered
                :name name :registered (vec (keys registry))}))))

(defn definition-owner
  "Return the owner keyword whose partition currently supplies `name`, or nil.

  Repairing an unresolvable entry means editing whichever owner declared it, so
  a structured failure names that owner rather than only the symbol."
  [rt name]
  (get-in (registry/explain (registry-handle rt) definition-kind)
          [name :effective :owner]))

(defn executor-for
  "Return the stall predicate for a ready gate's `waiter`, or nil.

  A raw direct/REPL function value wins; otherwise the effective executor symbol
  is resolved to its current Var, the per-gate-evaluation snapshot
  (DELTA-OlrDrt-001.CC10)."
  [rt waiter]
  (let [key (waiter-key waiter)]
    (or (get @(executor-fns rt) key)
        (when-let [sym (get (registry/effective (registry-handle rt) executor-kind) key)]
          (requiring-resolve sym)))))

(defn executor-map
  "Return the merged waiter-string -> predicate map of every effective executor.

  Effective symbols resolve to their current Var; raw function values shadow a
  same-waiter symbol."
  [rt]
  (merge (into {}
               (map (fn [[key sym]] [key (requiring-resolve sym)]))
               (registry/effective (registry-handle rt) executor-kind))
         @(executor-fns rt)))
