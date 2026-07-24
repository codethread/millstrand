(ns skein.core.weaver.module-refresh.entry-points
  "Resolve, validate, and bootstrap def-spool entry points for the coordinator.

  A narrow data-first seam for the module-refresh orchestration story. Given a
  module key, its declaration, and the namespace that owns its `spool` var,
  `resolve-entry-points` returns the effective `{:contribute sym :reconcile
  sym}`: fields come from the namespace's public `spool` var validated against
  `::spool`, and unqualified entry-point symbols are qualified against the
  declaring namespace. `resolve-fn!` turns a resolved entry-point symbol into
  the fn value its Var holds, failing loudly otherwise;
  `legacy-resolved-entry-points` bootstraps the retained set from coordinator
  state that predates that set, so live pickup still tears down."
  (:require [clojure.spec.alpha :as s]
            [skein.api.spool.alpha :as spool-api]
            [skein.core.format :as format]))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn resolve-fn!
  "Resolve `callable` to the fn value its Var holds, failing loudly otherwise.

  A clojure Var is itself `ifn?` regardless of what it holds, so the check
  derefs the resolved Var and tests its root value: an unresolvable symbol, or a
  Var whose value is not a fn (a plain data Var, a keyword), fails loudly
  (PROP-Dsp-001.G5)."
  [module-key role callable]
  (let [ns-sym (some-> callable namespace symbol)
        error-data {:module/key module-key
                    :module/role role
                    :module/callable callable}
        var (try
              (when ns-sym
                (when-not (find-ns ns-sym)
                  (require ns-sym))
                (get (ns-interns ns-sym) (symbol (name callable))))
              (catch Throwable throwable
                (throw (ex-info "Module callable did not resolve to a function"
                                error-data
                                throwable))))]
    (when-not (and (var? var) (fn? (deref var)))
      (fail! "Module callable did not resolve to a function" error-data))
    (deref var)))

(defn- validate-spool-value!
  "Validate a module `spool` var value against the shared `::spool` spec.

  The runtime enforcement path runs over the same spec authors validate with
  (PROP-Dsp-001.G6), so a non-map value, unknown keys, an absent entry point, or
  a non-symbol entry point (ADR-002.O1) all fail loudly with explain data."
  [module-key module-ns value]
  (when-not (s/valid? ::spool-api/spool value)
    (fail! (format/reflow
            "|Module spool declaration is malformed: it must be a map carrying a
             |:contribute and/or :reconcile symbol and no other keys")
           {:module/key module-key
            :module/namespace module-ns
            :spool value
            :explain (s/explain-data ::spool-api/spool value)}))
  value)

(defn module-namespace
  "Return the namespace symbol that owns a module's `spool` var, or nil.

  `:ns` and `:load :image` targets own their declared namespace; a `:file`
  target owns the single namespace its file declares (nil when the file declares
  no `ns`, which keeps the authoring-forms-only path)."
  [declaration context]
  (or (:ns declaration) (:source/namespace context)))

(defn- public-spool-var
  "Return the module namespace's public `spool` var, or nil.

  Only a public interned var named `spool` in the loaded `module-ns` is the
  declaration; private and referred vars are ignored (PROP-Dsp-001.G5)."
  [module-ns]
  (when (and module-ns (find-ns module-ns))
    (get (ns-publics module-ns) 'spool)))

(defn- qualify-entry-point [module-ns sym]
  (if (namespace sym)
    sym
    (symbol (name module-ns) (name sym))))

(defn resolve-entry-points
  "Return the effective `{:contribute sym :reconcile sym}` for a module.

  Each field is filled from the module namespace's public `spool` var, whose
  value is validated against `::spool` and whose unqualified symbols are
  qualified against the declaring namespace (PROP-Dsp-001.G1/G2). Entry-point
  keys on the declaration itself arrive only from a graph a live coordinator
  collected before the cutover; they still win per key so that pickup keeps
  resolving (DELTA-Dsp-004.D3). Present keys only."
  [module-key declaration module-ns]
  (let [explicit (select-keys declaration [:contribute :reconcile])
        absent (remove #(contains? explicit %) [:contribute :reconcile])
        from-var (when (seq absent)
                   (when-let [spool-var (public-spool-var module-ns)]
                     (into {}
                           (map (fn [[field sym]]
                                  [field (qualify-entry-point module-ns sym)]))
                           (select-keys
                            (validate-spool-value! module-key module-ns @spool-var)
                            absent))))]
    (merge from-var explicit)))

(defn legacy-resolved-entry-points
  "Bootstrap resolved entry points from pre-upgrade coordinator state.

  A live weaver may pick up this coordinator without restarting. Its existing
  state has no `:resolved-entry-points`, but its authored graph still carries
  the entry-point keys its declarations were collected with. Seed those keys so
  a module omitted by the first post-upgrade full refresh can still run its
  retained removal reconciler."
  [state]
  (into (sorted-map)
        (keep (fn [[key declaration]]
                (let [resolved (select-keys declaration [:contribute :reconcile])]
                  (when (seq resolved)
                    [key resolved]))))
        (:graph state)))

(s/def ::module-key keyword?)
(s/def ::role #{:contribute :reconcile})
(s/def ::callable qualified-symbol?)
(s/def ::contribute qualified-symbol?)
(s/def ::reconcile qualified-symbol?)
(s/def ::resolved-entry-points
  (s/and (s/keys :opt-un [::contribute ::reconcile])
         #(every? #{:contribute :reconcile} (keys %))))
(s/def ::declaration
  (s/and map?
         #(or (nil? (:ns %)) (symbol? (:ns %)))
         #(or (nil? (:contribute %)) (qualified-symbol? (:contribute %)))
         #(or (nil? (:reconcile %)) (qualified-symbol? (:reconcile %)))))
(s/def ::context
  (s/and map?
         #(or (nil? (:source/namespace %))
              (symbol? (:source/namespace %)))))
(s/def ::state
  (s/and map? #(map? (:graph %))))

(s/fdef resolve-fn!
  :args (s/cat :module-key ::module-key :role ::role :callable ::callable)
  :ret fn?)

(s/fdef module-namespace
  :args (s/cat :declaration ::declaration :context ::context)
  :ret (s/nilable symbol?))

(s/fdef resolve-entry-points
  :args (s/cat :module-key ::module-key
               :declaration ::declaration
               :module-ns (s/nilable symbol?))
  :ret ::resolved-entry-points)

(s/fdef legacy-resolved-entry-points
  :args (s/cat :state ::state)
  :ret (s/map-of ::module-key ::resolved-entry-points))
