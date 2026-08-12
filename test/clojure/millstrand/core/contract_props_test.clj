(ns millstrand.core.contract-props-test
  "Property-based coverage of the boundary specs in millstrand.core.specs, exercised
  through the real db encode/decode and batch-acceptance paths.

  These tests use the specs as the oracle: generators emit the shapes the specs
  describe, and the properties assert the db honours the same contract the spec
  declares (attribute JSON is a round-trip fixed point; batch spec-validity is
  exactly db-acceptance for structurally coherent batches)."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [millstrand.core.db :as db]
            [millstrand.core.specs :as specs]))

(def ^:dynamic *ds*
  "Datasource for the batch-acceptance property, bound by the once fixture."
  nil)

(defn- delete-sqlite-family! [db-file]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (java.io.File. (str db-file suffix)))))

(defn- with-shared-db
  "Provision one disposable SQLite db for every property in this namespace.

  The batch property only appends rows with generated ids, so a single db
  serves all iterations without interference."
  [f]
  (let [file (doto (java.io.File/createTempFile "millstrand-contract-props" ".sqlite") (.delete))
        db-file (.getAbsolutePath file)
        ds (db/datasource db-file)]
    (try
      (db/init! ds)
      (binding [*ds* ds] (f))
      (finally (delete-sqlite-family! db-file)))))

(use-fixtures :once with-shared-db)

;; --- generators tracking millstrand.core.specs shapes -------------------------

(def ^:private key-gen
  ;; ::specs/attributes admits keyword or string keys, but string keys decode
  ;; back as keywords, so keyword keys are the round-trip fixed point.
  (gen/one-of [gen/keyword gen/keyword-ns]))

(def ^:private scalar-gen
  ;; json-compatible scalars, minus keyword *values* (which the spec rejects)
  ;; and doubles (JSON has no NaN/Infinity; bounded longs keep equality exact).
  (gen/one-of [(gen/return nil)
               gen/boolean
               gen/string-ascii
               (gen/large-integer* {:min -1000000 :max 1000000})]))

(def ^:private json-value-gen
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/map key-gen inner {:max-elements 4})]))
   scalar-gen))

(def ^:private attributes-gen
  (gen/map key-gen json-value-gen {:max-elements 5}))

(def ^:private relation-name-gen
  (gen/elements ["depends-on" "parent-of" "supersedes" "annotates" "relates-to"]))

(def ^:private title-gen
  ;; Mostly valid non-blank titles, occasionally the blank string ::title
  ;; rejects, so batch spec-validity genuinely varies across iterations.
  (gen/frequency [[9 (gen/such-that (complement str/blank?) gen/string-ascii 100)]
                  [1 (gen/return "")]]))

(def ^:private strand-attributes-gen
  ;; Valid json attrs, occasionally a keyword-valued map that no JSON object
  ;; encodes — the second spec-invalidity source alongside a blank title.
  (gen/frequency [[8 attributes-gen]
                  [1 (gen/return {:bad :keyword-value})]]))

(defn- batch-strand-gen [idx]
  (gen/let [title title-gen
            attributes (gen/frequency [[1 (gen/return nil)]
                                       [3 strand-attributes-gen]])
            edges (if (pos? idx)
                    (gen/vector
                     (gen/let [target (gen/choose 0 (dec idx))
                               edge-type relation-name-gen]
                       {:type edge-type :to (symbol (str "s" target))})
                     0 3)
                    (gen/return []))]
    (cond-> {:ref (symbol (str "s" idx)) :title title :edges edges}
      (some? attributes) (assoc :attributes attributes))))

(def ^:private coherent-batch-gen
  ;; A "coherent" batch: unique refs, edges only pointing at strictly-earlier
  ;; refs (so declared acyclic relations can never cycle), and only batch-local
  ;; symbolic targets (never dangling durable ids). For this class the spec is
  ;; the sole gatekeeper, so spec-validity must equal db-acceptance exactly.
  (gen/let [n (gen/choose 1 4)
            strands (apply gen/tuple (map batch-strand-gen (range n)))]
    (vec strands)))

;; --- properties ----------------------------------------------------------

(defspec attribute-json-round-trip 60
  (prop/for-all [attributes attributes-gen]
                (and (s/valid? ::specs/attributes attributes)
                     (= attributes (db/<-json (db/->json attributes))))))

(defspec batch-spec-validity-matches-db-acceptance 40
  (prop/for-all [batch coherent-batch-gen]
                (let [spec-valid? (s/valid? ::specs/batch-input batch)
                      ;; Only require-valid! rejections (ex-data carries :explain)
                      ;; count as the db refusing the batch; anything else - id
                      ;; collisions, connection failures - rethrows so an
                      ;; unexpected error fails the property loudly instead of
                      ;; shrinking into a false spec/db mismatch.
                      db-accepts? (try
                                    (db/add-strand-batch! *ds* batch)
                                    true
                                    (catch clojure.lang.ExceptionInfo e
                                      (if (contains? (ex-data e) :explain)
                                        false
                                        (throw e))))]
                  (= spec-valid? db-accepts?))))

;; --- exact remove acceptance (PROP-Xer-001) ------------------------------
;; The create-path property above never emits a `:remove` — that op lives on
;; apply-batch!. This focused property proves the settled remove contract at the
;; db boundary: every present exact edge is removable, a valid remove batch is
;; accepted, and the committed graph is exactly the prestate minus the removed
;; rows (PROP-Xer-001.PO1, PO2, PO6).

(def ^:private remove-relation-gen
  ;; Edges only ever run from a lower to a higher index below, so even the
  ;; declared-acyclic relations here stay a DAG; `serves` is excluded so its
  ;; single-target rule never rejects a generated fan-out.
  (gen/elements ["depends-on" "parent-of" "supersedes" "annotates" "relates-to"]))

(def ^:private remove-batch-gen
  ;; A strand count, a unique set of acyclic-safe edges over those strands, and
  ;; a submitted-order subset to remove. from < to keeps every relation-local
  ;; graph acyclic; the set dedupes (from, to, type) identity.
  (gen/let [n (gen/choose 2 5)
            edges (gen/set
                   (gen/let [i (gen/choose 0 (- n 2))
                             k (gen/choose 1 1000)
                             type remove-relation-gen]
                     [i (+ i (mod (dec k) (- (dec n) i)) 1) type])
                   {:max-elements 6})
            mask (gen/vector gen/boolean 6)]
    (let [edge-vec (vec edges)]
      {:n n
       :edges edge-vec
       :remove (mapv first (filter second (map vector edge-vec mask)))})))

(defn- edge-triples
  "Return the (from-id, to-id, type) triples among ids present in id-set."
  [ds id-set]
  (->> (db/execute! ds ["SELECT from_strand_id, to_strand_id, edge_type FROM strand_edges"])
       (keep (fn [{:keys [from_strand_id to_strand_id edge_type]}]
               (when (and (id-set from_strand_id) (id-set to_strand_id))
                 [from_strand_id to_strand_id edge_type])))
       set))

(defspec valid-remove-batches-are-accepted-and-monotone 40
  (prop/for-all [{:keys [n edges remove]} remove-batch-gen]
                (let [ids (mapv #(:id (db/add-strand! *ds* {:title (str "s" %)})) (range n))
                      ref-of #(keyword (str "s" %))
                      id-of (fn [idx] (nth ids idx))
                      id-set (set ids)]
                  (doseq [[i j type] edges]
                    (db/add-edge! *ds* {:from (id-of i) :to (id-of j) :type type :attributes {}}))
                  (let [prestate (edge-triples *ds* id-set)
                        removed-triples (set (map (fn [[i j type]] [(id-of i) (id-of j) type]) remove))
                        result (db/apply-batch!
                                *ds*
                                {:refs (into {} (map (fn [idx] [(ref-of idx) (id-of idx)]) (range n)))
                                 :edges (mapv (fn [[i j type]]
                                                {:op :remove :from (ref-of i) :to (ref-of j) :type type})
                                              remove)})
                        transitions (:edges result)]
                    (and (= (count remove) (count transitions))
                         (every? #(nil? (:after %)) transitions)
                         (every? #(= #{:op :from :to :type :before :after} (set (keys %))) transitions)
                         (= (set/difference prestate removed-triples)
                            (edge-triples *ds* id-set)))))))
