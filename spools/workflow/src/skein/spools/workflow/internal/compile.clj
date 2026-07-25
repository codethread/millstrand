(ns skein.spools.workflow.internal.compile
  "Compile/normalize pipeline for the workflow spool: turn plain workflow data
  into a Skein batch payload.

  This is the recursion cluster — loop and procedure expansion, condition
  filtering, dependency splicing, and the `compile`/`describe` front half live
  together because inline procedure calls recurse back through `compile`. Spec
  keywords stay qualified to `skein.spools.workflow` so `explain`/`s/explain-data`
  paths are unchanged; nothing here registers or re-homes a spec."
  (:refer-clojure :exclude [compile])
  (:require [skein.api.current.alpha :as current]
            [skein.api.spool.alpha :refer [fail! require-valid!]]
            [skein.spools.workflow.internal.definitions :as defs]
            [skein.spools.workflow.internal.specs :as specs]
            [skein.spools.workflow.internal.util :as util]))

(defn- render [value params]
  (cond
    (fn? value) (value params)
    (map? value) (into {} (map (fn [[k v]] [k (render v params)])) value)
    (sequential? value) (mapv #(render % params) value)
    :else value))

(defn- param-defaults [declared]
  (reduce-kv (fn [acc k spec]
               (util/require-keyword! k [:params k])
               (if (and (map? spec) (contains? spec :default))
                 (assoc acc k (:default spec))
                 acc))
             {}
             (or declared {})))

(defn- required-params [declared]
  (->> (or declared {})
       (keep (fn [[k spec]] (when (and (map? spec) (:required spec)) k)))
       set))

(defn- resolve-params [workflow params]
  (let [declared (:params workflow)
        resolved (merge (param-defaults declared) params)
        missing (seq (remove #(contains? resolved %) (required-params declared)))]
    (when missing
      (fail! "Workflow required params are missing" {:missing (vec missing)}))
    resolved))

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

(defn- require-acyclic-procedure! [call-id procedure]
  (when (some #(= % procedure) *procedure-path*)
    (fail! "Workflow procedure call is cyclic"
           {:call call-id
            :procedure procedure
            :path (mapv #(if (symbol? %) % (type %)) *procedure-path*)})))

(defn- resolve-procedure
  "Return the call target's classification `{:kind … :value …}`.

  `:value` is the canonical procedure value — a map or a function — so cycle
  detection and dispatch never depend on whether the author wrote a registered
  name, a symbol, or the value itself. A registered name must declare `:call`;
  anything else is a raw trusted value with no capability to check."
  [procedure]
  (cond
    (keyword? procedure)
    (defs/require-entrypoint! (defs/resolve-registered (current/runtime) procedure) :call)

    (symbol? procedure)
    (if-let [resolved (requiring-resolve procedure)]
      {:kind :raw :value @resolved}
      (fail! "Workflow procedure symbol cannot be resolved" {:procedure procedure}))

    :else {:kind :raw :value procedure}))

(defn- procedure-workflow [procedure params]
  (cond
    (map? procedure) procedure
    (fn? procedure) (procedure params)
    :else (fail! "Workflow procedure must be a workflow map, function, or resolvable symbol"
                 {:procedure procedure})))

(defn- prefixed-ref [call-id ref]
  (keyword (str (name call-id) "--" (name (util/normalize-ref ref [:procedure :ref])))))

(defn- entry-refs [steps]
  (->> steps (remove #(seq (:depends-on %))) (map :id) vec))

(defn- exit-refs [steps]
  (let [depended (set (mapcat :depends-on steps))]
    (->> steps (map :id) (remove depended) vec)))

(defn- expand-call-step [call-step params]
  (let [call-id (util/normalize-ref (:id call-step) [:call :id])
        target (resolve-procedure (:procedure call-step))
        procedure (:value target)
        _ (require-acyclic-procedure! call-id procedure)
        params (defs/definition-params target (merge params (or (:params call-step) {})))
        workflow (procedure-workflow procedure params)
        payload (binding [*procedure-path* (conj *procedure-path* procedure)]
                  (compile workflow params))
        steps (mapv (fn [strand]
                      {:id (:ref strand)
                       :title (:title strand)
                       :state (:state strand)
                       :attributes (:attributes strand)})
                    (rest (:strands payload)))
        edges (:edges payload)
        internal-deps (reduce (fn [acc {:keys [from to type]}]
                                (if (= "depends-on" type)
                                  (update acc from (fnil conj []) to)
                                  acc))
                              {}
                              edges)
        steps-with-deps (mapv #(assoc % :depends-on (get internal-deps (:id %) [])) steps)
        entries (entry-refs steps-with-deps)
        exits (exit-refs steps-with-deps)
        prefixed (mapv (fn [step]
                         (let [id (:id step)
                               internal (mapv #(prefixed-ref call-id %) (get internal-deps id []))
                               entry-extra (when (some #{id} entries) (:depends-on call-step))]
                           (-> step
                               (assoc :id (prefixed-ref call-id id))
                               (assoc :depends-on (vec (concat internal entry-extra))))))
                       steps)
        join-title (or (:title call-step) (str "Complete " (name call-id)))]
    (conj prefixed {:id call-id
                    :title join-title
                    :depends-on (mapv #(prefixed-ref call-id %) exits)
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

(defn- normalize-steps [workflow params root-ref]
  (let [steps (util/require-vector! (:steps workflow) [:steps])
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
  (require-valid! :skein.spools.workflow/workflow workflow "Invalid workflow definition"))

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

(defn- step-attributes [step form]
  (let [attributes (merge {"workflow/role" "step"
                           "workflow/form" (name form)}
                          (:attributes step)
                          (when-let [description (:description step)]
                            {"description" description}))]
    (cond-> attributes
      (get attributes "workflow/choice-details")
      (update "workflow/choice-details" poured-choice-details))))

(defn- step-strand [step form]
  {:ref (util/normalize-ref (:id step) [:steps :id])
   :title (:title step)
   :state (or (:state step) "active")
   :attributes (step-attributes step form)})

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
  "Return `[rendered-workflow resolved-params root-ref normalized-steps]` — the
  materialization-free front half shared by `compile` and `describe`.

  Validates the workflow and params, resolves params (defaults + required
  checks), renders workflow-level fields (step render fns stay live so
  `normalize-steps` can render loop steps against per-item params), and
  expands/condition-filters/splices the steps. Materializes nothing."
  [workflow params opts]
  (util/require-map! workflow [:workflow])
  (require-valid! :skein.spools.workflow.values/params params "Invalid workflow params")
  (require-valid-workflow! workflow)
  (let [params (resolve-params workflow params)
        rendered (assoc (render (dissoc workflow :steps) params)
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
  parent-of + depends-on edges under `root`'s ref."
  [root form steps]
  {:strands (into [root] (mapv #(step-strand % form) steps))
   :edges (vec (concat (parent-edges (:ref root) steps)
                       (dependency-edges steps)))})

(defn compile
  "Return a batch payload for a workflow molecule or wisp.

  The internal recursion entry — `expand-call-step` re-enters here to splice an
  inline procedure's own compiled subgraph — composing the same named stages the
  public `skein.spools.workflow/compile` exposes: `resolve-and-normalize` (the
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
   (let [form (or (:form opts) (:form workflow) :molecule)
         [workflow _params root-ref steps] (resolve-and-normalize workflow params opts)]
     (payload (root-strand workflow root-ref form opts) form steps))))

(defn- step-attr
  "Read string-keyed workflow attribute `k` off a normalized step's `:attributes`
  map (builders write the `workflow/*` vocabulary string-keyed)."
  [step k]
  (get (:attributes step) k))

(defn- describe-choice
  "Project one checkpoint choice into `{:key … :label … :description …
  :input|:input-spec … :next|:revise …}` from its stored
  `workflow/choice-details` entry (`detail`, nil for a bare-keyword choice).

  `:input-spec` carries the declared spec identity and doc without its form
  graph: description stays cheap, and the graph is recorded when the checkpoint
  pours."
  [name detail]
  (cond-> {:key name}
    (get detail "label") (assoc :label (get detail "label"))
    (get detail "description") (assoc :description (get detail "description"))
    (get detail "input") (assoc :input (get detail "input"))
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
    (describe-choices step) (assoc :choices (describe-choices step))))

(defn- json-safe-context-value [value path]
  (cond
    (map? value) (into {} (map (fn [[k v]] [k (json-safe-context-value v (conj path k))])) value)
    (sequential? value) (mapv (fn [[idx v]] (json-safe-context-value v (conj path idx))) (map-indexed vector value))
    (keyword? value) (name value)
    (util/json-scalar? value) value
    (number? value) (fail! "Workflow params cannot be defaulted into workflow/context; non-finite numbers are not JSON-safe"
                           {:path path :value value :type (some-> value type str)})
    :else (fail! "Workflow params cannot be defaulted into workflow/context; pass :context explicitly"
                 {:path path :value value :type (some-> value type str)})))

(defn default-context
  "Return the JSON-safe `workflow/context` derived from start! `params`.

  Keyword values become strings; non-finite numbers and other non-JSON-safe
  values fail loudly (TEN-003) directing the caller to pass `:context` explicitly."
  [params]
  (when-not (map? params)
    (fail! "Workflow context params must be a map" {:params params}))
  (json-safe-context-value params []))

