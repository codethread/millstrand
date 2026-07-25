(ns skein.spools.workflow.internal.definitions
  "Resolution and capability rules for registered workflow definitions.

  A registry entry is a qualified symbol; what it resolves to decides how the
  engine may use it (PROP-Wcd-001.S6/S13). A map is a *static definition*: it
  declares its own doc, entrypoints, param spec, and defaults, so the engine can
  answer what a workflow is for and how it may be invoked without executing
  anything. A function is a *legacy constructor*: opaque, declaring nothing, and
  usable only by trusted Clojure that already knows what it builds.

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

  The defer topology rules live here too. They read the authored `:steps` rather
  than a compiled graph, so a defer is judged terminal before any params exist,
  and one predicate answers for the builder, the pour, and publication alike."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [fail! require-valid!]]
            [skein.spools.workflow.internal.registry :as registry]
            [skein.spools.workflow.internal.specs :as specs]
            [skein.spools.workflow.internal.util :as util])
  (:import [java.security MessageDigest]))

(def entrypoints
  "The invocation capabilities a static definition may declare.

  `:start` begins a fresh run, `:continue` is any tail continuation — an
  authored checkpoint route or a worker-selected deferred exit — and `:call`
  allows inline procedure expansion inside another workflow."
  #{:start :continue :call})

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
  "Return `{:kind :static|:legacy :value <v> :definition <sym>}` for a resolved
  workflow Var, or fail loudly when its value is neither.

  This type dispatch is the whole compatibility story: a map is self-describing
  and gets generic capabilities, a function is opaque and gets none."
  [definition value]
  (cond
    (map? value) {:kind :static :value value :definition definition
                  :entrypoints (set (:entrypoints value))}
    ;; An opaque function declares nothing, so its capability set is empty
    ;; rather than absent: a caller asking what it may do gets a real answer.
    (ifn? value) {:kind :legacy :value value :definition definition
                  :entrypoints #{}}
    :else (fail! "Workflow definition must resolve to a definition map or a constructor function"
                 {:reason :workflow/definition-invalid
                  :definition definition
                  :resolved-class (some-> value class .getName)})))

(defn resolve-registered
  "Return the live classification of registered workflow `name`.

  The result carries `:name`, `:definition` (the resolved symbol), `:kind`, and
  `:value`. An unregistered name, an unresolvable symbol, and a symbol resolving
  to an unusable value each fail before any caller can act on them."
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

(defn static?
  "True when `resolved` is a static definition map rather than a legacy
  constructor."
  [resolved]
  (= :static (:kind resolved)))

(defn require-entrypoint!
  "Fail loudly unless static `resolved`, reached by registered name, declares
  `entrypoint`.

  The registry is where the capability contract applies: a name is reached by
  callers that only know the name, so what they may do with it has to be
  declared. Trusted Clojure holding a Var or a workflow value directly is
  already past that boundary and is not checked (TEN-002).

  A legacy constructor is never checked either. It declares no capability at
  all, so the trusted paths that predate entrypoints — constructor start,
  raw-symbol routing, direct procedure values — keep working while shipped
  workflows migrate; the generic worker surfaces refuse it outright instead."
  [resolved entrypoint]
  (when (and (static? resolved) (:name resolved))
    (let [declared (:entrypoints resolved)]
      (when-not (contains? declared entrypoint)
        (fail! "Workflow definition does not declare this entrypoint"
               (cond-> {:reason :workflow/entrypoint-unsupported
                        :entrypoint entrypoint
                        :definition (:definition resolved)
                        :entrypoints (vec (sort declared))}
                 (:name resolved) (assoc :name (:name resolved)))))))
  resolved)

(defn- invoke-legacy
  "Call a legacy constructor with `params` and return `{:workflow w :params p}`.

  A constructor may return a bare workflow map, or `{:workflow w :params p}` to
  own the params its result compiles with. Both failure modes are the
  constructor's opacity showing through, so both name the migration to a static
  definition as the fix: the call itself can throw, and what it returns is only
  known to be a workflow after the fact."
  [{:keys [name definition value]} params]
  (let [result (try
                 (value params)
                 (catch Throwable throwable
                   (fail! "Legacy workflow constructor failed"
                          (cond-> {:reason :workflow/legacy-constructor-failed
                                   :definition definition
                                   :params params
                                   :alternative "Migrate to a static spec-first definition."}
                            name (assoc :name name))
                          throwable)))
        ;; A workflow map has its own `:params` key (the legacy declaration
        ;; map), so only `:workflow` can mark the params-owning wrapper form.
        wrapper? (and (map? result) (contains? result :workflow))
        built (if wrapper? (:workflow result) result)]
    (when-not (s/valid? :skein.spools.workflow/workflow built)
      (fail! "Legacy workflow constructor returned an invalid workflow"
             (cond-> {:reason :workflow/legacy-definition-invalid
                      :definition definition
                      :returned built
                      :explain (s/explain-str :skein.spools.workflow/workflow built)
                      :alternative "Migrate to a static spec-first definition."}
               name (assoc :name name))))
    {:workflow built
     :params (if wrapper? (get result :params params) params)}))

(defn definition-params
  "Return the params a static definition compiles with: its `:defaults` under
  the caller's `params`.

  Defaults are a partial overlay by design — a definition may default some keys
  and require the caller to supply the rest — so they are merged, never treated
  as a complete param map."
  [resolved params]
  (if (static? resolved)
    (merge (:defaults (:value resolved)) params)
    params))

(defn validate-params!
  "Return `params` when they satisfy static `resolved`'s `:param-spec`.

  The spec owns the *complete* merged map — defaults plus what the caller
  supplied — so it is applied after the merge and before anything compiles or
  pours. Resolution is live: a definition naming a spec that has since been
  removed fails here rather than accepting anything, and a redefined spec judges
  the next invocation. A definition declaring no `:param-spec` is unconstrained,
  which is what keeps the legacy per-key `:params` declaration working
  (PROP-Wcd-001.S9)."
  [resolved params]
  (if-let [param-spec (and (static? resolved) (:param-spec (:value resolved)))]
    (let [context (cond-> {:definition (:definition resolved) :params params}
                    (:name resolved) (assoc :name (:name resolved)))]
      (specs/require-spec! param-spec :workflow/param-spec-missing context)
      (specs/require-conformant! param-spec params :workflow/params-invalid context))
    params))

(defn build
  "Return `{:workflow w :params p}` for `resolved` and caller `params`.

  A static definition *is* the workflow; only its defaults need folding in,
  after which its `:param-spec` judges the merged map. A legacy constructor has
  to be run to find out what it builds, and declares no spec to judge it with."
  [resolved params]
  (if (static? resolved)
    {:workflow (:value resolved)
     :params (validate-params! resolved (definition-params resolved params))}
    (invoke-legacy resolved params)))

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

;; --- defer exits --------------------------------------------------------------

(defn defer-steps
  "Return the declared defer exit steps of `definition`, in declaration order."
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

(defn validate-defer-topology!
  "Return `definition` once its defer exits are terminal and unconditional.

  A defer is a terminal cross-spool exit: `continue!` closes the whole root and
  pours an independently registered one, so there is nothing for a successor to
  resume into. Every way a definition can continue past a step — a direct
  successor, a conditional or looping step, a procedure call — says so with
  `:depends-on`, which is why one check covers them all, and why it reads the
  declaration rather than an expansion: the answer must not depend on params
  (PROP-Wcd-001.S7).

  The exit itself carries no `:condition` or `:loop` for the same reason. The
  `defer` builder rejects both as unknown opts; this is the check a raw
  definition map registered directly still has to pass."
  [definition]
  (let [defers (into #{} (map #(util/normalize-ref (:id %) [:steps :id]))
                     (defer-steps definition))]
    (when (seq defers)
      (doseq [step (defer-steps definition)
              key [:condition :loop]
              :when (contains? step key)]
        (fail! "A workflow defer exit carries no :condition or :loop"
               {:reason :workflow/defer-not-static
                :defer (defer-name step)
                :key key}))
      (doseq [step (:steps definition)
              dep (:depends-on step)
              :let [ref (util/normalize-ref dep [:steps (:id step) :depends-on])]
              :when (contains? defers ref)]
        (fail! "Workflow steps cannot depend on a defer exit"
               {:reason :workflow/defer-not-terminal
                :defer ref
                :step (util/normalize-ref (:id step) [:steps :id])}))))
  definition)

(defn require-no-defers!
  "Return `definition` once it declares no defer exit, for the procedure-call
  path that must refuse one.

  A `call` join depends on its expansion's exit steps, so an enclosing workflow
  always continues past whatever it calls. That is precisely what a defer cannot
  do, and the two compose into a contradiction rather than a useful topology:
  returning composition stays `call`, and a cross-spool exit stays `defer`."
  [definition context]
  (when-let [defer (first (defer-steps definition))]
    (fail! "A workflow called as a procedure cannot declare a defer exit"
           (assoc context
                  :reason :workflow/defer-in-procedure
                  :defer (defer-name defer)
                  :alternative "Reach the continuation with a defer on the calling workflow.")))
  definition)

(defn validate-defer-bindings!
  "Return `definition` once every defer exit it declares is bound to a non-empty
  target set.

  An unbound defer is a legitimate published *template* — a spool naming an exit
  point without naming another spool's workflows — but it is not something a run
  can reach, so it may not be registered or poured. `bind-defers` is what turns
  the template into a complete definition."
  [definition context]
  (doseq [step (defer-steps definition)
          :when (empty? (defer-targets step))]
    (fail! "Workflow defer exit is not bound to any registered workflow"
           (assoc context
                  :reason :workflow/defer-unbound
                  :defer (defer-name step)
                  :alternative "Bind the exit with bind-defers before registering or pouring.")))
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
  to keep it distinguishable from a raw constructor symbol."
  [step]
  (into #{}
        (keep (fn [[_ detail]]
                (let [next-str (get detail "next")]
                  (when (and (string? next-str) (str/starts-with? next-str ":"))
                    (keyword (subs next-str 1))))))
        (get-in step [:attributes "workflow/choice-details"] {})))

(def use-entrypoint
  "The entrypoint each way of naming another registered workflow requires.

  `:continue` and `:defer` both demand the `:continue` capability — the proposal
  deliberately gives a worker-selected exit and an authored route one capability
  rather than inventing a second one — but they stay separate uses because only a
  defer refuses an opaque legacy target (PROP-Wcd-001.S6/S13)."
  {:continue :continue
   :defer :continue
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
      ;; A legacy constructor declares no entrypoints; it stays reachable by
      ;; name while shipped workflows migrate (PROP-Wcd-001.S13 compatibility).
      ;; A defer is the exception: nothing is registered against that surface
      ;; yet, so it enforces the refusal S13 asks for.
      (when (and (= :defer use) (= :legacy (:kind declared)))
        (fail! "Workflow defer exit names an opaque legacy constructor"
               (assoc context :reason :workflow/legacy-opaque
                      :target target
                      :definition-symbol (:definition declared)
                      :alternative "Migrate the target to a static spec-first definition.")))
      (when (and (= :call use) (= :static (:kind declared)))
        (require-no-defers! (:value declared) (assoc context :target target)))
      (when (and (= :static (:kind declared))
                 (not (contains? (:entrypoints declared) entrypoint)))
        (fail! "Workflow definition names a workflow that does not declare the required entrypoint"
               (assoc context :reason :workflow/reference-entrypoint-unsupported
                      :target target
                      :entrypoint entrypoint
                      :entrypoints (vec (sort (:entrypoints declared)))))))))

(defn- candidate-entry
  "Resolve one candidate entry into `{:kind … :entrypoints …}`, validating
  everything judgeable from the entry alone."
  [rt name definition owner]
  (let [context {:name name :definition definition :owner owner}
        resolved (classify definition @(resolve-symbol rt definition context))]
    (if (static? resolved)
      (let [value (:value resolved)]
        (require-valid! :skein.spools.workflow/definition value
                        "Registered workflow definition is invalid")
        (validate-defaults! context (:defaults value))
        (validate-param-spec! context (:param-spec value))
        (validate-input-specs! context value)
        ;; A registered name is reachable, so its defers must be terminal and
        ;; bound — the builder already refuses both, but a raw map registered
        ;; directly never passed through it.
        (validate-defer-topology! value)
        (validate-defer-bindings! value context)
        resolved)
      resolved)))

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
    (doseq [[name {:keys [kind value]}] resolved
            :when (= :static kind)]
      (validate-references! {:name name
                             :definition (get entries name)
                             :owner (get owners name)}
                            value
                            resolved))
    resolved))
