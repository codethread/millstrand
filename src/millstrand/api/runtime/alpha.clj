(ns millstrand.api.runtime.alpha
  "Explicit-runtime API for trusted weaver runtime loader/config workflows.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. Use `millstrand.api.current.alpha/runtime` only at trusted in-process
  entry points that need to capture the active runtime.

  The module exposes the live-image lifecycle: declare stable modules
  (`module!`), collect authoring-form entries and open kinds from
  module sources (`collect-entry!`, `collect-kind!`), reconcile the running
  image against them (`refresh!`, with `plan` its effect-free dry-run), inspect
  the joined offline picture
  (`status`), reach for the advanced code-only seam (`reload-code!`), and serve
  runtime-owned state, symbol resolution, and time to trusted spools
  (`spool-state`, `resolve-var`, `clock`, `now`).

  `module!`/`refresh!`/`plan`/`status`/`reload-code!` are the lifecycle surface:
  declarations are data, refresh replaces owner-complete contributions and
  reconciles resources without stopping the live image, and `reload-code!` is
  the sharp code-only tool. Component sub-specs live in
  `millstrand.api.runtime.internal.shapes`; every registered key stays
  alpha-qualified."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.clock.alpha :as clock-api]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.internal.shapes :as shapes]
            [millstrand.api.spool.alpha :refer [require-valid!]]
            [millstrand.core.weaver.access :as access]
            [millstrand.core.weaver.module-graph :as module-graph]
            [millstrand.core.weaver.runtime :as weaver-runtime]))

(declare validate-refresh-opts! validate-refresh-result! validate-plan-result!
         validate-status-result! validate-module-opts! validate-module-result!
         validate-reload-code-result!
         validate-spool-state-opts! versioned-value reinit-mismatched-state)

;; --- the live module lifecycle ----------------------------------------------
;;
;; module! declares stable modules as data; refresh! reconciles the running
;; image against them, plan is its effect-free dry-run, status reads the joined
;; offline picture, and reload-code! is the advanced code-only seam. The deep
;; multi-kind publication and reconcile is the shared coordinator in
;; millstrand.core.weaver.module-refresh (startup drives the same entry point), so
;; these bodies own the public surface: request classification, the arities, and
;; result-shape validation over named specs (DELTA-OlrRepl-001.CC3-CC9, CC14).

(s/def ::module-key keyword?)
(s/def ::root-lib symbol?)

(defn- non-blank-symbol? [value]
  (and (symbol? value) (not (str/blank? (str value)))))

(defn- workspace-relative-file? [value]
  (and (string? value)
       (not (str/blank? value))
       (not (.isAbsolute (java.io.File. ^String value)))))

;; `::module-opts` is the named public input grammar `module!` consults: a
;; source target plus world policy. The coordinator's `normalize-declaration`
;; owns the actionable refusal for withdrawn callback keys, so
;; `validate-module-opts!` routes a spec-invalid input through it before
;; refusing whatever this narrower grammar still rejects.
(s/def ::module-opts
  (s/and map?
         #(every? #{:ns :file :load :after :required?} (keys %))
         #(not= (contains? % :ns) (contains? % :file))
         #(or (not (contains? % :ns)) (non-blank-symbol? (:ns %)))
         #(or (not (contains? % :file)) (workspace-relative-file? (:file %)))
         #(or (not (contains? % :load)) (= :image (:load %)))
         #(or (not (contains? % :load)) (not (contains? % :file)))
         #(or (not (contains? % :after))
              (and (coll? (:after %)) (every? keyword? (:after %))))
         #(or (not (contains? % :required?)) (boolean? (:required? %)))))

;; This result shape owns the closed normalized declaration returned by
;; `module!`; `::module-opts` owns the less-normalized public input grammar.
;; Retained pre-cutover graph state is an internal coordinator concern and never
;; passes through this staged-result spec (DELTA-Dsp-004.D3).
(s/def ::module-declaration
  (s/and map?
         #(every? #{:ns :file :load :after :required?}
                  (keys %))
         #(not= (contains? % :ns) (contains? % :file))
         #(or (not (contains? % :ns)) (non-blank-symbol? (:ns %)))
         #(or (not (contains? % :file)) (workspace-relative-file? (:file %)))
         #(or (not (contains? % :load)) (= :image (:load %)))
         #(or (not (contains? % :load)) (not (contains? % :file)))
         #(and (vector? (:after %)) (every? keyword? (:after %)))
         #(boolean? (:required? %))))

;; `::refresh-opts` is the named public option grammar `refresh!` and `plan`
;; consult; `validate-refresh-opts!` owns the actionable error prose and treats
;; disagreement with this spec as loud drift.
(s/def ::refresh-opts
  (s/and map?
         #(every? #{:only} (keys %))
         #(or (not (contains? % :only))
              (and (coll? (:only %)) (seq (:only %))
                   (every? keyword? (:only %))))))

(s/def ::basis-fingerprint :millstrand.core.specs/basis-fingerprint)
(s/def ::basis-change :millstrand.core.specs/basis-change)
(s/def ::dependency-diagnostic :millstrand.core.specs/dependency-diagnostic)
(s/def ::restart-required-result
  (s/and #(shapes/exact-keys? #{:status :reason :basis} %)
         #(= :restart-required (:status %))
         #(= :dependency-basis-changed (:reason %))
         #(s/valid? ::basis-change (:basis %))))
(s/def ::refresh-status #{:applied :partial :unchanged})
(s/def ::refresh-mode #{:full :targeted})
(defn- valid-lifecycle-projection?
  [{:keys [status phase] :as projection}]
  (and (map? projection)
       (contains? #{:planned :applied :preserved :retained :degraded :blocked
                    :removed :not-attempted}
                  status)
       (or (nil? phase)
           (contains? #{:validate :resolve :open :apply :close :remove
                        :runtime-stop}
                      phase))))

(defn- valid-lifecycle-outcomes?
  "True when every present per-effect projection has closed status data."
  [modules]
  (every?
   (fn [module]
     (let [outcomes (:lifecycle/outcomes module)]
       (or (nil? outcomes)
           (and (map? outcomes)
                (every? valid-lifecycle-projection? (vals outcomes))))))
   (vals modules)))

(s/def ::refresh-result
  (s/or :dependency ::dependency-diagnostic
        :restart ::restart-required-result
        :current (s/and map?
                        #(s/valid? ::refresh-status (:status %))
                        #(s/valid? ::refresh-mode (:mode %))
                        #(map? (:modules %))
                        #(valid-lifecycle-outcomes? (:modules %)))))

(s/def ::caveat (s/and string? seq))
(s/def ::plan-result
  (s/or :dependency ::dependency-diagnostic
        :restart ::restart-required-result
        :current (s/and map?
                        #(s/valid? ::refresh-result %)
                        #(true? (:dry-run? %))
                        #(s/valid? ::caveat (:caveat %)))))

(s/def ::status-result
  (s/and #(shapes/exact-keys? #{:basis-fingerprint :modules :resources
                                :loaded-namespaces :last-refresh} %)
         #(s/valid? ::basis-fingerprint (:basis-fingerprint %))
         #(map? (:modules %))
         #(map? (:resources %))
         #(vector? (:loaded-namespaces %))))

(s/def ::reload-code-result
  (s/and #(shapes/exact-keys? #{:lib :status :namespaces} %)
         #(s/valid? ::root-lib (:lib %))
         #(contains? #{:reloaded :unchanged} (:status %))
         #(vector? (:namespaces %))
         #(every? non-blank-symbol? (:namespaces %))))

(s/def ::staged-module-result
  (s/and #(shapes/exact-keys? #{:module/key :module/declaration :staged?} %)
         #(true? (:staged? %))
         #(s/valid? ::module-key (:module/key %))
         #(s/valid? ::module-declaration (:module/declaration %))))
(s/def ::module-result
  (s/or :staged ::staged-module-result
        :refreshed ::refresh-result))

(defn module!
  "Declare one stable runtime module under keyword `key` for `runtime`.

  `opts` conforms to `::module-opts`: it is closed to a source target (`:ns`
  namespace symbol visible in the generation basis, or
  workspace-relative `:file` string; exactly one is required), an optional
  `:load :image` mode, optional module-key `:after` dependencies, and an
  optional boolean `:required?`.

  Registry entries and live effects are authored with top-level contribution
  and lifecycle forms. `opts` names neither callbacks nor entry points:
  `:contribute` and `:reconcile` are rejected with replacement-form guidance,
  and a public `spool` var in a loaded module namespace is rejected too. The
  removed grammar has no alias or fallback.

  `:load :image` (SPEC-004.C45/C46) trusts the already-loaded JVM image for the
  `:ns` target: refresh performs no source load for that module, and it accepts
  no `:file` target. It replays the namespace's retained authoring declaration
  record as data. Missing, stale, or foreign records fail module evaluation.
  The outcome reports `:source/status :image` and carries no source stamp.

  During startup-file collection this only stages the declaration and performs
  no source load, publication, or reconcile. Outside collection it replaces the
  desired declaration for `key` and refreshes that module plus affected
  dependents (CC4). Whole-module removal is expressed by omitting the module
  from a successfully collected full graph, not here. Malformed declarations
  fail loudly. The staged or refreshed result conforms to `::module-result`."
  [runtime key opts]
  (require-valid! ::module-key key "module! key must be a keyword")
  (validate-module-opts! key opts)
  (let [result (weaver-runtime/declare-module! runtime key opts)]
    (validate-module-result! result)))

(s/fdef module!
  :args (s/cat :runtime map? :key ::module-key :opts ::module-opts)
  :ret ::module-result)

(s/def ::contribution-kind keyword?)
(s/def ::collect-entry-opts
  (s/and map?
         #(every? #{:override?} (keys %))
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))

(defn collect-entry!
  "Collect one authoring-form registry entry for the module source being
  evaluated.

  `kind-id` conforms to `::contribution-kind` and `opts` to
  `::collect-entry-opts` (closed to boolean `:override?`); `entry-key` and
  `value` are deliberately unconstrained here because their shapes belong to
  the registry kind that owns them. Repeating the same `kind-id`/`entry-key`
  in one source evaluation replaces the earlier value deterministically;
  `{:override? true}` records explicit override intent. Outside contribution
  collection the form is passive, so a code-only source reload defines Vars
  without publishing declarations. The collection context is scoped to the
  source form under evaluation, not to a runtime, so this is the one lifecycle
  function taking no runtime argument. Malformed kinds and options fail
  loudly; returns `value`."
  ([kind-id entry-key value]
   (require-valid! ::contribution-kind kind-id
                   "collect-entry! kind-id must be a keyword")
   (weaver-runtime/collect-module-entry! kind-id entry-key value))
  ([kind-id entry-key value opts]
   (require-valid! ::contribution-kind kind-id
                   "collect-entry! kind-id must be a keyword")
   (require-valid! ::collect-entry-opts opts
                   "collect-entry! opts are closed to a boolean :override?")
   (weaver-runtime/collect-module-entry! kind-id entry-key value opts)))

(s/fdef collect-entry!
  :args (s/or :entry (s/cat :kind-id ::contribution-kind
                            :entry-key any? :value any?)
              :entry-opts (s/cat :kind-id ::contribution-kind
                                 :entry-key any? :value any?
                                 :opts ::collect-entry-opts))
  :ret any?)

(s/def ::lifecycle-effect-id keyword?)
(s/def ::lifecycle-declaration
  (s/and
   map?
   #(contains? #{:seed :resource :reconcile} (:kind %))
   (fn [{:keys [kind] :as declaration}]
     (let [options (dissoc declaration :kind)
           qualified-callable? qualified-symbol?]
       (case kind
         :seed
         (and (every? #{:apply :after} (keys options))
              (qualified-callable? (:apply options))
              (set? (get options :after #{})))

         :resource
         (and (every? #{:open :close :after :scope} (keys options))
              (every? qualified-callable? ((juxt :open :close) options))
              (set? (get options :after #{}))
              (contains? #{:module :runtime} (get options :scope :module)))

         :reconcile
         (and (every? #{:read-desired :read-actual :apply :on-removed
                        :trigger-kinds :after}
                      (keys options))
              (every? qualified-callable?
                      ((juxt :read-desired :read-actual :apply :on-removed)
                       options))
              (set? (get options :trigger-kinds #{}))
              (set? (get options :after #{})))

         false)))))

(defn collect-lifecycle!
  "Collect one validated lifecycle declaration from the current module source.

  Duplicate ids fail at collection. Outside module source collection the call
  is passive, allowing code-only reloads to define declaration Vars."
  [effect-id declaration]
  (require-valid! ::lifecycle-effect-id effect-id
                  "collect-lifecycle! effect-id must be a keyword")
  (require-valid! ::lifecycle-declaration declaration
                  "collect-lifecycle! declaration is invalid")
  (weaver-runtime/collect-lifecycle! effect-id declaration))

(s/fdef collect-lifecycle!
  :args (s/cat :effect-id ::lifecycle-effect-id
               :declaration ::lifecycle-declaration)
  :ret ::lifecycle-declaration)

(s/def ::kind-state-key keyword?)
(s/def ::kind-declaration ::registry/kind-declaration-input)

(defn collect-kind!
  "Collect one open registry kind for the module source being evaluated.

  `state-key` conforms to `::kind-state-key` and names the runtime spool-state
  slot that owns the registry handle. `declaration` conforms to
  `::kind-declaration`, the closed registry kind contract. Repeating one
  state-key/kind id replaces the earlier declaration deterministically.
  Outside module collection the call is passive. Returns `declaration`."
  [state-key declaration]
  (require-valid! ::kind-state-key state-key
                  "collect-kind! state-key must be a keyword")
  (require-valid! ::kind-declaration declaration
                  "collect-kind! declaration is invalid")
  ((requiring-resolve 'millstrand.core.weaver.module-graph/collect-kind!)
   state-key declaration))

(s/fdef collect-kind!
  :args (s/cat :state-key ::kind-state-key
               :declaration ::kind-declaration)
  :ret ::kind-declaration)

(defn refresh!
  "Reconcile `runtime`'s live image against its declared module graph.

  The no-opts arity re-reads `init.clj`/`init.local.clj`, collects the complete
  layered graph, compares the workspace dependency basis with the running
  generation, and applies source evaluation, owner-complete publication, and
  resource reconciliation when the basis is unchanged. A changed or invalid
  basis returns the exact dependency result without evaluating activation or
  module source. `(refresh! runtime {:only keys})` refreshes a non-empty set
  of known module keys and affected dependents against the active declaration
  graph without re-reading startup files. Options conform to `::refresh-opts`
  (closed to `:only`): unknown option keys, an empty or malformed `:only`, and
  unknown module keys fail loudly. Content-identical
  staged contributions skip publication and reconcile. The atomic multi-phase
  reconcile is the coordinator that startup also drives; this surface owns the
  arities, request classification, and result validation. The joined result
  conforms to `::refresh-result`."
  ([runtime] (refresh! runtime {}))
  ([runtime opts]
   (validate-refresh-opts! opts)
   (validate-refresh-result!
    (weaver-runtime/refresh-modules! runtime opts))))

(s/fdef refresh!
  :args (s/or :full (s/cat :runtime map?)
              :targeted (s/cat :runtime map? :opts ::refresh-opts))
  :ret ::refresh-result)

(defn plan
  "Return the dry-run intentions of `refresh!` without publishing or reconciling.

  Full plan performs the same dependency-basis comparison as `refresh!`.
  Current-basis plans collect and stage source without publishing, reconciling,
  or recording coordinator state. They return a `::refresh-result`-shaped map
  flagged `:dry-run? true` with a `:caveat`; collection may evaluate module
  source code. Options conform to `::refresh-opts`; malformed options fail
  loudly. The result conforms to `::plan-result`
  (DELTA-Dns-Repl-001.C11)."
  ([runtime] (plan runtime {}))
  ([runtime opts]
   (validate-refresh-opts! opts)
   (validate-plan-result!
    (weaver-runtime/refresh-modules! runtime (assoc opts :dry-run? true)))))

(s/fdef plan
  :args (s/or :full (s/cat :runtime map?)
              :targeted (s/cat :runtime map? :opts ::refresh-opts))
  :ret ::plan-result)

(defn status
  "Return `runtime`'s offline, read-only joined module status.

  Returns exactly the running basis fingerprint, desired modules, resource
  outcomes, loaded namespaces, and last refresh result. It performs no
  dependency read, file write, source load, registration, or reconcile. The
  result conforms to `::status-result` (DELTA-Dns-Repl-001.C12)."
  [runtime]
  (let [module-status (weaver-runtime/module-status runtime)]
    (validate-status-result!
     {:basis-fingerprint (:basis-fingerprint runtime)
      :modules (:modules module-status)
      :resources (:resources module-status)
      :loaded-namespaces (:loaded-namespaces module-status)
      :last-refresh (:last-refresh module-status)})))

(s/fdef status
  :args (s/cat :runtime map?)
  :ret ::status-result)

(defn reload-code!
  "Reload loaded namespaces from source-backed entries for basis `root-lib`.

  This advanced code-only seam reloads namespaces whose file resources belong
  to the selected library's source paths. It does not publish module
  contributions or reconcile resources; use `refresh!` for the normal path.
  The result conforms to `::reload-code-result`
  (DELTA-Dns-Repl-001.C5)."
  [runtime root-lib]
  (require-valid! ::root-lib root-lib "reload-code! root-lib must be a symbol")
  (validate-reload-code-result!
   (weaver-runtime/reload-basis-lib! runtime root-lib)))

(s/fdef reload-code!
  :args (s/cat :runtime map? :root-lib ::root-lib)
  :ret ::reload-code-result)

;; --- runtime-owned services for trusted spools ------------------------------

(s/def ::resolvable-symbol qualified-symbol?)

(defn clock
  "Return `runtime`'s installed `millstrand.api.clock.alpha/Clock`."
  [runtime]
  (weaver-runtime/clock runtime))

(s/fdef clock
  :args (s/cat :runtime map?)
  :ret ::clock-api/clock)

(defn now
  "Return the current java.time.Instant from `runtime`'s clock seam.

  Defaults to the real wall clock; deterministic tests inject an advanceable
  clock through `millstrand.test.alpha/set-clock!`."
  [runtime]
  (weaver-runtime/now runtime))

(s/fdef now
  :args (s/cat :runtime map?)
  :ret inst?)

(defn resolve-var
  "Resolve fully qualified `sym` to its Var under `runtime`'s spool classloader.

  Declarations name behavior by symbol, and a symbol living in a synced spool
  root only loads under that classloader — a bare `requiring-resolve` is blind
  to it. Returns the Var, or nil when its namespace loads but defines nothing
  under that name; a namespace that cannot be loaded at all throws, carrying the
  load error as its cause."
  [runtime sym]
  (require-valid! ::resolvable-symbol sym
                  "resolve-var symbol must be fully qualified")
  (access/with-generation-classloader runtime #(requiring-resolve sym)))

(s/fdef resolve-var
  :args (s/cat :runtime map? :sym ::resolvable-symbol)
  :ret (s/nilable var?))

(def ^:private spool-state-opt-keys #{:version :migrate-fn})

;; ::version is also the metadata key stamped on versioned spool-state values;
;; renaming it would make every preserved versioned state look mismatched on
;; the next upgrade and force a spurious reinit.
(s/def ::version (s/or :integer integer? :keyword keyword? :string string?))
(s/def ::spool-state-opts
  (s/nilable
   (s/and (s/keys :opt-un [::version ::migrate-fn])
          #(every? spool-state-opt-keys (keys %))
          #(or (not (contains? % :migrate-fn))
               (contains? % :version)))))

(defn spool-state
  "Return runtime-owned state for a spool key, creating it with `init-fn` once.

  The runtime stores spool state under arbitrary keys in its `:spool-state`
  atom. `init-fn` is called only when `key` has not been installed for this
  runtime; the returned value is then reused for the rest of the runtime
  lifetime. Spools should use this accessor instead of reaching into runtime
  internals.

  Spool state survives `refresh!` by design, so a spool whose state shape changed
  between refreshes would otherwise silently reuse a preserved value that is
  missing the new keys. The four-arg arity guards against that: pass opts
  `{:version v :migrate-fn f}` and, when a preserved value's stored version does
  not `=` `version`, the runtime deliberately reinits (or, with `:migrate-fn`,
  hands the old value to `f` to produce the new one) instead of reusing a
  shape-mismatched map. Silent reuse of shape-mismatched state is impossible
  once a version is declared. Opts conform to
  `:millstrand.api.runtime.alpha/spool-state-opts`; a malformed map fails loudly at
  the call site rather than degrading to the unversioned path."
  ([runtime key init-fn] (spool-state runtime key nil init-fn))
  ([runtime key opts init-fn]
   (validate-spool-state-opts! opts)
   (when-not (and runtime (:spool-state runtime))
     (throw (ex-info "Runtime does not support spool state" {:key key})))
   (let [{:keys [version migrate-fn]} opts
         state (:spool-state runtime)
         reuse? (fn [existing] (= version (::version (meta existing))))
         m @state]
     ;; Lock-free fast path: a present, version-matching value is reused as-is.
     (if (and (contains? m key) (reuse? (get m key)))
       (get m key)
       ;; Build path (first init OR version-mismatch reinit). Serialize it per
       ;; runtime so init-fn/migrate-fn — and the executors/schedulers they
       ;; allocate — run at most once. A lock-free CAS loser would discard its
       ;; freshly-built state and leak that value's live daemon threads for the
       ;; JVM lifetime (nothing else references it to shut it down). Reinit is
       ;; rare (a version bump on reload), so a coarse per-runtime lock is cheap;
       ;; only builders take it, readers on the fast path never do.
       (locking state
         (let [m* @state
               existing (get m* key)]
           (cond
             (not (contains? m* key))
             (let [value (versioned-value runtime (init-fn) version)]
               (swap! state assoc key value)
               value)

             (reuse? existing)
             existing

             :else
             (let [replacement (reinit-mismatched-state
                                runtime existing version migrate-fn init-fn)]
               (swap! state assoc key replacement)
               replacement))))))))

(s/fdef spool-state
  :args (s/or :unversioned (s/cat :runtime map? :key any? :init-fn ifn?)
              :versioned (s/cat :runtime map? :key any?
                                :opts ::spool-state-opts :init-fn ifn?))
  :ret any?)

;; --- result-shape validators ------------------------------------------------

(def ^:private allowed-refresh-keys #{:only})

(defn- validate-refresh-opts!
  "Validate the public refresh/plan options against the named `::refresh-opts`
  grammar.

  Options must be a map naming only `:only`; a present `:only` must be a
  non-empty collection of module keywords. The checks here own the actionable
  error prose; when they accept what the named spec rejects the two have
  drifted, and that disagreement fails loudly with the spec explain data. The
  coordinator separately rejects unknown module keys against the active graph."
  [opts]
  (when-not (s/valid? ::refresh-opts opts)
    (when-not (map? opts)
      (throw (ex-info "Refresh options must be a map" {:opts opts})))
    (when-let [unknown (seq (remove allowed-refresh-keys (keys opts)))]
      (throw (ex-info "Refresh options contain unknown keys"
                      {:unknown (vec (sort-by pr-str unknown))})))
    (when (contains? opts :only)
      (let [only (:only opts)]
        (when-not (and (coll? only) (seq only) (every? keyword? only))
          (throw (ex-info "Refresh :only must be a non-empty collection of module keys"
                          {:only only})))))
    (require-valid! ::refresh-opts opts
                    "refresh options do not match the ::refresh-opts grammar"))
  opts)

(defn- validate-refresh-result! [result]
  (require-valid! ::refresh-result result "runtime refresh result has an invalid shape")
  result)

(defn- validate-plan-result! [result]
  (require-valid! ::plan-result result "runtime plan result has an invalid shape")
  result)

(defn- validate-status-result! [result]
  (require-valid! ::status-result result "runtime status result has an invalid shape")
  result)

(defn- validate-module-opts!
  "Validate public module! opts against the named `::module-opts` grammar.

  The coordinator's `normalize-declaration` owns every declaration refusal, the
  withdrawn entry-point keys included, so a spec-invalid input is routed
  through it and this boundary answers with exactly the message and ex-data a
  directly authored declaration gets — one contract to keep true rather than a
  second copy here. Normalization is pure, so the probe has no effect. This
  grammar is the narrower surface, so input the normalizer accepts and the
  named spec rejects is still refused here, with the spec explain data."
  [key opts]
  (when-not (s/valid? ::module-opts opts)
    (module-graph/normalize-declaration key opts)
    (require-valid! ::module-opts opts
                    "module! opts do not match the module declaration grammar"))
  opts)

(defn- validate-module-result! [result]
  (require-valid! ::module-result result "runtime module result has an invalid shape")
  result)

(defn- validate-reload-code-result! [result]
  (require-valid! ::reload-code-result result "runtime reload-code result has an invalid shape")
  result)

;; --- spool-state versioning -------------------------------------------------

(defn- warn!
  "Emit a loud-but-non-fatal runtime warning to the weaver's stderr log.

  Used where discarding a signal entirely would be worse than continuing but a
  hard failure is not warranted (a best-effort resource cleanup that fails
  during a version-mismatch reinit): the reinit still proceeds and the
  divergence stays visible in the weaver log instead of vanishing."
  [message data]
  (binding [*out* *err*]
    (println (str "[runtime] WARN " message " " (pr-str data)))))

(defn- validate-spool-state-opts!
  "Validate spool-state opts against their owning public spec."
  [opts]
  (require-valid! ::spool-state-opts opts
                  "spool-state opts have an invalid shape"))

(defn- tag-spool-state-generation
  "Tag `value` with the runtime generation that created it, when metadata
  permits it."
  [runtime value]
  (if (instance? clojure.lang.IObj value)
    (vary-meta value assoc :millstrand.runtime/generation (:generation-id runtime))
    value))

(defn- versioned-value
  "Tag `value` with its declared spool-state `version` for later reload checks.

  Version nil (the unversioned default) leaves `value` untouched. A declared
  version is stored as value metadata, so `close-fn` lookups and consumers still
  see the plain state value; versioned state must therefore support metadata."
  [runtime value version]
  (tag-spool-state-generation
   runtime
   (if (nil? version)
     value
     (if (instance? clojure.lang.IObj value)
       (vary-meta value assoc ::version version)
       (throw (ex-info "Versioned spool state must support metadata"
                       {:version version :class (class value)}))))))

(defn- reinit-mismatched-state
  "Build the replacement value when preserved `existing` state mismatches the
  declared `version`.

  With a `migrate-fn`, it owns `existing` (including any resources it holds) and
  returns the new value. Without one, `existing`'s `:close-fn` runs best-effort
  so a stale executor or scheduler is released, then `init-fn` builds fresh
  state — preserving nothing. The result is re-tagged with `version`."
  [runtime existing version migrate-fn init-fn]
  (versioned-value
   runtime
   (if migrate-fn
     (migrate-fn existing)
     (do (when-let [close-fn (:close-fn existing)]
           (try (close-fn)
                (catch Throwable t
                  (warn! "spool-state reinit close-fn failed; a stale executor may leak"
                         {:version version :exception/message (ex-message t)}))))
         (init-fn)))
   version))
