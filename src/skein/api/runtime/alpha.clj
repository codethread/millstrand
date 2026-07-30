(ns skein.api.runtime.alpha
  "Explicit-runtime API for trusted weaver runtime loader/config workflows.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. Use `skein.api.current.alpha/runtime` only at trusted in-process
  entry points that need to capture the active runtime.

  The module reads as the live-image lifecycle: read the approved/declared
  config (`approved`, `declared`, `release-marker`), edit the primary
  `spools.edn` (`upsert-spool-entry!`, `remove-spool-entry!`), declare stable
  modules (`module!`), collect authoring-form entries and open kinds from
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
  `skein.api.runtime.internal.shapes`; every registered key stays
  alpha-qualified."
  (:require [clojure.spec.alpha :as s]
            [skein.api.clock.alpha :as clock-api]
            [skein.api.registry.alpha :as registry]
            [skein.api.runtime.internal.shapes :as shapes]
            [skein.api.runtime.internal.spools-edn :as spools-edn]
            [skein.api.spool.alpha :refer [require-valid!]]
            [skein.core.specs :as specs]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.module-graph :as module-graph]
            [skein.core.weaver.module-refresh.entry-points :as entry-points]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.weaver.spool-sync :as spool-sync]))

(declare running-release-marker spools-file
         validate-approved-result! validate-declared-result!
         validate-refresh-opts! validate-refresh-result! validate-plan-result!
         validate-status-result! validate-module-opts! validate-module-result!
         validate-reload-code-result!
         families-requiring
         validate-spool-state-opts! versioned-value reinit-mismatched-state)

;; --- reading spool config ---------------------------------------------------

(s/def ::approved-result :skein.core.weaver.spool-sync/approved-result)
(s/def ::declared-result :skein.core.weaver.spool-sync/declared-result)
(s/def ::pending-generation :skein.core.weaver.spool-sync/pending-generation)
(s/def ::running-marker (s/or :marker string?
                              :unavailable #{:none}
                              :deferred nil?))

(defn approved
  "Return the normalized approved spool roots for `runtime`'s config dir.

  Each root entry includes `:provenance :spools-edn|:local-overlay`; overlay
  entries also include their explicit `:claims` marker. `:families` maps family
  symbols to the declared `spools.edn` entry, effective post-overlay coordinate,
  provenance, and overlay claim or nil. The result conforms to
  `::approved-result`."
  [runtime]
  (validate-approved-result!
   (spool-sync/approved-spools runtime (running-release-marker runtime))))

(s/fdef approved
  :args (s/cat :runtime map?)
  :ret ::approved-result)

(defn declared
  "Return declared spool families with release-floor validation as data.

  `:families` has the same declared/effective projection as `approved`.
  Each family projection's `:declared`, `:effective-coordinate`, `:provenance`,
  and `:claims` conform to `::spool-entry`, `::spool-coordinate`,
  `::spool-provenance`, and `::spool-claims`.
  `:requirements` is valid with pending validations, or invalid with findings
  and bump suggestions. Stage-1 structural errors still throw. The explicit
  `running-marker` arity accepts nil to leave Skein floor checks pending. The
  result conforms to `::declared-result`."
  ([runtime]
   (declared runtime (running-release-marker runtime)))
  ([runtime running-marker]
   (validate-declared-result!
    (spool-sync/declared-spools runtime running-marker))))

(s/fdef declared
  :args (s/or :runtime (s/cat :runtime map?)
              :with-marker (s/cat :runtime map?
                                  :running-marker ::running-marker))
  :ret ::declared-result)

(s/def ::release-marker-claim ::specs/release-marker-claim)
(s/def ::release-marker-result ::specs/release-marker-result)

(defn release-marker
  "Return the running Skein release marker and its provenance.

  The result has marker `vN` and provenance `:claimed` for an explicit startup
  claim, marker `vN` and provenance `:tag` for an annotated tag on the source
  checkout's HEAD, or `{:marker nil :provenance :none}` when the checkout
  resource is absent or non-filesystem, or successful inspection finds no
  matching annotated tag. Git startup, checkout-root resolution, and nonzero
  Git command failures throw. Consumers that require marker arithmetic must
  reject `:none` explicitly. The result conforms to
  `::release-marker-result`; marker claims conform to `::release-marker-claim`."
  [runtime]
  (let [result (access/release-marker runtime)]
    (require-valid! ::release-marker-result result
                    "runtime release marker has an invalid shape")
    result))

(s/fdef release-marker
  :args (s/cat :runtime map?)
  :ret ::release-marker-result)

;; --- the spools.edn write seam ----------------------------------------------

(s/def ::spool-family symbol?)
(s/def ::spool-entry :skein.core.weaver.spool-sync/family-entry)
(s/def ::spool-coordinate ::spool-sync/coordinate)
(s/def ::spool-provenance ::spool-sync/provenance)
(s/def ::spool-claims ::spool-sync/claims)
(s/def ::spool-write-result
  (s/and #(shapes/exact-keys? #{:status :lib :entry :file} %)
         #(s/valid? ::spool-write-status (:status %))
         #(s/valid? ::spool-family (:lib %))
         #(s/valid? ::spool-entry (:entry %))
         #(s/valid? ::specs/spools-file-result (:file %))))

(defn upsert-spool-entry!
  "Insert or replace `lib` in `runtime`'s primary `spools.edn`.

  `lib` and `entry` conform to `::spool-family` and `::spool-entry`. The full
  post-edit config is validated through sync's stage-1 contract before an atomic
  write. Only the `:spools` map is rewritten, so comments outside it are kept.
  The result conforms to `::spool-write-result`."
  [runtime lib entry]
  (require-valid! ::spool-family lib "upsert-spool-entry! lib must be a symbol")
  (let [^java.io.File file (spools-file runtime)
        original (spools-edn/read-primary file)
        config (spools-edn/parse-primary original file)
        _ (spool-sync/validate-shared-spools-config! file config)
        existed? (contains? (:spools config) lib)
        updated (assoc-in config [:spools lib] entry)]
    (spools-edn/write-primary! file original updated)
    {:status (if existed? :updated :inserted)
     :lib lib
     :entry entry
     :file file}))

(s/fdef upsert-spool-entry!
  :args (s/cat :runtime map? :lib ::spool-family :entry ::spool-entry)
  :ret ::spool-write-result)

(defn remove-spool-entry!
  "Remove `lib` from `runtime`'s primary `spools.edn`.

  Refuses a missing family or a family whose root libs appear in another
  family's `:requires`, naming all requirers. Inputs and result conform to
  `::spool-family` and `::spool-write-result`. Only the primary file is changed."
  [runtime lib]
  (require-valid! ::spool-family lib "remove-spool-entry! lib must be a symbol")
  (let [^java.io.File file (spools-file runtime)
        original (spools-edn/read-primary file)
        config (spools-edn/parse-primary original file)
        _ (spool-sync/validate-shared-spools-config! file config)
        entry (get-in config [:spools lib])]
    (when-not entry
      (throw (ex-info "Spool family is not present in spools.edn"
                      {:reason :spool-family-not-found :lib lib :file (.getPath file)})))
    (let [normalized (:families (spool-sync/validate-shared-spools-config! file config))
          target (some #(when (= lib (:family %)) %) normalized)
          roots (set (keys (:roots-map target)))
          requirers (families-requiring normalized lib roots)]
      (when (seq requirers)
        (throw (ex-info "Spool family is required by other families"
                        {:reason :spool-family-required
                         :lib lib
                         :roots roots
                         :requirers requirers})))
      (spools-edn/write-primary! file original (update config :spools dissoc lib))
      {:status :removed :lib lib :entry entry :file file})))

(s/fdef remove-spool-entry!
  :args (s/cat :runtime map? :lib ::spool-family)
  :ret ::spool-write-result)

;; --- the live module lifecycle ----------------------------------------------
;;
;; module! declares stable modules as data; refresh! reconciles the running
;; image against them, plan is its effect-free dry-run, status reads the joined
;; offline picture, and reload-code! is the advanced code-only seam. The deep
;; multi-kind publication and reconcile is the shared coordinator in
;; skein.core.weaver.module-refresh (startup drives the same entry point), so
;; these bodies own the public surface: request classification, the arities, and
;; result-shape validation over named specs (DELTA-OlrRepl-001.CC3-CC9, CC14).

(s/def ::module-key keyword?)
(s/def ::root-lib symbol?)

;; `::module-opts` is the named public input grammar `module!` consults: a
;; source target plus world policy, with entry points owned by the module
;; namespace's `spool` var rather than by opts. The coordinator's
;; `normalize-declaration` stays the normalizer and the single authority for
;; actionable refusal prose — the withdrawn entry-point keys included — so
;; `validate-module-opts!` routes a spec-invalid input through it and then
;; refuses whatever this narrower grammar still rejects.
(s/def ::module-opts
  (s/and map?
         #(every? #{:ns :file :load :spools :after :required?} (keys %))
         #(not= (contains? % :ns) (contains? % :file))
         #(or (not (contains? % :ns)) (symbol? (:ns %)))
         #(or (not (contains? % :file)) (string? (:file %)))
         #(or (not (contains? % :load)) (= :image (:load %)))
         #(or (not (contains? % :load)) (not (contains? % :file)))
         #(or (not (contains? % :spools))
              (and (coll? (:spools %)) (every? symbol? (:spools %))))
         #(or (not (contains? % :after))
              (and (coll? (:after %)) (every? keyword? (:after %))))
         #(or (not (contains? % :required?)) (boolean? (:required? %)))))

;; This result shape owns the closed normalized declaration returned by
;; `module!`; `::module-opts` owns the less-normalized public input grammar.
;; Retained pre-cutover graph state is an internal coordinator concern and never
;; passes through this staged-result spec (DELTA-Dsp-004.D3).
(s/def ::module-declaration
  (s/and map?
         #(every? #{:ns :file :load :spools :after :required?}
                  (keys %))
         #(not= (contains? % :ns) (contains? % :file))
         #(or (not (contains? % :ns)) (symbol? (:ns %)))
         #(or (not (contains? % :file)) (string? (:file %)))
         #(or (not (contains? % :load)) (= :image (:load %)))
         #(and (vector? (:spools %)) (every? symbol? (:spools %)))
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

(s/def ::refresh-status #{:applied :partial :unchanged :refused})
(s/def ::refresh-mode #{:full :targeted})
(s/def ::resolved-entry-points
  (s/map-of ::module-key ::entry-points/resolved-entry-points))
(s/def ::refresh-result
  (s/and map?
         #(s/valid? ::refresh-status (:status %))
         #(s/valid? ::refresh-mode (:mode %))
         #(map? (:modules %))
         #(map? (:roots %))
         #(s/valid? ::resolved-entry-points (:resolved/entry-points %))
         #(vector? (:residuals %))
         #(vector? (:conflicts %))
         #(vector? (:remedies %))))

(s/def ::caveat (s/and string? seq))
(s/def ::plan-result
  (s/and ::refresh-result
         #(true? (:dry-run? %))
         #(s/valid? ::caveat (:caveat %))))

(s/def ::status-result
  (s/and map?
         #(map? (:modules %))
         #(map? (:contributions %))
         #(s/valid? ::resolved-entry-points (:resolved/entry-points %))
         #(map? (:loaded %))
         #(contains? % :pending-generation)
         #(s/valid? (s/nilable ::pending-generation) (:pending-generation %))
         #(contains? % :last-refresh)))

(s/def ::reload-code-result
  (s/and #(shapes/exact-keys? #{:root-lib :root :namespaces :residuals :hard-conflicts} %)
         #(s/valid? ::root-lib (:root-lib %))
         #(s/valid? ::canonical-root (:root %))
         #(s/valid? ::namespaces (:namespaces %))
         #(vector? (:residuals %))
         #(vector? (:hard-conflicts %))))

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
  namespace symbol — synced for ordinary source-loading declarations — or
  workspace-relative `:file` string; exactly one is required), an optional
  `:load :image` mode, optional approved `:spools` root prerequisites,
  optional module-key `:after` dependencies, and an optional boolean
  `:required?`.

  Entry points are declared by convention alone (PROP-Dsp-001) and `opts` names
  none: the module's namespace declares a public
  `(def spool {:contribute … :reconcile …})` var, and a public `spool` var in a
  module-loadable namespace unconditionally is that module's entry-point
  declaration. Every effective symbol is resolved and root-value-validated
  during source evaluation and the bounded missing-record image fallback.
  Normal image replay resolves no contribution symbol. When no `:contribute`
  resolves, the module's
  contribution is the declaration data collected from the authoring forms
  evaluated in its source, so a plain file of authoring forms is a complete
  module (DELTA-OlrRepl-001.CC3). A `spool` var supplying `:contribute` while
  the same source load collected authoring forms is a loud conflict; a
  `:reconcile`-only `spool` var composes with authoring forms. Opts naming
  `:contribute` or `:reconcile` — qualified or not — are refused with the
  removed keys named, the module key, and a `(def spool …)` remedy, the same
  refusal a directly authored declaration raises; the keys carry no alias and
  no fallback (DELTA-Dsp-003.CC1).

  `:load :image` (SPEC-004.C45/C46, ADR-003.P4) trusts the already-loaded JVM
  image for the `:ns` target: refresh performs no source load for that module,
  and it accepts no `:file` target. It replays the namespace's retained
  authoring declaration record as data. A missing or stale record fails the
  module evaluation. During the bounded migration window, only a missing
  record may fall back to the legacy namespace `spool` var's `:contribute`
  callback. The outcome reports `:source/status :image` and carries no source
  stamp.

  A `:reconcile` fn receives the contribution status under
  `[:module/contribution :status]` and branches: `:applied` ensures its live
  resources and registrations exist, `:removed` tears them down, and any other
  status — reachable only by direct call — fails loudly naming the status, the
  allowed set, the module, and the reconciler (SPEC-004.C46b).

  During startup-file collection this only stages the declaration and performs
  no source load, publication, or reconcile. Outside collection it replaces the
  desired declaration for `key` and refreshes that module plus affected
  dependents (CC4). Whole-module removal is expressed by omitting the module
  from a successfully collected full graph, not here. Malformed declarations
  fail loudly. The staged or refreshed result conforms to `::module-result`."
  [runtime key opts]
  (require-valid! ::module-key key "module! key must be a keyword")
  (validate-module-opts! key opts)
  (validate-module-result! (weaver-runtime/declare-module! runtime key opts)))

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
(s/def ::lifecycle-declaration map?)

(defn collect-lifecycle!
  "Collect one validated lifecycle declaration from the current module source.

  Duplicate ids fail at collection. Outside module source collection the call
  is passive, allowing code-only reloads to define declaration Vars."
  [effect-id declaration]
  (require-valid! ::lifecycle-effect-id effect-id
                  "collect-lifecycle! effect-id must be a keyword")
  (require-valid! ::lifecycle-declaration declaration
                  "collect-lifecycle! declaration must be a map")
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
  ((requiring-resolve 'skein.core.weaver.module-graph/collect-kind!)
   state-key declaration))

(s/fdef collect-kind!
  :args (s/cat :state-key ::kind-state-key
               :declaration ::kind-declaration)
  :ret ::kind-declaration)

(defn refresh!
  "Reconcile `runtime`'s live image against its declared module graph.

  The no-opts arity re-reads `init.clj`/`init.local.clj`, collects the complete
  layered graph, and applies the Weaver Runtime refresh contract: it composes
  approved-root synchronization, changed-source reload, contribution collection
  and classification, owner-complete registry publication, and resource
  reconciliation, leaving queued events, recent failures, and unrelated
  spool-state live. `(refresh! runtime {:only keys})` refreshes a non-empty set
  of known module keys and affected dependents against the active declaration
  graph without re-reading startup files. Options conform to `::refresh-opts`
  (closed to `:only`): unknown option keys, an empty or malformed `:only`, and
  unknown module keys fail loudly. Content-identical
  staged contributions skip publication and reconcile. The atomic multi-phase
  reconcile is the coordinator that startup also drives; this surface owns the
  arities, request classification, and result validation. The joined result
  conforms to `::refresh-result`, whose `:resolved/entry-points` projection
  conforms to `::resolved-entry-points` (DELTA-OlrRepl-001.CC7)."
  ([runtime] (refresh! runtime {}))
  ([runtime opts]
   (validate-refresh-opts! opts)
   (validate-refresh-result! (weaver-runtime/refresh-modules! runtime opts))))

(s/fdef refresh!
  :args (s/or :full (s/cat :runtime map?)
              :targeted (s/cat :runtime map? :opts ::refresh-opts))
  :ret ::refresh-result)

(defn plan
  "Return the dry-run intentions of `refresh!` without publishing or reconciling.

  `plan` and `(plan runtime {:only keys})` collect and diff against the current
  synchronized roots without fetching, synchronizing, publishing, reconciling,
  or recording coordinator state. They return a `::refresh-result`-shaped map
  flagged `:dry-run? true` with a `:caveat`. The one honest caveat, stated in
  the result and here: collection may load module source code and record that
  load in the namespace ledger. Options conform to `::refresh-opts`; malformed
  options fail loudly. The result conforms to `::plan-result`
  (DELTA-OlrRepl-001.CC14)."
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

  Reports desired modules and their declaration layers/shadows, active
  contributions, module and resource outcomes, root outcomes, and the joined
  loaded-code picture (current bindings, prior bindings, residuals, hard
  conflicts), the nullable `:pending-generation` record for a refused
  non-additive sync, and the last refresh result. It performs no network access,
  file write, source load, registration, or reconcile. The result conforms to
  `::status-result`, including `::resolved-entry-points`
  (DELTA-OlrRepl-001.CC8, DELTA-OlrDrt-001.CC15)."
  [runtime]
  (validate-status-result! (weaver-runtime/module-status runtime)))

(s/fdef status
  :args (s/cat :runtime map?)
  :ret ::status-result)

(defn reload-code!
  "Make `root-lib`'s current synced source live in dependency order (code only).

  The advanced code-only seam: it loads the selected synced root's namespaces in
  dependency order and records exact load-ledger entries, then classifies the
  generation's loaded code against current source. It performs no module
  contribution publication or resource reconciliation — use `refresh!` for the
  normal path. `root-lib` is a root-lib symbol from a family's effective `:roots`
  map (e.g. `skein.spools/kanban`); an unresolvable root fails loudly with a
  `:reason` in ex-data. The result names the reloaded root, its canonical path,
  the namespaces reloaded with their sources, and the residual and hard-conflict
  outcomes from the post-reload classification, conforming to
  `::reload-code-result` (DELTA-OlrRepl-001.CC9)."
  [runtime root-lib]
  (require-valid! ::root-lib root-lib "reload-code! root-lib must be a symbol")
  (let [reload (spool-sync/reload-synced-spool! runtime root-lib)
        loaded (spool-sync/loaded-namespace-status runtime)
        result (assoc (select-keys reload [:root-lib :root :namespaces])
                      :residuals (:residuals loaded)
                      :hard-conflicts (:hard-conflicts loaded))]
    (validate-reload-code-result! result)))

(s/fdef reload-code!
  :args (s/cat :runtime map? :root-lib ::root-lib)
  :ret ::reload-code-result)

;; --- runtime-owned services for trusted spools ------------------------------

(s/def ::resolvable-symbol qualified-symbol?)

(defn clock
  "Return `runtime`'s installed `skein.api.clock.alpha/Clock`."
  [runtime]
  (weaver-runtime/clock runtime))

(s/fdef clock
  :args (s/cat :runtime map?)
  :ret ::clock-api/clock)

(defn now
  "Return the current java.time.Instant from `runtime`'s clock seam.

  Defaults to the real wall clock; deterministic tests inject an advanceable
  clock through `skein.test.alpha/set-clock!`."
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
  (access/with-spool-classloader runtime #(requiring-resolve sym)))

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
  `:skein.api.runtime.alpha/spool-state-opts`; a malformed map fails loudly at
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

;; --- release marker and spools.edn access -----------------------------------

(defn- running-release-marker [runtime]
  (let [result (access/release-marker runtime)
        _ (require-valid! ::specs/release-marker-result result
                          "runtime release marker has an invalid shape")
        {:keys [marker provenance]} result]
    (if (= :none provenance) :none marker)))

(defn- spools-file
  "Return the `java.io.File` for `runtime`'s shared `spools.edn`.

  The result conforms to `:skein.core.specs/spools-file-result`."
  [runtime]
  (let [result (access/spools-file runtime "spools.edn")]
    (require-valid! ::specs/spools-file-result result
                    "runtime spools file has an invalid shape")
    result))

(defn- families-requiring
  "Return `{:family :roots}` rows for the families other than `lib` whose
  `:requires` name any root in `roots`."
  [normalized lib roots]
  (->> normalized
       (remove #(= lib (:family %)))
       (keep (fn [{:keys [family requires]}]
               (let [required (set (filter roots (keys requires)))]
                 (when (seq required)
                   {:family family :roots required}))))
       vec))

;; --- result-shape validators ------------------------------------------------

(defn- validate-approved-result! [result]
  (require-valid! ::approved-result result "runtime approved spool config has an invalid shape")
  result)

(defn- validate-declared-result! [result]
  (require-valid! ::declared-result result "runtime declared spool config has an invalid shape")
  result)

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
    (vary-meta value assoc :skein.runtime/generation (:generation-id runtime))
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
