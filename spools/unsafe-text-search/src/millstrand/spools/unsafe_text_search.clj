(ns millstrand.spools.unsafe-text-search
  "UNSAFE: uses millstrand.core.db for substring search over strand titles and
  attribute values, including archived rows the query language cannot see.

  This spool is a deliberate, maintained example of breaking Millstrand's
  namespace-tier rules in the open — the Clojure equivalent of a Rust `unsafe`
  block. It is **not** a blessed path. It exists to show, honestly, what
  reaching past the contract looks like and what you owe the next reader when
  you do it.

  ## Why it is unsafe

  The blessed query language (`millstrand.api.weaver.alpha`) has no text/substring
  operator, and its compiled predicates read only hot attribute rows: archived
  values are structurally invisible to every query. That invisibility is a
  deliberate invariant, not an oversight — archived attributes are memory and
  teaching material, not authority (see `devflow/PHILOSOPHY.md`, \"The work
  record is not the source of truth\"). To offer `LIKE`-style search — and to
  reach archived rows at all — this namespace requires `millstrand.core.db` directly
  and runs SQL against the physical `strands` and `attributes` tables. That
  reaches under the attribute-map contract (TEN-007, storage is the core's
  burden) and couples to a storage shape `millstrand.core.*` is free to change
  without notice (TEN-000@1).

  ## The contract you are opting into

  Because it binds to core internals, this spool may break on any Millstrand upgrade.
  It is maintained in-repo, in lockstep with the storage it reads, precisely so
  that breakage surfaces here and gets fixed here. An external spool that copies
  this pattern earns no such guarantee and owns its own breakage.

  See `spools/unsafe-text-search.md` for the full unsafe declaration and design
  rationale. Every search substring is parameter-bound — user input is never
  spliced into SQL — and the op is read-only: it mutates no strands, edges, or
  state."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :refer [fail!]]
            ;; UNSAFE: physical-table access. A blessed spool builds on
            ;; millstrand.api.*.alpha only; this one reaches past the contract on
            ;; purpose (see the ns docstring).
            [millstrand.core.db :as db]))

(def default-search-limit
  "Default row cap for `search`. Overflow fails loudly rather than truncating,
  so a caller always sees a complete result set or a clear instruction to narrow
  it.

  Search deliberately does not consult batteries' runtime-owned read cap
  (`millstrand.spools.batteries/read-limit`, set via `set-read-limit!`): that cap
  governs the silently-truncating `list`/`ready` reads, and this op's cap is a
  different contract — it fails on overflow instead of truncating. A workspace
  that raises its read limit does not thereby widen `search`; pass `:limit`."
  50)

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(s/def ::substring non-blank-string?)
(s/def ::archived? boolean?)
(s/def :millstrand.spools.unsafe-text-search.search/attr-key non-blank-string?)
(s/def ::limit pos-int?)
(s/def ::search-opts
  (s/keys :req-un [::substring]
          :opt-un [::archived? :millstrand.spools.unsafe-text-search.search/attr-key ::limit]))
(s/def ::id string?)
(s/def ::title string?)
(s/def :millstrand.spools.unsafe-text-search.result/attr-key (s/nilable string?))
(s/def ::snippet string?)
(s/def ::result-row
  (s/keys :req-un [::id ::title :millstrand.spools.unsafe-text-search.result/attr-key ::snippet]))

(defn- require-search-opts! [opts]
  (when-not (s/valid? ::search-opts opts)
    (fail! "unsafe-text-search opts are invalid"
           {:opts opts
            :explain (s/explain-str ::search-opts opts)}))
  opts)

(defn- require-result-rows! [rows]
  (doseq [row rows]
    (when-not (s/valid? ::result-row row)
      (fail! "unsafe-text-search result row is invalid"
             {:row row
              :explain (s/explain-str ::result-row row)})))
  rows)

(defn- runtime-datasource
  "Return the runtime's raw SQLite datasource, failing loudly when absent.

  UNSAFE: the datasource is a `millstrand.core.*`-owned handle with no compatibility
  promise; a blessed spool would never touch it."
  [runtime]
  (or (:datasource runtime)
      (fail! "unsafe-text-search could not resolve a datasource from the runtime"
             {:runtime-keys (vec (keys runtime))})))

(defn- like-escape
  "Escape LIKE metacharacters in `substring` so it matches literally under
  `ESCAPE '\\'`.

  Backslash is escaped first so the escapes added for `%` and `_` are not
  themselves re-escaped."
  [substring]
  (-> substring
      (str/replace "\\" "\\\\")
      (str/replace "%" "\\%")
      (str/replace "_" "\\_")))

(def ^:private title-select
  "Title-branch select: a title hit carries no attribute key, so `attr_key` is
  NULL and the whole title is the snippet."
  "SELECT id, title, NULL AS attr_key, title AS snippet FROM strands WHERE title LIKE ? ESCAPE '\\'")

(defn- attr-select
  "Attribute-branch select. Excludes archived rows unless `archived?`, and scopes
  to one attribute `attr-key?` when supplied. Only the fixed clauses vary; the
  substrings stay bound parameters."
  [archived? attr-key?]
  (str "SELECT s.id AS id, s.title AS title, a.key AS attr_key, a.value AS snippet "
       "FROM attributes a JOIN strands s ON s.id = a.strand_id "
       "WHERE a.value LIKE ? ESCAPE '\\'"
       (when-not archived? " AND a.archived = 0")
       (when attr-key? " AND a.key = ?")))

(defn search
  "Return strand rows whose title or an attribute value contains `substring`.

  `opts` is a map: `:substring` (required, non-blank, matched literally),
  `:archived?` (include archived/cold attribute rows the query language cannot
  see; default false — hot rows only), `:attr-key` (scope the attribute-value
  search to one attribute key, which also skips the title branch), and `:limit`
  (default `default-search-limit`). The runtime validates this map against
  `::search-opts`.

  Each row is `{:id :title :attr-key :snippet}`: `:attr-key` is nil for a title
  hit or the matching attribute key otherwise, and `:snippet` is the matched
  text (the title, or the attribute value as stored JSON). Rows are ordered by
  strand id then attribute key. Every row conforms to `::result-row`.

  Read-only. Fails loudly (TEN-003) on malformed opts or overflow: `search`
  fetches one row past `:limit` and, if the result exceeds it, throws naming
  `--limit` and query-narrowing rather than silently truncating."
  [runtime opts]
  (let [{:keys [substring archived? attr-key limit]
         :or {archived? false limit default-search-limit}}
        (require-search-opts! opts)
        ds (runtime-datasource runtime)
        like (str "%" (like-escape substring) "%")
        attr-sql (attr-select archived? attr-key)
        ;; An --attr-key search is an attribute-value search only; titles are not
        ;; attributes, so the title branch is dropped when a key scopes the query.
        [inner params] (if attr-key
                         [attr-sql [like attr-key]]
                         [(str title-select " UNION ALL " attr-sql) [like like]])
        capped (inc limit)
        sql (str "SELECT id, title, attr_key, snippet FROM (" inner ") ORDER BY id, attr_key LIMIT ?")
        rows (db/execute! ds (into [sql] (conj params capped)))]
    (when (> (count rows) limit)
      (fail! (str "unsafe-text-search matched more than the --limit of " limit
                  " rows; narrow the query (scope with --attr-key or a more specific substring) "
                  "or raise --limit — results are capped, never truncated")
             {:reason :overflow :limit limit}))
    (require-result-rows! (mapv (fn [row]
                                  {:id (:id row)
                                   :title (:title row)
                                   :attr-key (:attr_key row)
                                   :snippet (:snippet row)})
                                rows))))

(def ^:private search-doc
  "UNSAFE substring search over strand titles and attribute values, including archived rows.")

(def ^:private search-arg-spec
  "Declared command surface for the `search` op."
  {:op "search"
   :doc search-doc
   :flags {:archived {:type :boolean
                      :doc "Include archived (cold) attribute rows, which the query language cannot see."}
           :attr-key {:type :string
                      :doc "Scope the attribute-value search to one attribute key (skips the title branch)."}
           :limit {:type :int
                   :doc (format
                         (format-alpha/reflow
                          "|Row cap (default %d). Overflow fails loudly rather
                           |than truncating. Search does not consult batteries'
                           |set-read-limit! — that runtime-owned cap governs
                           |list/ready, not this op.")
                         default-search-limit)}}
   :positionals [{:name :substring :type :string :required? true
                  :doc "Substring to search for, matched literally."}]
   :hook-class :read
   :deadline-class :standard})

(def ^:private search-return
  {:type :collection
   :items {:type :map
           :required {:id :string
                      :title :string
                      :attr-key [:nullable :string]
                      :snippet :string}}})

(millstrand/defop! search
  "UNSAFE substring search over strand titles and attribute values, including
  archived rows."
  {:arg-spec search-arg-spec
   :returns search-return
   :stream? false}
  [ctx]
  (let [{:keys [substring archived attr-key limit]} (:op/args ctx)]
    (search (:op/runtime ctx)
            (cond-> {:substring substring}
              (some? archived) (assoc :archived? archived)
              attr-key (assoc :attr-key attr-key)
              limit (assoc :limit limit)))))
