(ns skein.spools.workflow.internal.definitions
  "Resolution and capability rules for registered workflow definitions.

  A registry entry is a qualified symbol; what it resolves to decides how the
  engine may use it (PROP-Wcd-001.S6/S13). A map is a *static definition*: it
  declares its own doc, entrypoints, param spec, and defaults, so the engine can
  answer what a workflow is for and how it may be invoked without executing
  anything.

  Resolution goes through the runtime's spool classloader, because a definition
  living in a synced spool root is not on the base classpath. Resolution is also
  deliberately live: it happens at each start, route, call, and revision, so a
  coordinator who repoints a name changes what the *next* transition pours while
  a run already under way keeps the strands it has (PROP-Wcd-001.S8).

  `validate-candidates!` is the pre-publication seam the definition kind
  declares. It sees the complete staged candidate registry, which is the only
  place cross-entry rules can be judged: a checkpoint route naming another
  registered workflow, a call target, a defer's bound target set, and the
  deletions an owner expressed by omitting an entry it used to contribute.

  The defer declaration rules live here too. They read the authored `:steps`
  rather than a compiled graph, so a defer is judged static before any params
  exist, and one predicate answers for the builder, the pour, and publication
  alike."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.format.alpha :as fmt]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [fail! require-valid!]]
            [skein.spools.workflow.internal.registry :as registry]
            [skein.spools.workflow.internal.specs :as specs]
            [skein.spools.workflow.internal.util :as util])
  (:import [java.security MessageDigest]))

(def entrypoint-order
  "The invocation capabilities a static definition may declare, in the order
  every projection reports them.

  `:start` begins a fresh run, `:continue` is an authored checkpoint route, and
  `:call` allows inline procedure expansion inside another workflow — whether
  the caller named the procedure when it was authored or a worker selected it at
  a defer.

  A definition declares its capabilities as a set, so this vector is where the
  reported order comes from: discovery emits `[\"start\" \"continue\"]` for the
  same definition however its author wrote the set."
  [:start :continue :call])

(def entrypoints
  "The invocation capabilities a static definition may declare, as a set."
  (set entrypoint-order))

(def ^:private repair-choices
  ["Restore the Var the registry entry names."
   "Remove the entry from its owner's contribution and refresh."
   "Repoint the name from trusted Clojure with register-workflow!."])

(defn- var-symbol [v]
  (let [m (meta v)]
    (symbol (str (ns-name (:ns m))) (str (:name m)))))

(defn- unresolvable!
  [{:keys [name definition owner]} cause]
  (fail! "Registered workflow definition cannot be resolved"
         (cond-> {:reason :workflow/definition-unresolvable
                  :definition definition
                  :namespace (symbol (namespace definition))
                  :repair repair-choices}
           name (assoc :name name)
           owner (assoc :owner owner))
         cause))

(defn resolve-symbol
  "Resolve definition symbol `definition` to its Var under `rt`'s spool
  classloader, failing loudly as `workflow/definition-unresolvable`.

  `context` carries the registered `:name` and contributing `:owner` when the
  symbol came from the registry, so the failure names who must repair it."
  [rt definition context]
  (let [found (try
                (runtime/resolve-var rt definition)
                (catch Throwable throwable
                  (unresolvable! (assoc context :definition definition) throwable)))]
    (or found (unresolvable! (assoc context :definition definition) nil))))

(defn classify
  "Return the static definition resolved from a workflow Var.

  Registered and direct Var definitions must resolve to a definition map."
  [definition value]
  (cond
    (map? value) {:value value :definition definition
                  :entrypoints (set (:entrypoints value))}
    :else (fail! "Workflow definition must resolve to a static definition map"
                 {:reason :workflow/definition-invalid
                  :definition definition
                  :resolved-class (some-> value class .getName)})))

(defn resolve-registered
  "Return the live classification of registered workflow `name`.

  The result carries `:name`, `:definition` (the resolved symbol), `:value`
  (the definition map itself), and `:entrypoints`. An unregistered name, an
  unresolvable symbol, and a symbol resolving to anything but a definition map
  each fail before any caller can act on them."
  [rt name]
  (let [definition (registry/workflow-definition rt name)
        owner (registry/definition-owner rt name)
        resolved (resolve-symbol rt definition {:name name :owner owner})]
    (assoc (classify definition @resolved) :name name)))

(defn resolve-var-input
  "Return the live classification of a workflow Var passed directly.

  A Var start keeps working without registration: it is trusted Clojure naming
  a definition it can see."
  [v]
  (classify (var-symbol v) @v))

(defn require-entrypoint!
  "Fail loudly unless `resolved`, reached by registered name, declares
  `entrypoint`.

  The registry is where the capability contract applies: a name is reached by
  callers that only know the name, so what they may do with it has to be
  declared. Trusted Clojure holding a Var or a workflow value directly is
  already past that boundary and is not checked (TEN-002)."
  [resolved entrypoint]
  (when (:name resolved)
    (let [declared (:entrypoints resolved)]
      (when-not (contains? declared entrypoint)
        (fail! "Workflow definition does not declare this entrypoint"
               (cond-> {:reason :workflow/entrypoint-unsupported
                        :entrypoint entrypoint
                        :definition (:definition resolved)
                        :entrypoints (vec (sort declared))}
                 (:name resolved) (assoc :name (:name resolved)))))))
  resolved)

(defn definition-params
  "Return the params a static definition compiles with: its `:defaults` under
  the caller's `params`.

  Defaults are a partial overlay by design — a definition may default some keys
  and require the caller to supply the rest — so they are merged, never treated
  as a complete param map."
  [resolved params]
  (merge (:defaults (:value resolved)) params))

(defn validate-params!
  "Return `params` when they satisfy `resolved`'s `:param-spec`.

  The spec owns the *complete* merged map — defaults plus what the caller
  supplied — so it is applied after the merge and before anything compiles or
  pours. Resolution is live: a definition naming a spec that has since been
  removed fails here rather than accepting anything, and a redefined spec judges
  the next invocation. A definition declaring no `:param-spec` is unconstrained."
  [resolved params]
  (if-let [param-spec (:param-spec (:value resolved))]
    (let [context (cond-> {:definition (:definition resolved) :params params}
                    (:name resolved) (assoc :name (:name resolved)))]
      (specs/require-spec! param-spec :workflow/param-spec-missing context)
      (specs/require-conformant! param-spec params :workflow/params-invalid context))
    params))

(defn build
  "Return `{:workflow w :params p}` for `resolved` and caller `params`.

  A definition *is* the workflow; its defaults are folded in before its
  `:param-spec` judges the merged map."
  [resolved params]
  {:workflow (:value resolved)
   :params (validate-params! resolved (definition-params resolved params))})

(defn identity-attrs
  "Return the `:definition`/`:definition-name` compile opts identifying
  `resolved`, so a poured root records what it was built from."
  [resolved]
  (cond-> {:definition (:definition resolved)}
    (:name resolved) (assoc :definition-name (:name resolved))))

(defn fingerprint
  "Return a short hex digest of the definition value `resolved` carries.

  A run that continued into a registered name records this beside the symbol, so
  a reader can tell a later repoint or edit apart from the definition that
  actually poured. It digests the *printed* value, which is exactly as much as it
  claims: two definitions printing identically fingerprint identically, and
  behavior hidden behind a render or predicate function is not fingerprinted at
  all (the same limit `spec-forms` states for live specs)."
  [resolved]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str (:value resolved)) "UTF-8"))]
    (str/join (map #(format "%02x" %) (take 8 digest)))))

;; --- defers -------------------------------------------------------------------

(defn defer-steps
  "Return the declared defer steps of `definition`, in declaration order."
  [definition]
  (filterv util/defer-step? (:steps definition)))

(defn defer-name
  "Return the declared name of defer `step` as a keyword."
  [step]
  (keyword (get-in step [:attributes "workflow/defer"])))

(defn defer-targets
  "Return the registered workflow names `step`'s defer binding allows, as a set
  of keywords. Empty for an unbound defer in a published template."
  [step]
  (into #{} (map keyword) (get-in step [:attributes "workflow/defer-workflows"] [])))

(defn validate-defer-declarations!
  "Return `definition` once every defer it declares is static and carries no
  authored lineage.

  A defer is runtime-selected returning composition, so it takes ordinary
  dependency topology — a step, checkpoint, call, condition, or loop may depend
  on one. What it may not be is conditional or multiplied: a selection point the
  params could delete, or fan out, is not a selection point. The `defer` builder
  rejects `:condition` and `:loop` as unknown opts; this is the check a raw
  definition map registered directly still has to pass (PROP-Dfr-001.S1).

  `workflow/defer-path` is engine-owned lineage. An authored value would be read
  as an already-stamped ancestry, so a defer declaring an empty one would walk
  straight past the fill-time cycle check (PROP-Dfr-001.S5)."
  [definition]
  (doseq [step (defer-steps definition)]
    (doseq [key [:condition :loop]
            :when (contains? step key)]
      (fail! "A workflow defer carries no :condition or :loop"
             {:reason :workflow/defer-not-static
              :defer (defer-name step)
              :key key}))
    (when (contains? (:attributes step) "workflow/defer-path")
      (fail! "A workflow defer may not author workflow/defer-path"
             {:reason :workflow/defer-path-reserved
              :defer (defer-name step)
              :alternative "Remove the attribute; compile stamps the lexical ancestry."})))
  definition)

(defn validate-defer-bindings!
  "Return `definition` once every defer it declares is bound to a non-empty
  target set.

  An unbound defer is a legitimate published *template* — a spool naming a
  selection point without naming another spool's workflows — but it is not
  something a run can reach, so it may not be registered or poured.
  `bind-defers` is what turns the template into a complete definition."
  [definition context]
  (doseq [step (defer-steps definition)
          :when (empty? (defer-targets step))]
    (fail! "Workflow defer is not bound to any registered workflow"
           (assoc context
                  :reason :workflow/defer-unbound
                  :defer (defer-name step)
                  :alternative
                  (fmt/reflow
                   "|Bind the defer with bind-defers before registering or
                    |pouring."))))
  definition)

(defn registry-input?
  "True when `input` names a workflow the registry must resolve.

  A Var and a plain map are already the thing they name, so building from them
  needs no runtime at all — which is what keeps `describe` and pure compilation
  usable outside a weaver."
  [input]
  (keyword? input))

(defn plan
  "Return `{:workflow w :params p}` plus definition identity for `input`.

  `input` is a registered name keyword, a Var, or a plain workflow map. Named and
  Var inputs also yield `:definition` (the resolved symbol) and, for a registered
  name, `:definition-name` — the pair a poured root persists so a later revision
  can resolve the *current* definition of that name rather than the symbol that
  happened to win when the run started.

  `opts` may name a required `:entrypoint`. A plain map carries no identity and
  no declared capability, so it is passed straight through: building and pouring
  an unregistered workflow stays a first-class trusted use."
  ([rt input params] (plan rt input params {}))
  ([rt input params opts]
   (let [resolved (cond
                    (registry-input? input) (resolve-registered rt input)
                    (var? input) (resolve-var-input input)
                    :else nil)]
     (if (nil? resolved)
       {:workflow input :params params}
       (do
         (when-let [entrypoint (:entrypoint opts)]
           (require-entrypoint! resolved entrypoint))
         (merge (build resolved params) (identity-attrs resolved)))))))

;; --- candidate validation -----------------------------------------------------

;; The publication coordinator calls `validate-candidates!` by symbol, so the
;; shape it passes is a contract with no compile-time check behind it. These own
;; it: a coordinator change that reshapes the callback input is a named failure
;; here rather than a nil punning its way through the checks below.
(s/def ::runtime map?)
(s/def ::entries (s/map-of keyword? qualified-symbol?))
(s/def ::owners (s/map-of keyword? keyword?))
(s/def ::candidate-input (s/keys :req-un [::runtime ::entries] :opt-un [::owners]))

(defn- choice-next-names
  "Return the registered workflow names a step's checkpoint choices route to.

  `checkpoint` stores its choices as strand attributes at build time, and a
  registered-name route is recorded there as a stringified keyword (`\":build\"`)
  to keep it distinguishable from a raw definition symbol."
  [step]
  (into #{}
        (keep (fn [[_ detail]]
                (let [next-str (get detail "next")]
                  (when (and (string? next-str) (str/starts-with? next-str ":"))
                    (keyword (subs next-str 1))))))
        (get-in step [:attributes "workflow/choice-details"] {})))

(def use-entrypoint
  "The entrypoint each way of naming another registered workflow requires.

  `:defer` and `:call` both demand the `:call` capability — filling a defer runs
  its target as an inline procedure that returns, which is exactly what a fixed
  call does — but they stay separate uses so a failure can name which one a
  caller took. `:continue` belongs to authored checkpoint routing alone
  (PROP-Dfr-001.S2)."
  {:continue :continue
   :defer :call
   :call :call})

(defn references
  "Return `{:continue #{names} :defer #{names} :call #{names}}` — the registered
  workflows a static definition names, grouped by how it names them."
  [definition]
  (reduce (fn [acc step]
            (cond-> (-> acc
                        (update :continue into (choice-next-names step))
                        (update :defer into (defer-targets step)))
              (keyword? (:procedure step))
              (update :call conj (:procedure step))))
          {:continue #{} :defer #{} :call #{}}
          (:steps definition)))

(defn- validate-defaults!
  [context defaults]
  (when-let [path (and (seq defaults) (util/json-incompatible-path defaults))]
    (fail! "Workflow definition :defaults must be JSON-compatible"
           (assoc context
                  :reason :workflow/defaults-invalid
                  :path path
                  :value (get-in defaults path)))))

(defn- validate-param-spec!
  [context param-spec]
  (when (and param-spec (not (specs/registered? param-spec)))
    (fail! "Workflow definition :param-spec names no registered spec"
           (assoc context :reason :workflow/param-spec-missing
                  :param-spec param-spec))))

(defn- input-spec-names
  "Return the checkpoint input specs a static definition's choices declare.

  `checkpoint` stores a spec-first `:input` under its choice's `input-spec`
  entry at build time, so the declaration is readable here without compiling or
  pouring anything."
  [definition]
  (into #{}
        (comp (mapcat #(vals (get-in % [:attributes "workflow/choice-details"] {})))
              (keep #(get % "input-spec"))
              (keep #(get % "spec"))
              (map keyword))
        (:steps definition)))

(defn- validate-input-specs!
  [context definition]
  (doseq [spec-name (sort (input-spec-names definition))]
    (when-not (specs/registered? spec-name)
      (fail! "Workflow checkpoint choice :input names no registered spec"
             (assoc context :reason :workflow/input-spec-missing
                    :spec spec-name)))))

(defn- validate-references!
  [context definition entry-kinds]
  (doseq [[use names] (references definition)
          target (sort names)]
    (let [entrypoint (get use-entrypoint use)
          declared (get entry-kinds target)]
      (when-not declared
        (fail! "Workflow definition names an unregistered workflow"
               (assoc context :reason :workflow/reference-unregistered
                      :target target
                      :entrypoint entrypoint
                      :registered (vec (sort (keys entry-kinds))))))
      (when-not (contains? (:entrypoints declared) entrypoint)
        (fail! "Workflow definition names a workflow that does not declare the required entrypoint"
               (assoc context :reason :workflow/reference-entrypoint-unsupported
                      :target target
                      :entrypoint entrypoint
                      :declaring-kind use
                      :entrypoints (vec (sort (:entrypoints declared)))))))))

(defn- candidate-entry
  "Resolve one candidate entry into `{:value … :entrypoints …}`, validating
  everything judgeable from the entry alone."
  [rt name definition owner]
  (let [context {:name name :definition definition :owner owner}
        resolved (classify definition @(resolve-symbol rt definition context))
        value (:value resolved)]
    (require-valid! :skein.spools.workflow/definition value
                    "Registered workflow definition is invalid")
    (validate-defaults! context (:defaults value))
    (validate-param-spec! context (:param-spec value))
    (validate-input-specs! context value)
    (validate-defer-declarations! value)
    (validate-defer-bindings! value context)
    resolved))

(defn validate-candidates!
  "Validate the complete staged definition registry before publication.

  The input conforms to `::candidate-input`: the `:runtime`, the kind's complete
  effective `:entries`, and the `:owners` that supplied them (SPEC-003.C23b).

  Every entry is resolved and classified first, so cross-entry checks read the
  same registry the publication would install — including a target an owner has
  just deleted by omission. Throwing here rejects the whole refresh, leaving
  every owner's previous live partition in place."
  [input]
  (require-valid! ::candidate-input input
                  "Workflow definition candidate validation input is invalid")
  (let [{:keys [runtime entries owners]} input
        resolved (reduce-kv (fn [acc name definition]
                              (assoc acc name (candidate-entry runtime name definition
                                                               (get owners name))))
                            {}
                            entries)]
    (doseq [[name {:keys [value]}] resolved]
      (validate-references! {:name name
                             :definition (get entries name)
                             :owner (get owners name)}
                            value
                            resolved))
    resolved))
