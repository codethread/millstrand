(ns millstrand.api.graph.alpha
  "Explicit-runtime API for the named-query registry, query selection, strand
  hydration, graph traversal, and burn.

  This namespace owns the blessed named-query registry surface and its
  validation, ad hoc and registered query id selection, strand hydration by
  id, relation-scoped traversal, edge adjacency, and burn with its pre-commit
  gate and event fanout — and reads in that order. The query compiler lives
  in `millstrand.core.query`, the SQL engine in `millstrand.core.db`, and the shared
  lifecycle and dispatch plumbing in `millstrand.core.weaver.*`.

  Callers own runtime selection and pass the target weaver runtime as the
  first argument to every function here."
  (:require [clojure.spec.alpha :as s]
            [next.jdbc :as jdbc]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.core.db :as db]
            [millstrand.core.query :as query]
            [millstrand.core.specs :as specs]
            [millstrand.core.weaver.access :as access]
            [millstrand.core.weaver.core-registry :as core-registry]
            [millstrand.core.weaver.dispatch :as dispatch]
            [millstrand.core.weaver.lifecycle :as lifecycle]))

(declare validated-entry where-clause details-entry)

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

;; A strand-id collection argument; ids are non-blank strings.
(s/def ::ids (s/coll-of ::specs/id :kind coll?))

;; --- named-query registry ---------------------------------------------------

;; Registration names are simple symbols or keywords; read-side lookups also
;; accept strings. The definition grammar itself is owned by
;; `millstrand.core.query`; these specs encode only the seam's top-level shape —
;; a bare where vector or a `:where`/`:params` map, registered under its
;; canonical string name.
(s/def ::query-name (s/or :symbol simple-symbol? :keyword simple-keyword?))
(s/def ::query-lookup (s/or :name ::query-name :string string?))
(s/def ::query-def (s/or :where-vector vector? :detailed map?))
(s/def ::query-registry (s/map-of string? ::query-def))

(def readiness-where
  "Canonical where-expression for ready strands."
  [:and [:= :state "active"] [:not [:edge/out "depends-on" [:= :state "active"]]]])

;; Every `unregister-*!` across the API namespaces answers with the canonical
;; registry key it retracted; query names canonicalize to strings.
(s/def ::unregistered string?)

(defn register-query!
  "Register a named query definition and return its canonical API shape.

  Canonicalizes the simple symbol or keyword name and validates that the
  definition compiles before it reaches the registry, so malformed query
  data fails loudly at registration time; `millstrand.core.query` is the
  grammar authority for definitions and compiles the stored definition at
  each use. Re-registering a name this owner already holds replaces that
  entry; a name another owner supplies collides loudly, and `replace-query!`
  is the deliberate override for it."
  ([runtime query-name query-def]
   (register-query! runtime core-registry/repl-owner query-name query-def))
  ([runtime owner query-name query-def]
   (let [[canonical-name definition :as entry] (validated-entry query-name query-def)]
     (core-registry/put-entry! (access/query-store runtime) owner canonical-name definition)
     (into {} [entry]))))

(s/fdef register-query!
  :args (s/or :direct (s/cat :runtime ::runtime :query-name ::query-name
                             :query-def ::query-def)
              :owned (s/cat :runtime ::runtime :owner keyword?
                            :query-name ::query-name :query-def ::query-def))
  :ret ::query-registry)

(defn replace-query!
  "Replace an already-registered query, failing loudly when the name is absent.

  Same signature and return shape as `register-query!`. This is the deliberate
  override for a name that already exists; unlike `register-query!` it requires
  the name to be present. When another owner supplies the name — a
  module-published query, say — the override intent is recorded, which is what
  lets the direct entry keep shadowing the original across `runtime/refresh!`.
  `unregister-query!` retracts the shadow and the shadowed entry becomes
  effective again. Queries are value-registered, so replacing a definition is
  the whole iteration loop: there is no function body to redefine."
  ([runtime query-name query-def]
   (replace-query! runtime core-registry/repl-owner query-name query-def))
  ([runtime owner query-name query-def]
   (let [[canonical-name definition :as entry] (validated-entry query-name query-def)
         registered (access/query-registry runtime)]
     (when-not (contains? registered canonical-name)
       (throw (ex-info "Query not registered; cannot replace"
                       {:query canonical-name
                        :available (sort (keys registered))})))
     (core-registry/replace-entry! (access/query-store runtime) owner canonical-name definition)
     (into {} [entry]))))

(s/fdef replace-query!
  :args (s/or :direct (s/cat :runtime ::runtime :query-name ::query-name
                             :query-def ::query-def)
              :owned (s/cat :runtime ::runtime :owner keyword?
                            :query-name ::query-name :query-def ::query-def))
  :ret ::query-registry)

(defn unregister-query!
  "Retract `owner`'s own registration of `query-name`.

  Removal reaches only into the calling owner's partition, so it is the
  counterpart of `replace-query!` rather than a way to delete another owner's
  query: retracting a shadow restores the shadowed definition as effective, and
  retracting a fresh claim leaves the name unregistered. Unregistering a name
  this owner never registered is an idempotent no-op. Returns `{:unregistered
  <canonical-name>}`."
  ([runtime query-name]
   (unregister-query! runtime core-registry/repl-owner query-name))
  ([runtime owner query-name]
   (let [canonical-name (query/canonical-query-name query-name)]
     (core-registry/remove-entry! (access/query-store runtime) owner canonical-name)
     {:unregistered canonical-name})))

(s/fdef unregister-query!
  :args (s/or :direct (s/cat :runtime ::runtime :query-name ::query-name)
              :owned (s/cat :runtime ::runtime :owner keyword?
                            :query-name ::query-name))
  :ret (s/keys :req-un [::unregistered]))

(defn queries
  "Return registered query definitions keyed by canonical string name."
  [runtime]
  (into (sorted-map) (access/query-registry runtime)))

(s/fdef queries
  :args (s/cat :runtime ::runtime)
  :ret ::query-registry)

(defn resolve-query
  "Return the registered query definition for a simple symbol or keyword name.

  Throws ex-info listing the available names when no definition matches."
  [runtime query-name]
  (query/query-def (access/query-registry runtime) query-name))

(s/fdef resolve-query
  :args (s/cat :runtime ::runtime :query-name ::query-lookup)
  :ret ::query-def)

;; The explain projection publishes unqualified keys for CLI/JSON callers.
(s/def ::name string?)
(s/def ::params (s/coll-of keyword? :kind vector?))
(s/def ::referenced-params (s/coll-of keyword? :kind vector?))
(s/def ::where vector?)
(s/def ::definition ::query-def)
(s/def ::where-form string?)
(s/def ::definition-form string?)
(s/def ::summary string?)
(s/def ::query-explanation
  (s/keys :req-un [::name ::params ::referenced-params ::where ::definition
                   ::where-form ::definition-form ::summary]))

(defn query-explain
  "Describe a registered query definition and how CLI callers invoke it."
  [runtime query-name]
  (let [query-def (resolve-query runtime query-name)
        lookup-name (query/query-lookup-name query-name)
        where (where-clause query-def)]
    (assoc (details-entry [lookup-name query-def])
           :where where
           :definition query-def
           :where-form (pr-str where)
           :definition-form (pr-str query-def)
           :summary (format-alpha/reflow
                     "|Invoke this query with `strand list --query <name>` or
                      |`strand ready --query <name>` and pass runtime values
                      |with repeated `--param key=value` arguments."))))

(s/fdef query-explain
  :args (s/cat :runtime ::runtime :query-name ::query-lookup)
  :ret ::query-explanation)

;; --- query selection --------------------------------------------------------

(defn query-ids
  "Return strand ids matching an ad hoc query definition or registered query name."
  [runtime query-or-name params]
  (let [query-def (if (or (vector? query-or-name) (map? query-or-name))
                    query-or-name
                    (resolve-query runtime query-or-name))]
    (db/query-strand-ids (access/ds runtime) query-def params)))

(s/fdef query-ids
  :args (s/cat :runtime ::runtime
               :query-or-name (s/or :ad-hoc ::query-def :registered ::query-lookup)
               :params (s/nilable map?))
  :ret (s/coll-of ::specs/id))

;; --- query composition + param validation -----------------------------------
;;
;; Blessed helpers a spool uses to build `--query` support without reaching
;; into the unpromised `millstrand.core.query` tier: overlay an extra where-clause
;; onto a resolved definition, coerce and validate CLI string params against
;; the declared set, and read a definition's parameter references and canonical
;; name. `millstrand.core.query` stays the grammar authority — core absorbs the
;; burden so this alpha module owns the promise.

;; CLI/JSON callers supply params string-keyed; declared names are keywords.
(s/def ::string-params (s/nilable (s/map-of string? any?)))
(s/def ::declared-params (s/coll-of keyword? :kind sequential?))
(s/def ::helper-map-def
  (s/and map?
         #(vector? (:where %))
         #(or (not (contains? % :params))
              (s/valid? ::declared-params (:params %)))))
(s/def ::helper-query-def
  (s/or :where-vector vector? :detailed ::helper-map-def))

(def ^:private helper-query-def-contract
  "a vector or map with vector :where and optional sequential keyword :params")

(defn- validate-helper-query-def!
  "Fail loudly when a helper receives a definition outside its documented seam."
  [query-def]
  (when-not (s/valid? ::helper-query-def query-def)
    (throw (ex-info "Invalid query helper definition"
                    {:query-def query-def :contract helper-query-def-contract}))))

(defn- validate-conjoin-params!
  "Fail when keyword-keyed `params` exceed `query-def`'s declared parameters."
  [query-def params]
  (let [declared (vec (:params query-def))
        declared-set (set declared)]
    (when-let [unknown (seq (remove declared-set (keys params)))]
      (throw (ex-info "Unknown query parameters"
                      {:params (vec unknown) :declared declared})))))

(defn conjoin-where
  "Return a query definition that conjoins `extra-where` onto `query-def`.

  Resolves `query-def` to its where-expression — validating `params` against
  any declared `:params` — and returns the canonical
  `[:and <where> <extra-where>]` shape a caller then lists or readies with the
  same `params`. A nil `extra-where` returns `query-def` unchanged so callers
  thread an optional overlay (a state filter, say) without a surrounding
  conditional. `millstrand.core.query` owns the where grammar and resolves
  `[:param name]` references at compile time, not here. The helper accepts a
  bare vector expression or a map with vector `:where` and optional sequential
  keyword `:params`; other definitions fail with ex-info at this seam."
  ([query-def extra-where] (conjoin-where query-def extra-where nil))
  ([query-def extra-where params]
   (let [_ (validate-helper-query-def! query-def)
         params (or params {})]
     (validate-conjoin-params! query-def params)
     (if (nil? extra-where)
       query-def
       [:and (query/query-expr query-def params) extra-where]))))

(s/fdef conjoin-where
  :args (s/or :bare (s/cat :query-def ::helper-query-def :extra-where (s/nilable ::where))
              :with-params (s/cat :query-def ::helper-query-def
                                  :extra-where (s/nilable ::where)
                                  :params (s/nilable map?)))
  :ret ::query-def)

(defn coerce-declared-params
  "Coerce string-keyed CLI `params` to a definition's declared keyword names.

  Restricts `params` to `query-def`'s declared `:params`, returning a map keyed
  by the declared keywords for the names actually supplied. Unknown param names
  fail loudly with the offending names and the full declared set in ex-data,
  mirroring the socket read path's contract (batteries hand-rolled this against
  the JSON dispatch) so a spool's `--query` support rejects exactly the params
  the built-in path does. A definition with no declared `:params` accepts an
  empty map and rejects every name. The helper accepts a bare vector expression
  or a map with vector `:where` and optional sequential keyword `:params`; other
  definitions fail with ex-info at this seam."
  [query-def params]
  (let [_ (validate-helper-query-def! query-def)
        params (or params {})
        declared (vec (:params query-def))
        declared-names (set (map name declared))]
    (when-let [unknown (seq (remove declared-names (keys params)))]
      (throw (ex-info "Unknown query parameters"
                      {:params (vec unknown) :declared declared})))
    (into {} (keep (fn [declared-kw]
                     (when-let [entry (find params (name declared-kw))]
                       [declared-kw (val entry)]))
                   declared))))

(s/fdef coerce-declared-params
  :args (s/cat :query-def ::helper-query-def :params ::string-params)
  :ret (s/map-of keyword? any?))

(defn referenced-params
  "Return ordered distinct `[:param name]` keyword references in `query-def`.

  Reads the definition's where-expression — a map's `:where` or a bare vector —
  and reports each referenced parameter name in first-seen order without
  compiling SQL. This is the composable read a spool uses to describe a query's
  runtime params; `query-explain` is the by-name descriptive projection that
  carries this same list beside the definition's declared `:params`. The helper
  accepts a bare vector expression or a map with vector `:where` and optional
  sequential keyword `:params`; other definitions fail with ex-info at this seam."
  [query-def]
  (validate-helper-query-def! query-def)
  (query/referenced-params (where-clause query-def)))

(s/fdef referenced-params
  :args (s/cat :query-def ::helper-query-def)
  :ret ::referenced-params)

;; --- strand hydration -------------------------------------------------------

(defn strands-by-ids
  "Return normalized strands for ids, preserving first-seen input order."
  [runtime ids]
  (access/normalize (db/strands-by-ids (access/ds runtime) ids)))

(s/fdef strands-by-ids
  :args (s/cat :runtime ::runtime :ids ::ids)
  :ret (s/coll-of map?))

;; --- graph traversal --------------------------------------------------------

(defn ancestor-root-ids
  "Return ancestor root ids reachable from `seed-ids`."
  ([runtime seed-ids]
   (ancestor-root-ids runtime seed-ids {}))
  ([runtime seed-ids opts]
   (db/ancestor-root-ids (access/ds runtime) seed-ids opts)))

(s/fdef ancestor-root-ids
  :args (s/or :default (s/cat :runtime ::runtime :seed-ids ::ids)
              :with-opts (s/cat :runtime ::runtime :seed-ids ::ids :opts map?))
  :ret (s/coll-of ::specs/id))

;; A subgraph result carries normalized strand rows and edge rows.
(s/def ::strands (s/coll-of map?))
(s/def ::edges (s/coll-of map?))

(defn subgraph
  "Return a normalized strand subgraph rooted at `root-ids`."
  ([runtime root-ids]
   (subgraph runtime root-ids {}))
  ([runtime root-ids opts]
   (let [{:keys [strands edges] :as result} (db/subgraph (access/ds runtime) root-ids opts)]
     (assoc result
            :strands (access/normalize strands)
            :edges (access/normalize edges)))))

(s/fdef subgraph
  :args (s/or :default (s/cat :runtime ::runtime :root-ids ::ids)
              :with-opts (s/cat :runtime ::runtime :root-ids ::ids :opts map?))
  :ret (s/keys :req-un [::strands ::edges]))

;; --- edge adjacency ---------------------------------------------------------

(defn incoming-edges
  "Return normalized `edge-type` edges whose target is one of `to-ids`.

  One indexed lookup for a strand's parents/annotators; no graph traversal.
  Adjacency is lenient: an id absent from storage yields no rows rather than
  a missing-id error (unlike subgraph/ancestor-root-ids seeds)."
  [runtime to-ids edge-type]
  (access/normalize (db/incoming-edges (access/ds runtime) to-ids edge-type)))

(s/fdef incoming-edges
  :args (s/cat :runtime ::runtime :to-ids ::ids :edge-type ::specs/edge-type)
  :ret ::edges)

(defn outgoing-edges
  "Return normalized `edge-type` edges whose source is one of `from-ids`.

  One indexed lookup for a strand's children; no graph traversal. Lenient
  adjacency: an absent id yields no rows rather than a missing-id error."
  [runtime from-ids edge-type]
  (access/normalize (db/outgoing-edges (access/ds runtime) from-ids edge-type)))

(s/fdef outgoing-edges
  :args (s/cat :runtime ::runtime :from-ids ::ids :edge-type ::specs/edge-type)
  :ret ::edges)

;; --- burn -------------------------------------------------------------------

;; A burn result names the removed ids and their count.
(s/def ::burned (s/coll-of ::specs/id :kind vector?))
(s/def ::count nat-int?)

(defn burn-by-ids!
  "Delete strands by id and enqueue burn events for removed rows.

  Loads the before-images and deletes inside one transaction, running the
  `:strand/burn-before-commit` validation gate between the two so a rejecting
  hook rolls the whole burn back; then enqueues the `:strand/burned` event
  carrying requested ids, burned ids, and before-images. The `req-ctx` arity
  threads an explicit request-context map (the same shape
  `millstrand.api.batch.alpha/apply!` accepts) into the gate; the two-argument
  form derives its own burn context."
  ([runtime ids]
   (burn-by-ids! runtime ids (lifecycle/request-context :burn)))
  ([runtime ids req-ctx]
   (let [requested-ids (vec ids)
         {:keys [before result]}
         (jdbc/with-transaction [tx (access/ds runtime)]
           (let [before (access/normalize (db/strands-by-ids tx requested-ids))]
             (lifecycle/run-validation-hooks! runtime
                                              :strand/burn-before-commit
                                              (merge req-ctx
                                                     {:mutation/operation :strand/burn
                                                      :strand/requested-ids requested-ids
                                                      :strand/before before}))
             {:before before
              :result (db/burn-by-ids! tx requested-ids)}))]
     (dispatch/enqueue! runtime (assoc (lifecycle/event-base :strand/burned)
                                       :strand/requested-ids requested-ids
                                       :strand/burned-ids (:burned result)
                                       :strand/before before))
     result)))

(s/fdef burn-by-ids!
  :args (s/or :default (s/cat :runtime ::runtime :ids ::ids)
              :with-ctx (s/cat :runtime ::runtime :ids ::ids :req-ctx map?))
  :ret (s/keys :req-un [::burned ::count]))

;; --- query-definition shape helpers -----------------------------------------
;;
;; A registered query definition is either a bare where vector or a map of
;; `:where` and `:params`; `millstrand.core.query` is the grammar authority.

(defn- validated-entry
  "Return a `[canonical-name query-def]` registry entry, validated loudly."
  [query-name query-def]
  [(query/canonical-query-name query-name)
   (query/validate-query-def! query-def)])

(defn- where-clause
  "Return the where expression of a bare-vector or map query definition."
  [query-def]
  (if (map? query-def)
    (:where query-def)
    query-def))

(defn- details-entry
  "Return the `:name`/`:params`/`:referenced-params` projection of an entry."
  [[lookup-name query-def]]
  {:name lookup-name
   :params (if (map? query-def) (vec (:params query-def)) [])
   :referenced-params (query/referenced-params (where-clause query-def))})
