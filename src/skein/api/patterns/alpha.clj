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
         normalize-weave-strand-attributes weave-payload weave-batch-context)

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
                       :input-spec input-spec}
         message (cond
                   (not (s/valid? :skein.pattern/name pattern-name))
                   "Pattern name is invalid"
                   (not (s/valid? :skein.pattern/doc doc))
                   "Pattern doc must be a non-blank string"
                   (not (s/valid? :skein.pattern/fn fn-sym))
                   "Pattern function must be a fully qualified symbol"
                   (not (s/valid? :skein.pattern/input-spec input-spec))
                   "Pattern input spec must be a keyword or symbol"
                   :else "Pattern registration input is invalid")]
     (spool/require-valid! ::specs/pattern-registration registration message)
     (let [entry (pattern-entry pattern-name doc fn-sym input-spec)]
       (core-registry/put-entry! (pattern-store runtime) owner (:name entry) entry)
       entry))))

(defn- public-pattern-entry [entry]
  (spool/require-valid! ::specs/pattern-entry
                        entry
                        "Pattern registry entry is invalid")
  entry)

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

  Missing patterns fail loudly."
  [runtime pattern-name]
  (let [canonical-name (canonical-pattern-name pattern-name)
        registered (pattern-registry runtime)]
    (or (some-> (get registered canonical-name) public-pattern-entry)
        (throw (ex-info "Pattern not found" {:pattern pattern-name
                                             :canonical-pattern canonical-name
                                             :available (sort (keys registered))})))))

(defn explain
  "Describe a registered weave pattern and its input contract in `runtime`.

  The input contract is the shared `skein.api.spec.alpha` projection:
  `:contract` is the nested node tree, `:template` the copyable JSON skeleton,
  and `:spec-forms` the printed form graph, all resolved against the live spec
  registry with no predicate invoked. Missing patterns or unregistered input
  specs fail loudly."
  [runtime pattern-name]
  ;; :fn and :name are renamed on destructure: locals named `fn` and `name`
  ;; shadow the clojure.core vars.
  (let [{:keys [doc input-spec] fn-sym :fn registered-name :name}
        (resolve-pattern runtime pattern-name)]
    (cond-> (merge {:name registered-name
                    :fn (str fn-sym)
                    :input-spec (str input-spec)}
                   (pattern-input-contract input-spec))
      doc (assoc :doc doc))))

(s/fdef explain
  :args (s/cat :runtime map?
               :pattern-name (s/or :keyword keyword? :symbol symbol? :string string?))
  :ret map?)

(defn weave!
  "Validate pattern input, invoke the pattern, and apply its create-only batch.

  The four-argument arity threads an explicit request-context map for trusted
  callers (the connected-client tier); the three-argument arity derives its own
  weave context."
  ([runtime pattern-name input]
   (weave! runtime pattern-name input (request-context :weave)))
  ([runtime pattern-name input req-ctx]
   (let [{fn-sym :fn input-spec :input-spec} (resolve-pattern runtime pattern-name)
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
                      result))]
       ;; a weave is a create-only batch apply; without this event, event-driven
       ;; spools (agent-run, the subagent executor) never see pattern-created
       ;; strands until an unrelated mutation happens to trigger their next scan
       (dispatch/enqueue! runtime (assoc (event-base :batch/applied)
                                         :batch/id (str (UUID/randomUUID))
                                         :pattern/name canonical-name
                                         :batch/refs (:refs result)
                                         :batch/created (:created result)))
       (select-keys result [:created :refs])))))

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
  (when-not (vector? batch)
    (throw (ex-info "Pattern must return a batch strand vector" {:value batch})))
  batch)

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
