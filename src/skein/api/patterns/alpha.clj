(ns skein.api.patterns.alpha
  "Explicit-runtime API for registering, inspecting, and invoking weave patterns.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns pattern validation, function resolution, input
  spec validation and caller guidance, and the transactional create-only batch a
  weave produces. The SQL batch engine lives in `skein.core.db`; the shared
  lifecycle and dispatch plumbing in `skein.core.weaver.*`."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [skein.api.batch.alpha :as batch-api]
            [skein.api.spec.alpha :as api-spec]
            [skein.api.spool.alpha :as spool]
            [skein.core.db :as db]
            [skein.core.query :as query]
            [skein.core.specs :as specs]
            [skein.core.weaver.access :refer [ds normalize pattern-registry pattern-store
                                              with-spool-classloader]]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :refer [event-base request-context
                                                 run-validation-hooks! run-transform-hooks]])
  (:import [java.util UUID]))

(declare canonical-pattern-name pattern-entry pattern-input-contract validate-pattern-input!
         normalize-weave-strand-attributes weave-payload weave-batch-context
         require-pattern-registration! public-pattern-entry validate-pattern-fn!)

(defn register-pattern!
  "Register a trusted weaver pattern handler and input spec in `runtime`.

  Registration input conforms to `::skein.core.specs/pattern-registration`,
  and the returned entry conforms to `::skein.core.specs/pattern-entry`."
  ([runtime pattern-name fn-sym input-spec]
   (register-pattern! runtime core-registry/repl-owner pattern-name nil fn-sym input-spec))
  ([runtime pattern-name doc fn-sym input-spec]
   (register-pattern! runtime core-registry/repl-owner pattern-name doc fn-sym input-spec))
  ([runtime owner pattern-name doc fn-sym input-spec]
   (let [registration {:name pattern-name
                       :doc doc
                       :fn fn-sym
                       :input-spec input-spec}]
     (require-pattern-registration! registration)
     (validate-pattern-fn! runtime fn-sym)
     (let [entry (pattern-entry pattern-name doc fn-sym input-spec)]
       (core-registry/put-entry! (pattern-store runtime) owner (:name entry) entry)
       entry))))

(defn patterns
  "Return registered weave pattern metadata from `runtime`, ordered by name.

  Each returned entry conforms to `::skein.core.specs/pattern-entry`."
  [runtime]
  (mapv (comp public-pattern-entry val)
        (sort-by key (pattern-registry runtime))))

(defn resolve-pattern
  "Return the registered weave pattern for a name.

  Accepts a simple symbol, keyword, or raw CLI string (trimmed, optional leading
  colon), matching `skein.api.graph.alpha/resolve-query` string handling.

  Missing patterns fail loudly. The returned entry conforms to
  `::skein.core.specs/pattern-entry`."
  [runtime pattern-name]
  (let [canonical-name (canonical-pattern-name pattern-name)
        registered (pattern-registry runtime)]
    (or (some-> (get registered canonical-name) public-pattern-entry)
        (throw (ex-info "Pattern not found" {:pattern pattern-name
                                             :canonical-pattern canonical-name
                                             :available (sort (keys registered))})))))

(s/def :skein.pattern-explain/name (s/and string? #(not (str/blank? %))))
(s/def :skein.pattern-explain/fn (s/and string? #(not (str/blank? %))))
(s/def :skein.pattern-explain/input-spec (s/and string? #(not (str/blank? %))))
(s/def :skein.pattern-explain/contract map?)
(s/def :skein.pattern-explain/template
  (s/or :placeholder string?
        :object (s/map-of string? :skein.pattern-explain/template)
        :array (s/coll-of :skein.pattern-explain/template :kind vector?)))
(s/def :skein.pattern-explain/spec-forms vector?)
(s/def :skein.pattern-explain/doc (s/and string? #(not (str/blank? %))))
(s/def ::explain-result
  (s/and (s/keys :req-un [:skein.pattern-explain/name
                          :skein.pattern-explain/fn
                          :skein.pattern-explain/input-spec
                          :skein.pattern-explain/contract
                          :skein.pattern-explain/template
                          :skein.pattern-explain/spec-forms]
                 :opt-un [:skein.pattern-explain/doc])
         #(or (= #{:name :fn :input-spec :contract :template :spec-forms}
                 (set (keys %)))
              (= #{:name :fn :input-spec :contract :template :spec-forms :doc}
                 (set (keys %))))))

(defn explain
  "Describe a registered weave pattern and its input contract in `runtime`.

  The input contract is the shared `skein.api.spec.alpha` projection:
  `:contract` is the nested node tree, `:template` the copyable JSON skeleton,
  and `:spec-forms` the printed form graph, all resolved against the live spec
  registry with no predicate invoked. Missing patterns or unregistered input
  specs fail loudly. The returned map conforms to `::explain-result`."
  [runtime pattern-name]
  ;; :fn and :name are renamed on destructure: locals named `fn` and `name`
  ;; shadow the clojure.core vars.
  (let [{:keys [doc input-spec] fn-sym :fn registered-name :name}
        (resolve-pattern runtime pattern-name)]
    (spool/require-valid!
     ::explain-result
     (cond-> (merge {:name registered-name
                     :fn (str fn-sym)
                     :input-spec (str input-spec)}
                    (pattern-input-contract input-spec))
       doc (assoc :doc doc))
     "Pattern explanation is invalid")))

(s/fdef explain
  :args (s/cat :runtime map?
               :pattern-name (s/or :keyword keyword? :symbol symbol? :string string?))
  :ret ::explain-result)

(s/def :skein.pattern-weave/ref-key (s/and string? #(not (str/blank? %))))
(s/def :skein.pattern-weave/refs
  (s/map-of :skein.pattern-weave/ref-key ::specs/id))
(s/def ::weave-result
  (s/and (s/keys :req-un [::batch-api/created :skein.pattern-weave/refs])
         #(every? #{:created :refs} (keys %))))

(defn weave!
  "Validate pattern input, invoke the pattern, and apply its create-only batch.

  The four-argument arity threads an explicit request-context map for trusted
  callers (the connected-client tier); the three-argument arity derives its own
  weave context. A caller-supplied context conforms to
  `::skein.core.specs/request-context`; the pre-commit hook context conforms to
  `::skein.core.specs/batch-hook-context`."
  ([runtime pattern-name input]
   (weave! runtime pattern-name input (request-context :weave)))
  ([runtime pattern-name input req-ctx]
   (let [req-ctx (spool/require-valid! ::specs/request-context
                                       req-ctx
                                       "Request context is invalid")
         {fn-sym :fn input-spec :input-spec} (resolve-pattern runtime pattern-name)
         canonical-name (canonical-pattern-name pattern-name)]
     (validate-pattern-input! canonical-name input-spec input)
     (let [batch (with-spool-classloader
                   runtime
                   #((requiring-resolve fn-sym) {:input input}))
           normalized-batch (normalize-weave-strand-attributes
                             runtime req-ctx canonical-name input batch)
           normalized-payload (weave-payload normalized-batch)
           result (jdbc/with-transaction [tx (ds runtime)]
                    (let [result (normalize
                                  (db/add-strand-batch-in-transaction! tx normalized-batch))]
                      (run-validation-hooks! runtime
                                             :batch/apply-before-commit
                                             (weave-batch-context req-ctx canonical-name input
                                                                  normalized-payload result))
                      result))
           weave-result (spool/require-valid! ::weave-result
                                              (select-keys result [:created :refs])
                                              "Pattern weave result is invalid")]
       ;; a weave is a create-only batch apply; without this event, event-driven
       ;; spools (agent-run, the subagent executor) never see pattern-created
       ;; strands until an unrelated mutation happens to trigger their next scan
       (dispatch/enqueue! runtime (assoc (event-base :batch/applied)
                                         :batch/id (str (UUID/randomUUID))
                                         :pattern/name canonical-name
                                         :batch/refs (:refs result)
                                         :batch/created (:created result)))
       weave-result))))

(s/fdef weave!
  :args (s/or :default (s/cat :runtime map?
                              :pattern-name (s/or :keyword keyword?
                                                  :symbol symbol?
                                                  :string string?)
                              :input map?)
              :with-ctx (s/cat :runtime map?
                               :pattern-name (s/or :keyword keyword?
                                                   :symbol symbol?
                                                   :string string?)
                               :input map?
                               :req-ctx ::specs/request-context))
  :ret ::weave-result)

(defn- pattern-registration-message
  "Return a caller-facing diagnostic for an already-rejected registration."
  [{:keys [name doc input-spec] fn-sym :fn}]
  (cond
    (not (s/valid? :skein.pattern/name name))
    "Pattern name is invalid"
    (not (s/valid? :skein.pattern/doc doc))
    "Pattern doc must be a non-blank string"
    (not (s/valid? :skein.pattern/fn fn-sym))
    "Pattern function must be a fully qualified symbol"
    (not (s/valid? :skein.pattern/input-spec input-spec))
    "Pattern input spec must be a keyword or symbol"
    :else "Pattern registration input is invalid"))

(defn- require-pattern-registration!
  "Validate the complete registration spec before deriving diagnostics."
  [registration]
  (try
    (spool/require-valid! ::specs/pattern-registration
                          registration
                          "Pattern registration input is invalid")
    (catch clojure.lang.ExceptionInfo error
      (throw (ex-info (pattern-registration-message registration)
                      (ex-data error)
                      error)))))

(defn- public-pattern-entry [entry]
  (spool/require-valid! ::specs/pattern-entry
                        entry
                        "Pattern registry entry is invalid")
  entry)

(defn- validate-pattern-fn!
  "Fail loudly unless fn-sym resolves to a callable value in `runtime`."
  [runtime fn-sym]
  (let [resolved (with-spool-classloader runtime #(requiring-resolve fn-sym))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Pattern function must resolve to a callable value"
                      {:fn fn-sym :resolved-class (str (class value))})))
    fn-sym))

;; --- Registry entry construction ---

(defn- canonical-pattern-name [pattern-name]
  ;; query-lookup-name, not canonical-query-name: pattern lookups accept the same
  ;; raw CLI string forms (trimmed, optional leading colon) as query lookups.
  (query/query-lookup-name pattern-name))

(defn- pattern-entry
  "Build a validated pattern registry entry; `doc` may be nil for a doc-less entry."
  [pattern-name doc fn-sym input-spec]
  (let [entry (cond-> {:name (canonical-pattern-name pattern-name)
                       :fn fn-sym
                       :input-spec input-spec}
                doc (assoc :doc doc))]
    (spool/require-valid! ::specs/pattern-entry
                          entry
                          "Pattern registry entry is invalid")
    entry))

;; --- Input contract introspection and caller guidance ---

(defn- require-registered-input-spec! [spec-name]
  (when-not (s/get-spec spec-name)
    (throw (ex-info "Pattern input spec is not registered" {:input-spec spec-name})))
  spec-name)

(defn- pattern-input-contract
  "Return the shared documentation projection of a registered input spec:
  `:contract`, `:template`, and `:spec-forms` (`skein.api.spec.alpha`)."
  [input-spec]
  (require-registered-input-spec! input-spec)
  (let [bundle (api-spec/projection input-spec)]
    {:contract (get bundle "contract")
     :template (get bundle "template")
     :spec-forms (get bundle "spec-forms")}))

(defn- problem-message
  "Return one caller-guidance line for a structured spec problem.

  A missing required key names the exact JSON key to add and points at its
  entry in the contract; anything else reports the failing location and the
  printed predicate."
  [contract problem]
  (if-let [missing (get problem "missing-key")]
    (let [entry (some #(when (= missing (get % "key")) %)
                      (get contract "required"))]
      (str "missing required key `" missing "`"
           (when-let [key-spec (get entry "spec")]
             (str " (see `" key-spec "` in the contract)"))))
    (str "value at " (pr-str (get problem "in"))
         " failed predicate " (get problem "pred"))))

(defn- validate-pattern-input!
  "Validate weave input against the pattern's registered spec.

  Throws when the spec is unregistered or the input fails it. The ex-data
  carries the shared projection fields (`:contract`, `:template`,
  `:spec-forms`), per-problem caller guidance, and `:explain` as plain
  `s/explain-str` text — raw `s/explain-data` never crosses this boundary."
  [canonical-name input-spec input]
  (require-registered-input-spec! input-spec)
  (when-not (s/valid? input-spec input)
    (let [{:keys [contract template spec-forms]} (pattern-input-contract input-spec)
          messages (mapv #(problem-message contract %)
                         (api-spec/problems input-spec input))]
      (throw (ex-info (str "Pattern input failed spec validation for `" canonical-name "`"
                           (when (seq messages) (str ": " (str/join "; " messages))))
                      {:code "pattern/input-invalid"
                       :pattern canonical-name
                       :input-spec (str input-spec)
                       :contract contract
                       :template template
                       :spec-forms spec-forms
                       :problems messages
                       :explain (api-spec/explain-text input-spec input)})))))

;; --- Weave batch plumbing ---

(defn- require-pattern-batch-vector! [batch]
  (spool/require-valid! ::specs/batch-input
                        batch
                        "Pattern batch is invalid"))

(defn- normalize-weave-strand-attributes
  "Run the `:attributes/normalize` transform hooks over every strand in `batch`.

  Requires `batch` to be a vector; strands without attributes pass through."
  [runtime req-ctx pattern-name input batch]
  (mapv (fn [{:keys [attributes] strand-ref :ref :as strand}]
          (if (nil? attributes)
            strand
            (assoc strand :attributes
                   (run-transform-hooks runtime
                                        :attributes/normalize
                                        (merge req-ctx
                                               {:hook/value attributes
                                                :mutation/operation :batch/apply
                                                :batch/ref strand-ref
                                                :strand/patch strand
                                                :pattern/name pattern-name
                                                :pattern/input input})))))
        (require-pattern-batch-vector! batch)))

(defn- weave-payload
  "Project a normalized batch strand vector into a create-only batch payload."
  [strands]
  {:refs {}
   :strands (mapv #(dissoc % :edges) strands)
   :edges (into []
                (mapcat (fn [{:keys [edges] strand-ref :ref}]
                          (map (fn [edge]
                                 (merge {:op :upsert
                                         :from (some-> strand-ref str)
                                         :to (cond-> (:to edge)
                                               (symbol? (:to edge)) str)}
                                        (select-keys edge [:type :attributes])))
                               edges)))
                strands)
   :burn []})

(defn- weave-batch-context
  "Build the `:batch/apply-before-commit` hook context for a weave batch apply."
  [req-ctx pattern-name input payload result]
  (let [context (merge req-ctx
                       {:mutation/operation :batch/apply
                        :batch/source :weave
                        :batch/payload payload
                        :batch/refs (:refs result)
                        :batch/created (:created result)
                        :batch/updated []
                        :batch/burned []
                        :batch/edge-ops (:edges result)
                        :pattern/name pattern-name
                        :pattern/input input})]
    (spool/require-valid! ::specs/batch-hook-context
                          context
                          "Batch hook context is invalid")))
