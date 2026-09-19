(ns millstrand.api.notes.alpha
  "Explicit-runtime cross-spool note primitive for strand memory.

  A note is an immutable, born-closed strand (memory, not work) linked to its
  target by an outgoing `notes` annotation edge. `note!` writes that strand and
  edge; `notes` walks the incoming `notes` edges to a target and projects each
  note in `note/at` order. The link is the edge alone — no `note/for` attribute
  — so a target's deletion cascades the edge and the note becomes unreachable
  through the read with no dangling pointer, regardless of the decorating
  attributes a caller layers on its notes.

  Note content is immutable by storage enforcement, not convention: `note/text`
  and `note/at` are declared write-once keys (SPEC-001.P4), so once a note is
  written its content and timestamp cannot be rewritten, deleted, or archived on
  any mutation path. Only the caller's decorating attributes stay mutable.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument, per the blessed-namespace convention. `writer-ref->prompt` renders
  a plain-data `{:target :identity/by-identity :decoration}` ref as a
  note-writing CLI fragment."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.specs :as specs]))

(declare identity-attr note-attr at-instant note-view require-int-round
         require-note-opts! truncate)

(defn note!
  "Append an immutable note strand to `target-id`'s memory and return its id.

  The note is born closed, carries `note/text`, a sub-second `note/at`
  timestamp, optional `identity/by-identity`/`note/round`, and any caller-supplied
  decorating attrs, and links to the target by an outgoing `notes` edge — never a
  `note/for` attribute. `note/text` and `note/at` are storage-enforced write-once
  (SPEC-001.P4): the birth write here is legal, but no later mutation path can
  rewrite, delete, or archive them. `:identity/by-identity` accepts a non-blank
  friendly identity string without registry lookup; absent identity attribution
  leaves the note valid. Fails loudly on blank text, a missing target, old `:by`
  attribution, or a non-integer `:round` (the `note/round` contract is
  single-typed)."
  [runtime target-id text {:identity/keys [by-identity] :keys [round] :as opts}]
  (when (str/blank? text)
    (throw (ex-info "Note text must be non-blank" {})))
  (when-not (weaver/show runtime target-id)
    (throw (ex-info "Note target strand not found" {:id target-id})))
  (require-note-opts! opts)
  (require-int-round round)
  (let [decorating (dissoc opts :identity/by-identity :round)
        note (weaver/add! runtime
                          {:title (truncate text 72)
                           :state "closed"
                           ;; note/at carries sub-second precision the
                           ;; seconds-only created_at column cannot, so it
                           ;; orders a note burst.
                           :attributes (cond-> (merge decorating
                                                      {"note/text" text
                                                       "note/at" (str (runtime/now runtime))})
                                         by-identity
                                         (assoc "identity/by-identity" by-identity)
                                         round (assoc "note/round" round))
                           :edges [{:type "notes" :to target-id}]})]
    {:id (:id note) :target target-id}))

(defn notes
  "Return `target-id`'s notes in `note/at` order, optionally one `:round`.

  Walks the incoming `notes` edges to the target, so it returns notes from every
  writer that used the primitive regardless of their decorating attrs. Projects
  each note as `{:id :note :at}` plus `:by-identity`/`:round` when present.
  `:round` must
  be an integer (fails loudly otherwise); ordering parses `note/at` so mixed
  fractional-precision timestamps still sort chronologically."
  [runtime target-id {:keys [round]}]
  (require-int-round round)
  (let [note-ids (mapv :from_strand_id (graph/incoming-edges runtime [target-id] "notes"))]
    (->> (graph/strands-by-ids runtime note-ids)
         (filter (fn [note] (or (nil? round) (= round (note-attr note "round")))))
         (sort-by (juxt at-instant :created_at :id))
         (mapv note-view))))

(defn writer-ref->prompt
  "Render `ref` as the note-writing CLI instruction fragment.

  This is the single renderer of the write fragment
  `strand note <target> \"<text>\" --by-identity <actor> --attr k=v …` — `<text>`
  stays a placeholder the agent fills in. `ref` must contain a string `:target`,
  an optional non-blank `:identity/by-identity` attribute, and an optional map
  of string `:decoration` entries. The old `:by` field and every other unknown
  field fail loudly. Renders only the write instruction — no read instruction."
  [ref]
  (when-not (map? ref)
    (throw (ex-info "writer-ref shape invalid" {:field :root :value ref})))
  (let [{:keys [target decoration] :identity/keys [by-identity]} ref
        unknown-fields (seq (remove #{:target :identity/by-identity :decoration}
                                    (keys ref)))]
    (when unknown-fields
      (throw (ex-info "writer-ref has unsupported fields"
                      {:field (first unknown-fields) :value ref})))
    (when-not (string? target)
      (throw (ex-info "writer-ref target must be a string" {:field :target :value target})))
    (when-not (or (nil? by-identity) (s/valid? ::by-identity by-identity))
      (throw (ex-info "writer-ref identity/by-identity must be a non-blank string"
                      {:field :identity/by-identity :value by-identity})))
    (when-not (or (nil? decoration)
                  (and (map? decoration)
                       (every? (fn [[k v]] (and (string? k) (string? v))) decoration)))
      (throw (ex-info "writer-ref decoration must be a map of strings"
                      {:field :decoration :value decoration})))
    (str "strand note " target " \"<text>\""
         (when by-identity (str " --by-identity " by-identity))
         ;; sort keeps the rendered flags deterministic across map orderings
         (str/join (for [[k v] (sort decoration)] (str " --attr " k "=" v))))))

;; --- seam specs ---------------------------------------------------------------

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

(s/def ::id ::specs/id)
(s/def ::target ::specs/id)
(s/def ::note string?)
(s/def ::at string?)
(s/def ::by-identity (s/and string? (complement str/blank?)))
(s/def :identity/by-identity (s/and string? (complement str/blank?)))
(s/def ::round integer?)

;; The read projection of one note strand; `:by-identity`/`:round` appear only
;; when the note carries them.
(s/def ::note-view
  (s/keys :req-un [::id ::note ::at] :opt-un [::by-identity ::round]))

;; Opts maps stay open — `:identity/by-identity`/`:round` ride beside caller-
;; owned decorating attributes — so the known keys are constrained by predicate
;; rather than `s/keys`: writers legitimately pass them as nil to mean absent,
;; which present-key `s/keys` validation would reject. `:by` and the unqualified
;; `:by-identity` are deliberately rejected rather than retained as aliases.
(s/def ::note-opts
  (s/nilable
   (s/and map?
          (fn [{:identity/keys [by-identity] :keys [round] :as opts}]
            (and (not (contains? opts :by))
                 (not (contains? opts :by-identity))
                 (or (nil? by-identity)
                     (s/valid? :identity/by-identity by-identity))
                 (or (nil? round) (integer? round)))))))

(s/def ::read-opts
  (s/nilable
   (s/and map?
          (fn [{:keys [round]}]
            (or (nil? round) (integer? round))))))

(s/fdef note!
  :args (s/cat :runtime ::runtime :target-id ::specs/id :text string? :opts ::note-opts)
  :ret (s/keys :req-un [::id ::target]))

(s/fdef notes
  :args (s/cat :runtime ::runtime :target-id ::specs/id :opts ::read-opts)
  :ret (s/coll-of ::note-view :kind vector?))

;; `writer-ref->prompt` is itself the authority for the writer-ref grammar: its
;; docstring documents the shape and its body defines it (SPEC-003.C19a), so no
;; spec mirrors it here.

;; --- note attribute mechanics -------------------------------------------------

(defn- identity-attr
  "Read the `identity/<k>` attribution attribute from a normalized strand."
  [strand k]
  (get (:attributes strand) (keyword "identity" k)))

(defn- note-attr
  "Read the `note/<k>` memory attribute from a normalized note strand."
  [note k]
  (get (:attributes note) (keyword "note" k)))

(defn- at-instant
  "Chronological sort key for a note: its `note/at` parsed as an Instant, else
  epoch. `Instant/toString` varies in fractional precision, so lexicographic
  comparison misorders notes; parsing restores chronological order."
  [note]
  (if-let [at (note-attr note "at")]
    (try (java.time.Instant/parse at)
         (catch Exception _ java.time.Instant/EPOCH))
    java.time.Instant/EPOCH))

(defn- note-view
  "Project a normalized note strand as `{:id :note :at}` plus
  `:by-identity`/`:round` when present."
  [note]
  (let [by-identity (identity-attr note "by-identity")]
    (cond-> {:id (:id note)
             :note (note-attr note "text")
             :at (or (note-attr note "at") (:created_at note))}
      by-identity (assoc :by-identity by-identity)
      (note-attr note "round") (assoc :round (note-attr note "round")))))

;; --- option and round contracts ----------------------------------------------

(defn- require-note-opts!
  "Return valid note opts, rejecting removed or unqualified actor aliases."
  [opts]
  (when-not (or (nil? opts) (map? opts))
    (throw (ex-info "Note opts must be a map" {:opts opts})))
  (when (contains? opts :by)
    (throw (ex-info "Note attribution uses :identity/by-identity; :by is not supported"
                    {:field :by :value (:by opts)})))
  (when (contains? opts :by-identity)
    (throw (ex-info "Note attribution uses :identity/by-identity"
                    {:field :by-identity :value (:by-identity opts)})))
  (when (and (some? (:identity/by-identity opts))
             (not (s/valid? :identity/by-identity
                            (:identity/by-identity opts))))
    (throw (ex-info "Note identity/by-identity must be a non-blank string"
                    {:field :identity/by-identity
                     :value (:identity/by-identity opts)})))
  opts)

(defn- require-int-round
  "Return `round` when it is an integer (or nil); otherwise fail loudly.

  `note/round` is single-typed by contract: every writer stores an integer and
  the read filter compares integers, so a round written through one surface is
  always visible through another."
  [round]
  (when (and (some? round) (not (integer? round)))
    (throw (ex-info "note/round must be an integer" {:round round :type (type round)})))
  round)

;; --- leaf mechanics -----------------------------------------------------------

(defn- truncate
  "Return `s` capped at `n` characters, ellipsizing when it overflows."
  [s n]
  (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))
