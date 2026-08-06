(ns millstrand.core.weaver.core-registry
  "Owner-partition backing for the six core weaver registries.

  CLI ops, named queries, weave patterns, lifecycle hooks, event handlers, and
  executable declarations each become one kind declared on an `owner-registry`
  kernel. Every declaration
  is owned by a stable keyword owner in a fixed layer (`:defaults < :spools <
  :workspace < :direct`); replacing an owner partition is complete, so an
  omitted key disappears, and cross-owner collisions or missing override intent
  fail before the atom changes.

  A `store` is `{:kind <kind-id> :kernel <owner-registry-atom>}`. Readers derive
  one immutable effective map from the kernel snapshot they capture; there is no
  second projection atom to keep in sync.

  Direct per-entry writes carry an explicit owner. The system owner contributes
  in the defaults layer and every other direct owner contributes in the direct
  layer. Owner-complete replacement and removal are the sharp tools the module
  contribution path reuses without forking these semantics."
  (:require [clojure.spec.alpha :as s]
            [millstrand.core.weaver.owner-registry :as owner-registry]))

;; Permissive entry specs: the API layer runs each registry's rich validator
;; before an entry reaches the kernel, so the kind spec only pins the coarse
;; stored shape a projection reader depends on.
(s/def ::op-entry map?)
(s/def ::query-entry (s/or :where-vector vector? :detailed map?))
(s/def ::pattern-entry map?)
(s/def ::hook-entry map?)
(s/def ::event-entry map?)
(s/def ::bin-entry map?)

(def ^:private kind-declarations
  "Declaration datum per core kind: registered entry spec and binding moment
  (DELTA-OlrDrt-001.CC10). The binding moment is retained declaration data;
  this slice does not yet dispatch on it."
  {:ops {:entry-spec ::op-entry :binding-moment :op/invocation}
   :queries {:entry-spec ::query-entry :binding-moment :query/use}
   :patterns {:entry-spec ::pattern-entry :binding-moment :pattern/invocation}
   :hooks {:entry-spec ::hook-entry :binding-moment :hook/dispatch-start}
   :events {:entry-spec ::event-entry :binding-moment :event/dispatch-start}
   :bins {:entry-spec ::bin-entry :binding-moment :bin/invocation}})

(def system-owner
  "Owner keyword for Millstrand-shipped defaults such as the built-in help op."
  :millstrand.owner/system)

(def system-layer
  "Layer the system owner contributes in; the lowest fixed precedence layer."
  :defaults)

(def repl-owner
  "Conventional owner for direct per-entry registration from the REPL."
  :millstrand.owner/repl)

(def repl-layer
  "Layer direct/REPL registration contributes in; the highest fixed layer."
  :direct)

(defn- declare-core-kind! [kernel kind-id]
  (let [{:keys [entry-spec binding-moment]} (kind-declarations kind-id)]
    (owner-registry/declare-kind! kernel
                                  {:id kind-id
                                   :entry-spec entry-spec
                                   :binding-moment binding-moment
                                   :layer-policy owner-registry/layer-precedence})))

(defn backed-registry
  "Create an owner-partition store for declared core `kind-id`."
  [kind-id]
  (when-not (contains? kind-declarations kind-id)
    (throw (ex-info "Unknown core registry kind" {:kind kind-id})))
  (let [kernel (owner-registry/registry)]
    (declare-core-kind! kernel kind-id)
    {:kind kind-id :kernel kernel}))

(defn effective
  "Return one immutable effective snapshot: `{entry-key -> entry-value}`."
  [store]
  (owner-registry/effective-values (owner-registry/snapshot (:kernel store))
                                   (:kind store)))

(defn snapshot
  "Return the store kernel's immutable owner-partition snapshot."
  [store]
  (owner-registry/snapshot (:kernel store)))

(defn- explain-entry
  "Split one entry-key's ordered contenders (low-to-high) into its effective
  winner and the lower partitions it shadows, mapping `value-fn` over every
  contender's stored value so a caller can strip function objects before the
  diagnostic leaves the registry."
  [contenders value-fn]
  (let [sanitized (mapv #(update % :value value-fn) contenders)]
    {:effective (some #(when (:effective? %) %) sanitized)
     :shadowed (filterv (complement :effective?) sanitized)
     :contenders sanitized}))

(defn explain
  "Return owner/provenance diagnostics for the store's kind as plain data.

  Maps each effective entry key to `{:effective <winning contender> :shadowed
  [<lower contenders>] :contenders [<all, low-to-high>]}`. Each contender names
  its `:owner`, `:layer`, `:value`, and `:override?`/`:effective?` flags, so a
  caller sees which owner supplies each entry and which lower-layer partitions
  it shadows. `value-fn` (default `identity`) is applied to every contender's
  stored value; a kind whose entries hold function objects passes a sanitizer
  that strips them, so neither a resolved function value nor an internal
  registry handle leaks into the diagnostic (DELTA-OlrDrt-001.CC9)."
  ([store] (explain store identity))
  ([store value-fn]
   (update-vals (get-in (snapshot store) [:provenance (:kind store)] {})
                #(explain-entry % value-fn))))

(defn- owner-partition [kernel kind owner]
  (get-in (owner-registry/snapshot kernel) [:partitions kind owner]))

(defn- direct-layer [owner]
  (if (= system-owner owner) system-layer repl-layer))

(defn- competing-owner?
  [snapshot kind owner key]
  (some (fn [[other-owner partition]]
          (and (not= owner other-owner)
               (contains? (:entries partition) key)))
        (get-in snapshot [:partitions kind])))

(defn put-entry!
  "Add or replace `key`→`value` in explicit `owner`'s direct partition.

  Other owners and the owner's other keys are untouched. Existing override
  intents are preserved; a new cross-owner collision fails loudly."
  [{:keys [kind kernel]} owner key value]
  (let [existing (owner-partition kernel kind owner)
        entries (assoc (:entries existing {}) key value)]
    (owner-registry/replace-owner! kernel kind owner
                                   {:layer (:layer existing (direct-layer owner))
                                    :entries entries
                                    :overrides (:overrides existing #{})})
    value))

(defn replace-entry!
  "Explicitly replace effective `key` with `value` under direct `owner`.

  When another owner currently supplies `key`, this records the required
  override intent. Replacing the owner's own key records no stale override."
  [{:keys [kind kernel]} owner key value]
  (let [snapshot (owner-registry/snapshot kernel)
        existing (get-in snapshot [:partitions kind owner])
        overrides (cond-> (:overrides existing #{})
                    (competing-owner? snapshot kind owner key) (conj key)
                    (not (competing-owner? snapshot kind owner key)) (disj key))]
    (owner-registry/replace-owner! kernel kind owner
                                   {:layer (:layer existing (direct-layer owner))
                                    :entries (assoc (:entries existing {}) key value)
                                    :overrides overrides})
    value))

(defn remove-entry!
  "Remove `key` from explicit `owner`'s direct partition.

  Removing the owner's last key removes the owner partition. Removing an absent
  key is an idempotent no-op."
  [{:keys [kind kernel]} owner key]
  (let [existing (owner-partition kernel kind owner)
        entries (dissoc (:entries existing {}) key)]
    (if (seq entries)
      (owner-registry/replace-owner! kernel kind owner
                                     {:layer (:layer existing (direct-layer owner))
                                      :entries entries
                                      :overrides (disj (:overrides existing #{}) key)})
      (owner-registry/remove-owner! kernel kind owner))))

(defn replace-owner!
  "Replace `owner`'s complete partition in `store`.

  `partition` is `{:layer <layer> :entries {<key> <value> ...} :overrides #{<key>
  ...}}`; keys the owner omits disappear. A same-layer duplicate or missing
  cross-layer override intent throws before the kernel changes."
  [store owner partition]
  (owner-registry/replace-owner! (:kernel store) (:kind store) owner partition))

(defn remove-owner!
  "Remove `owner`'s partition from `store`. Unrelated owners are untouched."
  [store owner]
  (owner-registry/remove-owner! (:kernel store) (:kind store) owner))
