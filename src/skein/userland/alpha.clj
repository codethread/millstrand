(ns skein.userland.alpha
  "Userland-only terse ergonomics over the explicit-runtime Skein API.

  RFC-016 made runtime passing explicit everywhere: `skein.api.*.alpha` take the
  target weaver runtime as their first argument. That is right for engine and
  shared-spool code, but userland config (`init.clj`), local glue, and tooling
  that legitimately work against ONE runtime end up threading it through every
  call. This module lets trusted userland code hold a single runtime once - via
  `bind!` or a `with-runtime` scope - and then make terse calls like
  `(ready)` or `(strand! \"title\")`. Hidden ambient magic is a trade USERS are
  allowed to make; skein namespaces are not.

  Positioning vs `skein.repl`: different jobs, not competing vocabularies.
  `skein.repl` is the interactive HUMAN surface - the live registration verbs
  plus session machinery (`connect!` and the client bridge for standalone JVMs).
  This module is the trusted USERLAND-CODE surface for terse strand work - it
  holds one in-process runtime (published OR unpublished, e.g. tests and
  embedded tooling that started with `:publish? false`), does no connection
  routing, and fails loudly when no runtime is resolvable.

  Runtime resolution order for every terse call: the innermost `with-runtime`
  scope, then the `bind!` default, then `skein.api.current.alpha/runtime` (the
  active startup binding or the published runtime), else a loud failure
  (TEN-003). `with-runtime` also binds the shared ambient runtime so nested
  `(current/runtime)` reads and explicit-runtime callees see the same runtime;
  `bind!` is module-local and only affects this module's terse calls, so scope
  the shared ambient with `with-runtime` when nested/shared code must agree.

  USERLAND-ONLY, FOREVER: no `skein.*` namespace may require this module. It is a
  strict downstream consumer tier and must never sit upstream of the engine,
  blessed API, shipped spools, or REPL surface. Shared/distributed spools must
  keep taking the runtime explicitly (see docs/spools/writing-shared-spools.md); this
  ergonomics layer is for workspace-local config, tests, and glue only."
  (:require [skein.api.current.alpha :as current]
            [skein.api.batch.alpha :as batch]
            [skein.api.graph.alpha :as graph]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.terse :as terse]))

(def ^:dynamic ^:private *scope*
  "Innermost `with-runtime` runtime, or nil. Beats the `bind!` default."
  nil)

(defonce ^:private bound-runtime
  (atom nil))

(defn bind!
  "Set `runtime` as the module-local default for terse calls, and return it.

  Affects only `skein.userland.alpha` terse calls; it does not publish a process
  ambient runtime. Use `with-runtime` to scope the shared ambient runtime for
  nested or shared code. A `with-runtime` scope overrides this default."
  [runtime]
  (when (nil? runtime)
    (throw (ex-info "Cannot bind! a nil runtime; call (unbind!) to clear the default" {})))
  (reset! bound-runtime runtime)
  runtime)

(defn unbind!
  "Clear the module-local default runtime and return nil."
  []
  (reset! bound-runtime nil)
  nil)

(defn bound
  "Return the module-local default runtime set by `bind!`, or nil."
  []
  @bound-runtime)

(defn- resolve-runtime
  "Return the scope, bound, or ambient runtime, failing loudly when none exists."
  []
  (or *scope*
      @bound-runtime
      (current/runtime-or-nil)
      (throw (ex-info "No Skein runtime for skein.userland.alpha; call (bind! rt), wrap in (with-runtime rt ...), or run inside a started/published weaver."
                      {}))))

(defn runtime
  "Return the resolved terse-call runtime for explicit `skein.api.*.alpha` use.

  Escape hatch for reaching the surface this module does not wrap (graph,
  events, hooks): grab the runtime and call the explicit-runtime API directly."
  []
  (resolve-runtime))

(defn with-runtime*
  "Call `thunk` with `runtime` as the terse-call and shared ambient runtime."
  [runtime thunk]
  (binding [*scope* runtime]
    (current/with-runtime* runtime thunk)))

(defmacro with-runtime
  "Evaluate `body` with `runtime` as the terse-call and shared ambient runtime.

  Overrides the `bind!` default for its dynamic extent and also binds the shared
  ambient runtime, so nested `(current/runtime)` reads and explicit-runtime
  callees resolve to the same runtime. Independent `with-runtime` scopes on
  different runtimes do not cross-talk."
  [runtime & body]
  `(with-runtime* ~runtime (fn [] ~@body)))

(defn init!
  "Initialize the resolved runtime's store schema."
  []
  (weaver/init (resolve-runtime)))

(defn strand!
  "Create a strand in the resolved runtime and return the created row.

  Accepts a title, optional attributes map, and optional lifecycle/options map
  such as `{:state \"closed\"}`. The two-argument form is attributes-only and
  rejects core strand fields instead of treating them as attributes."
  ([title]
   (strand! title {} {}))
  ([title attributes]
   (strand! title (terse/reject-core-attribute-keys! attributes) {}))
  ([title attributes lifecycle]
   (weaver/add! (resolve-runtime) (merge {:title title :attributes attributes} lifecycle))))

(defn strand
  "Return the normalized strand row for `id`, or nil when no such strand exists."
  [id]
  (weaver/show (resolve-runtime) id))

(defn update!
  "Apply `patch` to strand `id` and return the normalized update result."
  [id patch]
  (weaver/update! (resolve-runtime) id patch))

(defn supersede!
  "Replace `old-id` with `replacement-id` and return the supersession result."
  [old-id replacement-id]
  (weaver/supersede! (resolve-runtime) old-id replacement-id))

(defn burn!
  "Physically delete one or more strands and their incident edges.

  Missing ids fail loudly. Returns the weaver burn summary."
  ([id]
   (graph/burn-by-ids! (resolve-runtime) [id]))
  ([id & ids]
   (graph/burn-by-ids! (resolve-runtime) (vec (cons id ids)))))

(defn declare-acyclic-relation!
  "Declare `relation` as a durable acyclic structural relation (idempotent)."
  [relation]
  (weaver/declare-acyclic-relation! (resolve-runtime) relation))

(defn acyclic-relations
  "Return sorted relation names declared acyclic in the resolved runtime."
  []
  (weaver/acyclic-relations (resolve-runtime)))

(defn defquery!
  "Register `query-name` to `query-def` in the resolved runtime query registry."
  [query-name query-def]
  (graph/register-query! (resolve-runtime) query-name query-def))

(defn load-queries!
  "Merge one EDN map of named query definitions into the resolved runtime.

  Takes an already-parsed map rather than a path: trusted in-process code owns
  its own I/O."
  [registry]
  (let [runtime (resolve-runtime)]
    (reduce-kv (fn [loaded query-name query-def]
                 (merge loaded (graph/register-query! runtime query-name query-def)))
               {}
               registry)))

(defn queries
  "Return the resolved runtime's in-memory named query registry."
  []
  (graph/queries (resolve-runtime)))

(defn query-explain
  "Return caller guidance for the registered query `query-name`.

  Missing queries fail loudly with the weaver's query-not-found data."
  [query-name]
  (graph/query-explain (resolve-runtime) query-name))

(defn query
  "Return strands matching an ad hoc query definition or named query.

  `query-or-def` may be a registered query symbol/keyword or a query predicate
  form. `params` supplies runtime values for parameterized queries."
  ([query-or-def]
   (query query-or-def {}))
  ([query-or-def params]
   (let [runtime (resolve-runtime)]
     (if (terse/named-query? query-or-def)
       (weaver/list-query runtime query-or-def params)
       (weaver/list runtime query-or-def params)))))

(defn strands
  "Return resolved-runtime strands, optionally filtered by a query.

  With no arguments, returns all strands. With a query definition or registered
  query name, delegates to `query` with optional params."
  ([]
   (weaver/list (resolve-runtime)))
  ([query-or-def]
   (query query-or-def))
  ([query-or-def params]
   (query query-or-def params)))

(defn ready
  "Return active strands whose direct `depends-on` dependencies are not active.

  Optional query arguments further filter the ready set using an ad hoc predicate
  or registered query name with params."
  ([]
   (weaver/ready (resolve-runtime)))
  ([query-or-def]
   (ready query-or-def {}))
  ([query-or-def params]
   (let [runtime (resolve-runtime)]
     (if (terse/named-query? query-or-def)
       (weaver/ready runtime (graph/resolve-query runtime query-or-def) params)
       (weaver/ready runtime query-or-def params)))))

(defn defpattern!
  "Register a runtime pattern in the resolved runtime pattern registry.

  Accepts a pattern name, optional non-blank doc string, fully qualified function
  symbol, and input spec name. Registration claims the name in the caller's own
  owner partition: re-registering a name this caller already holds replaces it,
  while a name another owner supplies collides loudly.
  `skein.api.patterns.alpha/replace-pattern!` is the deliberate cross-owner
  override."
  ([pattern-name fn-sym input-spec]
   (patterns/register-pattern! (resolve-runtime) pattern-name fn-sym input-spec))
  ([pattern-name doc fn-sym input-spec]
   (patterns/register-pattern! (resolve-runtime) pattern-name doc fn-sym input-spec)))

(defn patterns
  "Return the resolved runtime's in-memory pattern registry."
  []
  (patterns/patterns (resolve-runtime)))

(defn pattern
  "Return the registered pattern named `pattern-name`. Missing patterns fail loudly."
  [pattern-name]
  (patterns/resolve-pattern (resolve-runtime) pattern-name))

(defn pattern-explain
  "Return serializable input guidance for the registered pattern `pattern-name`."
  [pattern-name]
  (patterns/explain (resolve-runtime) pattern-name))

(defn weave!
  "Invoke the registered pattern `pattern-name` with `input` and create its batch."
  [pattern-name input]
  (patterns/weave! (resolve-runtime) pattern-name input))

(defn apply!
  "Apply one transactional batch graph mutation `payload` to the resolved runtime."
  [payload]
  (batch/apply! (resolve-runtime) payload))
