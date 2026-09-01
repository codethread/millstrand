(ns millstrand.core.weaver.module-refresh
  "Internal live module refresh coordinator.

  Full refresh collects and validates the layered startup graph before runtime
  mutation. Targeted refresh uses the active graph and includes dependents.
  Source/contribution failures retain the affected owner's prior declarations;
  changed contributions prevalidate across all registered kinds before
  publication; resource reconcilers run afterward with explicit degradation."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.core.db :as db]
            [millstrand.core.format :as format]
            [millstrand.core.weaver.lifecycle-effects :as lifecycle-effects]
            [millstrand.core.weaver.module-graph :as module-graph]
            [millstrand.core.weaver.module-publication :as publication]
            [millstrand.core.weaver.module-refresh.entry-points :as entry-points]))

(def plan-caveat
  "The one honest side effect a dry-run plan still incurs (DELTA-OlrRepl-001.CC14)."
  (format/reflow
   "|Collection may evaluate module source code. No registry publication,
    |resource reconcile, or coordinator state write runs."))

(def ^:private declaration-record-key
  ::declaration-record)

(def ^:private declaration-record-version 2)

(def ^:private registry-state-key
  :millstrand.api.registry.alpha/state)

(def ^:dynamic *declaration-record-snapshots*
  nil)

(declare fail!)

(s/def ::initial-state
  (s/and map?
         #(= #{:graph :layers :shadows :startup/files :contributions
               :contribution-sources :lifecycle :resources :outcomes
               :last-refresh}
             (set (keys %)))
         #(every? map? ((juxt :graph :layers :shadows :contributions
                              :contribution-sources :lifecycle :resources
                              :outcomes) %))
         #(vector? (:startup/files %))
         #(nil? (:last-refresh %))))

(defn initial-state
  "Return the empty runtime-owned module coordinator state."
  []
  (let [state {:graph (sorted-map)
               :layers (sorted-map)
               :shadows (sorted-map)
               :startup/files []
               :contributions (sorted-map)
               :contribution-sources (sorted-map)
               :lifecycle (sorted-map)
               :resources (sorted-map)
               :outcomes (sorted-map)
               :last-refresh nil}]
    (when-not (s/valid? ::initial-state state)
      (fail! "Initial module coordinator state has an invalid shape"
             {:state state :explain (s/explain-data ::initial-state state)}))
    state))

(defn with-startup-file
  "Call `f` with startup-file declaration provenance dynamically bound."
  [startup-file f]
  (module-graph/with-startup-file startup-file f))

(defn collect-entry!
  "Collect one authoring-form entry for the module source being evaluated."
  ([kind-id entry-key value]
   (module-graph/collect-entry! kind-id entry-key value))
  ([kind-id entry-key value opts]
   (module-graph/collect-entry! kind-id entry-key value opts)))

(defn collect-lifecycle!
  "Collect one lifecycle declaration for the module source being evaluated."
  [effect-id declaration]
  (module-graph/collect-lifecycle! effect-id declaration))

(defn- reject-public-spool!
  "Fail when a module namespace exposes the withdrawn public `spool` var."
  [module-key module-ns]
  (when (and module-ns
             (find-ns module-ns)
             (some-> (ns-publics module-ns)
                     (get 'spool)
                     meta
                     (contains? :millstrand.api.authoring.alpha/declaration)
                     not))
    (fail! (format/reflow
            "|Module namespace exposes the removed public spool entry point.
             |Delete `def spool`; publish registry entries with millstrand/defop,
             |millstrand/defquery, millstrand/defpattern, millstrand/defhook, or
             |millstrand/defhandler, and declare live effects with the lifecycle
             |authoring forms.")
           {:reason :removed-def-spool
            :module/key module-key
            :module/namespace module-ns})))

(defn- reload-without-stale-spool!
  "Run `load!` after removing a legacy public `spool` Var.

  Clojure reload leaves Vars that disappeared from source interned. Removing the
  old Var before evaluation distinguishes a migrated module from source that
  still authors the withdrawn convention. Restore the old Var if loading fails,
  so a refused refresh does not mutate the live namespace."
  [module-ns load!]
  (let [namespace (some-> module-ns find-ns)
        stale-var (some-> namespace ns-publics (get 'spool))
        stale-meta (some-> stale-var meta)
        stale-bound? (and stale-var (bound? stale-var))
        stale-value (when stale-bound? @stale-var)]
    (when stale-var
      (ns-unmap namespace 'spool))
    (try
      (load!)
      (catch Throwable throwable
        (when stale-var
          (let [restored (if stale-bound?
                           (intern namespace 'spool stale-value)
                           (intern namespace 'spool))]
            (alter-meta! restored merge stale-meta)))
        (throw throwable)))))

(defn- staging-runtime
  "Return `runtime` with isolated copies of every domain registry handle."
  [runtime]
  (assoc runtime :spool-state
         (atom
          (update-vals
           @(:spool-state runtime)
           (fn [value]
             (if (registry/registry? value)
               (assoc value registry-state-key
                      (atom (registry/snapshot value)))
               value))))))

(defn- restore-staged-registry-slots!
  "Restore registry slots affected by staged publication."
  [runtime before staged-runtime]
  (let [keys-to-restore (for [[state-key value] @(:spool-state staged-runtime)
                              :when (registry/registry? value)]
                          state-key)]
    (swap! (:spool-state runtime)
           (fn [current]
             (reduce (fn [state state-key]
                       (if (contains? before state-key)
                         (assoc state state-key (get before state-key))
                         (dissoc state state-key)))
                     current
                     keys-to-restore)))))

(defn- retain-declarations!
  "Replace `ns-sym`'s complete replay record."
  [module-key ns-sym contribution kind-declarations lifecycle-declarations]
  (let [namespace (find-ns ns-sym)]
    (when-not namespace
      (fail! "Cannot retain declarations for an unloaded module namespace"
             {:reason :namespace-not-loaded
              :module/key module-key
              :ns ns-sym}))
    (when *declaration-record-snapshots*
      (swap! *declaration-record-snapshots*
             #(if (contains? % ns-sym)
                %
                (assoc % ns-sym
                       (get (meta namespace) declaration-record-key)))))
    (alter-meta! namespace assoc declaration-record-key
                 {:version declaration-record-version
                  :module-key module-key
                  :namespace ns-sym
                  :contribution contribution
                  :kind-declarations kind-declarations
                  :lifecycle lifecycle-declarations})
    {:contribution contribution
     :kind-declarations kind-declarations
     :lifecycle lifecycle-declarations}))

(defn- restore-declaration-records!
  "Restore declaration metadata changed during a refused refresh."
  [snapshots]
  (doseq [[ns-sym record] snapshots
          :let [namespace (find-ns ns-sym)]
          :when namespace]
    (if record
      (alter-meta! namespace assoc declaration-record-key record)
      (alter-meta! namespace dissoc declaration-record-key))))

(defn- replay-declarations
  "Return the retained declaration record for loaded `ns-sym`."
  [module-key ns-sym]
  (let [namespace (find-ns ns-sym)
        record (some-> namespace meta declaration-record-key)]
    (when-not namespace
      (fail! "Image module namespace is not loaded"
             {:reason :namespace-not-loaded
              :module/key module-key
              :ns ns-sym
              :load :image}))
    (when-not record
      (fail! "Image module has no retained authoring declaration record"
             {:reason :missing-declaration-record
              :module/key module-key
              :ns ns-sym
              :load :image}))
    (when-not (= declaration-record-version (:version record))
      (fail! "Image module authoring declaration record is stale"
             {:reason :stale-declaration-record
              :module/key module-key
              :ns ns-sym
              :load :image
              :record/version (:version record)
              :expected/version declaration-record-version}))
    (when-not (= ns-sym (:namespace record))
      (fail! "Image module authoring declaration record names another namespace"
             {:reason :foreign-declaration-record
              :module/key module-key
              :ns ns-sym
              :record/namespace (:namespace record)}))
    (when-not (= module-key (:module-key record))
      (fail! "Image module authoring declaration record belongs to another module"
             {:reason :foreign-declaration-record
              :module/key module-key
              :ns ns-sym
              :record/module-key (:module-key record)}))
    (select-keys record [:contribution :kind-declarations :lifecycle])))

(defn- informative-throwable
  "Return the deepest structured cause beneath compiler and loader wrappers."
  [throwable]
  (or (last (filter ex-data
                    (take-while some? (iterate ex-cause throwable))))
      throwable))

(defn- exception-data [^Throwable throwable]
  (let [causes (vec (take-while some? (iterate ex-cause throwable)))
        informative (informative-throwable throwable)
        suppressed (mapv exception-data (.getSuppressed throwable))]
    (cond-> {:message (ex-message informative)
             :class (str (class informative))
             :data (when (some ex-data causes)
                     (reduce (fn [data cause]
                               (merge data (ex-data cause)))
                             {}
                             (reverse causes)))}
      (seq suppressed) (assoc :suppressed suppressed))))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- normalize-kind-contribution [kind-id value]
  (when-not (keyword? kind-id)
    (fail! "Module contribution kind must be a keyword"
           {:kind kind-id}))
  (when-not (map? value)
    (fail! "Module contribution entries must be a map"
           {:kind kind-id :value value}))
  (let [partition? (or (contains? value :entries)
                       (contains? value :overrides))
        unknown (when partition?
                  (seq (remove #{:entries :overrides} (keys value))))
        entries (if partition? (:entries value) value)
        overrides (if partition? (or (:overrides value) #{}) #{})]
    (when unknown
      (fail! "Module contribution partition contains unknown keys"
             {:kind kind-id :unknown (vec (sort-by pr-str unknown))}))
    (when-not (map? entries)
      (fail! "Module contribution :entries must be a map"
             {:kind kind-id :entries entries}))
    (when-not (set? overrides)
      (fail! "Module contribution :overrides must be a set"
             {:kind kind-id :overrides overrides}))
    (when-let [orphan (seq (remove (set (keys entries)) overrides))]
      (fail! "Module contribution overrides absent entries"
             {:kind kind-id :keys (vec (sort-by pr-str orphan))}))
    {:entries entries :overrides overrides}))

(defn- normalize-contribution [value]
  (when-not (map? value)
    (fail! "Collected module contribution must be a map"
           {:contribution value}))
  (into (sorted-map)
        (map (fn [[kind-id entries]]
               [kind-id (normalize-kind-contribution kind-id entries)]))
        value))

(defn- dependency-problem [outcomes declaration]
  (some (fn [dependency]
          (let [outcome (get outcomes dependency)]
            (when-not (#{:ready :applied :unchanged} (:status outcome))
              {:reason :missing-dependency
               :dependency dependency
               :dependency/outcome outcome})))
        (:after declaration)))

(defn- retained-outcome [key declaration problem]
  (merge {:module/key key
          :required? (:required? declaration)
          :status (cond
                    (= :hard-conflict (:reason problem)) :refused
                    (:required? declaration) :failed
                    :else :skipped)
          :contribution/status :retained}
         problem))

(defn- namespace-resource-name [ns-sym]
  (str (str/replace (munge (str ns-sym)) "." "/") ".clj"))

(defn- ns-source-file
  "Return the file-backed generation resource for `ns-sym`, or nil."
  [ns-sym]
  (when-let [resource (io/resource (namespace-resource-name ns-sym))]
    (when (= "file" (.getProtocol resource))
      (-> resource .toURI io/file .getCanonicalPath))))

(defn- source-stamp [file]
  (when file
    (let [source (io/file file)
          digest (java.security.MessageDigest/getInstance "SHA-256")]
      {:file (.getCanonicalPath source)
       :sha256 (format "%064x"
                       (java.math.BigInteger.
                        1 (.digest digest
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath source)))))})))

(defn- module-file [runtime relative-path]
  (.getCanonicalPath (io/file (:source-config-dir runtime) relative-path)))

;; Keep this evaluated/deferred traversal policy aligned with
;; quality.spool-var/declaration-sites. Runtime evaluation owns the authoritative
;; failure; the repository scanner remains a structural pre-merge guard.
(def ^:private deferred-reader-forms
  '#{comment defn defmacro fn fn* quote var})

(defn- namespaces-in-form
  "Return namespace symbols declared in evaluated positions of one reader form."
  [form]
  (cond
    (and (seq? form)
         (contains? #{'ns 'clojure.core/ns} (first form))
         (symbol? (second form)))
    [(second form)]

    (seq? form)
    (if (contains? deferred-reader-forms (first form))
      []
      (mapcat namespaces-in-form (rest form)))

    (coll? form)
    (mapcat namespaces-in-form form)

    :else []))

(defn- declared-file-namespaces
  "Return every namespace form evaluated while loading `file`."
  [file]
  (let [rdr (reader-types/indexing-push-back-reader (slurp file) 1 file)
        opts {:eof ::eof :read-cond :allow :features #{:clj}}]
    (binding [reader/*read-eval* false
              ;; Namespace aliases declared by the file take effect only as
              ;; load-file evaluates its leading ns form. This structural
              ;; pre-scan needs only to read later ::alias/kw forms, not resolve
              ;; their runtime identity.
              reader/*alias-map* (fn [alias]
                                   (symbol (str "module-refresh." alias)))
              reader/*default-data-reader-fn* (fn [_tag _value]
                                                ::tagged-literal)]
      (loop [namespaces []]
        (let [form (reader/read opts rdr)]
          (if (= ::eof form)
            namespaces
            (recur (into namespaces (namespaces-in-form form)))))))))

(defn- declared-file-namespace [key file]
  (let [namespaces (vec (distinct (declared-file-namespaces file)))]
    (when (< 1 (count namespaces))
      (fail! (format/reflow
              "|Module :file source declares more than one namespace; declaration
               |collection requires one unambiguous owner")
             {:reason :multiple-module-namespaces
              :module/key key
              :file file
              :namespaces namespaces}))
    (first namespaces)))

(defn- collection-context [runtime key declaration]
  (if-let [ns-sym (:ns declaration)]
    {:module/key key
     :source/file (ns-source-file ns-sym)
     :source/namespace ns-sym}
    (let [file (module-file runtime (:file declaration))]
      {:module/key key
       :source/file file
       :source/namespace (declared-file-namespace key file)})))

(defn- load-module-file! [module-ns file result]
  (reload-without-stale-spool!
   module-ns
   (fn []
     (load-file file)
     result)))

(defn- load-source!
  [runtime with-loader declaration context previous-source]
  (if-let [ns-sym (:ns declaration)]
    (let [file (:source/file context)
          stamp (source-stamp file)]
      (if (and (find-ns ns-sym) (= previous-source stamp))
        {:ns ns-sym}
        (with-loader
          #(if file
             (load-module-file! ns-sym file
                                {:ns ns-sym
                                 :file file
                                 :collection/reload? (boolean (find-ns ns-sym))})
             (do
               (reload-without-stale-spool!
                ns-sym
                (fn [] (require ns-sym :reload)))
               {:ns ns-sym})))))
    (let [file (module-file runtime (:file declaration))]
      (with-loader #(load-module-file!
                     (:source/namespace context) file {:file file})))))

(def ^:private image-contribution-remedy
  "The actionable remedy for image-mode declaration replay failures."
  (format/reflow
   "|To load or require the module namespace into the JVM image, refresh it once
    |in source mode so authoring forms retain their declaration record."))

(defn- evaluate-image-module
  "Evaluate a `:load :image` module: trust the already-loaded JVM image for its
  `:ns` target with no source load and no contribution-collection scope. Entry
  contribution replays from the namespace's retained authoring declaration
  record. The outcome carries `:source/status :image` and no source stamp."
  [key declaration]
  (let [ns-sym (:ns declaration)]
    (when-not (find-ns ns-sym)
      (fail! image-contribution-remedy
             {:module/key key :ns ns-sym :load :image
              :reason :namespace-not-loaded}))
    (try
      (let [_ (reject-public-spool! key ns-sym)
            replay (replay-declarations key ns-sym)]
        {:status :ready
         :module/key key
         :module/namespace ns-sym
         :source/status :image
         :declaration/source :image-replay
         :kind-declarations (:kind-declarations replay [])
         :lifecycle (:lifecycle replay {})
         :contribution (normalize-contribution (:contribution replay))})
      (catch clojure.lang.ExceptionInfo throwable
        (if (= :removed-def-spool (:reason (ex-data throwable)))
          (throw throwable)
          (throw (ex-info image-contribution-remedy (ex-data throwable))))))))

(defn- evaluate-module
  [runtime with-loader key declaration previous-contribution previous-source]
  (try
    (if (= :image (:load declaration))
      (evaluate-image-module key declaration)
      (let [context (collection-context runtime key declaration)
            {:keys [return kind-declarations lifecycle] collected :contribution}
            (module-graph/with-contribution-collection
              context
              #(load-source!
                runtime with-loader declaration context previous-source))
            source-status (if (and (:ns declaration) (nil? (:file return)))
                            :unchanged
                            :loaded)
            module-ns (entry-points/module-namespace declaration context)
            _ (reject-public-spool! key module-ns)
            kind-declarations
            (if (= :unchanged source-status)
              (try
                (:kind-declarations (replay-declarations key module-ns))
                (catch clojure.lang.ExceptionInfo throwable
                  (if (= :missing-declaration-record
                         (:reason (ex-data throwable)))
                    kind-declarations
                    (throw throwable))))
              kind-declarations)
            lifecycle
            (if (= :unchanged source-status)
              (try
                (:lifecycle (replay-declarations key module-ns))
                (catch clojure.lang.ExceptionInfo throwable
                  (if (= :missing-declaration-record
                         (:reason (ex-data throwable)))
                    lifecycle
                    (throw throwable))))
              lifecycle)
            contribution (cond
                           (and (= :unchanged source-status)
                                (some? previous-contribution))
                           previous-contribution

                           :else collected)
            normalized (normalize-contribution contribution)
            _ (lifecycle-effects/validate! lifecycle)]
        {:status :ready
         :module/key key
         :module/namespace module-ns
         :source/status source-status
         :source/result return
         :source/stamp (when-let [ns-sym (:ns declaration)]
                         (source-stamp (ns-source-file ns-sym)))
         :declaration/source :source-collection
         :kind-declarations kind-declarations
         :lifecycle lifecycle
         :contribution normalized}))
    (catch Throwable throwable
      {:status :failed
       :module/key key
       :source/status :failed
       :contribution/status :retained
       :error (exception-data throwable)})))

(defn- evaluate-affected
  [runtime with-loader graph order previous-contributions previous-sources]
  (let [unaffected (set/difference (set (keys graph)) (set order))
        seeded (into (sorted-map)
                     (map (fn [key] [key {:status :unchanged}]))
                     unaffected)]
    (select-keys
     (reduce
      (fn [outcomes key]
        (let [declaration (get graph key)
              problem (dependency-problem outcomes declaration)]
          (assoc outcomes key
                 (if problem
                   (retained-outcome key declaration problem)
                   (evaluate-module runtime with-loader key declaration
                                    (get previous-contributions key)
                                    (get previous-sources key))))))
      seeded
      order)
     order)))

(defn- realize-kind-declarations!
  "Realize every collected open-kind registry before backend discovery."
  [runtime raw]
  (doseq [[module-key outcome] raw
          {:keys [spool-state/key declaration]} (:kind-declarations outcome)]
    (let [handle (or (get @(:spool-state runtime) key)
                     (let [created (registry/registry)]
                       (get (swap! (:spool-state runtime)
                                   #(if (contains? % key) % (assoc % key created)))
                            key)))]
      (when-not (registry/registry? handle)
        (fail! "Open-kind declaration spool-state slot is not a registry handle"
               {:module/key module-key
                :spool-state/key key
                :kind (:id declaration)
                :value handle}))
      (registry/declare-kind! handle declaration))))

(defn- require-kind-declarations-staged!
  "Fail the whole boundary when a kind provider's contribution cannot stage."
  [raw outcomes]
  (doseq [[module-key raw-outcome] raw
          :when (seq (:kind-declarations raw-outcome))
          :let [outcome (get outcomes module-key)]
          :when (= :failed (:status outcome))]
    (fail! "Open-kind provider contribution failed to stage"
           {:module/key module-key
            :kind/declarations (:kind-declarations raw-outcome)
            :error (:error outcome)})))

(defn- previous-module [state key]
  {:module/declaration (get-in state [:graph key])
   :module/contribution (get-in state [:contributions key])
   :module/outcome (get-in state [:outcomes key])
   :module/resource (get-in state [:resources key])})

(defn- store-source-stamp [result key raw-outcome]
  (if-let [stamp (:source/stamp raw-outcome)]
    (assoc-in result [:source-stamps key] stamp)
    (update result :source-stamps dissoc key)))

(defn- publishable-outcome [raw-outcome]
  (dissoc raw-outcome
          :module/namespace
          :contribution
          :source/stamp
          :module/reconcile-fn
          :module/resolved))

(defn- stage-publications
  [runtime backends candidate-map graph order raw previous-contributions previous-sources]
  (let [unaffected (set/difference (set (keys graph)) (set order))
        seeded (into (sorted-map)
                     (map (fn [key] [key {:status :unchanged}]))
                     unaffected)
        staged
        (reduce
         (fn [{:keys [candidates outcomes] :as result} key]
           (let [declaration (get graph key)
                 raw-outcome (get raw key)
                 dependency (dependency-problem outcomes declaration)]
             (cond
               dependency
               (assoc-in result [:outcomes key]
                         (retained-outcome key declaration dependency))

               (not= :ready (:status raw-outcome))
               (assoc-in result [:outcomes key] raw-outcome)

               (= (get previous-contributions key) (:contribution raw-outcome))
               (-> result
                   (store-source-stamp key raw-outcome)
                   (assoc-in [:outcomes key]
                             (-> raw-outcome
                                 publishable-outcome
                                 (assoc :status :unchanged
                                        :contribution/status :unchanged))))

               :else
               (try
                 (let [next-candidates (publication/stage-owner
                                        runtime backends candidates key
                                        (:contribution raw-outcome))]
                   (-> result
                       (assoc :candidates next-candidates)
                       (store-source-stamp key raw-outcome)
                       (assoc-in [:contributions key] (:contribution raw-outcome))
                       (assoc-in [:outcomes key]
                                 (-> raw-outcome
                                     publishable-outcome
                                     (assoc :status :applied
                                            :contribution/status :replaced)))))
                 (catch Throwable throwable
                   (assoc-in result [:outcomes key]
                             (-> raw-outcome
                                 publishable-outcome
                                 (assoc :status :failed
                                        :contribution/status :retained
                                        :error (exception-data throwable)))))))))
         {:candidates candidate-map
          :outcomes seeded
          :contributions previous-contributions
          :source-stamps previous-sources}
         order)]
    (update staged :outcomes #(select-keys % order))))

(defn- retainable-staged-declarations?
  [raw-outcome outcome]
  (and (#{:applied :unchanged} (:status outcome))
       (= :ready (:status raw-outcome))
       (= :loaded (:source/status raw-outcome))))

(defn- retain-staged-declarations!
  "Retain declaration records only for modules whose candidates staged.

  Source evaluation is intentionally earlier than owner staging. A staged
  failure or dependency retention therefore leaves the namespace's previous
  record untouched, while the outer refresh snapshot still rolls back records
  if a later coordinator-wide validation or publication step refuses."
  [raw outcomes]
  (doseq [[module-key raw-outcome] raw
          :let [outcome (get outcomes module-key)]
          :when (retainable-staged-declarations? raw-outcome outcome)]
    (retain-declarations!
     module-key
     (:module/namespace raw-outcome)
     (:contribution raw-outcome)
     (:kind-declarations raw-outcome)
     (:lifecycle raw-outcome))))

(defn- provisional-result
  [mode shadows outcomes removed]
  {:status :unchanged
   :mode mode
   :modules (into (sorted-map)
                  (concat (map (fn [key]
                                 [key {:module/key key
                                       :status :removed
                                       :contribution/status :removed}])
                               removed)
                          outcomes))
   :residuals []
   :conflicts []
   :remedies []
   :declaration/shadows shadows})

(defn- lifecycle-symbols
  [declarations]
  (into #{}
        (comp (mapcat vals)
              (filter qualified-symbol?))
        (vals declarations)))

(defn- resolve-lifecycle-callables!
  "Resolve every staged lifecycle callable before contribution publication."
  [with-loader raw]
  (into {}
        (keep
         (fn [[module-key outcome]]
           (when (and (= :ready (:status outcome))
                      (seq (:lifecycle outcome)))
             [module-key
              (with-loader
                #(into {}
                       (map (fn [callable]
                              (let [resolved-var (requiring-resolve callable)
                                    resolved (some-> resolved-var deref)]
                                (when-not (fn? resolved)
                                  (fail! "Lifecycle callable does not resolve to a function"
                                         {:module/key module-key
                                          :effect/callable callable
                                          :effect/phase :resolve
                                          :resolved/value resolved
                                          :resolved/type (some-> resolved class .getName)}))
                                [callable resolved])))
                       (lifecycle-symbols (:lifecycle outcome))))])))
        raw))

(defn- reconcile-lifecycle
  [runtime state graph raw result changed-kinds order resolvers]
  (reduce
   (fn [{:keys [outcomes lifecycle-state]} module-key]
     (let [outcome (get outcomes module-key)
           previous (get lifecycle-state module-key)
           declarations (if (= :removed (:status outcome))
                          {}
                          (get-in raw [module-key :lifecycle]))
           executable? (and (#{:applied :removed :unchanged} (:status outcome))
                            (or (seq declarations)
                                (seq (:effects previous))))]
       (if-not executable?
         {:outcomes outcomes :lifecycle-state lifecycle-state}
         (let [execution
               (lifecycle-effects/refresh
                {:runtime runtime
                 :module-key module-key
                 :resolver (get resolvers module-key {})
                 :state (or previous {})
                 :declarations (or declarations {})
                 :changed-kinds changed-kinds
                 :context
                 {:module/declaration (get graph module-key)
                  :module/previous (previous-module state module-key)
                  :module/previous-contribution
                  (get-in state [:contributions module-key])
                  :module/contribution outcome}
                 :published? true})
               projected-outcomes (:outcomes execution)
               module-status (:status execution)
               lifecycle-changed?
               (some seq
                     (vals (select-keys (:plan execution)
                                        [:apply :retry :replace :reconcile :remove])))
               next-state (:state execution)]
           {:outcomes
            (assoc outcomes module-key
                   (cond-> (assoc outcome
                                  :lifecycle/status module-status
                                  :lifecycle/outcomes projected-outcomes
                                  :lifecycle/plan (:plan execution))
                     (= :degraded module-status) (assoc :status :degraded)
                     (and lifecycle-changed?
                          (= :applied module-status)
                          (= :unchanged (:status outcome)))
                     (assoc :status :applied)))
            :lifecycle-state
            #_{:clj-kondo/ignore [:type-mismatch]}
            (if (seq (:effects next-state))
              (assoc lifecycle-state module-key next-state)
              (dissoc lifecycle-state module-key))}))))
   {:outcomes (:modules result)
    :lifecycle-state (:lifecycle state)}
   order))

(defn- top-status [outcomes changed-kinds]
  (let [module-values (vals outcomes)
        failures (filter #(#{:failed :degraded :refused} (:status %))
                         module-values)
        changed? (or (seq changed-kinds)
                     (some #(#{:applied :removed :degraded} (:status %))
                           module-values))]
    (cond
      (and (seq failures)
           (every? #(= :refused (:status %)) module-values)
           (not changed?)) :refused
      (seq failures) :partial
      changed? :applied
      :else :unchanged)))

(defn- loaded-namespaces []
  (->> (all-ns) (map ns-name) (sort-by str) vec))

(defn- diagnostic!
  "Append one probe diagnostic through the caller-owned sink."
  [opts stage status data]
  (when-let [report! (:diagnostic! opts)]
    (report! {:stage stage :status status :data data})))

(defn- projection-value [value]
  (let [projection
        (cond
          (fn? value) {"callable" true "class" (.getName (class value))}
          (or (nil? value) (string? value) (number? value) (boolean? value)) value
          (or (keyword? value) (symbol? value)) (db/json-key value)
          (map? value) (reduce-kv (fn [projection key nested]
                                    (let [json-key (db/json-key key)]
                                      (when (contains? projection json-key)
                                        (fail! "Registry projection map keys collide after JSON canonicalization"
                                               {:key key :canonical-key json-key}))
                                      (assoc projection json-key (projection-value nested))))
                                  (sorted-map)
                                  value)
          (vector? value) (mapv projection-value value)
          (set? value) (->> value (map projection-value) (sort-by pr-str) vec)
          (sequential? value) (mapv projection-value value)
          :else (fail! "Registry projection contains a value that cannot cross the status boundary"
                       {:value value :class (str (class value))}))]
    (when-not (s/valid? :millstrand.registry-projection/value projection)
      (fail! "Registry projection contains an invalid JSON value"
             {:value projection
              :explain (s/explain-data :millstrand.registry-projection/value
                                       projection)}))
    projection))

(defn- candidate-projection
  "Return candidate registry data with executable values redacted.

  Candidate snapshots are immutable plain data except for captured event
  callables. The probe reports the complete registry projection while ensuring
  diagnostics never expose a function object."
  [backends candidates]
  (let [projection
        (into (sorted-map)
              (map (fn [[kind-id {:keys [storage]}]]
                     (let [candidate (get candidates storage)]
                       (when-not (and (some? candidate)
                                      (every? #(map? (get candidate %))
                                              [:effective :owners :provenance]))
                         (fail! "Registry candidate is incomplete for status projection"
                                {:kind kind-id
                                 :storage storage
                                 :candidate candidate
                                 :required [:effective :owners :provenance]}))
                       [(db/json-key kind-id)
                        (projection-value (select-keys candidate
                                                       [:effective :owners :provenance]))])))
              backends)]
    (when-not (s/valid? :millstrand.registry-projection/registry projection)
      (fail! "Registry projection has an invalid status shape"
             {:projection projection
              :explain (s/explain-data :millstrand.registry-projection/registry
                                       projection)}))
    projection))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- registry-projection
  "Return a redacted effective registry projection for runtime diagnostics.

  The projection is immutable plain data: executable values are represented by
  their callable class, so a status response can carry an honest generation
  baseline without exposing functions or registry implementation state."
  [runtime]
  (let [backends (publication/backends runtime)
        projection (candidate-projection backends (publication/candidates backends))]
    (when-not (s/valid? :millstrand.registry-projection/registry projection)
      (fail! "Registry projection has an invalid status shape"
             {:projection projection
              :explain (s/explain-data :millstrand.registry-projection/registry projection)}))
    projection))

(defn- semantic-diff
  "Describe the top-level registry changes between two projections."
  [baseline candidate]
  (let [added (apply dissoc candidate (keys baseline))
        removed (apply dissoc baseline (keys candidate))
        changed (into (sorted-map)
                      (keep (fn [kind-id]
                              (let [before (get baseline kind-id)
                                    after (get candidate kind-id)]
                                (when-not (= before after)
                                  [kind-id {:old before :new after}]))))
                      (sort (set/intersection (set (keys baseline))
                                              (set (keys candidate)))))]
    {:old baseline
     :new candidate
     :changes {:added added :removed removed :changed changed}
     :changed? (boolean (or (seq added) (seq removed) (seq changed)))}))

(defn- plan-result
  "Assemble the dry-run intentions from staged candidates without publishing.

  Diffs the staged candidate snapshots against the live backends, classifies
  loaded code, and returns a refresh-result-shaped map flagged `:dry-run?` with
  the honest caveat. No registry publication, resource reconcile, or coordinator
  state write occurs; source loads during collection already happened."
  [state staged provisional backends raw]
  (let [changed-kinds (publication/changed-kinds backends (:candidates staged))
        outcomes
        (reduce-kv
         (fn [planned module-key outcome]
           (let [declarations (:lifecycle outcome)
                 retained (get-in state [:lifecycle module-key])]
             (if (or (seq declarations) (seq (:effects retained)))
               (assoc-in planned [module-key :lifecycle/plan]
                         (lifecycle-effects/plan
                          (or retained {}) (or declarations {}) changed-kinds))
               planned)))
         (:modules provisional)
         raw)
        status (top-status outcomes changed-kinds)
        lifecycle-plan (into (sorted-map)
                             (keep (fn [[module-key outcome]]
                                     (when-let [plan (:lifecycle/plan outcome)]
                                       [module-key plan])))
                             outcomes)]
    (assoc provisional
           :status status
           :modules outcomes
           :dry-run? true
           :caveat plan-caveat
           :publication/kinds (vec (sort-by pr-str changed-kinds))
           :candidate-registries (candidate-projection backends (:candidates staged))
           :lifecycle/plan lifecycle-plan)))

(defn- record-result!
  [runtime collection contributions contribution-sources resources lifecycle-state
   outcomes result]
  (swap! (:module-state runtime)
         (fn [state]
           (-> state
               (assoc :graph (:graph collection)
                      :layers (:layers collection)
                      :shadows (:shadows collection)
                      :startup/files (mapv #(dissoc % :return)
                                           (:files collection))
                      :contributions (into (sorted-map) contributions)
                      :contribution-sources (into (sorted-map) contribution-sources)
                      :resources (into (sorted-map) resources)
                      :lifecycle (into (sorted-map) lifecycle-state)
                      :outcomes (into (sorted-map) outcomes)
                      :last-refresh result))))
  result)

(defn- select-refresh
  [runtime load-startup-files! opts]
  (let [state @(:module-state runtime)]
    (cond
      (:declare opts)
      (let [[key declaration] (:declare opts)
            declaration (module-graph/normalize-declaration key declaration)
            graph (assoc (:graph state) key declaration)
            ;; Only the declaration being authored is normalized. The graph it
            ;; joins was normalized when it was collected.
            order (module-graph/dependency-order graph)]
        {:mode :targeted
         :collection (assoc (select-keys state [:layers :shadows :startup/files])
                            :files (:startup/files state)
                            :graph graph
                            :order order)
         :selected #{key}})

      (contains? opts :only)
      (let [selected (:only opts)]
        (when-not (and (coll? selected) (seq selected) (every? keyword? selected))
          (fail! "Targeted refresh :only must be a non-empty collection of module keys"
                 {:only selected}))
        (let [selected (set selected)
              unknown (set/difference selected (set (keys (:graph state))))]
          (when (seq unknown)
            (fail! "Targeted refresh names unknown module keys"
                   {:unknown (vec (sort-by pr-str unknown))}))
          {:mode :targeted
           :collection (assoc (select-keys state [:graph :layers :shadows])
                              :files (:startup/files state)
                              :order (module-graph/dependency-order (:graph state)))
           :selected selected}))

      :else
      {:mode :full
       :collection (module-graph/collect-modules load-startup-files!)})))

(defn refresh!
  "Collect or select modules and reconcile the live runtime.

  `context` supplies `:load-startup-files!` and `:with-loader` callbacks owned
  by the daemon runtime namespace. `opts` is empty for full refresh, carries
  `:only` for targeted refresh, or internal `:declare` for module declaration
  outside startup collection. `:dry-run? true` runs collection, source-load,
  and staging without publishing, reconciling, or recording coordinator state
  (SPEC-003.C26b).

  Validation failures leave the live world untouched."
  [runtime {:keys [load-startup-files! with-loader]} opts]
  ;; The runtime slot is one dedicated Object monitor. Splint cannot see the
  ;; stable object behind the map lookup; refreshes serialize so two collectors
  ;; never publish interleaved desired graphs.
  #_{:clj-kondo/ignore [:locking-suspicious-lock]
     :splint/disable [lint/locking-object]}
  (locking (:module-refresh-lock runtime)
    (let [selection (select-refresh runtime load-startup-files! opts)
          {:keys [mode collection selected]} selection
          state @(:module-state runtime)
          old-graph (:graph state)
          graph (:graph collection)
          removed (if (= :full mode)
                    (set/difference (set (keys old-graph)) (set (keys graph)))
                    #{})
          selected (or selected (set (keys graph)))
          order (module-graph/affected-modules graph selected)
          record-snapshots (atom {})]
      (binding [*declaration-record-snapshots* record-snapshots]
        (try
          (let [previous-contributions (:contributions state)
                previous-sources (:contribution-sources state)
                raw (evaluate-affected runtime with-loader graph order
                                       previous-contributions previous-sources)
                lifecycle-resolvers
                (resolve-lifecycle-callables! with-loader raw)
                _ (diagnostic!
                   opts :module/evaluate
                   (if (some #(= :failed (:status %)) (vals raw))
                     :failed
                     :completed)
                   {:modules (select-keys raw order)
                    :lifecycle/callables (keys lifecycle-resolvers)})
                staged-runtime (staging-runtime runtime)
                _ (realize-kind-declarations! staged-runtime raw)
                backends (publication/backends staged-runtime)
                base-candidates (reduce publication/remove-owner
                                        (publication/candidates backends)
                                        removed)
                staged (stage-publications runtime backends base-candidates graph order raw
                                           previous-contributions previous-sources)
                _ (require-kind-declarations-staged! raw (:outcomes staged))
                candidate-projection
                (candidate-projection backends (:candidates staged))
                old-generation (:old-generation/baseline opts)
                _ (when (and (:probe? opts)
                             (not (s/valid? :millstrand.weaver-start/old-generation-baseline
                                            old-generation)))
                    (fail! "Fresh probe requires an admitted old-generation baseline"
                           {:baseline old-generation
                            :explain (s/explain-data
                                      :millstrand.weaver-start/old-generation-baseline
                                      old-generation)}))
                _ (diagnostic!
                   opts :candidate/staged :completed
                   {:candidate-registries candidate-projection
                    :old-generation/diff
                    (assoc (semantic-diff (:projection old-generation)
                                          candidate-projection)
                           :baseline-status (:status old-generation))})
                _ (publication/validate-op-candidates! backends (:candidates staged))
                _ (publication/validate-kind-candidates!
                   runtime backends (:candidates staged))
                _ (diagnostic! opts :candidate/validate :completed
                               {:candidate-registries candidate-projection})
                provisional (provisional-result mode (:shadows collection)
                                                (:outcomes staged)
                                                removed)]
                ;; A dry-run stops here: it has collected, classified, staged and
                ;; validated candidates but publishes nothing, reconciles nothing,
                ;; and records no coordinator state (DELTA-OlrRepl-001.CC14).
            (if (:dry-run? opts)
              (let [result (plan-result state staged provisional backends raw)]
                (diagnostic! opts :lifecycle/plan :completed
                             (select-keys result [:candidate-registries
                                                  :lifecycle/plan
                                                  :modules]))
                result)
              (let [live-backends (publication/backends runtime)
                    live-candidates (publication/candidates live-backends)
                    live-spool-state @(:spool-state runtime)
                    _ (retain-staged-declarations! raw (:outcomes staged))
                    changed-kinds (publication/publish!
                                   runtime staged-runtime backends (:candidates staged))
                    removal-order (->> (module-graph/dependency-order old-graph)
                                       reverse
                                       (filter removed))
                    reconcile-order (vec (concat removal-order order))
                    lifecycle-reconciled
                    (reconcile-lifecycle
                     runtime state graph raw
                     provisional
                     changed-kinds reconcile-order lifecycle-resolvers)
                    _ (try
                        (publication/validate-op-glossary-refs!
                         runtime backends (:candidates staged))
                        (catch Throwable throwable
                          (publication/publish! live-backends live-candidates)
                          (restore-staged-registry-slots!
                           runtime live-spool-state staged-runtime)
                          (throw throwable)))
                    contributions (apply dissoc (:contributions staged) removed)
                    contribution-sources (apply dissoc (:source-stamps staged) removed)
                    outcomes (:outcomes lifecycle-reconciled)
                    state-outcomes (-> (:outcomes state)
                                       (merge outcomes)
                                       (#(apply dissoc % removed)))
                    status (top-status outcomes changed-kinds)
                    result (assoc provisional
                                  :status status
                                  :modules outcomes
                                  :publication/kinds (vec (sort-by pr-str changed-kinds)))]
                (record-result! runtime collection contributions contribution-sources
                                (:resources state)
                                (:lifecycle-state lifecycle-reconciled) state-outcomes
                                result))))
          (catch Throwable throwable
            (restore-declaration-records! @record-snapshots)
            (try
              (diagnostic! opts :probe/failure :failed
                           (exception-data throwable))
              (catch Throwable diagnostic-failure
                (.addSuppressed throwable diagnostic-failure)))
                    ;; Source loads may already have occurred, but no publication
                    ;; follows a coordinator-wide validation failure.
            (throw throwable)))))))

(defn module!
  "Stage a module during startup collection, otherwise declare and refresh it."
  [runtime context key opts]
  (if (module-graph/collecting-modules?)
    (module-graph/stage-module! key opts)
    (refresh! runtime context {:declare [key opts]})))

(defn status
  "Return the coordinator's offline module state joined with loaded-code state."
  [runtime]
  (let [state @(:module-state runtime)]
    {:modules (:graph state)
     :resources (:resources state)
     :loaded-namespaces (loaded-namespaces)
     :last-refresh (:last-refresh state)}))

(defn close-runtime-lifecycle!
  "Close retained runtime-scoped lifecycle resources during runtime stop."
  [runtime]
  (let [state @(:module-state runtime)
        results
        (into (sorted-map)
              (map
               (fn [[module-key retained]]
                 [module-key
                  (lifecycle-effects/refresh
                   {:runtime runtime
                    :module-key module-key
                    :resolver {}
                    :state retained
                    :declarations {}
                    :changed-kinds #{}
                    :published? true
                    :runtime-stop? true})]))
              (:lifecycle state))]
    (swap! (:module-state runtime) assoc :lifecycle (sorted-map))
    results))
