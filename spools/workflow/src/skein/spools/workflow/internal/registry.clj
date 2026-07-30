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
  stages keeps the value it started with. An executor entry is either a bare
  stall-predicate symbol or a declaration map (`:stalled?` plus an optional
  `:request-spec` naming the executor's registered gate-request spec); the
  stall symbol is resolved to a function value at each gate evaluation, and
  that resolution is the per-evaluation snapshot.

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

  A registered symbol resolves to a static definition map."
  :skein.spools.workflow/definition)

(def executor-kind
  "Kind id for the gate-waiter -> stall-predicate-symbol registry."
  :skein.spools.workflow/executor)

(def ^:private repl-owner
  "Owner keyword for direct/REPL registrations, published in the `:direct` layer
  (DELTA-OlrDrt-001.CC3), matching the core registries' `:skein.owner/repl`."
  :skein.owner/repl)

;; Entry specs the kinds validate against. Definitions are fully qualified
;; symbols; an executor entry is a stall-predicate symbol or a declaration map,
;; and a raw function value never reaches the kind.
(s/def ::definition-symbol qualified-symbol?)
(s/def ::executor-symbol qualified-symbol?)
(s/def ::stalled? qualified-symbol?)
(s/def ::request-spec qualified-keyword?)

(defn- executor-decl-keys?
  "True when a declaration map carries only the declared executor keys."
  [decl]
  (every? #{:stalled? :request-spec} (keys decl)))

(s/def ::executor-decl
  (s/and (s/keys :req-un [::stalled?] :opt-un [::request-spec])
         executor-decl-keys?))

(s/def ::executor-entry
  (s/or :symbol ::executor-symbol
        :decl ::executor-decl))

(defn- new-registry-handle []
  (doto (registry/registry)
    (registry/declare-kind!
     {:id definition-kind
      :entry-spec ::definition-symbol
      :binding-moment :route-transition
      :candidate-validator
      'skein.spools.workflow.internal.definitions/validate-candidates!})
    (registry/declare-kind! {:id executor-kind
                             :entry-spec ::executor-entry
                             :binding-moment :gate-evaluation})))

(defn registry-handle
  "Return `rt`'s workflow registry handle, materializing it on first use.

  The handle is a direct `spool-state` value so the refresh kernel discovers its
  definition and executor kinds. The workflow module's collected kind
  declarations populate it before dependent contributions stage; direct/REPL
  callers get the same declarations when they materialize a bare handle."
  [rt]
  (runtime/spool-state rt ::registry new-registry-handle))

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

(defn register-executor-entry!
  "Register executor `entry` (symbol or declaration map) for `waiter` at the
  direct layer.

  Replaces any prior direct entry for `waiter`; other direct executors are
  retained. Also drops any raw function value previously held for `waiter` so
  the two sources never disagree. Returns the waiter as a keyword."
  [rt waiter entry]
  (let [handle (registry-handle rt)
        key (waiter-key waiter)]
    (swap! (executor-fns rt) dissoc key)
    (registry/replace-owner! handle executor-kind repl-owner
                             (direct-partition handle executor-kind key entry))
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

(defn entry-stalled-symbol
  "Return the stall-predicate symbol a declared executor `entry` names.

  An entry is a bare qualified symbol or a declaration map carrying
  `:stalled?`."
  [entry]
  (if (map? entry) (:stalled? entry) entry))

(defn entry-request-spec
  "Return the registered gate-request spec name `entry` declares, or nil.

  Only a declaration-map entry can carry `:request-spec`; a bare symbol
  declares no request contract."
  [entry]
  (when (map? entry) (:request-spec entry)))

(defn- resolve-stalled!
  "Resolve a declared executor `entry`'s stall symbol to its current Var.

  A declaration whose symbol no longer names a Var fails loudly with the
  waiter, symbol, and declaring owner (TEN-003) rather than reading as an
  absent executor and leaving its gates waiting with no diagnostic."
  [rt key entry]
  (let [sym (entry-stalled-symbol entry)]
    (or (requiring-resolve sym)
        (fail! "Registered executor stall symbol does not resolve"
               {:reason :workflow/executor-unresolved
                :waiter key
                :stalled? sym
                :owner (get-in (registry/explain (registry-handle rt) executor-kind)
                               [key :effective :owner])}))))

(defn executor-for
  "Return the stall predicate for a ready gate's `waiter`, or nil.

  A raw direct/REPL function value wins; otherwise the effective entry's stall
  symbol is resolved to its current Var, the per-gate-evaluation snapshot
  (DELTA-OlrDrt-001.CC10). A declared symbol that no longer resolves fails
  loudly rather than reading as an absent executor."
  [rt waiter]
  (let [key (waiter-key waiter)]
    (or (get @(executor-fns rt) key)
        (when-let [entry (get (registry/effective (registry-handle rt) executor-kind) key)]
          (resolve-stalled! rt key entry)))))

(defn executor-map
  "Return the merged waiter-string -> predicate map of every effective executor.

  Effective entries resolve their stall symbol to its current Var, failing
  loudly on a symbol that no longer resolves; raw function values shadow a
  same-waiter declaration."
  [rt]
  (merge (into {}
               (map (fn [[key entry]] [key (resolve-stalled! rt key entry)]))
               (registry/effective (registry-handle rt) executor-kind))
         @(executor-fns rt)))

(defn executor-entries
  "Return the discovery view of every registered executor, keyed by waiter.

  Effective declaration entries carry their stall symbol and any declared
  `:request-spec`; a raw direct/REPL function value appears with a nil symbol,
  because a bare function carries no declaration to report."
  [rt]
  (merge (into {}
               (map (fn [[key entry]]
                      [key {:stalled? (entry-stalled-symbol entry)
                            :request-spec (entry-request-spec entry)}]))
               (registry/effective (registry-handle rt) executor-kind))
         (into {}
               (map (fn [[key _]] [key {:stalled? nil :request-spec nil}]))
               @(executor-fns rt))))
