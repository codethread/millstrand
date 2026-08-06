(ns millstrand.repl
  "The interactive complement to Millstrand's module authoring forms.

  Two things live here. The registration verbs are the same
  `register/replace/unregister-*!` matrix the `millstrand.api.*.alpha` tier
  publishes, with the runtime implied instead of passed — the live-iteration
  tool for someone sitting at a weaver REPL. The session machinery is the rest:
  `connect!` and the accessors a standalone JVM hands to `millstrand.core.client`,
  the nREPL attach/eval plumbing behind `mill weaver repl`, and the
  burn-tombstone recovery reads.

  Nothing here is REPL-only. Code that holds a runtime calls the
  `millstrand.api.*.alpha` verbs directly; this namespace is the same capability with
  the ergonomics of an interactive session."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [nrepl.cmdline]
            [nrepl.core :as nrepl]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.patterns.alpha :as patterns]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.client :as client]
            [millstrand.core.db :as db]
            [millstrand.core.weaver.access :as access]
            [millstrand.core.weaver.config :as weaver-config]))

;; ---------------------------------------------------------------------------
;; Connected-world selection
;;
;; A standalone JVM selects one weaver world with `connect!` and then drives it
;; explicitly through `millstrand.core.client`, reading the selection back from
;; `connected-config-dir` and `connected-opts`.

(def ^:private no-connection ::no-connection)
(defonce ^:private active-config-dir (atom no-connection))
(defonce ^:private active-state-dir (atom no-connection))

(defn connected-config-dir
  "Return the config directory of the world `connect!` selected.

  This is the first argument a connected session passes to
  `millstrand.core.client/call-world`. Throws with remediation when nothing has been
  selected."
  []
  (case @active-config-dir
    ::no-connection (throw (ex-info (format-alpha/reflow
                                     "|No Millstrand weaver world is connected. Use `mill weaver repl` for
                                       |direct live evaluation, or call (connect! \"/path/to/config-dir\")
                                       |before using explicit connected-client helpers.")
                                    {:helper 'connect!}))
    @active-config-dir))

(defn connected-opts
  "Return the client options of the world `connect!` selected.

  This is the second argument a connected session passes to
  `millstrand.core.client/call-world`. It carries the explicit state-dir supplied by
  a mill-routed REPL launch, so calls reach XDG-hosted weaver metadata instead
  of assuming metadata lives below the selected config-dir; worlds connected by
  config-dir alone get an empty map."
  []
  (if (= no-connection @active-state-dir)
    {}
    {:state-dir @active-state-dir}))

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
                   {:helper 'connect! :code :millstrand.repl/no-selected-world})))
  ([config-dir]
   (connect! config-dir nil))
  ([config-dir state-dir]
   (connect!* config-dir state-dir client/status-world)))

;; ---------------------------------------------------------------------------
;; Interactive registration verbs
;;
;; The same register/replace/unregister verbs the `millstrand.api.*.alpha` tier
;; publishes, with the runtime implied instead of passed. They delegate into the
;; live weaver's in-memory registries, so they are in-process only: a connected
;; client session holds no registry to write to.

(defn- no-runtime-remediation
  "Return remediation prose for registration verb `verb` under session `mode`.

  `mode` is `:connected` when `connect!` selected a weaver in another process and
  `:standalone` when this session has no weaver at all. The two need different
  next steps, so they get different prose."
  [verb mode]
  (format (format-alpha/reflow
           (case mode
             :connected
             "|`%s` writes to the weaver's in-memory registry, so it needs the runtime
               |in this JVM. This session is a connected client: `connect!` selected a
               |weaver running in another process. Run the verb inside that weaver with
               |`mill weaver repl`, or drive the selected world from here with explicit
               |`millstrand.core.client` calls."
             :standalone
             "|`%s` writes to the weaver's in-memory registry, so it needs the runtime
               |in this JVM, and this session has neither a runtime nor a `connect!`
               |selection. Run the verb inside the weaver with `mill weaver repl`, or
               |select a world with `connect!` and drive it from here with explicit
               |`millstrand.core.client` calls."))
          verb))

(defn- registering-runtime
  "Return this JVM's live weaver runtime for registration verb `verb`.

  Throws with the verb, the session mode, and the connected-session alternative
  when no in-process runtime is bound."
  [verb]
  (or (current/runtime-or-nil)
      (let [mode (if (connected?) :connected :standalone)]
        (throw (ex-info (no-runtime-remediation verb mode)
                        {:helper verb
                         :session-mode mode
                         :code :millstrand.repl/no-in-process-runtime})))))

(defn register-op!
  "Register op `op-name` live, claiming the name in this session's partition.

  Runtime-implied twin of `millstrand.api.weaver.alpha/register-op!`, which owns the
  contract: ops reject any name already registered, their own included, so
  `replace-op!` is the only way to take one over."
  ([op-name fn-sym]
   (weaver/register-op! (registering-runtime 'register-op!) op-name fn-sym))
  ([op-name opts fn-sym]
   (weaver/register-op! (registering-runtime 'register-op!) op-name opts fn-sym)))

(defn replace-op!
  "Replace the live op `op-name`, recording intent to shadow another owner.

  Runtime-implied twin of `millstrand.api.weaver.alpha/replace-op!`: loud when the
  name is absent, and the recorded intent is what carries the shadow across
  `millstrand.api.runtime.alpha/refresh!`."
  ([op-name fn-sym]
   (weaver/replace-op! (registering-runtime 'replace-op!) op-name fn-sym))
  ([op-name opts fn-sym]
   (weaver/replace-op! (registering-runtime 'replace-op!) op-name opts fn-sym)))

(defn unregister-op!
  "Retract this session's own registration of op `op-name`.

  Runtime-implied twin of `millstrand.api.weaver.alpha/unregister-op!`: retracting a
  shadow makes the shadowed op effective again rather than deleting the name."
  [op-name]
  (weaver/unregister-op! (registering-runtime 'unregister-op!) op-name))

(defn register-query!
  "Register named query `query-name` live, claiming the name for this session.

  Runtime-implied twin of `millstrand.api.graph.alpha/register-query!`: re-registering
  a name this session already holds replaces it, and a name another owner
  supplies collides loudly."
  [query-name query-def]
  (graph/register-query! (registering-runtime 'register-query!) query-name query-def))

(defn replace-query!
  "Replace the live definition of query `query-name`.

  Runtime-implied twin of `millstrand.api.graph.alpha/replace-query!`. Queries are
  value-registered, so replacing the definition is the whole iteration loop —
  there is no function body to redefine."
  [query-name query-def]
  (graph/replace-query! (registering-runtime 'replace-query!) query-name query-def))

(defn unregister-query!
  "Retract this session's own registration of query `query-name`.

  Runtime-implied twin of `millstrand.api.graph.alpha/unregister-query!`."
  [query-name]
  (graph/unregister-query! (registering-runtime 'unregister-query!) query-name))

(defn register-pattern!
  "Register weave pattern `pattern-name` live, claiming it for this session.

  Runtime-implied twin of `millstrand.api.patterns.alpha/register-pattern!`; the doc
  arity carries an optional non-blank description."
  ([pattern-name fn-sym input-spec]
   (patterns/register-pattern! (registering-runtime 'register-pattern!)
                               pattern-name fn-sym input-spec))
  ([pattern-name doc fn-sym input-spec]
   (patterns/register-pattern! (registering-runtime 'register-pattern!)
                               pattern-name doc fn-sym input-spec)))

(defn replace-pattern!
  "Replace the live weave pattern `pattern-name`.

  Runtime-implied twin of `millstrand.api.patterns.alpha/replace-pattern!`. Patterns
  resolve their handler symbol at invocation, so iterating a body under a stable
  contract needs no registry call — reach for this when the contract or the
  symbol itself changes."
  ([pattern-name fn-sym input-spec]
   (patterns/replace-pattern! (registering-runtime 'replace-pattern!)
                              pattern-name fn-sym input-spec))
  ([pattern-name doc fn-sym input-spec]
   (patterns/replace-pattern! (registering-runtime 'replace-pattern!)
                              pattern-name doc fn-sym input-spec)))

(defn unregister-pattern!
  "Retract this session's own registration of pattern `pattern-name`.

  Runtime-implied twin of `millstrand.api.patterns.alpha/unregister-pattern!`."
  [pattern-name]
  (patterns/unregister-pattern! (registering-runtime 'unregister-pattern!) pattern-name))

(defn register-hook!
  "Register lifecycle hook `key` live for `types`, claiming it for this session.

  Runtime-implied twin of `millstrand.api.hooks.alpha/register-hook!`; `opts` may
  carry an integer `:order` plus data-first metadata."
  ([key types fn-sym]
   (hooks/register-hook! (registering-runtime 'register-hook!) key types fn-sym))
  ([key types fn-sym opts]
   (hooks/register-hook! (registering-runtime 'register-hook!) key types fn-sym opts)))

(defn replace-hook!
  "Replace the live lifecycle hook registered under `key`.

  Runtime-implied twin of `millstrand.api.hooks.alpha/replace-hook!`. Hooks bind their
  callable at dispatch, so iterating a body needs no registry call — reach for
  this when the types, order, metadata, or symbol change."
  ([key types fn-sym]
   (hooks/replace-hook! (registering-runtime 'replace-hook!) key types fn-sym))
  ([key types fn-sym opts]
   (hooks/replace-hook! (registering-runtime 'replace-hook!) key types fn-sym opts)))

(defn unregister-hook!
  "Retract this session's own lifecycle hook registration for `key`.

  Runtime-implied twin of `millstrand.api.hooks.alpha/unregister-hook!`."
  [key]
  (hooks/unregister-hook! (registering-runtime 'unregister-hook!) key))

(defn register-handler!
  "Register event handler `key` live for `types`, claiming it for this session.

  Runtime-implied twin of `millstrand.api.events.alpha/register-handler!`."
  ([key types fn-sym]
   (events/register-handler! (registering-runtime 'register-handler!) key types fn-sym))
  ([key types fn-sym metadata]
   (events/register-handler! (registering-runtime 'register-handler!)
                             key types fn-sym metadata)))

(defn replace-handler!
  "Replace the live event handler registered under `key`.

  Runtime-implied twin of `millstrand.api.events.alpha/replace-handler!`. Handlers
  capture their function value at registration rather than binding it at
  dispatch, so redefining the underlying fn never reaches one: iterating a
  handler is always this call."
  ([key types fn-sym]
   (events/replace-handler! (registering-runtime 'replace-handler!) key types fn-sym))
  ([key types fn-sym metadata]
   (events/replace-handler! (registering-runtime 'replace-handler!)
                            key types fn-sym metadata)))

(defn unregister-handler!
  "Retract this session's own event handler registration for `key`.

  Runtime-implied twin of `millstrand.api.events.alpha/unregister-handler!`."
  [key]
  (events/unregister-handler! (registering-runtime 'unregister-handler!) key))

;; ---------------------------------------------------------------------------
;; Burn-tombstone recovery
;;
;; Recovery reads go straight to the live datasource rather than through any
;; client bridge, so the whole trio is in-process only: recovering a burned
;; strand is work you do inside the weaver.

(defn- recovery-runtime
  "Return this JVM's live weaver runtime for recovery verb `verb`.

  Throws with remediation pointing at `mill weaver repl` when no in-process
  runtime is bound (a connected-client REPL has none)."
  [verb]
  (or (current/runtime-or-nil)
      (throw (ex-info (format-alpha/reflow
                       "|Burn-tombstone recovery runs inside the live weaver JVM. No
                         |in-process runtime is bound here (a connected-client REPL has
                         |none). Start an in-process weaver REPL with `mill weaver repl`
                         |and rerun.")
                      {:helper verb :code :millstrand.repl/no-in-process-runtime}))))

(defn- recovery-datasource
  "Return the live weaver datasource backing tombstone reads for `verb`."
  [verb]
  (access/ds (recovery-runtime verb)))

(defn burn-by-ids!
  "Physically delete every strand id in `ids` and their incident edges.

  The write twin of the tombstone reads below. Missing ids fail loudly. Returns
  the weaver burn summary."
  [ids]
  (graph/burn-by-ids! (recovery-runtime 'burn-by-ids!) (vec ids)))

(defn burn-history
  "Return every burn tombstone recorded for burned strand `id`, newest first.

  Disaster-recovery read: each tombstone carries the burned strand's core
  fields, its full attribute map (values tagged `{:value ... :archived ...}`
  so archived keys stay distinguishable), its incident edges, and
  `recorded_at`, shaped to feed a batch graph mutation payload by hand."
  [id]
  (db/burn-history-for-strand (recovery-datasource 'burn-history) id))

(defn recent-burns
  "Return the latest `limit` burn tombstones across all strands, newest first.

  Disaster-recovery read for scanning recent deletions; `limit` is required
  and must be positive. Each tombstone has the shape documented on
  `burn-history`."
  [limit]
  (db/recent-burn-history (recovery-datasource 'recent-burns) limit))

;; ---------------------------------------------------------------------------
;; Sessions
;;
;; Interactive and stdin sessions evaluate in the neutral `user` namespace, not
;; in `millstrand.repl`. Landing a session in the implementation namespace makes the
;; user's own `def`s shadow the machinery they are calling, and it reads as if
;; the helpers were REPL-only rather than ordinary functions. `millstrand.repl` is
;; required into the session and aliased `repl`, so the registration verbs stay
;; one keystroke away.

(def ^:private session-ns-name 'user)

(def ^:private remote-session-bootstrap
  "Source that lands a fresh weaver-side nREPL session in the neutral namespace."
  "(do (in-ns 'user) (require '[millstrand.repl :as repl]))")

(defn- session-ns
  "Return the neutral session namespace, with `millstrand.repl` required and aliased."
  []
  (let [target (the-ns session-ns-name)]
    (binding [*ns* target]
      (require '[millstrand.repl :as repl]))
    target))

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
  inside the selected weaver process rather than in the thin attach client.
  Forms evaluate in the neutral session namespace."
  [source]
  (let [reader (java.io.PushbackReader. (java.io.StringReader. source))
        eof (Object.)]
    (binding [*ns* (session-ns)]
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
    (eval-remote! session remote-session-bootstrap)
    [conn session]))

(defn- attach-stdin! [host port]
  (let [source (slurp *in*)
        [conn session] (attach-session host port)]
    (with-open [_ ^java.io.Closeable conn]
      (let [responses (eval-remote-responses! session
                                              {:ns (str session-ns-name)
                                               :code (str "(millstrand.repl/eval-source-forms! "
                                                          (pr-str source) ")")})]
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
          (eval-remote! session remote-session-bootstrap)))
      (print "millstrand=> "))))

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

  Usage: `millstrand.repl [--stdin] [config-dir] [state-dir]`,
  `millstrand.repl --attach host port`, or `millstrand.repl --attach-stdin host port`.
  Attach modes send forms to the selected weaver nREPL, print direct Clojure
  results, and exit non-zero on read, evaluation, or transport failure."
  [& args]
  (if (#{"--attach" "--attach-stdin"} (first args))
    (let [[mode host port & extra] args]
      (when (or (str/blank? host) (str/blank? port) (seq extra))
        (throw (ex-info "Usage: millstrand.repl --attach host port or millstrand.repl --attach-stdin host port" {:args args})))
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
                                            (throw (ex-info "Usage: millstrand.repl [--stdin] [config-dir] [state-dir]" {:args args})))
                                        (throw (ex-info "Usage: millstrand.repl [--stdin] [config-dir] [state-dir]" {:args args})))]
      (connect! config-dir state-dir)
      (binding [*ns* (session-ns)]
        (case mode
          :stdin (try
                   (eval-stdin!)
                   (catch Throwable t
                     (binding [*out* *err*]
                       (println (or (ex-message t) (str t))))
                     (System/exit 1)))
          :repl (main/repl :prompt #(print "millstrand=> ")))))))
