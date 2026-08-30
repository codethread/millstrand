(ns millstrand.core.weaver.module-publication
  "Prevalidate and publish complete module-owner contributions across kinds.

  Core stores and runtime-owned `registry.alpha` handles use the same
  owner-registry snapshot grammar. This namespace builds every affected
  candidate first, so a malformed entry or collision retains the owner's prior
  contribution in every kind; publication only begins after validation passes.

  Per-entry shape is the kind's own `:entry-spec`. Rules spanning entries —
  cross-owner references, deletion by omission, symbol resolvability — belong to
  a kind's optional `:candidate-validator`, which `validate-kind-candidates!`
  runs over the complete staged candidate before any snapshot is swapped."
  (:require [millstrand.api.registry.alpha :as registry]
            [millstrand.core.weaver.access :as access]
            [millstrand.core.weaver.core-registry :as core-registry]
            [millstrand.core.weaver.owner-registry :as owner-registry]))

(def ^:private registry-state-key
  :millstrand.api.registry.alpha/state)

(defn- core-backend [kind store]
  {:kind kind :type :core :store store :storage (:kernel store)})

(defn- core-backends [runtime]
  {:ops (core-backend :ops (:op-store runtime))
   :queries (core-backend :queries (:query-store runtime))
   :patterns (core-backend :patterns (:pattern-store runtime))
   :hooks (core-backend :hooks (:hook-store runtime))
   :bins (core-backend :bins (:bin-store runtime))
   :events {:kind :events
            :type :core
            :store (get-in runtime [:event-system :handler-store])
            :storage (get-in runtime [:event-system :handler-store :kernel])}})

(defn- domain-backends [runtime]
  (reduce-kv
   (fn [result state-key value]
     (if (registry/registry? value)
       (reduce-kv
        (fn [m kind-id _declaration]
          (when (contains? m kind-id)
            (throw (ex-info "Registry kind is declared by more than one runtime store"
                            {:kind kind-id
                             :spool-state/key state-key
                             :existing (:spool-state/key (get m kind-id))})))
          (assoc m kind-id {:kind kind-id
                            :type :domain
                            :handle value
                            :storage (get value registry-state-key)
                            :spool-state/key state-key}))
        result
        (:kinds (registry/snapshot value)))
       result))
   {}
   @(:spool-state runtime)))

(defn backends
  "Return the runtime's unique kind-id to publication-backend map.

  The six core stores are always present. Each runtime-owned registry handle
  found directly in `:spool-state` contributes its declared open kinds. A kind
  declared by two stores fails loudly before contribution evaluation."
  [runtime]
  (let [core (core-backends runtime)
        domain (domain-backends runtime)
        duplicate (seq (filter (set (keys core)) (keys domain)))]
    (when duplicate
      (throw (ex-info "Registry kind conflicts with a core kind"
                      {:kinds (vec (sort-by pr-str duplicate))})))
    (merge core domain)))

(defn- backend-snapshot [{:keys [type store handle]}]
  (case type
    :core (core-registry/snapshot store)
    :domain (registry/snapshot handle)))

(defn candidates
  "Return one current immutable candidate snapshot per backing store."
  [backends]
  (reduce (fn [result {:keys [storage] :as backend}]
            (if (contains? result storage)
              result
              (assoc result storage (backend-snapshot backend))))
          {}
          (vals backends)))

(defn- without-owner [snapshot owner]
  (owner-registry/normalize
   (update snapshot :partitions
           (fn [partitions]
             (into {}
                   (keep (fn [[kind-id owner-map]]
                           (let [remaining (dissoc owner-map owner)]
                             (when (seq remaining) [kind-id remaining]))))
                   partitions)))))

(defn remove-owner
  "Return candidates with `owner` removed completely from every declared kind."
  [candidate-map owner]
  (update-vals candidate-map #(without-owner % owner)))

(defn- resolve-event-handler-fn!
  "Resolve one module event handler symbol to its captured callable."
  [runtime fn-sym]
  (access/validate-fn-symbol! "Event handler" fn-sym)
  (let [resolved (try
                   (access/with-generation-classloader runtime #(requiring-resolve fn-sym))
                   (catch Throwable throwable
                     (throw (ex-info "Event handler function could not be resolved"
                                     {:fn fn-sym}
                                     throwable))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Event handler symbol must resolve to a callable value"
                      {:fn fn-sym
                       :resolved-class (str (class value))})))
    value))

(defn- realize-event-handlers
  "Return `contribution` with module event handlers bound for dispatch.

  Authoring declarations retain the public `:fn` symbol. The internal event
  registry additionally captures the resolved `:fn-value` at publication, which
  gives dispatch one immutable callable snapshot while public readers continue
  to strip the internal value."
  [runtime contribution]
  (if (contains? contribution :events)
    (update-in contribution [:events :entries]
               (fn [entries]
                 (update-vals entries
                              #(assoc % :fn-value
                                      (resolve-event-handler-fn! runtime (:fn %))))))
    contribution))

(defn stage-owner
  "Validate and stage one owner's complete workspace-layer contribution.

  `contribution` is `{kind-id {:entries {...} :overrides #{...}}}`. Kinds the
  owner previously supplied but now omits are removed. Any undeclared kind,
  invalid entry, same-layer collision, or missing override intent throws while
  leaving the caller's prior candidate map unchanged."
  [runtime backends candidate-map owner contribution]
  (let [contribution (realize-event-handlers runtime contribution)
        unknown (seq (remove (set (keys backends)) (keys contribution)))]
    (when unknown
      (throw (ex-info "Module contribution names undeclared registry kinds"
                      {:module/key owner
                       :kinds (vec (sort-by pr-str unknown))})))
    (reduce-kv
     (fn [result kind-id partition]
       (let [storage (:storage (get backends kind-id))
             snapshot (get result storage)
             candidate (-> snapshot
                           (assoc-in [:partitions kind-id owner]
                                     {:layer :workspace
                                      :entries (:entries partition)
                                      :overrides (:overrides partition)})
                           owner-registry/normalize)]
         (assoc result storage candidate)))
     (remove-owner candidate-map owner)
     contribution)))

(defn- publish-core! [{:keys [store]} snapshot]
  (reset! (:kernel store) snapshot))

(defn- publish-domain! [{:keys [handle]} snapshot]
  ;; `registry.alpha` deliberately publishes one immutable owner-registry
  ;; snapshot atom. The coordinator is the engine-side multi-kind transaction
  ;; boundary and swaps that same handle only after all candidate snapshots have
  ;; validated. Public callers continue to mutate through registry.alpha.
  (reset! (get handle registry-state-key) snapshot))

(defn changed-kinds
  "Return the kind ids whose candidate snapshot differs from the live backend.

  The effect-free counterpart of `publish!`, used by the dry-run plan path to
  diff intentions without swapping any registry snapshot."
  [backends candidate-map]
  (->> backends
       (keep (fn [[kind-id {:keys [storage] :as backend}]]
               (let [before (backend-snapshot backend)
                     after (get candidate-map storage)]
                 (when-not (= (get-in before [:partitions kind-id])
                              (get-in after [:partitions kind-id]))
                   kind-id))))
       (sort-by pr-str)
       vec))

(defn candidate-ops
  "Return the effective op entries in `candidate-map`."
  [backends candidate-map]
  (let [storage (get-in backends [:ops :storage])]
    (vals (owner-registry/effective-values (get candidate-map storage) :ops))))

(defn validate-op-candidates!
  "Validate every effective operation in `candidate-map` before publication."
  [backends candidate-map]
  (let [validate! (requiring-resolve 'millstrand.api.weaver.alpha/validate-op-entry!)]
    (run! validate! (candidate-ops backends candidate-map)))
  candidate-map)

(defn- candidate-owners
  "Return `kind-id`'s entry-key to winning-owner map inside one candidate."
  [candidate kind-id]
  (into {}
        (keep (fn [[entry-key contenders]]
                (when-let [owner (some #(when (:effective? %) (:owner %)) contenders)]
                  [entry-key owner])))
        (get-in candidate [:provenance kind-id] {})))

(defn validate-kind-candidates!
  "Run every declared kind's `:candidate-validator` over the staged candidates.

  A kind declaring one owns cross-entry rules its per-entry spec cannot state,
  so the validator sees the complete effective entry map the publication would
  install — including entries other owners contributed and the absences of
  entries an owner dropped. `:owners` names the winning contributor per entry,
  which is what a rejection needs to say who must repair it. The symbol resolves
  under the runtime's spool classloader, because a synced spool root's namespace
  is only loadable there. A throwing validator aborts the refresh before
  `publish!`, so every owner retains its previous live partition."
  [runtime backends candidate-map]
  (doseq [[kind-id {:keys [storage]}] (sort-by (comp pr-str key) backends)
          :let [candidate (get candidate-map storage)
                validator (get-in candidate [:kinds kind-id :candidate-validator])]
          :when validator]
    (let [validate! (access/with-generation-classloader
                      runtime #(requiring-resolve validator))]
      (when-not validate!
        (throw (ex-info "Registry kind candidate validator cannot be resolved"
                        {:kind kind-id :candidate-validator validator})))
      (validate! {:runtime runtime
                  :kind kind-id
                  :entries (owner-registry/effective-values candidate kind-id)
                  :owners (candidate-owners candidate kind-id)})))
  candidate-map)

(defn validate-op-glossary-refs!
  "Validate candidate operation glossary refs after generation reconciliation."
  [runtime backends candidate-map]
  (let [validate! (requiring-resolve
                   'millstrand.api.weaver.alpha/validate-op-glossary-refs!)]
    (run! #(validate! runtime %) (candidate-ops backends candidate-map)))
  candidate-map)

(defn publish!
  "Publish all changed candidate snapshots and return changed kind ids.

  Callers must build candidates solely through `remove-owner`/`stage-owner`,
  which normalize before this function runs. Each kind's readers observe one
  immutable before-or-after snapshot; no event lane is stopped or cleared.

  The four-argument arity commits isolated domain handles from a staging
  runtime while preserving existing live handle identities."
  ([backends candidate-map]
   (let [changed (changed-kinds backends candidate-map)]
     (reduce
      (fn [published {:keys [storage] :as backend}]
        (if (contains? published storage)
          published
          (let [before (backend-snapshot backend)
                after (get candidate-map storage)]
            (if (= before after)
              (conj published storage)
              (do
                (case (:type backend)
                  :core (publish-core! backend after)
                  :domain (publish-domain! backend after))
                (conj published storage))))))
      #{}
      (vals backends))
     changed))
  ([runtime staged-runtime backends candidate-map]
   (let [changed (changed-kinds backends candidate-map)]
     (doseq [[state-key staged-handle] @(:spool-state staged-runtime)
             :when (registry/registry? staged-handle)
             :let [current (get @(:spool-state runtime) state-key)]
             :when current]
       (when-not (registry/registry? current)
         (throw (ex-info "Staged registry target changed before publication"
                         {:spool-state/key state-key :value current}))))
     (doseq [{:keys [type store storage]} (vals backends)
             :when (= :core type)
             :let [snapshot (get candidate-map storage)]]
       (reset! (:kernel store) snapshot))
     (doseq [[state-key staged-handle] @(:spool-state staged-runtime)
             :when (registry/registry? staged-handle)
             :let [staged-storage (get staged-handle registry-state-key)
                   snapshot (get candidate-map staged-storage)
                   current (get @(:spool-state runtime) state-key)
                   target (or current staged-handle)]]
       (when-not current
         (swap! (:spool-state runtime) assoc state-key target))
       (reset! (get target registry-state-key) snapshot))
     changed)))
