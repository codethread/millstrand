(ns skein.spools.workflow.internal.discovery
  "Registry discovery projections: the compact catalogue `list` returns and the
  full-fidelity point read `show` returns (PROP-Wcd-001.S3).

  Both read the live registry at the moment they are called, so a repointed
  name, a refreshed owner partition, and a reloaded definition Var are visible
  to the next call without a restart. Neither executes anything a definition
  carries: no constructor is invoked, no render function for a name/title/
  attribute is called, no `:condition` is evaluated, and no spec predicate runs.
  Every value emitted here is read from declaration data or from the `s/form`
  documentation graph, which is what makes a catalogue safe to ask for at any
  time.

  The projections are topology-lazy on purpose. `show` reports what a definition
  *declares* — its entry items, loops, gates, checkpoints, calls, defer exits,
  and the registered workflows it routes to — and never expands one. An
  expansion depends on params that do not exist yet, and a deferred exit cannot
  be honestly described before a worker fills it.

  Definitions that declare nothing are reported as such rather than hidden. A
  legacy constructor is opaque: it gets an empty entrypoint vector, a stated
  opacity marker, and no invented contract, so a reader can tell \"declares no
  params\" apart from \"is a function nobody can inspect\"."
  (:require [skein.spools.workflow.internal.definitions :as defs]
            [skein.spools.workflow.internal.registry :as registry]
            [skein.spools.workflow.internal.specs :as specs]
            [skein.spools.workflow.internal.util :as util :refer [require-shape!]]))

(def ^:private legacy-doc
  "The doc reported for an opaque legacy constructor entry.

  A constructor declares nothing about itself, so there is no authored doc to
  report. Stating the opacity in the field every entry carries keeps the
  catalogue one uniform shape instead of a nullable field every reader has to
  branch on."
  "Opaque legacy constructor: declares no doc, entrypoints, or param contract.")

(def ^:private undeclared-doc
  "The doc reported for a static definition that declares none.

  `defworkflow` requires a doc, but a raw definition map registered directly may
  omit it; the catalogue says so rather than dropping the field."
  "No doc declared.")

(defn- item-id
  "Return the declared id of workflow item `item` as a plain string."
  [item]
  (name (util/normalize-ref (:id item) [:steps :id])))

(defn- entrypoint-names
  "Return `resolved`'s declared entrypoints as an ordered vector of strings."
  [resolved]
  (into [] (comp (filter (:entrypoints resolved)) (map name)) defs/entrypoint-order))

(defn- definition-doc
  [resolved]
  (if (defs/static? resolved)
    (or (:doc (:value resolved)) undeclared-doc)
    legacy-doc))

(defn- catalog-item
  "Return the compact catalogue entry for `resolved`.

  Exactly the four fields `::catalog-item` requires: what the workflow is called,
  what it is for, how it may be invoked, and where its definition lives. Anything
  a worker needs beyond choosing between routines is a `show` away, which is what
  keeps a catalogue read cheap however many workflows a workspace registers."
  [resolved]
  (require-shape! :skein.spools.workflow/catalog-item
                  {:name (name (:name resolved))
                   :doc (definition-doc resolved)
                   :entrypoints (entrypoint-names resolved)
                   :definition (str (:definition resolved))}
                  :workflow/catalog-item-invalid
                  "Workflow catalogue item is invalid"
                  {:name (:name resolved)}))

;; --- declared summaries -------------------------------------------------------

(defn- checkpoint-item?
  [item]
  (= "checkpoint" (get-in item [:attributes "workflow/role"])))

(defn- gate-item?
  [item]
  (contains? (:attributes item) "workflow/gate"))

(defn- call-item?
  [item]
  (contains? item :procedure))

(defn- entry-item?
  "True when `item` waits for nothing declared and is therefore ready at pour.

  Conditions can still exclude an entry item for a given param map, which is
  exactly why this reads the declaration and reports it as declared."
  [item]
  (empty? (:depends-on item)))

(defn- loop-view
  "Return the declared summary of a looping step.

  `:each` names the param the items come from when it is a keyword; a literal
  collection and a function of params are reported as such rather than resolved,
  because resolving either means running the definition against params discovery
  does not have."
  [item]
  (let [{:keys [each chain] loop-count :count} (:loop item)]
    (cond-> {:step (item-id item)}
      (keyword? each) (assoc :each (name each))
      (sequential? each) (assoc :each "literal")
      (fn? each) (assoc :each "fn")
      loop-count (assoc :count loop-count)
      chain (assoc :chain true))))

(defn- gate-view
  [item]
  {:step (item-id item)
   :waiter (get-in item [:attributes "workflow/gate"])})

(defn- checkpoint-view
  "Return the declared summary of a checkpoint: its id and its choice keys.

  The keys are what a worker needs to know a decision is coming and what it will
  be asked. Each choice's input contract belongs to the ready frontier, where the
  live spec is resolved against the run rather than described in advance."
  [item]
  {:step (item-id item)
   :choices (vec (get-in item [:attributes "workflow/choices"] []))})

(defn- procedure-view
  "Return `{:procedure … :kind …}` for a call's declared target.

  The kind is the whole compatibility answer: only a `\"registered\"` target is a
  name discovery can follow to another catalogue entry, while a symbol, Var, or
  inline definition is trusted Clojure naming something the registry never saw."
  [procedure]
  (cond
    (keyword? procedure) {:procedure (name procedure) :kind "registered"}
    (symbol? procedure) {:procedure (str procedure) :kind "symbol"}
    (var? procedure) {:procedure (str (symbol procedure)) :kind "var"}
    (map? procedure) {:procedure (str (:name procedure)) :kind "inline"}
    :else {:procedure "fn" :kind "constructor"}))

(defn- call-view
  [item]
  (assoc (procedure-view (:procedure item)) :step (item-id item)))

(defn- defer-view
  "Return the declared summary of a defer exit: the named exit and the registered
  workflows its binding allows, in the stored order."
  [item]
  {:step (item-id item)
   :defer (name (defs/defer-name item))
   :workflows (vec (get-in item [:attributes "workflow/defer-workflows"] []))})

(defn- declared-view
  "Return the declared-shape summary of static `definition`.

  Every vector holds declaration order — the order the author wrote — except
  `:routes`, which is a set of registered names and is therefore sorted. Nothing
  here is an expansion: a loop reports its declared source, a call reports its
  declared target, and a defer reports its binding."
  [definition]
  (let [items (:steps definition)
        defers (filterv util/defer-step? items)
        calls (filterv call-item? items)
        checkpoints (filterv checkpoint-item? items)]
    {:kind "static"
     :entry (mapv item-id (filterv entry-item? items))
     :loops (mapv loop-view (filterv :loop items))
     :gates (mapv gate-view (filterv gate-item? items))
     :checkpoints (mapv checkpoint-view checkpoints)
     :calls (mapv call-view calls)
     :defers (mapv defer-view defers)
     :routes (mapv name (sort (:continue (defs/references definition))))}))

;; --- param contract views -----------------------------------------------------

(defn- param-view
  "Return the view of one deprecated per-key param declaration.

  A per-key `:default` may be a function of the params resolved at compile time.
  Discovery never calls one, so a computed default is reported as `:rendered`
  rather than as a value that does not exist yet."
  [declaration]
  (let [declared? (contains? declaration :default)
        computed? (and declared?
                       (some? (util/json-incompatible-path (:default declaration))))]
    (cond-> {:required (boolean (:required declaration))}
      (and declared? (not computed?)) (assoc :default (:default declaration))
      computed? (assoc :rendered true))))

(defn- declared-params-view
  [definition]
  (into {} (map (fn [[key declaration]] [(name key) (param-view declaration)]))
        (:params definition)))

(defn- params-view
  "Return the param contract view of static `definition`.

  `:kind` says which contract judges an invocation: `\"spec\"` when the
  definition names a whole-map `:param-spec`, `\"declared\"` when it carries only
  the deprecated per-key declarations, and `\"none\"` when it constrains nothing.
  The spec is resolved live and reported through its current `s/form` graph, so a
  spec redefined since the definition was authored documents itself as it is
  now — and one that has since been deleted fails loudly instead of reading as an
  unconstrained workflow."
  [definition]
  (let [param-spec (:param-spec definition)
        declared (declared-params-view definition)]
    (cond-> {:kind (cond param-spec "spec" (seq declared) "declared" :else "none")
             :defaults (or (:defaults definition) {})}
      param-spec (assoc :spec (subs (str param-spec) 1)
                        :spec-forms (specs/spec-forms param-spec))
      (seq declared) (assoc :params declared))))

;; --- discovery reads ----------------------------------------------------------

(defn- resolved-entries
  "Return every registered entry's live classification, in registered-name order.

  Resolution is what turns a registry of symbols into a catalogue: a name's
  capabilities and doc live in the value its symbol resolves to. An entry whose
  Var has since vanished fails the whole read with the owner and repair choices
  attached, rather than being quietly skipped — a catalogue missing an entry
  reads like a workflow nobody registered."
  [rt]
  (mapv (fn [[name _]] (defs/resolve-registered rt name))
        (sort-by key (registry/workflow-definitions rt))))

(defn catalog
  "Return the ordered discovery catalogue for `request`.

  `request` is `{:entrypoint :start|:continue|:call, :all? boolean}`, both
  optional. The default answers the question a worker actually has — which
  routines can I begin? — by listing only definitions declaring `:start`.
  `:entrypoint` selects one capability instead, and `:all?` drops the filter
  entirely, which is also the only way opaque legacy entries appear: they declare
  no capability, so no capability filter can match them.

  The result is deterministic: entries are in registered-name order, and each
  item is exactly the four `::catalog-item` fields."
  [rt request]
  (require-shape! :skein.spools.workflow/list-request request
                  :workflow/list-request-invalid
                  "Workflow list request is invalid"
                  {})
  (let [{:keys [entrypoint all?]} request
        wanted (when-not all? (or entrypoint :start))]
    (mapv catalog-item
          (cond->> (resolved-entries rt)
            wanted (filterv #(contains? (:entrypoints %) wanted))))))

(defn definition-view
  "Return the full-fidelity discovery view of registered workflow `name`.

  One definition, whatever its entrypoints: `show` is a point read, so a
  call-only component answers here even though the default catalogue omits it.
  The view carries the catalogue fields plus `:kind`/`:opaque` opacity markers,
  the param contract, and the declared summary. An unregistered name fails as
  `:workflow/definition-unregistered` and an unresolvable symbol as
  `:workflow/definition-unresolvable`, each carrying what a reader needs to
  repair it."
  [rt name]
  (require-shape! :skein.spools.workflow/show-request {:workflow name}
                  :workflow/show-request-invalid
                  "Workflow show request is invalid"
                  {})
  (let [resolved (defs/resolve-registered rt name)
        static? (defs/static? resolved)
        definition (:value resolved)]
    (require-shape! :skein.spools.workflow/definition-view
                    (assoc (catalog-item resolved)
                           :kind (if static? "static" "legacy")
                           :opaque (not static?)
                           :params (if static? (params-view definition) {:kind "opaque"})
                           :declared (if static? (declared-view definition) {:kind "opaque"}))
                    :workflow/definition-view-invalid
                    "Workflow definition view is invalid"
                    {:name name})))
