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

  The op is a thin control surface (TEN-006). Every verb parses declared args,
  hands a request map to the engine's Clojure discovery functions, and stamps
  the answer with its `:operation`; the semantics — live registry reads, opacity,
  refusal — belong to the engine and are documented at `spools/workflow.md`.

  Activate it beside the engine:

      (runtime/module! rt :skein/spools-workflow-cli
        {:ns 'skein.spools.workflow.cli
         :spools ['skein.spools/workflow]
         :after [:skein/spools-workflow]})"
  (:require [skein.api.format.alpha :as fmt]
            [skein.spools.workflow :as workflow]))

(defn- list-request
  "Return the engine list request for parsed `args`.

  `--all` and `--entrypoint` are both absent by default, which is what selects
  the `:start` catalogue; the request spec refuses them together rather than
  ranking one over the other."
  [{:keys [entrypoint all]}]
  (cond-> {}
    entrypoint (assoc :entrypoint (keyword entrypoint))
    all (assoc :all? true)))

(defn workflow-op
  "Handle `strand workflow <verb>`, routing to the engine's discovery reads.

  The registered op handler; resolved by symbol at dispatch time, so it is public
  like the other spools' op handlers. The declared arg-spec rejects an unknown
  verb before dispatch, so the fall-through exists only to keep a direct Clojure
  caller loud."
  [{:op/keys [args]}]
  (let [{:keys [subcommand] target :workflow} args]
    (case (first subcommand)
      "list" {:operation "workflow list"
              :definitions (workflow/catalog (list-request args))}
      "show" (assoc (workflow/definition-view (keyword target))
                    :operation "workflow show")
      (throw (ex-info "Unsupported workflow subcommand"
                      {:subcommand subcommand
                       :allowed ["list" "show"]})))))

(def ^:private workflow-doc
  (fmt/reflow
   "|Discover the workflows this weaver has registered: list the catalogue,
    |show one definition."))

(def ^:private workflow-arg-spec
  "Declared command surface for the `workflow` op."
  {:op "workflow"
   :doc workflow-doc
   :annotations
   {:use-when [(fmt/reflow
                "|Choosing which registered routine fits a piece of work, or
                 |reading one definition's param contract before starting a
                 |run.")]}
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
                            |continue, or call. Mutually exclusive with --all.")}
                    :all
                    {:type :boolean
                     :doc (fmt/reflow
                           "|Drop the capability filter, including opaque legacy
                            |constructor entries, which declare no entrypoints
                            |and so match no filter.")}}
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
                       |and no constructor, render function, or spec predicate
                       |is executed.")]}}}})

(def ^:private catalog-item-return
  {:type :map
   :required {:name :string
              :doc :string
              :entrypoints {:type :collection :items :string}
              :definition :string}})

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
                       :kind :string
                       :opaque :boolean
                       ;; The param contract and declared summary are owned by
                       ;; the engine's ::definition-view spec, which validates
                       ;; them before emission. Restating that tree in a second
                       ;; schema language would be a copy free to disagree.
                       :params :json
                       :declared :json}}}})

(def ^:private workflow-meta
  "Cross-verb narrative for `workflow`, projected by the `about`/`prime`
  meta-verbs."
  {:about (fmt/reflow
           "|workflow is the discovery half of the worker surface over Skein's
            |workflow engine: list answers which registered routines exist and
            |what each may be used for, and show answers what one of them
            |expects before you invoke it. Both read the weaver's live registry,
            |so they describe the definitions this weaver would actually pour,
            |not a catalogue baked in when Skein was built.")
   :prime (fmt/reflow
           "|Run workflow list before choosing a routine, and workflow show
            |<name> before supplying params — the param contract it prints is
            |the one the engine will judge your invocation against. Neither verb
            |expands topology: a workflow reports the loops, calls, checkpoints,
            |and deferred exits it declares, and the ready frontier of a live run
            |is what tells you the next actual step.")})

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

(def spool
  "Entry-point declaration for the workflow CLI module (PROP-Dsp-001 `def spool`
  convention).

  The refresh coordinator resolves `:contribute` from this public var at every
  module evaluation. The module owns no live resources of its own — the registry
  and vocabulary belong to the engine module — so it declares no `:reconcile`."
  {:contribute 'contribute})
