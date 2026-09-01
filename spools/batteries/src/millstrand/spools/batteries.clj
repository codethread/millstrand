(ns millstrand.spools.batteries
  "Shipped core strand command surface as parser-backed weaver ops.

  Batteries declares the everyday strand operations — add/update/show/supersede/
  burn/list/ready/subgraph, the create-only `weave` op, and the read-only
  `query`/`pattern` registry-introspection ops — through
  `millstrand.api.millstrand.alpha/defop!`. Their
  `:arg-spec` is parsed by `millstrand.api.cli.alpha`. Each op delegates to the same
  `millstrand.api.*.alpha` calls the JSON socket dispatch uses and returns
  the same JSON shapes, so the ops are reachable through `strand <name>` at the
  CLI root. The namespace owns no module-level state:
  op handlers read the runtime from their invocation context (`:op/runtime`).

  `defop!` declarations are the durable source for this surface. They define
  Vars and select their declarations during module collection; the selected set
  publishes as this module's complete, owner-complete ops partition.
  Explicit-runtime registration functions are the live code and test seam, and
  `millstrand.repl` supplies the same verbs with the runtime implied for an in-process
  session. Evaluating an authoring form outside module collection defines its Var
  but publishes no op.

  Batteries also defines an inert `runbook` op (`defop`, not `defop!`): the
  opinionated strand-tracking loop, loaded from classpath markdown. A workspace
  elects it with `millstrand/use-op!`.

  Production loading makes Batteries available as an ordinary library in
  `deps.edn` (or `deps.local.edn`) and activates it explicitly with an
  owner-complete `runtime/module!` declaration in `init.clj` (or
  `init.local.clj`). Dependency presence does not activate Batteries; deleting
  either dependency or declaration is the supported opt-out.

  Ops adopt the discovery-tier pattern (DELTA-Dtf-003.CC2): their arg-specs drive
  help, and where it adds value they carry closed `:annotations` sub-maps
  (`use-when`/`notes`/`failure-modes`) and op-level `:about`/`:prime` prose.
  `failure-modes` reference the batteries-owned glossary outcomes seeded by the
  batteries module (the load-order contract, DELTA-Dtf-002.CC7).

  Batteries also EXPORTS `default-help-transform` — the reference default help
  transform (DELTA-Dtf-002.CC1): one recursive renderer over the uniform fractal
  node (DELTA-Dtf-001.CC2) with no per-level branch. It is exported for trusted
  config election and never auto-registers.

  Attribute/edge flag semantics preserve the retired builtin CLI behavior:
  `--attr key=value` is a repeatable, highest-precedence string map whose values
  may be payload references; `--attributes` references a JSON object of typed
  bulk attributes at lowest precedence; `--edge edge-type:to-id` adds outgoing
  edges. `--state` accepts `active|closed` for mutations and
  `active|closed|replaced` for `list` filtering."
  (:refer-clojure :exclude [await list update])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.notes.alpha :as notes]
            [millstrand.api.patterns.alpha :as patterns]
            [millstrand.api.runtime.alpha :as runtime-api]
            [millstrand.api.runtime.glossary.alpha :as glossary]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :as spool]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.io PushbackReader StringReader]
           [java.time Duration]))

(def ^:private generic-states #{"active" "closed"})
(def ^:private lean-attribute-byte-floor 1024)
(def ^:private default-read-limit 500)
(def ^:private read-limit-state-version 1)
(def ^:private readable-states #{"active" "closed" "replaced"})
(def ^:private await-default-timeout-secs 1800)
(def ^:private await-poll-ms 1000)

(defn- exact-keys? [expected value]
  (and (map? value) (= expected (set (keys value)))))

(s/def ::runbook string?)
(s/def ::runbook-result
  (s/and (s/keys :req-un [::runbook])
         #(exact-keys? #{:runbook} %)))

(defn- require-valid! [spec value message]
  (when-not (s/valid? spec value)
    (throw (ex-info message
                    {:spec spec
                     :value value
                     :explain (s/explain-data spec value)})))
  value)

(defn- validate-generic-state
  "Return state when it is active|closed, else fail loudly (mutations)."
  [state]
  (when-not (generic-states state)
    (throw (ex-info "Strand state must be active or closed"
                    {:state state :allowed (vec (sort generic-states))})))
  state)

(defn- validate-readable-state
  "Return state when it is active|closed|replaced, else fail loudly (list filter)."
  [state]
  (when-not (readable-states state)
    (throw (ex-info "Strand state must be active, closed, or replaced"
                    {:state state :allowed (vec (sort readable-states))})))
  state)

(s/def ::read-limit pos-int?)

(defn- validate-read-limit
  "Return limit when it is a positive integer, else fail loudly."
  [limit]
  (when-not (s/valid? ::read-limit limit)
    (throw (ex-info "Read result limit must be a positive integer"
                    {:limit limit :explain (s/explain-str ::read-limit limit)})))
  limit)

(defn- read-limit-state [rt]
  (runtime-api/spool-state rt ::read-limit {:version read-limit-state-version}
                           #(hash-map :limit (atom default-read-limit))))

(defn read-limit
  "Return the runtime's batteries read-result cap for CLI list/ready ops."
  [rt]
  @(:limit (read-limit-state rt)))

(defn set-read-limit!
  "Set the runtime's batteries read-result cap for CLI list/ready ops.

  Intended for trusted workspace config. Invalid values fail loudly instead of
  falling back to the default cap."
  [rt limit]
  (let [limit (validate-read-limit limit)]
    (reset! (:limit (read-limit-state rt)) limit)
    limit))

(defn- effective-read-limit [rt explicit-limit]
  (validate-read-limit (or explicit-limit (read-limit rt))))

(defn- request-context
  "Build the mutation request context so hooks and events see the operation."
  [operation]
  {:request/source :json-socket
   :request/operation operation})

(defn- json-safe-value
  "Coerce query-introspection payloads (which carry EDN query expressions with
  keywords, symbols, and sets) into JSON-safe data, matching the JSON socket's
  `query-list`/`query-explain` projection so `strand query …` returns identical
  shapes to the old builtin."
  [value]
  (cond
    (nil? value) nil
    (or (string? value) (number? value) (boolean? value)) value
    (keyword? value) (subs (str value) 1)
    (symbol? value) (str value)
    (map? value) (into {} (map (fn [[k v]] [(json-safe-value k) (json-safe-value v)])) value)
    (sequential? value) (mapv json-safe-value value)
    (set? value) (mapv json-safe-value (sort-by pr-str value))
    :else (pr-str value)))

;; The blessed parser's :parse :json uses clojure.data.json/read-str, which
;; silently returns the first value and ignores trailing input, so it cannot
;; enforce the retired builtin's "exactly one JSON value" contract. weave reads
;; --input as a raw string and parses it strictly here instead: empty, malformed,
;; and trailing-value inputs all fail loudly before any mutation.
(defn- read-single-json
  "Read exactly one JSON value from s, failing loudly on empty, malformed, or
  trailing input. This preserves the retired builtin's strict stdin parsing."
  [s]
  (let [eof (Object.)
        ;; data.json/read unreads several characters of lookahead while parsing,
        ;; so the reader needs a pushback buffer wider than the default of 1.
        rdr (PushbackReader. (StringReader. s) 64)
        value (try (json/read rdr :eof-error? false :eof-value eof)
                   (catch Exception e
                     (throw (ex-info (str "weave --input is not valid JSON: " (ex-message e))
                                     {:code "pattern/input-invalid"}))))]
    (when (identical? value eof)
      (throw (ex-info "weave --input requires exactly one JSON value"
                      {:code "pattern/input-invalid"})))
    (when-not (identical? (json/read rdr :eof-error? false :eof-value eof) eof)
      (throw (ex-info "weave --input must contain exactly one JSON value"
                      {:code "pattern/input-invalid"})))
    value))

;; The blessed parser's :map flag silently collapses duplicate keys, but old
;; C6e requires duplicate keys within a single --attr priority to fail loudly.
;; The parser guarantees each --attr is followed by a well-formed key=value
;; token, so the flag keys can be recovered from the raw argv to enforce it.
(defn- attr-flag-keys [argv]
  (keep (fn [[flag token]]
          (when (= "--attr" flag)
            (subs token 0 (str/index-of token "="))))
        (partition 2 1 argv)))

(defn- check-attr-duplicates! [argv]
  (when-let [dup (some (fn [[k n]] (when (> n 1) k))
                       (frequencies (attr-flag-keys argv)))]
    (throw (ex-info (str "Duplicate attribute key in --attr: " dup) {:key dup}))))

(defn- attributes->map
  "Coerce a supplied --attributes value into an attribute map, failing loudly on
  anything but a JSON object. A JSON null parses to nil and is rejected here, not
  read as an empty patch; callers guard against an omitted flag before calling."
  [attributes]
  (if (map? attributes)
    (do (doseq [k (keys attributes)]
          (when (str/blank? k)
            (throw (ex-info "--attributes contains a blank attribute key" {:key k}))))
        attributes)
    (throw (ex-info "--attributes must reference a JSON object" {:value attributes}))))

(defn- parse-edges
  "Parse repeatable --edge edge-type:to-id specs into edge maps."
  [edge-specs]
  (mapv (fn [spec]
          (let [idx (str/index-of spec ":")]
            (when (or (nil? idx) (zero? idx) (= idx (dec (count spec))))
              (throw (ex-info "Malformed --edge; expected edge-type:to-id" {:edge spec})))
            {:type (subs spec 0 idx) :to (subs spec (inc idx))}))
        edge-specs))

(defn- run-named-query
  "Resolve a named query, validate params, overlay an optional state filter, and
  invoke the runtime list/ready fn exactly as the socket dispatch does."
  [rt query-fn query-name raw-params state limit]
  (let [query-def (graph/resolve-query rt query-name)
        params (graph/coerce-declared-params query-def raw-params)
        query-def (graph/conjoin-where query-def
                                       (when state [:= :state state])
                                       params)]
    (query-fn rt lean-attribute-byte-floor query-def params limit)))

(defn- run-named-ready-lean [rt query-name raw-params limit]
  (let [query-def (graph/resolve-query rt query-name)
        params (graph/coerce-declared-params query-def raw-params)]
    (weaver/ready-lean rt lean-attribute-byte-floor query-def params limit)))

(defn- await-options
  [{:keys [query param min-count max-count timeout-secs]}]
  (when (str/blank? query)
    (throw (ex-info "await --query requires a non-empty name" {})))
  (when (and (nil? min-count) (nil? max-count))
    (throw (ex-info "await requires --min-count or --max-count" {})))
  (doseq [[flag value] [["--min-count" min-count] ["--max-count" max-count]]
          :when (some? value)]
    (when (neg? value)
      (throw (ex-info (str "await " flag " must be non-negative") {flag value}))))
  (when (and (some? min-count) (some? max-count) (> min-count max-count))
    (throw (ex-info "await --min-count must not exceed --max-count"
                    {:min-count min-count :max-count max-count})))
  (when (and (zero? (or min-count -1)) (nil? max-count))
    (throw (ex-info "await --min-count 0 alone is vacuous" {:min-count min-count})))
  (let [timeout-secs (or timeout-secs await-default-timeout-secs)]
    (when (or (neg? timeout-secs) (> timeout-secs (quot Long/MAX_VALUE 1000)))
      (throw (ex-info "await --timeout-secs must be non-negative and safely bounded"
                      {:timeout-secs timeout-secs})))
    {:query query
     :raw-params (or param {})
     :min-count min-count
     :max-count max-count
     :timeout-secs timeout-secs}))

(defn- await-query
  [rt {:keys [query raw-params] :as opts}]
  (let [query-def (graph/resolve-query rt query)
        params (graph/coerce-declared-params query-def raw-params)]
    (assoc opts :query-def query-def :params params)))

(defn- await-probe
  [rt {:keys [query-def params min-count max-count]}]
  (let [limit (long (if (some? max-count) (inc max-count) min-count))
        count (count (weaver/list-lean rt lean-attribute-byte-floor query-def params limit
                                       {:clamp? true}))]
    {:count count
     :satisfied? (and (or (nil? min-count) (<= min-count count))
                      (or (nil? max-count) (<= count max-count)))}))

(defn- await-result
  [rt started {:keys [query min-count max-count]} reason probe]
  {:operation "await"
   :query query
   :reason reason
   :count (:count probe)
   :min_count min-count
   :max_count max-count
   :elapsed_ms (.toMillis (Duration/between started (runtime-api/now rt)))})

(defn- query-list-entry [[name query-def]]
  {:name name
   :params (if (map? query-def) (vec (:params query-def)) [])
   :referenced-params (graph/referenced-params query-def)})

(defn- query-list-entries [rt]
  (mapv query-list-entry (graph/queries rt)))

;; --- op handlers ------------------------------------------------------------

;; --- arg-specs --------------------------------------------------------------

(def ^:private add-arg-spec
  {:op "add"
   :doc "Create a strand with attributes, lifecycle state, and outgoing edges."
   :hook-class :mutating
   :deadline-class :standard
   :flags {:state {:type :string
                   :doc "Lifecycle state: active (default) or closed."}
           :attr {:type :map
                  :doc "String attribute key=value; repeatable, highest precedence. Values may be payload references."}
           :attributes {:type :string
                        :parse :json
                        :doc "Payload reference to a JSON object of typed bulk attributes (lowest precedence)."}
           :edge {:type :string
                  :repeat? true
                  :doc "Outgoing edge edge-type:to-id; repeatable."}}
   :positionals [{:name :title :type :string :required? true :doc "Strand title."}]
   :annotations {:use-when ["Minting a new unit of work with its initial attributes, state, and edges in one call."]
                 :failure-modes ["batteries/state-invalid"
                                 "batteries/attr-key-duplicate"
                                 "batteries/edge-malformed"]}})

(def ^:private update-arg-spec
  {:op "update"
   :doc "Update one strand's title, state, attributes, and outgoing edges. Attributes merge-patch, they do not replace the whole map."
   :hook-class :mutating
   :deadline-class :standard
   :flags {:title {:type :string
                   :doc "New strand title."}
           :state {:type :string
                   :doc "Lifecycle state: active or closed (cannot set replaced)."}
           :attr {:type :map
                  :doc "String attribute key=value merge patch; repeatable, highest precedence. Values may be payload references."}
           :attributes {:type :string
                        :parse :json
                        :doc "Payload reference to a JSON object merge patch of typed attributes (lowest precedence); a JSON null removes that key, an empty string stores \"\"."}
           :edge {:type :string
                  :repeat? true
                  :doc "Outgoing edge edge-type:to-id; repeatable."}}
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]
   :annotations {:notes ["Omitting every attribute flag leaves the stored attribute map untouched; supply --attr or --attributes to patch it."]
                 :failure-modes ["batteries/state-invalid"
                                 "batteries/attr-key-duplicate"
                                 "batteries/edge-malformed"]}})

(def ^:private show-arg-spec
  {:op "show"
   :doc "Return one strand by id."
   :hook-class :read
   :deadline-class :standard
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]
   :annotations {:use-when ["Fetching one strand's full normalized shape by id, including its typed attributes."]}})

(def ^:private supersede-arg-spec
  {:op "supersede"
   :doc "Replace one strand with another, marking the old replaced and rewiring dependencies."
   :hook-class :mutating
   :deadline-class :standard
   :positionals [{:name :old-id :type :string :required? true :doc "Strand being replaced."}
                 {:name :replacement-id :type :string :required? true :doc "Replacement strand."}]})

(def ^:private burn-arg-spec
  {:op "burn"
   :doc "Physically delete one strand and its incident edges."
   :hook-class :mutating
   :deadline-class :standard
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]})

(def ^:private list-arg-spec
  {:op "list"
   :doc "List lean-projected strands, optionally filtered by state and/or a named query."
   :hook-class :read
   :deadline-class :standard
   :flags {:state {:type :string
                   :doc "Filter by lifecycle state: active, closed, or replaced."}
           :query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}
           :limit {:type :int
                   :doc "Explicit maximum result count; set above the total for an intentional full read."}}
   :annotations {:use-when ["Browsing or filtering strands; combine --state and --query to narrow the set."]
                 :failure-modes ["batteries/state-invalid" "batteries/query-unknown"]}})

(def ^:private ready-arg-spec
  {:op "ready"
   :doc "List lean-projected ready strands, optionally from a named query result set."
   :hook-class :read
   :deadline-class :standard
   :flags {:query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}
           :limit {:type :int
                   :doc "Explicit maximum result count; set above the total for an intentional full read."}}
   :annotations {:use-when ["Selecting actionable strands whose blocking dependencies are already closed."]
                 :failure-modes ["batteries/query-unknown"]}})

(def ^:private await-arg-spec
  {:op "await"
   :doc "Block until a named query's result count is inside an inclusive band."
   :hook-class :read
   :deadline-class :unbounded
   :flags {:query {:type :string :doc "Weaver-registered named query."}
           :param {:type :map :doc "Named-query parameter key=value; repeatable."}
           :min-count {:type :int :doc "Inclusive minimum result count."}
           :max-count {:type :int :doc "Inclusive maximum result count."}
           :timeout-secs
           {:type :int
            :doc (format-alpha/reflow
                  "|Seconds to block before returning the timeout reason (default
                   |1800). Cap waits at about 50 minutes and re-issue them so an
                   |idle provider prompt cache does not expire.")}}
   :annotations
   {:notes [(format-alpha/reflow
             "|Await observes query cardinality, not strand completion. Closing,
              |replacing, or burning a strand all remove it from an active-set
              |query.")]
    :failure-modes ["batteries/query-unknown"]}})

(def ^:private subgraph-arg-spec
  {:op "subgraph"
   :doc "Return a relation-scoped subgraph rooted at a strand."
   :hook-class :read
   :deadline-class :standard
   :flags {:relation {:type :string
                      :doc "Declared acyclic relation type (defaults to parent-of)."}}
   :positionals [{:name :root-id :type :string :required? true :doc "Root strand id."}]})

(def ^:private weave-arg-spec
  {:op "weave"
   :doc "Apply a registered create-only weave pattern to one JSON input value."
   :hook-class :mutating
   :deadline-class :standard
   :flags {:pattern {:type :string
                     :required? true
                     :doc "Registered weave pattern name."}
           :input {:type :string
                   :required? true
                   :doc "Payload reference (e.g. :stdin) to exactly one JSON value for the pattern."}}
   :annotations {:use-when ["Applying a registered create-only pattern to bulk-mint a coordinated strand set from one JSON value."]
                 :failure-modes ["batteries/weave-input-invalid" "batteries/pattern-unknown"]}})

(def ^:private query-arg-spec
  {:op "query"
   :doc "Introspect registered named queries: list all or explain one."
   :annotations {:use-when ["Discovering which named queries the runtime exposes before driving list or ready."]}
   :subcommands {"list" {:doc "List registered named query metadata."
                         :hook-class :read
                         :deadline-class :standard}
                 "explain" {:doc "Explain one registered named query."
                            :positionals [{:name :name
                                           :type :string
                                           :required? true
                                           :doc "Query name."}]
                            :hook-class :read
                            :deadline-class :standard
                            :annotations {:failure-modes ["batteries/query-unknown"]}}}})

(def ^:private pattern-arg-spec
  {:op "pattern"
   :doc "Introspect registered weave patterns: list all or explain one."
   :annotations {:use-when ["Discovering which weave patterns the runtime exposes before calling weave."]}
   :subcommands {"list" {:doc "List registered weave pattern metadata."
                         :hook-class :read
                         :deadline-class :standard}
                 "explain" {:doc "Explain one registered weave pattern."
                            :positionals [{:name :name
                                           :type :string
                                           :required? true
                                           :doc "Pattern name."}]
                            :hook-class :read
                            :deadline-class :standard
                            :annotations {:failure-modes ["batteries/pattern-unknown"]}}}})

(def ^:private note-arg-spec
  {:op "note"
   :doc "Append a note to a target strand's memory; its note/text/note/at content is write-once."
   :hook-class :mutating
   :deadline-class :standard
   :flags {:by {:type :string
                :doc "Author attribution recorded on the note."}
           :round {:type :int
                   :doc "Review round the note belongs to."}
           :attr {:type :map
                  :doc "Decorating attribute key=value on the note strand (e.g. note/kind); repeatable. Values may be payload references."}}
   :positionals [{:name :id :type :string :required? true :doc "Target strand id."}
                 {:name :text :type :string :required? true :doc "Note text."}]})

(def ^:private notes-arg-spec
  {:op "notes"
   :doc "Return a target strand's notes in note/at order from every writer."
   :hook-class :read
   :deadline-class :standard
   :flags {:round {:type :int
                   :doc "Filter to notes from one review round."}}
   :positionals [{:name :id :type :string :required? true :doc "Target strand id."}]})

(def ^:private runbook-arg-spec
  {:op "runbook"
   :doc "Show the batteries strand-tracking runbook."
   :hook-class :read
   :deadline-class :standard
   :annotations {:use-when ["Loading the opinionated batteries loop: when to plan, body convention, ready, and close."]}})

(def ^:private attributes-return
  {:type :map :extra :json})

(def ^:private strand-return
  {:type :map
   :required {:id :string
              :title :string
              :state :string
              :created_at :string
              :updated_at :string
              :attributes attributes-return}})

(def ^:private edge-return
  {:type :map
   :required {:from_strand_id :string
              :to_strand_id :string
              :edge_type :string
              :attributes attributes-return}})

(def ^:private strand-collection-return
  {:type :collection :items strand-return})

(def ^:private query-list-return
  {:type :map
   :required {:name :string
              :params {:type :collection :items :string}
              :referenced-params {:type :collection :items :string}}})

(def ^:private op-returns
  {'add strand-return
   'update strand-return
   'show strand-return
   'supersede
   {:type :map
    :required {:old {:type :map :required {:before strand-return :after strand-return}}
               :replacement-id :string
               :supersedes-edge edge-return
               :rewired-dependencies
               {:type :collection
                :items {:type :map
                        :required {:from :string :old-to :string :new-to :string :type :string
                                   :deleted-edge edge-return :edge edge-return}}}}}
   'burn {:type :map
          :required {:burned {:type :collection :items :string}
                     :count :integer}}
   'list strand-collection-return
   'ready strand-collection-return
   'await {:type :map
           :required {:operation :string
                      :query :string
                      :reason :string
                      :count :integer
                      :min_count [:nullable :integer]
                      :max_count [:nullable :integer]
                      :elapsed_ms :integer}}
   'subgraph {:type :map
              :required {:root_ids {:type :collection :items :string}
                         :strands strand-collection-return
                         :edges {:type :collection :items edge-return}}}
   'weave {:type :map
           :required {:created strand-collection-return
                      :refs {:type :map :extra :string}}}
   'query {:subcommands
           {"list" {:type :collection :items query-list-return}
            "explain" {:type :map
                       :required {:name :string
                                  :operation :string
                                  :params {:type :collection :items :string}
                                  :referenced-params {:type :collection :items :string}
                                  :where :json
                                  :definition :json
                                  :where-form :string
                                  :definition-form :string
                                  :summary :string}}}}
   'pattern {:subcommands
             {"list" {:type :collection
                      :items {:type :map
                              :required {:name :string :fn :string :input-spec :string}
                              :optional {:doc :string}}}
              "explain" {:type :map
                         :required {:name :string :operation :string :fn :string :input-spec :string
                                    ;; the projection node grammar is owned by
                                    ;; millstrand.api.spec.alpha, so these carry as json
                                    :contract :json :template :json :spec-forms :json}
                         :optional {:doc :string}}}}
   'note {:type :map :required {:id :string :target :string}}
   'notes {:type :collection
           :items {:type :map
                   :required {:id :string :note :string :at :string}
                   :optional {:by :string :round :integer}}}
   'runbook {:type :map :required {:runbook :string}}})

;; --- op-level about/prime prose ---------------------------------------------

(def ^:private add-meta
  "Cross-verb narrative for `add`, projected by the `about`/`prime` meta-verbs
  (DELTA-Dtf-002.CC4). Kept off the node: it frames add against its sibling verbs
  rather than restating any node-derivable flag."
  {:about (format-alpha/reflow
           "|add is the create verb of the batteries strand surface: it mints one
            |strand and hands back its generated id. update patches that strand
            |afterward, supersede replaces it wholesale, and burn deletes it —
            |add is where a unit of work first enters the graph.")
   :prime (format-alpha/reflow
           "|Reach for add the moment a unit of work appears. Prefer --edge over
            |free-text references so later readiness and subgraph traversal can
            |follow the structural links you record now.")})

(def ^:private weave-meta
  "Cross-verb narrative for `weave` (DELTA-Dtf-002.CC4)."
  {:about (format-alpha/reflow
           "|weave applies a registered create-only pattern to one JSON value,
            |minting a coordinated set of strands and returning their refs. It is
            |the bulk-creation counterpart to add's single-strand mint, and the
            |pattern op explains which patterns a runtime exposes.")
   :prime (format-alpha/reflow
           "|Reach for weave when one input should fan out into several linked
            |strands under a reviewed pattern. Run `strand pattern list` first to
            |see the registered patterns and their input specs.")})

;; --- batteries-owned glossary outcomes --------------------------------------

(def ^:private batteries-glossary
  "Batteries-owned named failure outcomes (DELTA-Dtf-002.CC5).

  Reconciled by the module before help resolves an op whose `:annotations` `failure-modes`
  reference them — the load-order contract (DELTA-Dtf-002.CC7). Each name is
  qualified and stable; a changed meaning takes a new name, never a redefinition."
  [{:name "batteries/state-invalid"
    :definition "A mutation named a lifecycle state outside active|closed; list also permits replaced."}
   {:name "batteries/attr-key-duplicate"
    :definition "Two --attr flags in one invocation set the same key at the same precedence."}
   {:name "batteries/edge-malformed"
    :definition "An --edge token is not the required edge-type:to-id shape."}
   {:name "batteries/query-unknown"
    :definition "A --query names no registered query, or a --param names an undeclared query parameter."}
   {:name "batteries/pattern-unknown"
    :definition "A named weave pattern is not registered in the runtime."}
   {:name "batteries/weave-input-invalid"
    :definition "weave --input did not carry exactly one JSON value."}])

;; --- reference default help renderer (the forcing function) -----------------
;;
;; DELTA-Dtf-002.CC1 / DELTA-Dtf-003.D1: batteries exports ONE recursive renderer
;; over the uniform fractal node (DELTA-Dtf-001.CC2). `render-node` is the whole
;; point: an op root, a verb child, and any deeper descendant render through its
;; single body with no per-level branch, and the only recursion over nodes is its
;; own closing tail over `:children`. Everything else here — the envelope headers,
;; the leaf flag/positional lines, the returns pretty-printer — is non-recursive
;; framing around that one uniform recursion.

(defn- indent
  "A two-space indent string for nesting `depth`."
  [depth]
  (str/join (repeat depth "  ")))

(defn- bullet-lines
  "Render a labelled bullet block for a string-array annotation, or nil when empty."
  [depth label items]
  (when (seq items)
    (cons (str (indent depth) label ":")
          (map #(str (indent (inc depth)) "- " %) items))))

(defn- spaced
  "Prepend a blank line to a non-empty rendered section."
  [lines]
  (when (seq lines) (cons "" lines)))

(defn- arg-markers
  "Render the shared trailing markers of a flag or positional line."
  [{:keys [required repeat variadic parse spec doc]}]
  (str (when required " (required)")
       (when repeat " (repeatable)")
       (when variadic " (variadic)")
       (when parse (str " {parse " parse "}"))
       (when spec (str " {spec " spec "}"))
       (when (seq doc) (str "  " doc))))

(declare shape-lines)

(defn- inline-shape
  "Render a nested return-shape value compactly on one line."
  [value]
  (cond
    (map? value)
    (str "{" (str/join ", " (map (fn [[k v]]
                                   (str (if (keyword? k) (name k) k)
                                        ": " (inline-shape v)))
                                 value)) "}")

    (sequential? value)
    (str "[" (str/join ", " (map inline-shape value)) "]")

    :else
    (str value)))

(defn- template-lines
  "Render a declared-spec copyable template block under its arg line, or nil."
  [depth template]
  (when (some? template)
    (cons (str (indent depth) "template:")
          (shape-lines (inc depth) template))))

(defn- flag-lines
  "Render one declared flag: its line, then any declared-spec template block.

  A presence `:boolean` flag takes no value on the command line, so its line
  renders the bare token with no value placeholder; every value-consuming type
  keeps its `<type>` form."
  [depth {:keys [flag type template] :as entry}]
  (cons (str (indent depth) flag
             (when-not (= type "boolean") (str " <" type ">"))
             (arg-markers entry))
        (template-lines (inc depth) template)))

(defn- positional-lines
  "Render one declared positional: its line, then any declared-spec template
  block."
  [depth {:keys [name type template] :as entry}]
  (cons (str (indent depth) "<" name "> <" type ">" (arg-markers entry))
        (template-lines (inc depth) template)))

(defn- shape-lines
  "Render one JSON-safe return-shape value as indented readable lines.

  A generic recursion over the return-shape `explain` data (SPEC-003.C60b),
  distinct from the fractal-node recursion — it descends return shapes, never
  help nodes, so it does not touch the node-uniformity invariant."
  [depth value]
  (cond
    (map? value)
    (if (empty? value)
      [(str (indent depth) "{}")]
      (map (fn [[k v]]
             (str (indent depth)
                  (if (keyword? k) (name k) k)
                  ": " (inline-shape v)))
           value))

    (sequential? value)
    (if (some coll? value)
      (mapcat #(shape-lines depth %) value)
      [(str (indent depth) "[" (str/join ", " value) "]")])

    :else
    [(str (indent depth) value)]))

(defn- json-help-command
  "Return the raw-help command selecting `jq-path` for one help node path."
  [path jq-path]
  (str "strand help --json " (str/join " " path) " | jq '" jq-path "'"))

(defn- render-node
  "THE recursive renderer over the uniform fractal node (DELTA-Dtf-001.CC2).

  One body renders every level: an op root, a verb child, and any deeper
  descendant are the same shape, and the sole node recursion is the closing tail
  over `:children`. No branch keys off the node's depth or kind — that uniformity
  is the schema's forcing function (DELTA-Dtf-003.D1). If a level ever needed its
  own case, the schema would be wrong, not this renderer."
  [path depth {:keys [name doc invocation returns hook-class deadline-class
                      use-when notes failure-modes children]}]
  (let [field (inc depth)
        entry (inc field)]
    (concat
     [(str (indent depth) name (when (seq doc) (str " — " doc)))
      (str (indent field) "invocation: " (:mode invocation))]
     ;; classes render only where they exist: invocable leaf nodes
     ;; (DELTA-Lhc-003.CC1); interior nodes carry null and stay silent.
     (when hook-class
       [(str (indent field) "hook-class: " hook-class "   deadline: " deadline-class)])
     (spaced
      (when (seq (:flags invocation))
        (cons (str (indent field) "flags:")
              (mapcat #(flag-lines entry %) (:flags invocation)))))
     (spaced
      (when (seq (:positionals invocation))
        (cons (str (indent field) "positionals:")
              (mapcat #(positional-lines entry %) (:positionals invocation)))))
     (spaced
      (when returns
        [(str (indent field) "returns: " (json-help-command path ".node.returns"))]))
     (spaced (bullet-lines field "use-when" use-when))
     (spaced (bullet-lines field "notes" notes))
     (spaced (bullet-lines field "failure-modes-glossary" failure-modes))
     (mapcat (fn [{child-name :name :as child}]
               (cons "" (render-node (conj path child-name) field child)))
             children))))

(defn- inspect-lines
  "Render commands for op-wide details omitted from friendly help."
  [path glossary]
  (cond-> ["inspect:"
           (str (indent 1) "operation: " (json-help-command path ".operation"))]
    (seq glossary)
    (conj (str (indent 1) "glossary:  " (json-help-command path ".glossary")))))

(defn- render-detail
  "Render a detail help envelope `{schema-version, operation, source, glossary,
  node}` (DELTA-Dtf-001.CC1) as text."
  [{:keys [operation glossary node]}]
  (let [operation-name (:name operation)
        path (cond-> [operation-name]
               (not= operation-name (:name node)) (conj (:name node)))]
    (str/join
     "\n"
     (concat (render-node path 0 node)
             [""]
             (inspect-lines path glossary)))))

(defn- render-catalog
  "Render the versioned no-arg catalog `{schema-version, ops[]}`
  (DELTA-Dtf-001.CC3) as text.

  Each shallow per-op envelope's summary node renders through the SAME uniform
  `render-node`, so the catalog reuses the node contract unchanged."
  [{:keys [ops]}]
  (str/join
   "\n"
   (mapcat (fn [{:keys [node]}]
             (concat (render-node [(:name node)] 0 node) [""]))
           ops)))

(defn- colorize-help
  "Add ANSI emphasis to friendly help rendered for a terminal."
  [text]
  (-> text
      (str/replace #"(?m)^(\s*)([^ ]+)( — )"
                   (fn [[_ prefix name separator]]
                     (str prefix "\u001b[1;36m" name "\u001b[0m" separator)))
      (str/replace #"(?m)^(\s*)(flags|positionals|returns|use-when|notes|failure-modes-glossary|inspect|operation|glossary):"
                   (fn [[_ prefix label]]
                     (str prefix "\u001b[1;33m" label ":\u001b[0m")))))

(defn default-help-transform
  "Render a canonical help envelope (DELTA-Dtf-001.CC1) as readable text.

  The batteries reference default help transform (DELTA-Dtf-002.CC1): a full
  envelope plus terminal capabilities → the string the CLI relays verbatim. It is EXPORTED for trusted
  `init.clj` election through `register-default-help-transform!` (Task 8) and is
  deliberately not auto-registered by the module, so a fresh world keeps the
  raw-JSON floor (DELTA-Dtf-002.D1).

  Both members of the one help-schema family render through the single uniform
  node renderer (`render-node`): the detail envelope carrying `node`, and the
  no-arg catalog carrying `ops[]` of summary nodes (DELTA-Dtf-001.CC3). The only
  branch is which envelope family this is — an envelope-shape choice, never a
  per-node-level one, so the recursive node renderer stays uniform at every depth
  (the forcing-function invariant, DELTA-Dtf-003.D1). ANSI color is added only
  when the caller reports `:is-tty true`; redirected and agent output stays plain."
  [envelope {:keys [is-tty]}]
  (cond-> (if (contains? envelope :ops)
            (render-catalog envelope)
            (render-detail envelope))
    is-tty colorize-help))

;; --- declarations -----------------------------------------------------------

(defn- op-options
  [op-name arg-spec & [metadata]]
  (merge {:arg-spec arg-spec
          :returns (get op-returns op-name)}
         metadata))

(millstrand/defop! add
  "Create a strand with merged attributes, optional state, and outgoing edges."
  (op-options 'add add-arg-spec add-meta)
  [ctx]
  (let [rt (:op/runtime ctx)
        args (:op/args ctx)
        {:keys [title state attr attributes edge]} args]
    (check-attr-duplicates! (:op/argv ctx))
    (let [merged (merge (when (contains? args :attributes) (attributes->map attributes))
                        (or attr {}))
          edges (parse-edges edge)]
      (weaver/add! rt
                   (cond-> {:title title :attributes merged}
                     (some? state) (assoc :state (validate-generic-state state))
                     (seq edges) (assoc :edges edges))
                   (request-context :add)))))

(millstrand/defop! update
  "Patch one strand's title, state, attributes, and outgoing edges."
  (op-options 'update update-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        args (:op/args ctx)
        {:keys [id title state attr attributes edge]} args]
    (check-attr-duplicates! (:op/argv ctx))
    (let [edges (parse-edges edge)
          attribute-patch? (or (contains? args :attr) (contains? args :attributes))
          patch (cond-> {}
                  (seq edges) (assoc :edges edges)
                  (some? title) (assoc :title title)
                  (some? state) (assoc :state (validate-generic-state state))
                  attribute-patch? (assoc :attributes
                                          (merge (when (contains? args :attributes)
                                                   (attributes->map attributes))
                                                 (or attr {}))))]
      (weaver/update! rt id patch (request-context :update)))))

(millstrand/defop! show
  "Return one normalized strand by id."
  (op-options 'show show-arg-spec)
  [ctx]
  (weaver/show (:op/runtime ctx) (:id (:op/args ctx))))

(millstrand/defop! supersede
  "Replace one strand with another and return the supersession result."
  (op-options 'supersede supersede-arg-spec)
  [ctx]
  (let [{:keys [old-id replacement-id]} (:op/args ctx)]
    (weaver/supersede! (:op/runtime ctx) old-id replacement-id (request-context :supersede))))

(millstrand/defop! burn
  "Physically delete one strand by id and return the burn summary."
  (op-options 'burn burn-arg-spec)
  [ctx]
  (graph/burn-by-ids! (:op/runtime ctx) [(:id (:op/args ctx))] (request-context :burn)))

(millstrand/defop! list
  "List lean-projected strands, optionally filtered by lifecycle state or a named query."
  (op-options 'list list-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [state query param limit]} (:op/args ctx)
        params (or param {})
        limit (effective-read-limit rt limit)]
    (when state (validate-readable-state state))
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-query rt weaver/list-lean query params state limit))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (weaver/list-lean rt lean-attribute-byte-floor (if state [:= :state state] [:exists :id]) {} limit)))))

(millstrand/defop! ready
  "List lean-projected ready strands, optionally from a named query result set."
  (op-options 'ready ready-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [query param limit]} (:op/args ctx)
        params (or param {})
        limit (effective-read-limit rt limit)]
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-ready-lean rt query params limit))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (weaver/ready-lean rt lean-attribute-byte-floor [:exists :id] {} limit)))))

(millstrand/defquery! strand-closed
  "Return the closed strand identified by `id`, when it exists."
  {:usage "strand await --query strand-closed --param id=<id> --min-count 1"}
  {:params [:id]
   :where [:and [:= :state "closed"]
           [:= :id [:param :id]]]})

(millstrand/defquery! strand-active
  "Return the active strand identified by `id`, when it exists."
  {:usage "strand await --query strand-active --param id=<id> --max-count 0"}
  {:params [:id]
   :where [:and [:= :state "active"]
           [:= :id [:param :id]]]})

(millstrand/defquery! children-active
  "Return active children of the strand identified by `parent`."
  {:usage "strand await --query children-active --param parent=<id> --max-count 0"}
  {:params [:parent]
   :where [:and [:= :state "active"]
           [:edge/in "parent-of" [:= :id [:param :parent]]]]})

(millstrand/defquery! blockers-active
  "Return active blockers of the strand identified by `id`."
  {:usage "strand await --query blockers-active --param id=<id> --max-count 0"}
  {:params [:id]
   :where [:and [:= :state "active"]
           [:edge/in "depends-on" [:= :id [:param :id]]]]})

(millstrand/defop! await
  "Block until a named query's result count is inside the requested band."
  (op-options 'await await-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        opts (await-query rt (await-options (:op/args ctx)))
        started (runtime-api/now rt)]
    (spool/poll-until!
     (runtime-api/clock rt)
     {:timeout-ms (* 1000 (long (:timeout-secs opts)))
      :poll-ms await-poll-ms
      :check #(await-probe rt opts)
      :pred->result #(when (:satisfied? %) (await-result rt started opts "satisfied" %))
      :on-timeout #(await-result rt started opts "timeout" %)})))

(millstrand/defop! subgraph
  "Return a relation-scoped subgraph rooted at one strand."
  (op-options 'subgraph subgraph-arg-spec)
  [ctx]
  (let [{:keys [root-id relation]} (:op/args ctx)
        {:keys [root-ids strands edges]}
        (graph/subgraph (:op/runtime ctx) [root-id]
                        (cond-> {} relation (assoc :type relation)))]
    {"root_ids" root-ids
     "strands" strands
     "edges" edges}))

(millstrand/defop! weave
  "Apply a registered create-only weave pattern to one JSON input value."
  (op-options 'weave weave-arg-spec weave-meta)
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [pattern input]} (:op/args ctx)]
    (patterns/weave! rt
                     pattern
                     (walk/keywordize-keys (read-single-json input))
                     (request-context :weave))))

(millstrand/defop! query
  "Introspect registered named queries: list all metadata or explain one."
  (op-options 'query query-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case (first subcommand)
      "list" (json-safe-value (query-list-entries rt))
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "query explain requires a query name" {})))
                    (json-safe-value (graph/query-explain rt nm))))))

(millstrand/defop! pattern
  "Introspect registered weave patterns: list all metadata or explain one."
  (op-options 'pattern pattern-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case (first subcommand)
      "list" (patterns/patterns rt)
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "pattern explain requires a pattern name" {})))
                    (patterns/explain rt nm)))))

(millstrand/defop! note
  "Append a note to a target strand's memory via the note primitive."
  (op-options 'note note-arg-spec)
  [ctx]
  (let [{:keys [id text by round attr]} (:op/args ctx)]
    (check-attr-duplicates! (:op/argv ctx))
    ;; note! folds every non-:by/:round opt into decorating attrs, so the
    ;; string-keyed --attr map lands as ordinary strand attrs on the note.
    (notes/note! (:op/runtime ctx) id text (merge (or attr {}) {:by by :round round}))))

(millstrand/defop! notes
  "Return a target strand's notes in note/at order."
  (op-options 'notes notes-arg-spec)
  [ctx]
  (let [{:keys [id round]} (:op/args ctx)]
    (notes/notes (:op/runtime ctx) id {:round round})))

(defn- slurp-runbook
  "Return the batteries runbook markdown.

  Fails loudly when the classpath resource is missing."
  []
  (let [resource (io/resource "millstrand/spools/runbook.md")]
    (when-not resource
      (throw (ex-info "Batteries runbook resource is missing"
                      {:code "batteries/runbook-missing"
                       :resource "millstrand/spools/runbook.md"})))
    (slurp resource)))

(millstrand/defop runbook
  "Return the batteries strand-tracking runbook."
  (op-options 'runbook runbook-arg-spec)
  [_ctx]
  (require-valid! ::runbook-result
                  {:runbook (slurp-runbook)}
                  "Batteries runbook returned an invalid result"))

(s/def ::runtime some?)
(s/def ::seed-context (s/keys :req-un [::runtime]))
(s/def ::seeded #{:batteries-glossary})
(s/def ::seed-result
  (s/and (s/keys :req-un [::seeded])
         #(= #{:seeded} (set (keys %)))))

(defn seed-batteries-glossary!
  "Seed Batteries' process-lifetime failure glossary.

  Input conforms to `::seed-context`; the result conforms to `::seed-result`."
  [ctx]
  (require-valid! ::seed-context ctx "Batteries glossary seed context is invalid")
  (let [runtime (:runtime ctx)
        result {:seeded :batteries-glossary}]
    (doseq [outcome batteries-glossary]
      (glossary/register-glossary-outcome!
       runtime (assoc outcome :owner 'millstrand.spools.batteries)))
    (require-valid! ::seed-result result "Batteries glossary seed result is invalid")))

(lifecycle/defseed! batteries-glossary-seed
  "Seed the process-lifetime Batteries failure glossary."
  {:apply 'millstrand.spools.batteries/seed-batteries-glossary!})
