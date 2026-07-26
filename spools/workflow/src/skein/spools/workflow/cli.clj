(ns skein.spools.workflow.cli
  "The opt-in worker CLI over the workflow engine: the root `workflow` op
  (PROP-Wcd-001.S1).

  This is a module of its own, not part of the engine's contribution. A
  workspace that activates `skein.spools.workflow` gets the engine, its
  registries, and its Clojure API and no CLI verbs at all; the `workflow` op
  appears only when trusted startup config also activates this namespace. That
  split is deliberate. A spool that pours workflows for its own domain surface
  should not thereby hand every worker a generic way to drive those runs, and a
  workspace that wants exactly that should not have to accept the engine's
  vocabulary by accident.

  The op is a thin control surface (TEN-006). Every verb parses declared args
  and hands a request map to the engine function that owns the answer; the
  semantics — live registry reads, role-aware frontier resolution,
  concurrency, refusal — belong to the engine and are documented at
  `spools/workflow.md`. Nothing here resolves a step, infers a role, or names an
  operation the engine does not.

  Activate it beside the engine:

      (runtime/module! rt :skein/spools-workflow-cli
        {:ns 'skein.spools.workflow.cli
         :spools ['skein.spools/workflow]
         :after [:skein/spools-workflow]})"
  (:require [clojure.string :as str]
            [skein.api.format.alpha :as fmt]
            [skein.api.runtime.glossary.alpha :as glossary]
            [skein.spools.workflow :as workflow]))

(defn- list-request
  "Return the engine list request for parsed `args`.

  When `--entrypoint` is absent, the engine selects the `:start` catalogue."
  [{:keys [entrypoint]}]
  (cond-> {}
    entrypoint (assoc :entrypoint (keyword entrypoint))))

(defn- carry
  "Return `request` with `args`' `flag` under `key`, when the worker supplied it.

  Presence, not truthiness: a flag the worker did not pass must stay absent,
  because the engine's request specs read absence as \"infer this\". A flag
  supplied as an empty string or a zero is something the worker did say, and the
  request spec is what judges it."
  [request args flag key]
  (cond-> request
    (contains? args flag) (assoc key (get args flag))))

(defn- run-request
  "Return the request keys every run verb shares, from parsed `args`."
  [args]
  (-> {:run-id (:run-id args)}
      (carry args :step :step)
      (carry args :by :by)))

(defn- with-json-object
  "Return `request` with `args`' `flag` parsed into `key` as a params map.

  The JSON-bearing counterpart of `carry`: a supplied `--params null` parses to
  nil, and `json->params` refuses it rather than letting an unsupplied flag and a
  wrongly stated one mean the same thing."
  [request args flag key]
  (cond-> request
    (contains? args flag) (assoc key (workflow/json->params (get args flag)))))

;; --- complete's attribute pair ---------------------------------------------
;; `strand add`'s contract, kept verb-for-verb: repeatable `--attr key=value` for
;; string values, `--attributes` for a typed JSON object underneath it, and a
;; duplicate key within one `--attr` priority is a mistake rather than a silent
;; last-wins. The blessed :map parser collapses duplicates before a handler sees
;; them, so the raw argv is where that check has to read the keys from.

(defn- attr-flag-keys [argv]
  (keep (fn [[flag token]]
          (when (= "--attr" flag)
            (subs token 0 (str/index-of token "="))))
        (partition 2 1 argv)))

(defn- check-attr-duplicates! [argv]
  (when-let [dup (some (fn [[k n]] (when (> n 1) k))
                       (frequencies (attr-flag-keys argv)))]
    (throw (ex-info (str "Duplicate attribute key in --attr: " dup)
                    {:reason :workflow/attr-key-duplicate :key dup}))))

(defn- attributes->map
  "Coerce a supplied `--attributes` value into an attribute map, failing loudly on
  anything but a JSON object. A JSON null parses to nil and is rejected here
  rather than read as an empty patch, so a wrongly stated flag and an unsupplied
  one never mean the same thing."
  [attributes]
  (when-not (map? attributes)
    (throw (ex-info "--attributes must reference a JSON object"
                    {:reason :workflow/attributes-invalid :value attributes})))
  (doseq [k (keys attributes)]
    (when (str/blank? k)
      (throw (ex-info "--attributes contains a blank attribute key"
                      {:reason :workflow/attributes-invalid :key k}))))
  attributes)

(defn- with-attributes
  "Return `request` carrying the merged `--attr`/`--attributes` map, when either
  flag was supplied. `--attr` wins key by key, as it does on `strand add`."
  [request args argv]
  (if (or (contains? args :attr) (contains? args :attributes))
    (do (check-attr-duplicates! argv)
        (assoc request :attributes
               (merge (when (contains? args :attributes)
                        (attributes->map (:attributes args)))
                      (or (:attr args) {}))))
    request))

(defn workflow-op
  "Handle `strand workflow <verb>`, routing to the engine's worker surface.

  The registered op handler; resolved by symbol at invocation time, so it is public
  like the other spools' op handlers. Each verb assembles a request map and hands
  it to the engine function that owns the semantics — the CLI resolves no step,
  infers no role, and stamps no outcome of its own. The declared arg-spec rejects
  an unknown verb before invocation, so the fall-through exists only to keep a
  direct Clojure caller loud."
  [{:op/keys [args argv]}]
  (let [{:keys [subcommand choice] target :workflow} args]
    (case (first subcommand)
      "list" {:operation "workflow list"
              :definitions (workflow/catalog (list-request args))}
      "show" (assoc (workflow/definition-view (keyword target))
                    :operation "workflow show")
      "start" (workflow/run-start!
               (-> {:run-id (:run-id args) :workflow (keyword target)}
                   (with-json-object args :params :params)))
      "ready" (workflow/run-ready {:run-id (:run-id args)})
      "complete" (workflow/run-complete!
                  (-> (with-attributes (run-request args) args argv)
                      (with-json-object args :context :context)))
      "choose" (workflow/run-choose!
                (-> (assoc (run-request args) :choice choice)
                    (with-json-object args :input :input)))
      "defer" (workflow/run-defer!
               (-> (assoc (run-request args) :workflow (keyword target))
                   (with-json-object args :params :params)))
      "await" (workflow/run-await
               (carry {:run-id (:run-id args)} args :timeout-secs :timeout-secs))
      (throw (ex-info "Unsupported workflow subcommand"
                      {:subcommand subcommand
                       :allowed ["list" "show" "start" "ready" "complete"
                                 "choose" "defer" "await"]})))))

(def ^:private workflow-doc
  (fmt/reflow
   "|Discover and drive the workflows this weaver has registered: list the
    |catalogue, show one definition, then start a run and move it through its
    |ready frontier."))

(def ^:private run-id-positional
  {:name :run-id
   :type :string
   :required? true
   :doc "Workflow run id."})

(def ^:private step-flag
  {:type :string
   :doc (fmt/reflow
         "|Ready step id, to disambiguate a frontier with more than one item
          |this verb could act on. Required to close a gate.")})

(def ^:private by-flag
  {:type :string
   :doc (fmt/reflow
         "|Who is acting, recorded on the closed item. Required to close a
          |gate.")})

(def ^:private workflow-arg-spec
  "Declared command surface for the `workflow` op."
  {:op "workflow"
   :doc workflow-doc
   :annotations
   {:use-when [(fmt/reflow
                "|Choosing which registered routine fits a piece of work, or
                 |reading one definition's param contract before starting a
                 |run.")
               (fmt/reflow
                "|Driving a run you own: start it, read what is ready, and
                 |complete, choose, or defer your way through it.")]}
   :subcommands
   {"list" {:doc (fmt/reflow
                  "|List registered workflow definitions: name, doc,
                   |entrypoints, and definition symbol.")
            :hook-class :read
            :deadline-class :standard
            :flags {:entrypoint
                    {:type :string
                     :doc (fmt/reflow
                           "|Select one invocation capability: start (default),
                            |continue, or call.")}}
            :annotations
            {:notes [(fmt/reflow
                      "|The catalogue is read from the live registry on every
                       |call, so a refreshed or repointed definition shows up
                       |without a restart.")
                     (fmt/reflow
                      "|Items are ordered by registered name and carry only
                       |name, doc, entrypoints, and definition; everything else
                       |is a workflow show away.")]}}
    "show" {:doc (fmt/reflow
                  "|Show one registered workflow definition in full: params,
                   |defaults, spec forms, and declared shape.")
            :hook-class :read
            :deadline-class :standard
            :positionals [{:name :workflow
                           :type :string
                           :required? true
                           :doc "Registered workflow name."}]
            :annotations
            {:notes [(fmt/reflow
                      "|Answers for any registered definition, including the
                       |call-only and continue-only components list omits by
                       |default.")
                     (fmt/reflow
                      "|Topology-lazy and side-effect free: loops, calls, and
                       |continuations are reported as declared, never expanded,
                       |and no render function or spec predicate is
                       |executed.")]}}
    "start" {:doc (fmt/reflow
                   "|Pour a registered workflow as a new run and return its
                    |opening ready frontier.")
             :hook-class :mutating
             :deadline-class :standard
             :positionals [run-id-positional]
             :flags {:workflow
                     {:type :string
                      :required? true
                      :doc "Registered workflow name; it must declare the start entrypoint."}
                     :params
                     {:type :string
                      :parse :json
                      :doc (fmt/reflow
                            "|JSON object of the definition's own params. Its
                             |defaults merge underneath and its param spec judges
                             |the merged map.")}}
             :annotations
             {:notes [(fmt/reflow
                       "|Only a registered name can be started here. Pouring a
                        |workflow map or a definition var is trusted Clojure.")]}}
    "ready" {:doc "Show the complete current ready frontier of a run."
             :hook-class :read
             :deadline-class :standard
             :positionals [run-id-positional]
             :annotations
             {:notes [(fmt/reflow
                       "|Reports every ready item of every role, so a worker can
                        |see the siblings its own verb would filter out. Every
                        |mutation returns this same shape.")]}}
    "complete" {:doc "Close the ready ordinary step of a run."
                :hook-class :mutating
                :deadline-class :standard
                :positionals [run-id-positional]
                :flags {:step step-flag
                        :by by-flag
                        :context
                        {:type :string
                         :parse :json
                         :doc (fmt/reflow
                               "|JSON object shallow-merged into the run's
                                |workflow context in the closing transaction.")}
                        :attr
                        {:type :map
                         :doc (fmt/reflow
                               "|String attribute key=value merged onto the
                                |closed step; repeatable, highest precedence.
                                |Values may be payload references.")}
                        :attributes
                        {:type :string
                         :parse :json
                         :doc (fmt/reflow
                               "|JSON object of typed attributes merged onto the
                                |closed step (lowest precedence).")}}
                :annotations
                {:notes [(fmt/reflow
                          "|The sole ready ordinary step is inferred: a
                           |checkpoint or defer exit ready beside it is not
                           |ambiguity, because neither is a step this verb could
                           |close.")
                         (fmt/reflow
                          "|A gate is never inferred. Closing one asserts that
                           |something outside the run happened, so it takes both
                           |--step and --by.")
                         (fmt/reflow
                          "|Attributes ride the closing mutation, so no observer
                           |sees the step closed without them. The engine records
                           |no outcome prose of its own: name your own keys.")]}}
    "choose" {:doc "Record a choice on the ready checkpoint of a run."
              :hook-class :mutating
              :deadline-class :standard
              :positionals [run-id-positional
                            {:name :choice
                             :type :string
                             :required? true
                             :doc "Choice key declared by the checkpoint."}]
              :flags {:input
                      {:type :string
                       :parse :json
                       :doc "JSON object satisfying the choice's own input contract."}
                      :step step-flag
                      :by by-flag}
              :annotations
              {:notes [(fmt/reflow
                        "|A routed choice pours its continuation in the same
                         |mutation, so the frontier returned is already the
                         |continuation's.")]}}
    "defer" {:doc "Fill the ready defer of a run with a registered workflow."
             :hook-class :mutating
             :deadline-class :standard
             :positionals [run-id-positional]
             :flags {:workflow
                     {:type :string
                      :required? true
                      :doc (fmt/reflow
                            "|Registered workflow to run at the defer; the
                             |allowlist must permit it and it must declare the call
                             |entrypoint.")}
                     :params
                     {:type :string
                      :parse :json
                      :doc "JSON object of the target's own params."}
                     :step step-flag
                     :by by-flag}
             :annotations
             {:notes [(fmt/reflow
                       "|Returning composition: the selected workflow pours beneath
                        |the current root, and the run resumes after it finishes.")]
              :failure-modes ["workflow/ready-defer-absent"
                              "workflow/ready-defer-ambiguous"
                              "workflow/ready-defer-incompatible"
                              "workflow/defer-target-not-allowed"
                              "workflow/defer-cyclic"
                              "workflow/step-not-defer"]}}
    "await" {:doc "Block until a run is done or needs a worker."
             :hook-class :read
             :deadline-class :unbounded
             :positionals [run-id-positional]
             :flags {:timeout-secs
                     {:type :int
                      :doc (fmt/reflow
                            "|Seconds to block before answering with the timeout
                             |reason (default 1800). Cap blocking awaits at ~50
                             |minutes and re-issue, so provider prompt caches do
                             |not expire while idle.")}}
             :annotations
             {:notes [(fmt/reflow
                       "|The reason says which attention the run needs: done,
                        |checkpoint, defer, step, gate, stalled, or timeout. A
                        |frontier that is entirely executor-owned and healthy is
                        |what this call waits through.")]}}}})

(def ^:private catalog-item-return
  {:type :map
   :required {:name :string
              :doc :string
              :entrypoints {:type :collection :items :string}
              :definition :string}})

(def ^:private ready-item-return
  ;; Every ready item names itself the same way; what it carries beyond that is
  ;; its role's business, owned by the engine's ::ready-item spec. Restating that
  ;; branching in a second schema language would be a copy free to disagree.
  {:type :map
   :required {:id :string
              :role :string
              :title :string}
   :extra :json})

(def ^:private run-result-return
  {:type :map
   :required {:operation :string
              :run-id :string
              :root {:type :map
                     :required {:id :string :title :string :state :string}}
              :ready {:type :collection :items ready-item-return}
              :done :boolean}})

(def ^:private workflow-returns
  {:subcommands
   {"list" {:type :map
            :required {:operation :string
                       :definitions {:type :collection :items catalog-item-return}}}
    "show" {:type :map
            :required {:operation :string
                       :name :string
                       :doc :string
                       :entrypoints {:type :collection :items :string}
                       :definition :string
                       ;; The param contract and declared summary are owned by
                       ;; the engine's ::definition-view spec, which validates
                       ;; them before emission. Restating that tree in a second
                       ;; schema language would be a copy free to disagree.
                       :params :json
                       :declared :json}}
    "start" run-result-return
    "ready" run-result-return
    "complete" run-result-return
    "choose" run-result-return
    "defer" run-result-return
    "await" {:type :map
             :required {:operation :string
                        :run-id :string
                        :reason :string
                        :ready {:type :collection :items ready-item-return}
                        :done :boolean}
              ;; `detail` is the item behind the reason, so its shape is the
              ;; reason's; ::attention-result owns it.
             :optional {:detail :json}}}})

(def ^:private workflow-meta
  "Cross-verb narrative for `workflow`, projected by the `about`/`prime`
  meta-verbs."
  {:about (fmt/reflow
           "|workflow is the worker surface over Skein's workflow engine. list
            |and show answer which registered routines exist and what one of them
            |expects, both read from the weaver's live registry rather than a
            |catalogue baked in when Skein was built. start, ready, complete,
            |choose, defer, and await drive a run: they share one
            |result shape — the run, its current root, its complete ready
            |frontier, and whether it is done — so every call tells you what
            |you may do next without a second read.")
   :prime (fmt/reflow
           "|Run workflow list before choosing a routine and workflow show
            |<name> before supplying params; the param contract it prints is the
            |one the engine will judge your invocation against. Then start the
            |run and work its frontier: complete a step, choose a checkpoint, or
            |defer to a registered routine. Each verb
            |infers the sole ready item of its own role, so pass --step only
            |when it says the frontier is ambiguous — and always to close a
            |gate, which also needs --by. If a mutation fails as
            |workflow/frontier-stale, another worker moved the run: re-read
            |workflow ready and act on what is there now.")})

(def ^:private workflow-glossary
  [{:name "workflow/ready-defer-absent"
    :definition "No ready defer exists for workflow defer to fill."}
   {:name "workflow/ready-defer-ambiguous"
    :definition "More than one defer is ready; workflow defer needs --step."}
   {:name "workflow/ready-defer-incompatible"
    :definition "The selected ready item is not a defer."}
   {:name "workflow/defer-target-not-allowed"
    :definition "The selected workflow is not in the defer point's allowlist."}
   {:name "workflow/defer-cyclic"
    :definition "The selected workflow is already in this defer's lexical ancestry."}
   {:name "workflow/step-not-defer"
    :definition "The step named for workflow defer holds another role."}])

(defn contribute
  "Return the workflow CLI module's complete operation contribution.

  One entry, assembled into the canonical `::op-entry` shape (string key,
  `:name`, the handler `:fn`, provenance, and arg-spec node metadata) exactly as
  `register-op!` would — mirrored here because a blessed spool may not reach the
  weaver's internal op-entry plumbing (SPEC-003.C19a). Publishing the op as this
  module's complete partition is also what makes opting back out real: dropping
  the module and refreshing removes the verb from the effective registry."
  [_ctx]
  {:ops {:entries {"workflow" (merge {:name "workflow"
                                      :fn 'skein.spools.workflow.cli/workflow-op
                                      :stream? false
                                      :provenance 'skein.spools.workflow.cli
                                      :doc workflow-doc
                                      :arg-spec workflow-arg-spec
                                      :returns workflow-returns}
                                     workflow-meta)}}})

(defn reconcile
  "Seed the workflow worker CLI's failure glossary when the module is applied.

  Removal is deliberately effect-free: the glossary API ships register and
  replace but no unregister, so outcomes are process-lifetime seeds."
  [{:keys [runtime] :as ctx}]
  (case (get-in ctx [:module/contribution :status])
    :applied (do (doseq [outcome workflow-glossary]
                   (glossary/register-glossary-outcome!
                    runtime (assoc outcome :owner 'skein.spools.workflow.cli)))
                 {:reconciled :workflow-cli-glossary})
    :removed {:reconciled :removed}
    (throw (ex-info "Unsupported module contribution status"
                    {:status (get-in ctx [:module/contribution :status])
                     :module/key (:module/key ctx)}))))

(def spool
  "Entry-point declaration for the workflow CLI module (PROP-Dsp-001 `def spool`
  convention).

  The refresh coordinator resolves `:contribute` from this public var at every
  module evaluation. The op registry and vocabulary belong to the engine module;
  `reconcile` seeds this module's process-lifetime glossary outcomes."
  {:contribute 'contribute
   :reconcile 'reconcile})
