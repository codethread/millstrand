(ns skein.repl
  "Interactive helper API for live and connected Skein weaver workflows.

  This namespace is preloaded by `mill weaver repl` and exposes the compact
  trusted Clojure surface for strand, query, relation, and pattern operations.
  Inside the weaver JVM helpers dispatch through the active runtime; explicit
  client/test workflows may still call `connect!` to route to a selected world."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [nrepl.cmdline]
            [nrepl.core :as nrepl]
            [skein.core.client :as client]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.graph.alpha :as graph]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.db :as db]
            [skein.core.terse :as terse]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.config :as weaver-config]
            [skein.api.current.alpha :as current]
            [skein.core.query :as query]))

(def ^:private no-connection ::no-connection)
(defonce ^:private active-config-dir (atom no-connection))
(defonce ^:private active-state-dir (atom no-connection))

(defn connected-config-dir
  "Return the selected weaver config directory for connected helper routing.

  Throws with remediation when no helper connection has been selected. Alpha
  helper namespaces use this to route calls from connected REPL clients to the
  active weaver selected by config dir."
  []
  (case @active-config-dir
    ::no-connection (throw (ex-info (format-alpha/reflow
                                     "|No Skein weaver world is connected. Use `mill weaver repl` for
                                       |direct live evaluation, or call (connect! \"/path/to/config-dir\")
                                       |before using explicit connected-client helpers.")
                                    {:helper 'connect!}))
    @active-config-dir))

(defn connected-opts
  "Return client options for the active connected helper world.

  Includes the explicit state-dir supplied by mill-routed REPL launch so helper
  namespaces attach to XDG-hosted weaver metadata instead of assuming metadata
  lives below the selected config-dir. Returns an empty map for legacy direct
  test/runtime worlds that were connected by config-dir alone."
  []
  (if (= no-connection @active-state-dir)
    {}
    {:state-dir @active-state-dir}))

(defn- config-dir []
  (connected-config-dir))

(defn- client-opts []
  (connected-opts))

(defn- connected? []
  (not= no-connection @active-config-dir))

(defn- connect!*
  [config-dir state-dir status-world-fn]
  (reset! active-config-dir no-connection)
  (reset! active-state-dir no-connection)
  (when (and config-dir (.isFile (java.io.File. ^String config-dir)))
    (throw (ex-info "connect! expects a daemon config directory, not a database file" {:config-dir config-dir})))
  (let [world (if state-dir
                (weaver-config/world config-dir state-dir (str state-dir "/data"))
                (weaver-config/world config-dir))]
    (status-world-fn (:config-dir world) (cond-> {}
                                           state-dir (assoc :state-dir (:state-dir world))))
    (reset! active-config-dir (:config-dir world))
    (when state-dir
      (reset! active-state-dir (:state-dir world)))
    (:config-dir world)))

(defn connect!
  "Select the active weaver world for helper calls.

  Requires `config-dir`, the selected daemon config directory supplied by the CLI
  or chosen explicitly in standalone/test workflows. Fails loudly if given no
  selected world, a database file, or an unreachable weaver. Returns the
  normalized config directory path for the selected world."
  ([]
   (throw (ex-info "connect! requires an explicit config-dir; use `mill weaver repl` from a repo world or call (connect! \"/path/to/config-dir\")"
                   {:helper 'connect! :code :skein.repl/no-selected-world})))
  ([config-dir]
   (connect! config-dir nil))
  ([config-dir state-dir]
   (connect!* config-dir state-dir client/status-world)))

(declare call-daemon)

(defn- in-process-call [rt op args]
  (case op
    :init (weaver/init rt)
    :add (apply weaver/add! rt args)
    :update (apply weaver/update! rt args)
    :supersede (apply weaver/supersede! rt args)
    :show (apply weaver/show rt args)
    :declare-acyclic-relation! (apply weaver/declare-acyclic-relation! rt args)
    :acyclic-relations (weaver/acyclic-relations rt)
    :burn-by-ids (apply graph/burn-by-ids! rt args)
    :register-query (apply graph/register-query! rt args)
    :queries (graph/queries rt)
    :query-explain (apply graph/query-explain rt args)
    :list (if (seq args) (apply weaver/list rt args) (weaver/list rt))
    :list-query (apply weaver/list-query rt args)
    :ready (if (seq args) (apply weaver/ready rt args) (weaver/ready rt))
    :register-pattern! (apply patterns/register-pattern! rt args)
    :patterns (patterns/patterns rt)
    :resolve-pattern (apply patterns/resolve-pattern rt args)
    :pattern-explain (apply patterns/explain rt args)
    :weave! (apply patterns/weave! rt args)))

(defn- daemon [op & args]
  (call-daemon
   #(if-let [rt (when-not (connected?) (current/runtime-or-nil))]
      (in-process-call rt op args)
      (let [dir (config-dir)]
        (apply client/call-world dir (client-opts) op args)))))

(defn init!
  "Initialize the active weaver store schema."
  []
  (daemon :init))

(defn strand!
  "Create a strand in the active weaver world.

  Accepts a title, optional attributes map, and optional lifecycle/options map
  such as `{:state \"closed\"}` as the third argument. The two-argument form is
  attributes-only and rejects core strand fields instead of treating them as
  attributes. Returns the created normalized strand row."
  ([title]
   (strand! title {} {}))
  ([title attributes]
   (strand! title (terse/reject-core-attribute-keys! attributes) {}))
  ([title attributes lifecycle]
   (daemon :add (merge {:title title :attributes attributes} lifecycle))))

(defn update!
  "Apply a patch to an existing strand.

  The patch may include supported core fields such as `:title`, `:state`,
  `:attributes`, and `:edges`; invalid update fields fail in the weaver. Returns
  the weaver-normalized update result."
  ([id patch]
   (daemon :update id patch)))

(defn supersede!
  "Replace one strand with another through the weaver supersession operation.

  Marks `old-id` replaced, records the supersession edge from replacement to old,
  rewires direct dependents, and returns the normalized supersession result."
  [old-id replacement-id]
  (daemon :supersede old-id replacement-id))

(defn strand
  "Return the normalized strand row for `id`, or nil when no such strand exists."
  [id]
  (daemon :show id))

(defn declare-acyclic-relation!
  "Declare `relation` as a durable acyclic structural relation.

  Declaration is idempotent and fails loudly if existing edges of that relation
  prevent making the acyclicity guarantee."
  [relation]
  (daemon :declare-acyclic-relation! relation))

(defn acyclic-relations
  "Return sorted relation names declared acyclic in the active world."
  []
  (daemon :acyclic-relations))

(defn burn!
  "Physically delete one or more strands and their incident edges.

  Missing ids fail loudly. Returns the weaver burn summary."
  ([id]
   (daemon :burn-by-ids [id]))
  ([id & ids]
   (daemon :burn-by-ids (vec (cons id ids)))))

(defn burn-by-ids!
  "Physically delete all strand ids in `ids` and their incident edges.

  Missing ids fail loudly. Returns the weaver burn summary."
  [ids]
  (daemon :burn-by-ids (vec ids)))

(defn- recovery-datasource
  "Return the in-process weaver datasource for burn-tombstone recovery reads.

  Tombstone reads are an in-process weaver REPL activity: they read directly
  from the live datasource rather than riding the `daemon` client dispatch.
  Throws with remediation pointing at `mill weaver repl` when no in-process
  runtime is bound (a connected-client REPL or none)."
  []
  (if-let [rt (current/runtime-or-nil)]
    (access/ds rt)
    (throw (ex-info (format-alpha/reflow
                     "|Burn-tombstone recovery reads run inside the live weaver JVM. No
                       |in-process runtime is bound here (a connected-client REPL has none).
                       |Start an in-process weaver REPL with `mill weaver repl` and rerun.")
                    {:helper 'burn-history :code :skein.repl/no-in-process-runtime}))))

(defn burn-history
  "Return every burn tombstone recorded for burned strand `id`, newest first.

  Disaster-recovery read: each tombstone carries the burned strand's core
  fields, its full attribute map (values tagged `{:value ... :archived ...}`
  so archived keys stay distinguishable), its incident edges, and
  `recorded_at`, shaped to feed a batch graph mutation payload by hand.
  In-process only — throws with remediation when no live weaver runtime is
  bound; run it from `mill weaver repl`."
  [id]
  (db/burn-history-for-strand (recovery-datasource) id))

(defn recent-burns
  "Return the latest `limit` burn tombstones across all strands, newest first.

  Disaster-recovery read for scanning recent deletions; `limit` is required
  and must be positive. Each tombstone has the shape documented on
  `burn-history`. In-process only — throws with remediation when no live
  weaver runtime is bound; run it from `mill weaver repl`."
  [limit]
  (db/recent-burn-history (recovery-datasource) limit))

(defn- call-daemon [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if-let [weaver-message (:weaver-message (ex-data e))]
        (throw (ex-info weaver-message (:weaver-data (ex-data e))))
        (throw e)))))

(defn defquery!
  "Register `query-name` to `query-def` in the active weaver query registry.

  Registry state is in-memory for the current weaver lifetime. Query definitions
  may be plain predicates or parameterized query maps. Returns the registered
  query entry."
  [query-name query-def]
  (daemon :register-query query-name query-def))

(defn load-queries!
  "Load named queries from one EDN map at `path` into the active weaver registry.

  The file must contain exactly one map of query names to query definitions.
  Returns the loaded query entries."
  [path]
  (when-not (or (current/runtime-or-nil) (connected?))
    (config-dir))
  (let [registry (query/read-edn-file path)]
    (when-not (map? registry)
      (throw (ex-info "Query file must contain one EDN map of query names to query definitions" {:path path})))
    (reduce-kv (fn [loaded query-name query-def]
                 (merge loaded (daemon :register-query query-name query-def)))
               {}
               registry)))

(defn queries
  "Return the active weaver's in-memory named query registry."
  []
  (daemon :queries))

(defn query-explain
  "Return caller guidance for the registered query `query-name`.

  Accepts a simple symbol, keyword, or string name. Missing queries fail loudly
  with the weaver's query-not-found data, including available query names."
  [query-name]
  (daemon :query-explain query-name))

(defn- run-query [query-or-def params ad-hoc named]
  (if (terse/named-query? query-or-def)
    (daemon named query-or-def params)
    (daemon ad-hoc query-or-def params)))

(defn query
  "Return strands matching an ad hoc query definition or named query.

  `query-or-def` may be a registered query symbol/keyword or a query predicate
  form. `params` supplies runtime values for parameterized queries."
  ([query-or-def]
   (query query-or-def {}))
  ([query-or-def params]
   (run-query query-or-def params :list :list-query)))

(defn strands
  "Return active-world strands, optionally filtered by a query.

  With no arguments, returns all strands. With a query definition or registered
  query name, delegates to `query` with optional params."
  ([]
   (daemon :list))
  ([query-or-def]
   (query query-or-def))
  ([query-or-def params]
   (query query-or-def params)))

(defn ready
  "Return active strands whose direct `depends-on` dependencies are not active.

  Optional query arguments further filter the ready set using an ad hoc predicate
  or registered query name with params."
  ([]
   (daemon :ready))
  ([query-or-def]
   (ready query-or-def {}))
  ([query-or-def params]
   (if (terse/named-query? query-or-def)
     (daemon :ready (daemon :resolve-query query-or-def) params)
     (daemon :ready query-or-def params))))

(defn defpattern!
  "Register a runtime pattern in the active weaver pattern registry.

  Accepts a simple pattern name, optional non-blank doc string, fully qualified
  function symbol, and input spec name. Registration claims the name in the
  caller's own owner partition for the current weaver lifetime: re-registering a
  name this caller already holds replaces it, while a name another owner supplies
  collides loudly. `skein.api.patterns.alpha/replace-pattern!` is the deliberate
  cross-owner override."
  ([pattern-name fn-sym input-spec]
   (daemon :register-pattern! pattern-name fn-sym input-spec))
  ([pattern-name doc fn-sym input-spec]
   (daemon :register-pattern! pattern-name doc fn-sym input-spec)))

(defn patterns
  "Return the active weaver's in-memory pattern registry."
  []
  (daemon :patterns))

(defn pattern
  "Return the registered pattern named `pattern-name`.

  Missing patterns fail loudly."
  [pattern-name]
  (daemon :resolve-pattern pattern-name))

(defn pattern-explain
  "Return serializable input guidance for the registered pattern `pattern-name`.

  Missing patterns or invalid registered specs fail loudly."
  [pattern-name]
  (daemon :pattern-explain pattern-name))

(defn weave!
  "Invoke the registered pattern `pattern-name` with `input` and create its batch.

  The weaver validates input against the pattern's registered spec and requires
  the pattern function to return a valid batch strand vector. Returns the batch
  creation result."
  [pattern-name input]
  (daemon :weave! pattern-name input))

(defn- eval-stdin! []
  (let [reader (java.io.PushbackReader. *in*)
        eof (Object.)]
    (loop []
      (let [form (read reader false eof)]
        (when-not (identical? eof form)
          (prn (eval form))
          (recur))))))

(defn- response-error [responses]
  (or (some :err responses)
      (when-let [bad-status (some #(some #{"eval-error" "read-error" "interrupted"} (:status %)) responses)]
        (str "nREPL evaluation failed with status " bad-status))))

(defn- eval-remote-responses! [session message]
  (let [request (if (string? message) {:code message} message)
        responses (doall (nrepl/message session (assoc request :op "eval")))]
    (when-let [err (response-error responses)]
      (throw (ex-info err {:responses responses})))
    responses))

(defn- eval-remote! [session message]
  (last (keep :value (eval-remote-responses! session message))))

(defn eval-source-forms!
  "Read and evaluate all top-level forms from `source` in the current JVM.

  Returns ordered event maps with optional `:out` and one `:value` per form.
  Intended for the thin nREPL attach client so stdin read/eval semantics run
  inside the selected weaver process rather than in the thin attach client."
  [source]
  (let [reader (java.io.PushbackReader. (java.io.StringReader. source))
        eof (Object.)]
    (binding [*ns* (the-ns 'skein.repl)]
      (loop [events []]
        (let [form (read reader false eof)]
          (if (identical? eof form)
            events
            (let [out (java.io.StringWriter.)
                  value (binding [*out* out]
                          (pr-str (eval form)))
                  event (cond-> {:value value}
                          (pos? (.length (.getBuffer out))) (assoc :out (str out)))]
              (recur (conj events event)))))))))

(defn- attach-session
  "Open a thin nREPL client session prepared for live weaver-side evaluation."
  [host port]
  (let [conn (nrepl/connect :host host :port (Integer/parseInt port))
        session (nrepl/client-session (nrepl/client conn 60000))]
    (eval-remote! session "(do (require 'skein.repl) (in-ns 'skein.repl))")
    [conn session]))

(defn- attach-stdin! [host port]
  (let [source (slurp *in*)
        [conn session] (attach-session host port)]
    (with-open [_ ^java.io.Closeable conn]
      (let [responses (eval-remote-responses! session {:ns "skein.repl"
                                                       :code (str "(skein.repl/eval-source-forms! " (pr-str source) ")")})]
        (doseq [{:keys [out value]} (read-string (last (keep :value responses)))]
          (when out
            (print out)
            (flush))
          (println value))))))

(defn- nrepl-run-repl []
  (or (resolve 'nrepl.cmdline/run-repl)
      (throw (ex-info "nREPL command-line REPL implementation is unavailable"
                      {:var 'nrepl.cmdline/run-repl}))))

(defn- helper-ready-prompt []
  (let [initialized? (atom false)]
    (fn [_]
      (when (compare-and-set! initialized? false true)
        (let [session (:client @nrepl.cmdline/running-repl)]
          (when-not session
            (throw (ex-info "nREPL cmdline client did not expose an active session before prompting"
                            {:var 'nrepl.cmdline/running-repl})))
          (eval-remote! session "(do (require 'skein.repl) (in-ns 'skein.repl))")))
      (print "skein=> "))))

(defn- attach-repl!
  ([host port]
   (attach-repl! host port {}))
  ([host port {:keys [run-repl-fn]
               :or {run-repl-fn (nrepl-run-repl)}}]
   (run-repl-fn
    host
    (Integer/parseInt port)
    {:prompt (helper-ready-prompt)})))

(defn -main
  "Start a direct live weaver REPL or evaluate stdin forms.

  Usage: `skein.repl [--stdin] [config-dir] [state-dir]`,
  `skein.repl --attach host port`, or `skein.repl --attach-stdin host port`.
  Attach modes send forms to the selected weaver nREPL, print direct Clojure
  results, and exit non-zero on read, evaluation, or transport failure."
  [& args]
  (if (#{"--attach" "--attach-stdin"} (first args))
    (let [[mode host port & extra] args]
      (when (or (str/blank? host) (str/blank? port) (seq extra))
        (throw (ex-info "Usage: skein.repl --attach host port or skein.repl --attach-stdin host port" {:args args})))
      (try
        (case mode
          "--attach" (attach-repl! host port)
          "--attach-stdin" (attach-stdin! host port))
        (catch Throwable t
          (binding [*out* *err*]
            (println (or (ex-message t) (str t))))
          (System/exit 1))))
    (let [[mode config-dir state-dir] (case (count args)
                                        0 [:repl nil nil]
                                        1 (if (= "--stdin" (first args))
                                            [:stdin nil nil]
                                            [:repl (first args) nil])
                                        2 (if (= "--stdin" (first args))
                                            [:stdin (second args) nil]
                                            [:repl (first args) (second args)])
                                        3 (if (= "--stdin" (first args))
                                            [:stdin (second args) (nth args 2)]
                                            (throw (ex-info "Usage: skein.repl [--stdin] [config-dir] [state-dir]" {:args args})))
                                        (throw (ex-info "Usage: skein.repl [--stdin] [config-dir] [state-dir]" {:args args})))]
      (connect! config-dir state-dir)
      (binding [*ns* (the-ns 'skein.repl)]
        (case mode
          :stdin (try
                   (eval-stdin!)
                   (catch Throwable t
                     (binding [*out* *err*]
                       (println (or (ex-message t) (str t))))
                     (System/exit 1)))
          :repl (main/repl :prompt #(print "skein=> ")))))))
