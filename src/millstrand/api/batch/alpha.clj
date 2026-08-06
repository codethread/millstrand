(ns millstrand.api.batch.alpha
  "Explicit-runtime API for applying batch graph mutations.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns transactional batch persistence, attribute
  normalization through transform hooks, the pre-commit validation gate, and the
  batch plus per-strand event fanout. The SQL batch engine lives in
  `millstrand.core.db`; the shared lifecycle and dispatch plumbing in
  `millstrand.core.weaver.*`."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [millstrand.api.spool.alpha :as spool]
            [millstrand.core.db :as db]
            [millstrand.core.specs :as specs]
            [millstrand.core.weaver.access :as access]
            [millstrand.core.weaver.dispatch :as dispatch]
            [millstrand.core.weaver.lifecycle :as lifecycle])
  (:import [java.util UUID]))

(declare require-normalized-payload! require-batch-result!
         normalize-strand-attributes batch-apply-context
         enqueue-batch-applied! enqueue-strand-fanout! strand-patch-for-ref)

(defn apply!
  "Apply one transactional batch graph mutation payload to `runtime`.

  The payload is a map of `:refs` (unqualified non-blank keyword -> existing
  strand id), `:strands`
  (patches keyed by `:ref`), `:edges` (ordered edge ops between refs), and
  `:burn` (refs to burn); `millstrand.core.db/normalize-batch-payload!` is the
  grammar authority and rejects malformed payloads loudly.

  An `:edges` entry is one of two closed ops. `{:op :upsert ...}` carries
  `:from`, `:to`, `:type`, and optional `:attributes`; it inserts a missing
  edge or replaces attributes on the matching `(from, to, type)` edge, and
  either endpoint may be a ref created earlier in the same payload.
  `{:op :remove ...}` carries exactly `:from`, `:to`, and `:type` — no
  `:attributes`, no other keys — and deletes that exact `(from, to, type)`
  edge; both endpoints must be top-level pre-bound `:refs`, never a ref
  created earlier in the payload. A remove whose exact edge is absent — a
  wrong direction or wrong relation type included — fails loudly and rolls the
  whole batch back. Edge ops execute in submitted `:edges` order inside the
  one transaction, so an ordered `:remove` then `:upsert` of one identity is a
  deterministic program.

  Each returned edge outcome is one transition with exactly `:op`, `:from`,
  `:to`, `:type`, `:before`, and `:after`. `:from` and `:to` are the submitted
  local refs and `:type` the submitted relation text; `:before` and `:after`
  are each `nil` or a normalized edge row with `:from_strand_id`,
  `:to_strand_id`, `:edge_type`, and a decoded-map `:attributes` — durable ids
  and the full attribute map, never storage JSON. An upsert carries its
  pre-image (or `nil` when the edge is new) in `:before` and the written row
  in `:after`; a remove carries the removed row in `:before` and `nil` in
  `:after`. There is no `:edge` alias. The result `:edges`, the
  `:batch/apply-before-commit` hook's `:batch/edge-ops`, and the
  `:batch/applied` event's `:batch/edges` are equal ordered transition
  vectors.

  Normalizes strand attributes through the `:attributes/normalize` transform
  hooks, persists the batch atomically, runs the `:batch/apply-before-commit`
  validation gate inside the transaction, then enqueues the batch event
  followed by the per-strand created/updated/burned fanout.

  The published shapes are the `::normalized-payload` grammar (whose `::edge-op`
  is the closed `::upsert-edge`/`::remove-edge` alternative) and the `::result`
  contract (whose `:created`/`:updated`/`:burned` entries are the closed
  `::strand-row`/`::updated-row`/`::burned-row` shapes and whose `:edges` are
  `::edge-transition`s over `::edge-row`s). Every published map boundary is
  closed: an unexpected key at the payload or result top level, on an edge row,
  or on a lifecycle entry fails loudly at the seam.
  `millstrand.core.db/normalize-batch-payload!` stays the grammar authority for
  malformed public input, rejecting it with detailed errors; apply! then
  consults `::normalized-payload` on that authority's output, and `::result` on
  the transactional engine's output before the pre-commit hook, events, and
  return. Those two seam checks only catch impossible drift and never weaken the
  authority's rejections. A caller-supplied `req-ctx` conforms to
  `::millstrand.core.specs/request-context`; the pre-commit hook context conforms to
  `::millstrand.core.specs/batch-hook-context`."
  ([runtime payload]
   (apply! runtime payload (lifecycle/request-context :apply-batch)))
  ([runtime payload req-ctx]
   (let [req-ctx (spool/require-valid! ::specs/request-context
                                       req-ctx
                                       "Request context is invalid")
         submitted-payload payload
         normalized-payload (require-normalized-payload!
                             (normalize-strand-attributes
                              runtime req-ctx (db/normalize-batch-payload! payload)))
         result (jdbc/with-transaction [tx (access/ds runtime)]
                  (let [result (require-batch-result!
                                (access/normalize
                                 (db/apply-batch-in-transaction! tx normalized-payload)))]
                    (lifecycle/run-validation-hooks!
                     runtime
                     :batch/apply-before-commit
                     (batch-apply-context req-ctx submitted-payload result))
                    result))
         batch-id (str (UUID/randomUUID))]
     (enqueue-batch-applied! runtime batch-id result)
     (enqueue-strand-fanout! runtime batch-id normalized-payload result)
     result)))

;; --- seam specs --------------------------------------------------------------

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

;; Batch refs are unqualified non-blank keywords; `:refs` binds each to a
;; durable strand id string.
(s/def ::batch-ref (s/and simple-keyword? #(not (str/blank? (name %)))))
(s/def ::refs (s/map-of ::batch-ref ::specs/id))

;; Strand patch keyed by its local `:ref` (grammar authority owns closedness);
;; a create carries a non-blank `:title`, and `:state`/`:attributes` are
;; optional post-normalization.
(s/def ::ref ::batch-ref)
(s/def ::title ::specs/title)
(s/def ::state ::specs/generic-state)
(s/def ::attributes ::specs/attributes)
(s/def :millstrand.api.batch.result/attributes (s/and map? ::specs/attributes))
(s/def ::strand-patch
  (s/keys :req-un [::ref] :opt-un [::title ::state ::attributes]))
(s/def ::strands (s/coll-of ::strand-patch :kind vector?))

;; The two closed edge ops. `:upsert` allows an optional `:attributes`;
;; `:remove` forbids it and carries exactly `:op`/`:from`/`:to`/`:type`.
(s/def ::op #{:upsert :remove})
(s/def ::from ::batch-ref)
(s/def ::to ::batch-ref)
(s/def ::type ::specs/edge-type)
(s/def ::upsert-edge
  (s/and (s/keys :req-un [::op ::from ::to ::type] :opt-un [::attributes])
         #(= :upsert (:op %))
         #(every? #{:op :from :to :type :attributes} (keys %))))
(s/def ::remove-edge
  (s/and (s/keys :req-un [::op ::from ::to ::type])
         #(= :remove (:op %))
         #(every? #{:op :from :to :type} (keys %))))
(s/def ::edge-op (s/or :upsert ::upsert-edge :remove ::remove-edge))
(s/def :millstrand.api.batch.payload/edges (s/coll-of ::edge-op :kind vector?))

(s/def ::burn (s/coll-of ::batch-ref :kind vector?))

;; The normalized payload: exactly the four sections, all present and defaulted
;; by the grammar authority. apply! consults this closed shape on that
;; authority's output, so an unexpected top-level key fails loudly.
(s/def ::normalized-payload
  (s/and (s/keys :req-un [::refs ::strands :millstrand.api.batch.payload/edges ::burn])
         #(every? #{:refs :strands :edges :burn} (keys %))))

;; A normalized edge row carries exactly durable endpoint ids, the relation
;; text, and a decoded-map `:attributes` — never storage JSON, never extra keys.
(s/def ::from_strand_id ::specs/id)
(s/def ::to_strand_id ::specs/id)
(s/def ::edge_type ::specs/edge-type)
(s/def ::edge-row
  (s/and (s/keys :req-un [::from_strand_id ::to_strand_id ::edge_type
                          :millstrand.api.batch.result/attributes])
         #(every? #{:from_strand_id :to_strand_id :edge_type :attributes} (keys %))))

;; One edge transition: the submitted local refs and relation text plus the
;; pre-/post-images. An upsert always writes an `:after`; a remove clears
;; `:after` and reports the removed row in `:before`.
(s/def :millstrand.api.batch.edge/before (s/nilable ::edge-row))
(s/def :millstrand.api.batch.edge/after (s/nilable ::edge-row))
(s/def ::edge-transition
  (s/and (s/keys :req-un [::op ::from ::to ::type
                          :millstrand.api.batch.edge/before
                          :millstrand.api.batch.edge/after])
         #(every? #{:op :from :to :type :before :after} (keys %))
         #(case (:op %)
            :upsert (some? (:after %))
            :remove (and (some? (:before %)) (nil? (:after %))))))
(s/def :millstrand.api.batch.result/edges
  (s/coll-of ::edge-transition :kind vector?))

;; A persisted strand row as the batch engine returns it and `access/normalize`
;; decodes it: exactly the durable id, core fields, decoded-map `:attributes`,
;; and the SQLite timestamps. Closed — created rows and the before/after images
;; of updated and burned entries all carry this shape. Its `:state` is the full
;; stored lifecycle set (a burned row may be `"replaced"`), not the create-only
;; generic state accepted on input.
(s/def ::timestamp (s/and string? #(not (str/blank? %))))
(s/def :millstrand.api.batch.row/id ::specs/id)
(s/def :millstrand.api.batch.row/state ::specs/state)
(s/def :millstrand.api.batch.row/created_at ::timestamp)
(s/def :millstrand.api.batch.row/updated_at ::timestamp)
(s/def ::strand-row
  (s/and (s/keys :req-un [:millstrand.api.batch.row/id ::title
                          :millstrand.api.batch.row/state
                          :millstrand.api.batch.result/attributes
                          :millstrand.api.batch.row/created_at
                          :millstrand.api.batch.row/updated_at])
         #(every? #{:id :title :state :attributes :created_at :updated_at}
                  (keys %))))

;; One updated entry: its local `:ref`, resolved durable `:id`, and the full
;; pre-/post-mutation strand rows. Both images are always present.
(s/def :millstrand.api.batch.updated/before ::strand-row)
(s/def :millstrand.api.batch.updated/after ::strand-row)
(s/def ::updated-row
  (s/and (s/keys :req-un [::ref :millstrand.api.batch.row/id
                          :millstrand.api.batch.updated/before
                          :millstrand.api.batch.updated/after])
         #(every? #{:ref :id :before :after} (keys %))))

;; One burned entry: its local `:ref`, resolved durable `:id`, and the deleted
;; strand's pre-image. There is no `:after` — the row is gone.
(s/def :millstrand.api.batch.burned/before ::strand-row)
(s/def ::burned-row
  (s/and (s/keys :req-un [::ref :millstrand.api.batch.row/id
                          :millstrand.api.batch.burned/before])
         #(every? #{:ref :id :before} (keys %))))

;; The public result: exactly resolved refs, the per-lifecycle entry vectors,
;; and the ordered edge transitions shared by the result, the pre-commit hook,
;; and the event. Closed — an unexpected top-level key fails loudly.
(s/def ::created (s/coll-of ::strand-row :kind vector?))
(s/def ::updated (s/coll-of ::updated-row :kind vector?))
(s/def ::burned (s/coll-of ::burned-row :kind vector?))
(s/def ::result
  (s/and (s/keys :req-un [::refs ::created ::updated ::burned
                          :millstrand.api.batch.result/edges])
         #(every? #{:refs :created :updated :burned :edges} (keys %))))

(s/fdef apply!
  :args (s/or :default (s/cat :runtime ::runtime :payload map?)
              :with-ctx (s/cat :runtime ::runtime :payload map?
                               :req-ctx ::specs/request-context))
  :ret ::result)

;; --- seam validation ---------------------------------------------------------

(defn- require-normalized-payload!
  "Return `payload` when it conforms to `::normalized-payload`.

  `millstrand.core.db/normalize-batch-payload!` is the grammar authority that has
  already rejected malformed public input with detailed errors; this guard only
  catches impossible drift between that authority's output and the published
  shape, and throws structured ex-info naming the spec and value."
  [payload]
  (when-not (s/valid? ::normalized-payload payload)
    (throw (ex-info "Normalized batch payload violates its published contract"
                    {:spec ::normalized-payload
                     :value payload
                     :explain (s/explain-str ::normalized-payload payload)})))
  payload)

(defn- require-batch-result!
  "Return `result` when it conforms to `::result`.

  Guards the pre-commit hook, event fanout, and return value against impossible
  drift in the transactional engine's output; a violation throws structured
  ex-info naming the spec and value before any hook runs or event is enqueued."
  [result]
  (when-not (s/valid? ::result result)
    (throw (ex-info "Batch result violates its published contract"
                    {:spec ::result
                     :value result
                     :explain (s/explain-str ::result result)})))
  result)

;; --- attribute normalization -------------------------------------------------

(defn- normalize-strand-attributes [runtime req-ctx payload]
  (update payload :strands
          (fn [strands]
            (mapv (fn [{:keys [ref attributes] :as strand}]
                    (if (nil? attributes)
                      strand
                      (assoc strand :attributes
                             (lifecycle/run-transform-hooks
                              runtime
                              :attributes/normalize
                              (merge req-ctx
                                     {:hook/value attributes
                                      :mutation/operation :batch/apply
                                      :batch/ref ref
                                      :strand/patch strand})))))
                  strands))))

;; --- validation-gate context -------------------------------------------------

(defn- batch-apply-context [req-ctx payload result]
  (let [context (merge req-ctx
                       {:mutation/operation :batch/apply
                        :batch/source :apply
                        :batch/payload payload
                        :batch/refs (:refs result)
                        :batch/created (:created result)
                        :batch/updated (:updated result)
                        :batch/burned (:burned result)
                        :batch/edge-ops (:edges result)})]
    (spool/require-valid! ::specs/batch-hook-context
                          context
                          "Batch hook context is invalid")))

;; --- event fanout ------------------------------------------------------------

(defn- enqueue-batch-applied! [runtime batch-id result]
  (dispatch/enqueue! runtime (assoc (lifecycle/event-base :batch/applied)
                                    :batch/id batch-id
                                    :batch/refs (:refs result)
                                    :batch/created (:created result)
                                    :batch/updated (:updated result)
                                    :batch/burned (:burned result)
                                    :batch/edges (:edges result))))

(defn- enqueue-strand-fanout! [runtime batch-id payload result]
  (doseq [created (:created result)]
    (dispatch/enqueue! runtime (assoc (lifecycle/event-base :strand/added)
                                      :batch/id batch-id
                                      :strand/id (:id created)
                                      :strand created)))
  (doseq [{:keys [ref id before after]} (:updated result)]
    (dispatch/enqueue! runtime (assoc (lifecycle/event-base :strand/updated)
                                      :batch/id batch-id
                                      :strand/id id
                                      :strand/patch (strand-patch-for-ref payload ref)
                                      :strand/before before
                                      :strand/after after)))
  (when (seq (:burned result))
    (dispatch/enqueue! runtime (assoc (lifecycle/event-base :strand/burned)
                                      :batch/id batch-id
                                      :strand/requested-ids (mapv :id (:burned result))
                                      :strand/burned-ids (mapv :id (:burned result))
                                      :strand/before (mapv :before (:burned result))))))

;; --- leaf mechanics ----------------------------------------------------------

(defn- strand-patch-for-ref [payload ref]
  (some (fn [strand]
          (when (= ref (:ref strand))
            (dissoc strand :ref)))
        (:strands payload)))
