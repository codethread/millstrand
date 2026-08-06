(ns millstrand.spools.workflow.internal.compile
  "Compile/normalize pipeline for the workflow spool: turn plain workflow data
  into a Millstrand batch payload.

  This is the recursion cluster — loop and procedure expansion, condition
  filtering, dependency splicing, and the `compile`/`describe` front half live
  together because inline procedure calls recurse back through `compile`. Spec
  keywords stay qualified to `millstrand.spools.workflow` so `explain`/`s/explain-data`
  paths are unchanged; nothing here registers or re-homes a spec."
  (:refer-clojure :exclude [compile])
  (:require [millstrand.api.current.alpha :as current]
            [millstrand.api.spool.alpha :refer [fail! require-valid!]]
            [millstrand.spools.workflow.internal.definitions :as defs]
            [millstrand.spools.workflow.internal.specs :as specs]
            [millstrand.spools.workflow.internal.util :as util]))

(defn- render [value params]
  (cond
    (fn? value) (value params)
    (map? value) (into {} (map (fn [[k v]] [k (render v params)])) value)
    (sequential? value) (mapv #(render % params) value)
    :else value))

(defn- include-step? [step params]
  (let [condition (:condition step)]
    (cond
      (nil? condition) true
      (keyword? condition) (boolean (get params condition))
      (and (vector? condition) (= := (first condition))) (= (get params (second condition)) (nth condition 2))
      (and (vector? condition) (= :!= (first condition))) (not= (get params (second condition)) (nth condition 2))
      :else (fail! "Unsupported workflow step condition" {:step (:id step) :condition condition}))))

(defn- loop-values
  "Return the sequence of items a `:loop` expands over, resolved against `params`.

  `{:count n}` yields 1..n. `{:each xs}` accepts a literal sequential, a keyword
  naming a resolved param, or a fn of the resolved params map; a param value or fn
  result that is not sequential fails loudly (TEN-003)."
  [loop-spec params]
  (cond
    (nil? loop-spec) [nil]
    (integer? (:count loop-spec)) (range 1 (inc (:count loop-spec)))
    (contains? loop-spec :each)
    (let [each (:each loop-spec)
          resolved (cond
                     (sequential? each) each
                     (keyword? each) (get params each)
                     (fn? each) (each params))]
      (if (sequential? resolved)
        resolved
        (fail! "Workflow loop :each must resolve to a sequential"
               {:each each :resolved resolved})))
    :else (fail! "Workflow loop requires :count integer or :each collection" {:loop loop-spec})))

(defn- loop-suffix
  "Return the id suffix for one expanded loop item: the number for `:count`,
  `(:id item)` when the item is a map carrying `:id`, else the 1-based position."
  [loop-spec item idx]
  (let [suffix (cond
                 (integer? (:count loop-spec)) item
                 (and (map? item) (contains? item :id)) (:id item)
                 :else (inc idx))]
    (if (or (keyword? suffix) (symbol? suffix)) (name suffix) suffix)))

(defn- expand-loop-step
  "Expand a `:loop` step into one step per item, rendering `:title`,
  `:description`, and `:attributes` against `(merge params {:item item :i idx})`
  so loop steps see both the per-iteration binding (`:i` is the 0-based index)
  and the workflow params. With `:chain true`, expansion i depends on expansion
  i-1 while expansion 0 keeps the step's declared dependencies. Non-loop steps
  pass through unchanged."
  [step params]
  (if-let [loop-spec (:loop step)]
    (let [base-id (util/normalize-ref (:id step) [:step :id])
          items (vec (loop-values loop-spec params))
          expansion-id (fn [idx item]
                         (keyword (str (name base-id) "-" (loop-suffix loop-spec item idx))))]
      (vec (map-indexed
            (fn [idx item]
              (let [env (merge params {:item item :i idx})
                    expanded-id (expansion-id idx item)]
                (cond-> (-> step
                            (dissoc :loop)
                            (assoc :id expanded-id)
                            (update :title render env)
                            (update :description render env)
                            (update :attributes render env))
                  (and (:chain loop-spec) (pos? idx))
                  (assoc :depends-on [(expansion-id (dec idx) (nth items (dec idx)))]))))
            items)))
    [step]))

(defn- call-step? [step]
  (contains? step :procedure))

(declare compile)

(def ^:private ^:dynamic *procedure-path*
  ;; Conditions filter steps only after procedure expansion, so a cyclic
  ;; procedure reference can never terminate; re-entry must fail loudly
  ;; (TEN-003) instead of overflowing the stack.
  [])

(def ^:private ^:dynamic *defer-path*
  ;; A defer records the lexical definition ancestry that encloses it. Unlike
  ;; `*procedure-path*`, which exists solely to reject recursive fixed calls,
  ;; this holds the durable wire values written on every poured defer.
  [])

(defn- defer-identity
  "Return the persisted identity entry for a definition being compiled."
  [workflow definition]
  {"fingerprint" (defs/fingerprint {:value workflow})
   "definition" (some-> definition str)})

(defn- require-acyclic-procedure! [call-id procedure]
  (when (some #(= % procedure) *procedure-path*)
    (fail! "Workflow procedure call is cyclic"
           {:call call-id
            :procedure procedure
            :path (mapv #(if (symbol? %) % (type %)) *procedure-path*)})))

(defn- resolve-procedure
  "Return the call target's classification `{:kind … :value …}`.

  `:value` is the canonical procedure definition map, so cycle detection and
  expansion never depend on whether the author wrote a registered name, a
  symbol, a Var, or the definition itself. A registered name must declare
  `:call`; raw targets skip that entrypoint check but still supply their
  `:defaults` and `:param-spec` when they are definition maps."
  [procedure]
  (cond
    (keyword? procedure)
    (assoc (defs/require-entrypoint! (defs/resolve-registered (current/runtime) procedure) :call)
           :kind :registered)

    (symbol? procedure)
    (if-let [resolved (requiring-resolve procedure)]
      (assoc (defs/classify procedure @resolved) :kind :raw)
      (fail! "Workflow procedure symbol cannot be resolved" {:procedure procedure}))

    (var? procedure) (assoc (defs/resolve-var-input procedure) :kind :raw)

    :else {:kind :raw :value procedure}))

(defn- procedure-workflow [procedure _params]
  (if (map? procedure)
    procedure
    (fail! "Workflow procedure must be a definition map, a Var, or a resolvable symbol"
           {:procedure procedure
            :resolved-class (some-> procedure class .getName)})))

(defn- prefixed-ref
  [call-id ref]
  (keyword (str (name call-id) "--" (name (util/normalize-ref ref [:procedure :ref])))))

(defn- entry-refs
  [steps]
  (->> steps (remove #(seq (:depends-on %))) (map :id) vec))

(defn- exit-refs
  [steps]
  (let [depended (set (mapcat :depends-on steps))]
    (->> steps (map :id) (remove depended) vec)))

(defn procedure-expansion
  "Return a rootless prefixed expansion of compiled procedure `payload`.

  Entry strands inherit `entry-deps`; callers use the returned `:entries`,
  `:exits`, and `:dependencies` to wire their own parent and join shapes."
  [call-id payload entry-deps]
  (let [steps (mapv (fn [strand]
                      {:id (:ref strand) :title (:title strand) :state (:state strand)
                       :attributes (:attributes strand)})
                    (rest (:strands payload)))
        internal (reduce (fn [acc {:keys [from to type]}]
                           (if (= "depends-on" type) (update acc from (fnil conj []) to) acc))
                         {} (:edges payload))
        steps (mapv #(assoc % :depends-on (get internal (:id %) [])) steps)
        entries (entry-refs steps)
        exits (exit-refs steps)
        strands (mapv (fn [step]
                        (let [id (:id step)]
                          (assoc step :id (prefixed-ref call-id id)
                                 :depends-on (vec (concat (map #(prefixed-ref call-id %)
                                                               (get internal id []))
                                                          (when (some #{id} entries) entry-deps))))))
                      steps)]
    {:strands strands
     :entries (mapv #(prefixed-ref call-id %) entries)
     :exits (mapv #(prefixed-ref call-id %) exits)}))

(defn- expand-call-step [call-step params]
  (let [call-id (util/normalize-ref (:id call-step) [:call :id])
        target (resolve-procedure (:procedure call-step))
        procedure (:value target)
        _ (require-acyclic-procedure! call-id procedure)
        ;; A registered call target is reached by name, the same boundary that
        ;; requires its `:call` entrypoint. Every definition map, including one
        ;; reached through a raw value or symbol, applies its `:defaults` and
        ;; judges the params it is expanded with through `:param-spec`.
        params (->> (merge params (or (:params call-step) {}))
                    (defs/definition-params target)
                    (defs/validate-params! target))
        workflow (procedure-workflow procedure params)
        payload (binding [*procedure-path* (conj *procedure-path* procedure)]
                  (compile workflow params
                           (assoc (select-keys target [:definition])
                                  :defer-path *defer-path*)))
        expansion (procedure-expansion call-id payload (:depends-on call-step))
        prefixed (:strands expansion)
        join-title (or (:title call-step) (str "Complete " (name call-id)))]
    (conj prefixed {:id call-id
                    :title join-title
                    :depends-on (:exits expansion)
                    :attributes (merge {"workflow/role" "procedure"
                                        "workflow/procedure" (name call-id)}
                                       (:attributes call-step))})))

(defn- expand-procedures [steps params]
  (mapcat (fn [step]
            (if (call-step? step)
              (expand-call-step step params)
              [step]))
          steps))

(defn- require-no-root-collision! [rendered root-ref]
  (doseq [step rendered]
    (let [ref (util/normalize-ref (:id step) [:steps :id])]
      (when (= ref root-ref)
        (fail! "Workflow step ref collides with the root ref" {:step ref :root-ref root-ref})))))

(defn- excluded-dep-map [excluded]
  (into {}
        (map (fn [step]
               [(util/normalize-ref (:id step) [:steps :id])
                (mapv #(util/normalize-ref % [:steps (:id step) :depends-on]) (:depends-on step))]))
        excluded))

(defn- resolve-dep-refs
  "Resolve one dependency ref owned by `owner-id` (the step whose own
  :depends-on literally names `ref`), splicing transitively through
  condition-excluded steps until reaching included steps, matching beads'
  behavior for conditional steps. Recursing into an excluded step's own deps
  reattributes `owner-id` to that excluded step, so a bad ref is always
  blamed on whichever step's definition actually names it. A ref matching
  neither an included nor an excluded step never existed in the definition
  and fails loudly."
  [included-ids excluded-deps seen owner-id ref]
  (condp contains? ref
    included-ids #{ref}
    excluded-deps
    (if (contains? seen ref)
      #{}
      (into #{}
            (mapcat #(resolve-dep-refs included-ids excluded-deps (conj seen ref) ref %))
            (get excluded-deps ref)))
    (fail! "Workflow step depends on an unknown ref" {:step owner-id :missing ref})))

(defn- splice-depends-on [included excluded]
  (let [included-ids (set (map #(util/normalize-ref (:id %) [:steps :id]) included))
        excluded-deps (excluded-dep-map excluded)]
    (mapv (fn [step]
            (let [dependent-id (util/normalize-ref (:id step) [:steps :id])
                  deps (mapv #(util/normalize-ref % [:steps (:id step) :depends-on]) (:depends-on step))
                  spliced (into [] (distinct)
                                (mapcat #(resolve-dep-refs included-ids excluded-deps #{} dependent-id %) deps))]
              (assoc step :depends-on spliced)))
          included)))

(defn- fan-in-deps
  "Rewrite each step's `:depends-on`: a ref naming a loop step's base id (a key
  of `fanin`) fans out to all that loop's expanded ids, so a downstream step can
  depend on the loop as a whole. Other refs pass through untouched for F4's
  unknown-ref validation to check later."
  [steps fanin]
  (mapv (fn [step]
          (if-let [deps (:depends-on step)]
            (assoc step :depends-on
                   (into [] (distinct)
                         (mapcat (fn [dep]
                                   (let [ref (util/normalize-ref dep [:steps (:id step) :depends-on])]
                                     (get fanin ref [dep])))
                                 deps)))
            step))
        steps))

(defn- require-unique-base-ids!
  "Fail loudly on any collision among steps' pre-expansion ids (loop base ids,
  step ids, call ids) or with `root-ref`. Fan-in keys deps on the base loop id,
  so a base-id collision must be rejected before it can silently misroute a
  dependency; the later expanded-ref checks cannot see base ids."
  [steps root-ref]
  (let [base-ids (mapv #(util/normalize-ref (:id %) [:steps :id]) steps)]
    (when-let [dupes (seq (for [[ref n] (frequencies base-ids) :when (> n 1)] ref))]
      (fail! "Workflow step ids must be unique" {:duplicates (vec dupes)}))
    (when-let [collision (some #{root-ref} base-ids)]
      (fail! "Workflow step ref collides with the root ref" {:step collision :root-ref root-ref}))))

(defn- stamp-defer-paths
  "Return `steps` with every authored defer carrying the engine-owned
  `workflow/defer-path` of the definition being compiled.

  The lineage a cycle check reads is compile's to write, so this overwrites
  whatever the step arrived with: a raw definition map that skipped the builder
  cannot declare an empty ancestry and walk straight past the fill-time
  membership check (PROP-Dfr-001.S5). It runs before procedure expansion, so a
  step spliced in from a nested compile keeps the deeper path that compile
  already computed for it — re-stamping there would flatten the nesting away."
  [steps]
  (mapv (fn [step]
          (cond-> step
            (util/defer-step? step)
            (assoc-in [:attributes "workflow/defer-path"] (vec *defer-path*))))
        steps))

(defn- normalize-steps [workflow params root-ref]
  (let [steps (stamp-defer-paths (util/require-vector! (:steps workflow) [:steps]))
        _ (require-unique-base-ids! steps root-ref)
        expansions (mapv (fn [step]
                           (let [expanded (expand-loop-step step params)]
                             {:base (when (:loop step) (util/normalize-ref (:id step) [:steps :id]))
                              :ids (mapv #(util/normalize-ref (:id %) [:steps :id]) expanded)
                              :steps expanded}))
                         steps)
        fanin (into {} (keep (fn [{:keys [base ids]}] (when base [base ids])) expansions))
        expanded (fan-in-deps (vec (mapcat :steps expansions)) fanin)
        procedures (expand-procedures expanded params)
        rendered (mapv #(render % params) procedures)
        _ (require-no-root-collision! rendered root-ref)
        by-condition (group-by #(include-step? % params) rendered)
        included (vec (get by-condition true))
        excluded (vec (get by-condition false))
        spliced (splice-depends-on included excluded)
        refs (mapv #(util/normalize-ref (:id %) [:steps :id]) spliced)
        duplicates (seq (for [[ref n] (frequencies refs) :when (> n 1)] ref))]
    (when duplicates
      (fail! "Workflow step ids must be unique" {:duplicates (vec duplicates)}))
    (doseq [[idx step] (map-indexed vector spliced)]
      (util/require-map! step [:steps idx])
      (util/require-non-blank! (:title step) [:steps idx :title]))
    spliced))

(defn- require-valid-workflow! [workflow]
  (require-valid! :millstrand.spools.workflow/workflow workflow "Invalid workflow definition"))

(defn- poured-choice-details
  "Return `details` with each spec-first choice input expanded to the spec's
  current form graph.

  The graph is recorded when the checkpoint pours, so the run's history holds
  what the worker was shown at that moment. It is documentation, not a snapshot
  of meaning: `choose!` resolves the spec identity again and validates against
  whatever it names then (PROP-Wcd-001.S10)."
  [details]
  (reduce-kv (fn [acc choice detail]
               (assoc acc choice
                      (if-let [declared (get detail "input-spec")]
                        (assoc detail "input-spec"
                               (assoc declared "spec-forms"
                                      (specs/spec-forms (keyword (get declared "spec")))))
                        detail)))
             {}
             details))

(defn- step-attributes [step form position]
  (let [attributes (merge {"workflow/role" "step"
                           "workflow/form" (name form)}
                          (:attributes step)
                          ;; Engine-owned, so it merges last: a spliced procedure
                          ;; step arrives carrying the position it held inside its
                          ;; own compile, and the position that means anything is
                          ;; the one it holds in the run being poured.
                          {"workflow/position" position}
                          (when-let [description (:description step)]
                            {"description" description}))]
    (cond-> attributes
      (get attributes "workflow/choice-details")
      (update "workflow/choice-details" poured-choice-details))))

(defn- step-strand
  "Build one step strand at `position` in the normalized step order.

  `position` is the step's index after loops expanded and calls spliced, which
  is what gives a ready frontier an order a reader recognizes: the order the
  author wrote, with each loop round in its own order. Strand ids carry no such
  meaning, so without it a frontier reads in an arbitrary order that happens to
  be stable."
  [step form position]
  {:ref (util/normalize-ref (:id step) [:steps :id])
   :title (:title step)
   :state (or (:state step) "active")
   :attributes (step-attributes step form position)})

(defn- dependency-edges [steps]
  (mapcat (fn [step]
            (let [from (util/normalize-ref (:id step) [:steps :id])]
              (for [dep (:depends-on step)]
                {:op :upsert
                 :from from
                 :to (util/normalize-ref dep [:steps (:id step) :depends-on])
                 :type "depends-on"})))
          steps))

(defn- parent-edges [root-ref steps]
  (mapv (fn [step]
          {:op :upsert
           :from root-ref
           :to (util/normalize-ref (:id step) [:steps :id])
           :type "parent-of"})
        steps))

(defn resolve-and-normalize
  "Return `[rendered-workflow params root-ref normalized-steps]` — the
  materialization-free front half shared by `compile` and `describe`.

  Validates the workflow and params, renders workflow-level fields (step render
  fns stay live so `normalize-steps` can render loop steps against per-item
  params), and expands/condition-filters/splices the steps. The params arrive
  already resolved: a definition's `:defaults` merge and its `:param-spec`
  judges the whole map before anything reaches here. Materializes nothing."
  [workflow params opts]
  (util/require-map! workflow [:workflow])
  (require-valid! :millstrand.spools.workflow.values/params params "Invalid workflow params")
  (require-valid-workflow! workflow)
  (let [rendered (assoc (render (dissoc workflow :steps) params)
                        :steps (:steps workflow))
        _ (util/require-non-blank! (:name rendered) [:name])
        root-ref (util/normalize-ref (or (:root-ref opts) :molecule) [:root-ref])
        steps (normalize-steps rendered params root-ref)]
    [rendered params root-ref steps]))

(defn root-strand
  "Build the root strand for a compiled workflow from the rendered `workflow`,
  its `root-ref`, `form` (`:molecule`/`:wisp`), and `opts`. `opts` supplies the
  run-id/family/definition/context stamped onto the root, plus the
  `:root-attributes` a routed continuation carries onto its fresh root.

  A root poured from a registered name persists both `:definition-name` and the
  symbol that name resolved to. The name is what a later revision resolves
  against, so a repointed registry takes effect; the symbol records which
  definition this root was actually built from."
  [workflow root-ref form opts]
  {:ref root-ref
   :title (:name workflow)
   :state (or (:state workflow) "active")
   :attributes (merge {"workflow/role" "root"
                       "workflow/form" (name form)}
                      (:attributes workflow)
                      (:root-attributes opts)
                      (when-let [run-id (:run-id opts)]
                        {"workflow/run-id" run-id})
                      (when-let [family (:family opts)]
                        {"workflow/family" family})
                      (when-let [definition (:definition opts)]
                        {"workflow/definition" (str definition)})
                      (when-let [definition-name (:definition-name opts)]
                        {"workflow/definition-name" (name definition-name)})
                      (when-let [context (:context opts)]
                        {"workflow/context" context}))})

(defn payload
  "Assemble the batch payload from a compiled `root` strand and its normalized
  `steps` for `form`: `root` followed by one `step-strand` per step, and the
  parent-of + depends-on edges under `root`'s ref.

  This is where every pour passes and `describe` does not, so it is where an
  unbound defer is refused: a published template may name a selection point
  nobody has bound yet and still describe itself, but materializing it would
  strand the run at a defer no worker could fill."
  [root form steps]
  (defs/validate-defer-bindings! {:steps steps} {:workflow (:title root)})
  {:strands (into [root] (map-indexed #(step-strand %2 form %1) steps))
   :edges (vec (concat (parent-edges (:ref root) steps)
                       (dependency-edges steps)))})

(defn with-defer-path
  "Call `f` with the lexical defer path for compiling `workflow` and `opts`.

  The public compile story and recursive inline-call compiler share this small
  dynamic-context seam; the named compilation stages stay independently visible."
  [workflow opts f]
  (let [path (conj (or (:defer-path opts) *defer-path*)
                   (defer-identity workflow (:definition opts)))]
    (binding [*defer-path* path]
      (f))))

(defn compile
  "Return a batch payload for a workflow molecule or wisp.

  The internal recursion entry — `expand-call-step` re-enters here to splice an
  inline procedure's own compiled subgraph — composing the same named stages the
  public `millstrand.spools.workflow/compile` exposes: `resolve-and-normalize` (the
  materialization-free front half, including loop/procedure expansion),
  `root-strand`, and `payload`.

  `workflow` accepts plain maps or values produced by the `workflow` builder.
  Each step requires `:id` and `:title`, and may include
  `:description`, `:attributes`, `:state`, `:depends-on`, `:condition`, or a
  simple `:loop` of `{:count n}` / `{:each xs}`. Dynamic names, titles,
  descriptions, and attribute values may be functions of the resolved params map.

  A `:depends-on` ref pointing at a `:condition`-excluded step is spliced onto
  that step's own deps, transitively, matching beads' behavior for conditional
  steps. A ref that matches neither an included nor an excluded step, or a step
  ref colliding with the root ref (`:molecule`, overridable via opts
  `:root-ref`), fails loudly."
  ([workflow]
   (compile workflow {}))
  ([workflow params]
   (compile workflow params {}))
  ([workflow params opts]
   (with-defer-path
     workflow opts
     (fn []
       (let [form (or (:form opts) (:form workflow) :molecule)
             [workflow _params root-ref steps] (resolve-and-normalize workflow params opts)]
         (payload (root-strand workflow root-ref form opts) form steps))))))

(defn- step-attr
  "Read string-keyed workflow attribute `k` off a normalized step's `:attributes`
  map (builders write the `workflow/*` vocabulary string-keyed)."
  [step k]
  (get (:attributes step) k))

(defn- describe-choice
  "Project one checkpoint choice into `{:key … :label … :description …
  :input-spec … :next|:revise …}` from its stored
  `workflow/choice-details` entry (`detail`, nil for a bare-keyword choice).

  `:input-spec` carries the declared spec identity and doc without its form
  graph: description stays cheap, and the graph is recorded when the checkpoint
  pours."
  [name detail]
  (cond-> {:key name}
    (get detail "label") (assoc :label (get detail "label"))
    (get detail "description") (assoc :description (get detail "description"))
    (get detail "input-spec") (assoc :input-spec (get detail "input-spec"))
    (get detail "next") (assoc :next (get detail "next"))
    (get detail "revise") (assoc :revise (get detail "revise"))))

(defn- describe-choices
  "Project a checkpoint step's choices in declared order, or nil for a non-checkpoint."
  [step]
  (when-let [choices (step-attr step "workflow/choices")]
    (let [details (or (step-attr step "workflow/choice-details") {})]
      (mapv #(describe-choice % (get details %)) choices))))

(defn describe-step
  "Project one normalized step into its compile-time description."
  [step]
  (cond-> {:id (util/normalize-ref (:id step) [:steps :id])
           :title (:title step)
           :role (or (step-attr step "workflow/role") "step")
           :depends-on (:depends-on step)}
    (:condition step) (assoc :condition (:condition step))
    (step-attr step "workflow/gate") (assoc :gate (step-attr step "workflow/gate"))
    (step-attr step "workflow/defer") (assoc :defer (step-attr step "workflow/defer"))
    (step-attr step "workflow/defer-workflows")
    (assoc :workflows (step-attr step "workflow/defer-workflows"))
    (describe-choices step) (assoc :choices (describe-choices step))))

(defn- json-safe-context-value [value path]
  (cond
    (map? value) (into {} (map (fn [[k v]] [k (json-safe-context-value v (conj path k))])) value)
    (sequential? value) (mapv (fn [[idx v]] (json-safe-context-value v (conj path idx))) (map-indexed vector value))
    (keyword? value) (if-let [key-ns (namespace value)]
                       (str key-ns "/" (name value))
                       (name value))
    (util/json-scalar? value) value
    (number? value) (fail! "Workflow params cannot be defaulted into workflow/context; non-finite numbers are not JSON-safe"
                           {:path path :value value :type (some-> value type str)})
    :else (fail! "Workflow params cannot be defaulted into workflow/context; pass :context explicitly"
                 {:path path :value value :type (some-> value type str)})))

(defn default-context
  "Return the JSON-safe `workflow/context` derived from start! `params`.

  Keyword values become strings, preserving `ns/name` for qualified keywords;
  non-finite numbers and other non-JSON-safe values fail loudly (TEN-003)
  directing the caller to pass `:context` explicitly."
  [params]
  (when-not (map? params)
    (fail! "Workflow context params must be a map" {:params params}))
  (json-safe-context-value params []))
