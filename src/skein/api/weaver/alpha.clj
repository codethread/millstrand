(ns skein.api.weaver.alpha
  "Explicit-runtime API for the strand lifecycle, schema init, and the op
  registry.

  This namespace owns the primitives no domain namespace does: strand
  create/read/update (`add!`, `update!`, `supersede!`,
  `archive-attributes!`/`unarchive-attributes!`, `show`,
  `list`/`list-lean`/`list-query`, and `ready`/`ready-lean`), database schema
  `init`, acyclic-relation declaration
  (`declare-acyclic-relation!`/`acyclic-relations`), and the CLI op registry
  (`register-op!`, `replace-op!`, `ops`, `resolve-op`, `op!`). Domain surfaces
  (events, hooks, graph queries, batch, patterns, scheduler, runtime config)
  each own their own alpha namespace.

  The module reads in that order. The mutating writes lead — each shows its own
  transaction/hook/event sequencing at the top level — followed by the acyclic
  relations, attribute archival, the read surface, and the op registry, whose
  `op!` is the dispatch entry point for a root-level `strand <name>` invoke.
  Registration validation and entry construction are plumbing in
  `skein.api.weaver.internal.op-entry`; the built-in `help` op and the
  help-alias projection live in `skein.core.weaver.help`, which both `op!` and
  the JSON socket consume.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument to every function here."
  (:refer-clojure :exclude [list])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [skein.api.cli.alpha :as cli]
            [skein.api.return-shape.alpha :as return-shape]
            [skein.api.runtime.glossary.alpha :as glossary]
            [skein.api.weaver.internal.op-entry :as op-entry]
            [skein.core.db :as db]
            [skein.core.query :as query]
            [skein.core.specs :as specs]
            [skein.core.weaver.access :refer [ds normalize query-registry op-registry
                                              op-store with-spool-classloader]]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.help :as help]
            [skein.core.weaver.lifecycle :refer [event-base request-context
                                                 run-validation-hooks! run-transform-hooks]]))

(declare apply-edges! reject-unknown-update-keys! supersede-context
         require-archive-result! require-lean-result!
         validated-op-entry validate-op-annotations! check-op-glossary-refs!
         check-arg-spec-refs! operation-label)

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

(s/def ::op-name
  (s/and #(or (symbol? %) (keyword? %)) #(nil? (namespace %))))
(s/def ::doc (s/and string? (complement str/blank?)))
(s/def ::arg-spec map?)
(s/def ::returns any?)
(s/def ::stream? boolean?)
(s/def ::deadline-class op-entry/op-deadline-classes)
(s/def ::hook-class op-entry/op-hook-classes)
(s/def ::about (s/and string? (complement str/blank?)))
(s/def ::prime (s/and string? (complement str/blank?)))
(s/def ::annotations map?)
(s/def ::op-metadata-map
  (s/and (s/keys :opt-un [::doc ::arg-spec ::returns ::stream? ::deadline-class
                          ::hook-class ::about ::prime ::annotations])
         #(every? op-entry/op-metadata-keys (keys %))))
(s/def ::op-metadata
  (s/nilable (s/or :legacy-doc ::doc :metadata ::op-metadata-map)))

;; --- schema init ------------------------------------------------------------

(defn init
  "Initialize the runtime database schema."
  [runtime]
  (db/init! (ds runtime))
  {:database "initialized"})

(s/fdef init
  :args (s/cat :runtime ::runtime)
  :ret map?)

;; --- strand create / read / update ------------------------------------------

(defn add!
  "Create a strand, enqueue a creation event, and return the normalized strand.

  The transaction normalizes attributes through the `:attributes/normalize`
  transform hooks, inserts the strand, applies its edges, and runs the
  `:strand/add-before-commit` validation hooks before committing; the
  `:strand/added` event is enqueued only after the commit succeeds."
  ([runtime strand]
   (add! runtime strand (request-context :add)))
  ([runtime strand req-ctx]
   (let [created (jdbc/with-transaction [tx (ds runtime)]
                   (let [edges (:edges strand)
                         strand (cond-> strand
                                  true (dissoc :edges)
                                  (some? (:attributes strand))
                                  (assoc :attributes
                                         (run-transform-hooks
                                          runtime
                                          :attributes/normalize
                                          (merge req-ctx
                                                 {:hook/value (:attributes strand)
                                                  :mutation/operation :strand/add
                                                  :strand/patch strand}))))
                         created (normalize (db/add-strand! tx strand))]
                     (apply-edges! tx (:id created) edges)
                     (run-validation-hooks! runtime
                                            :strand/add-before-commit
                                            (merge req-ctx
                                                   {:mutation/operation :strand/add
                                                    :strand/before nil
                                                    :strand/after created
                                                    :strand/edge-ops (vec edges)}))
                     created))]
     (dispatch/enqueue! runtime (assoc (event-base :strand/added)
                                       :strand/id (:id created)
                                       :strand created))
     created)))

(s/fdef add!
  :args (s/or :default (s/cat :runtime ::runtime :strand ::specs/strand-input)
              :with-ctx (s/cat :runtime ::runtime :strand ::specs/strand-input
                               :req-ctx map?))
  :ret map?)

(defn update!
  "Update a strand and/or add edges atomically, then enqueue an update event.

  Rejects unknown patch fields up front. The transaction reads the current
  strand (failing loudly when absent), normalizes any supplied attributes
  through the `:attributes/normalize` transform hooks, applies edges, writes the
  changed columns, and runs the `:strand/update-before-commit` validation hooks;
  the `:strand/updated` event is enqueued only after the commit succeeds."
  ([runtime id patch]
   (update! runtime id patch (request-context :update)))
  ([runtime id patch req-ctx]
   (reject-unknown-update-keys! patch)
   (let [{:keys [title state edges]} patch
         result (jdbc/with-transaction [tx (ds runtime)]
                  (let [before (or (some-> (db/get-strand tx id) normalize)
                                   (throw (ex-info "Strand not found" {:strand-id id})))
                        patch (if (some? (:attributes patch))
                                (assoc patch :attributes
                                       (run-transform-hooks
                                        runtime
                                        :attributes/normalize
                                        (merge req-ctx
                                               {:hook/value (:attributes patch)
                                                :mutation/operation :strand/update
                                                :strand/id id
                                                :strand/before before
                                                :strand/patch patch})))
                                patch)
                        attributes (:attributes patch)]
                    (apply-edges! tx id edges)
                    (let [after (normalize
                                 (db/update-strand!
                                  tx id
                                  (cond-> {}
                                    (contains? patch :title)
                                    (assoc :title title)
                                    (contains? patch :state)
                                    (assoc :state state)
                                    (contains? patch :attributes)
                                    (assoc :attributes attributes))))]
                      (run-validation-hooks! runtime
                                             :strand/update-before-commit
                                             (merge req-ctx
                                                    {:mutation/operation :strand/update
                                                     :strand/id id
                                                     :strand/patch patch
                                                     :strand/before before
                                                     :strand/after after
                                                     :strand/edge-ops (vec edges)}))
                      {:before before :after after :patch patch})))]
     (dispatch/enqueue! runtime (assoc (event-base :strand/updated)
                                       :strand/id id
                                       :strand/patch (:patch result)
                                       :strand/before (:before result)
                                       :strand/after (:after result)))
     (:after result))))

(s/fdef update!
  :args (s/or :default (s/cat :runtime ::runtime :id ::specs/id :patch map?)
              :with-ctx (s/cat :runtime ::runtime :id ::specs/id :patch map?
                               :req-ctx map?))
  :ret map?)

(defn supersede!
  "Replace one strand with another and enqueue a supersession event.

  The transaction performs the supersession and runs the
  `:strand/supersede-before-commit` validation hooks with the supersession
  context; the `:strand/superseded` event is enqueued only after the commit
  succeeds."
  ([runtime old-id replacement-id]
   (supersede! runtime old-id replacement-id (request-context :supersede)))
  ([runtime old-id replacement-id req-ctx]
   (let [result (jdbc/with-transaction [tx (ds runtime)]
                  (let [result (normalize
                                (db/supersede-strand-in-transaction! tx old-id replacement-id))]
                    (run-validation-hooks!
                     runtime
                     :strand/supersede-before-commit
                     (merge req-ctx
                            {:mutation/operation :strand/supersede}
                            (supersede-context old-id replacement-id result)))
                    result))]
     (dispatch/enqueue! runtime (merge (event-base :strand/superseded)
                                       (supersede-context old-id replacement-id result)))
     result)))

(s/fdef supersede!
  :args (s/or :default (s/cat :runtime ::runtime :old-id ::specs/id
                              :replacement-id ::specs/id)
              :with-ctx (s/cat :runtime ::runtime :old-id ::specs/id
                               :replacement-id ::specs/id :req-ctx map?))
  :ret map?)

;; --- acyclic relations ------------------------------------------------------

(defn declare-acyclic-relation!
  "Declare an edge relation as acyclic for future graph writes."
  [runtime relation]
  (db/declare-acyclic-relation! (ds runtime) relation))

(s/fdef declare-acyclic-relation!
  :args (s/cat :runtime ::runtime :relation some?)
  :ret any?)

(defn acyclic-relations
  "Return declared acyclic edge relation names."
  [runtime]
  (db/list-acyclic-relations (ds runtime)))

(s/fdef acyclic-relations
  :args (s/cat :runtime ::runtime)
  :ret coll?)

;; --- attribute archival -----------------------------------------------------

(defn archive-attributes!
  "Archive all attributes, or an explicit non-empty key set, for one strand.

  Archived keys drop out of hot-tier reads (`list`, `ready`, and query
  execution) but stay visible to full point reads. A later write to an
  archived key makes that key hot again; untouched archived keys remain
  archived. Archiving a registered immutable key is rejected — it would hide
  write-once history.

  The strand id and key set are validated by the storage layer against
  `:skein.core.specs/attribute-key-set`, failing loudly on malformed or
  missing input; the result is checked here against
  `:skein.core.specs/attribute-archive-result`.

  This is a trusted in-process primitive only; it has no socket or CLI
  surface, runs no lifecycle hooks, and enqueues no event."
  ([runtime strand-id]
   (require-archive-result! (db/archive-attributes! (ds runtime) strand-id)))
  ([runtime strand-id keys]
   (require-archive-result! (db/archive-attributes! (ds runtime) strand-id keys))))

(s/fdef archive-attributes!
  :args (s/or :all (s/cat :runtime ::runtime :strand-id ::specs/id)
              :keys (s/cat :runtime ::runtime :strand-id ::specs/id
                           :keys ::specs/attribute-key-set))
  :ret ::specs/attribute-archive-result)

(defn unarchive-attributes!
  "Mark all attributes, or an explicit non-empty key set, hot again for one
  strand.

  Restores hot-tier visibility without changing any value. Untouched archived
  keys remain archived. Unarchiving a registered immutable key is legal — it
  is the recovery path for immutable rows archived before enforcement existed.

  The strand id and key set are validated by the storage layer against
  `:skein.core.specs/attribute-key-set`, failing loudly on malformed or
  missing input; the result is checked here against
  `:skein.core.specs/attribute-archive-result`.

  This is a trusted in-process primitive only; it has no socket or CLI
  surface, runs no lifecycle hooks, and enqueues no event."
  ([runtime strand-id]
   (require-archive-result! (db/unarchive-attributes! (ds runtime) strand-id)))
  ([runtime strand-id keys]
   (require-archive-result! (db/unarchive-attributes! (ds runtime) strand-id keys))))

(s/fdef unarchive-attributes!
  :args (s/or :all (s/cat :runtime ::runtime :strand-id ::specs/id)
              :keys (s/cat :runtime ::runtime :strand-id ::specs/id
                           :keys ::specs/attribute-key-set))
  :ret ::specs/attribute-archive-result)

;; --- read surface -----------------------------------------------------------

(defn show
  "Return one normalized strand by id, or nil when absent."
  [runtime id]
  (normalize (db/get-strand (ds runtime) id)))

(s/fdef show
  :args (s/cat :runtime ::runtime :id ::specs/id)
  :ret (s/nilable map?))

(defn list
  "Return strands visible to `runtime`, optionally filtered by a query definition."
  ([runtime]
   (normalize (db/all-strands (ds runtime))))
  ([runtime query-def params]
   (normalize (db/all-strands (ds runtime) query-def params))))

(s/fdef list
  :args (s/or :all (s/cat :runtime ::runtime)
              :filtered (s/cat :runtime ::runtime :query-def any? :params any?))
  :ret coll?)

(defn list-lean
  "Return strands with oversized attributes replaced by descriptors.

  The optional limit arity is for the CLI/wire read surface; the trusted
  in-process arities remain unbounded by default. Passing `{:clamp? true}` in
  the final arity returns at most the limit without running an overflow count."
  ([runtime lean-byte-floor]
   (require-lean-result! (normalize (db/all-strands-lean (ds runtime) lean-byte-floor))))
  ([runtime lean-byte-floor query-def params]
   (require-lean-result!
    (normalize (db/all-strands-lean (ds runtime) lean-byte-floor query-def params))))
  ([runtime lean-byte-floor query-def params limit]
   (require-lean-result!
    (normalize (db/all-strands-lean (ds runtime) lean-byte-floor query-def params limit))))
  ([runtime lean-byte-floor query-def params limit opts]
   (require-lean-result!
    (normalize
     (db/all-strands-lean (ds runtime) lean-byte-floor query-def params limit opts)))))

(s/fdef list-lean
  :args (s/or :floor (s/cat :runtime ::runtime :lean-byte-floor nat-int?)
              :filtered (s/cat :runtime ::runtime :lean-byte-floor nat-int?
                               :query-def any? :params any?)
              :limited (s/cat :runtime ::runtime :lean-byte-floor nat-int?
                              :query-def any? :params any? :limit nat-int?)
              :clamped (s/cat :runtime ::runtime :lean-byte-floor nat-int?
                              :query-def any? :params any? :limit nat-int?
                              :opts #(= {:clamp? true} %)))
  :ret coll?)

(defn list-query
  "Return strands matching a registered query definition."
  [runtime query-name params]
  (list runtime (query/query-def (query-registry runtime) query-name) params))

(s/fdef list-query
  :args (s/cat :runtime ::runtime :query-name any? :params any?)
  :ret coll?)

(defn ready
  "Return ready strands for `runtime`, optionally filtered by a query definition."
  ([runtime]
   (normalize (db/ready-strands (ds runtime))))
  ([runtime query-def params]
   (normalize (db/ready-strands (ds runtime) query-def params))))

(s/fdef ready
  :args (s/or :all (s/cat :runtime ::runtime)
              :filtered (s/cat :runtime ::runtime :query-def any? :params any?))
  :ret coll?)

(defn ready-lean
  "Return ready strands with oversized attributes replaced by descriptors.

  The optional limit arity is for the CLI/wire read surface; the trusted
  in-process arities remain unbounded by default."
  ([runtime lean-byte-floor]
   (require-lean-result! (normalize (db/ready-strands-lean (ds runtime) lean-byte-floor))))
  ([runtime lean-byte-floor query-def params]
   (require-lean-result!
    (normalize (db/ready-strands-lean (ds runtime) lean-byte-floor query-def params))))
  ([runtime lean-byte-floor query-def params limit]
   (require-lean-result!
    (normalize (db/ready-strands-lean (ds runtime) lean-byte-floor query-def params limit)))))

(s/fdef ready-lean
  :args (s/or :floor (s/cat :runtime ::runtime :lean-byte-floor nat-int?)
              :filtered (s/cat :runtime ::runtime :lean-byte-floor nat-int?
                               :query-def any? :params any?)
              :limited (s/cat :runtime ::runtime :lean-byte-floor nat-int?
                              :query-def any? :params any? :limit nat-int?))
  :ret coll?)

;; --- op registry ------------------------------------------------------------

(defn register-op!
  "Register a trusted weaver-side CLI operation.

  Registered operations are invoked at the CLI root as `strand <name>
  [args...]`. The handler symbol must resolve to a function that accepts one
  context map (see `op!` for the context keys) and returns JSON-compatible data.
  The third positional argument is either a doc string or an op metadata map
  with keys `:doc`, `:arg-spec` (parser spec, structurally validated at
  registration), `:returns` (validated return-shape declaration), `:stream?`
  (default false), `:deadline-class` (`:standard`/`:unbounded`), and
  `:hook-class` (`:read`/`:mutating`); unknown keys fail loudly. Arg-spec ops
  declare both classes on every leaf and may not declare them in this metadata
  map. Raw-envelope ops declare both classes here. Provenance (the registering
  namespace) is recorded from the handler symbol and must never be
  caller-supplied.

  Registering an already-registered name fails loudly, naming both the existing
  entry's provenance and the attempted registrant; use `replace-op!` to override
  deliberately. Registry contents live only for the current weaver lifetime and
  are normally published by owner-complete modules from init.clj or registered
  directly from a live REPL. Module refresh replaces its owner's partition;
  direct registrations remain until explicitly replaced or removed."
  ([runtime op-name fn-sym]
   (register-op! runtime core-registry/repl-owner op-name nil fn-sym))
  ([runtime op-name opts fn-sym]
   (register-op! runtime core-registry/repl-owner op-name opts fn-sym))
  ([runtime owner op-name opts fn-sym]
   (let [entry (validated-op-entry op-name opts fn-sym)]
     (check-op-glossary-refs! runtime entry)
     (when-let [existing (get (op-registry runtime) (:name entry))]
       (throw (ex-info "Operation already registered"
                       {:operation (:name entry)
                        :existing-provenance (:provenance existing)
                        :attempted-provenance (:provenance entry)})))
     (core-registry/put-entry! (op-store runtime) owner (:name entry) entry)
     entry)))

(s/fdef register-op!
  :args (s/or :default (s/cat :runtime ::runtime :op-name ::op-name :fn-sym symbol?)
              :with-opts (s/cat :runtime ::runtime :op-name ::op-name
                                :opts ::op-metadata :fn-sym symbol?)
              :owned (s/cat :runtime ::runtime :owner keyword? :op-name ::op-name
                            :opts ::op-metadata :fn-sym symbol?))
  :ret map?)

(defn replace-op!
  "Replace an already-registered op, failing loudly when the name is absent.

  Same signature as `register-op!`. This is the deliberate override for a name
  that already exists; unlike `register-op!` it requires the name to be present.
  When another owner supplies the name — a module-published op, say — the
  override intent is recorded, which is what lets the direct entry keep
  shadowing the original across `runtime/refresh!`. `unregister-op!` retracts
  the shadow and the shadowed entry becomes effective again."
  ([runtime op-name fn-sym]
   (replace-op! runtime core-registry/repl-owner op-name nil fn-sym))
  ([runtime op-name opts fn-sym]
   (replace-op! runtime core-registry/repl-owner op-name opts fn-sym))
  ([runtime owner op-name opts fn-sym]
   (let [entry (validated-op-entry op-name opts fn-sym)]
     (check-op-glossary-refs! runtime entry)
     (when-not (contains? (op-registry runtime) (:name entry))
       (throw (ex-info "Operation not registered; cannot replace"
                       {:operation (:name entry)
                        :available (sort (keys (op-registry runtime)))})))
     (core-registry/replace-entry! (op-store runtime) owner (:name entry) entry)
     entry)))

(s/fdef replace-op!
  :args (s/or :default (s/cat :runtime ::runtime :op-name ::op-name :fn-sym symbol?)
              :with-opts (s/cat :runtime ::runtime :op-name ::op-name
                                :opts ::op-metadata :fn-sym symbol?)
              :owned (s/cat :runtime ::runtime :owner keyword? :op-name ::op-name
                            :opts ::op-metadata :fn-sym symbol?))
  :ret map?)

;; Every `unregister-*!` across the API namespaces answers with the canonical
;; registry key it retracted; op keys canonicalize to strings.
(s/def ::unregistered string?)

(defn unregister-op!
  "Retract `owner`'s own op registration for `op-name`.

  Removal reaches only into the calling owner's partition, so it is the
  counterpart of `replace-op!` rather than a way to delete another owner's op:
  retracting a shadow restores the shadowed entry as effective, and retracting a
  fresh claim leaves the name unregistered. Unregistering a name this owner
  never registered is an idempotent no-op. Returns `{:unregistered
  <canonical-name>}`."
  ([runtime op-name]
   (unregister-op! runtime core-registry/repl-owner op-name))
  ([runtime owner op-name]
   (let [canonical-name (op-entry/canonical-op-name op-name)]
     (core-registry/remove-entry! (op-store runtime) owner canonical-name)
     {:unregistered canonical-name})))

(s/fdef unregister-op!
  :args (s/or :direct (s/cat :runtime ::runtime :op-name ::op-name)
              :owned (s/cat :runtime ::runtime :owner keyword? :op-name ::op-name))
  :ret (s/keys :req-un [::unregistered]))

(defn ops
  "Return registered CLI operation entries for the current weaver runtime."
  [runtime]
  (mapv val (sort-by key (op-registry runtime))))

(defn ^:no-doc validate-op-entry!
  "Validate one assembled registry `entry`, returning its canonical form.

  This is the publication-side entrance to the same op-entry validator direct
  registration uses (DELTA-Lhc-002.CC2). It validates the closed entry shape,
  provenance, arg-spec structure and leaf classes, stream deadlines, and
  returns alignment; glossary references are generation-order dependent and
  are checked separately by `validate-op-glossary-refs!`."
  [entry]
  (when-not (map? entry)
    (throw (ex-info "Operation registry entry must be a map" {:entry entry})))
  (let [entry-keys (into #{:name :fn :provenance} op-entry/op-metadata-keys)
        unknown (seq (remove entry-keys (keys entry)))
        missing (seq (remove #(contains? entry %) [:name :fn :provenance]))]
    (when unknown
      (throw (ex-info "Operation registry entry contains unknown keys"
                      {:keys (vec unknown) :entry entry})))
    (when missing
      (throw (ex-info "Operation registry entry is missing required keys"
                      {:keys (vec missing) :entry entry})))
    (let [opts (select-keys entry op-entry/op-metadata-keys)
          canonical (validated-op-entry (symbol (:name entry)) opts (:fn entry))]
      (when-not (= (:provenance canonical) (:provenance entry))
        (throw (ex-info "Operation registry entry provenance does not match its handler"
                        {:operation (:name canonical)
                         :provenance (:provenance entry)
                         :expected-provenance (:provenance canonical)})))
      canonical)))

(defn ^:no-doc validate-op-glossary-refs!
  "Require every glossary reference in assembled registry `entry` to resolve."
  [runtime entry]
  (check-op-glossary-refs! runtime entry)
  entry)

(s/fdef ops
  :args (s/cat :runtime ::runtime)
  :ret (s/coll-of map? :kind vector?))

(defn resolve-op
  "Return the registered CLI operation entry for `op-name`, or fail loudly.

  Reads one effective op snapshot for the invocation already beginning, so a
  concurrent registry replacement takes effect only for a later resolve — the
  in-flight lookup and its not-found diagnostic share one immutable view
  (DELTA-OlrDrt-001.CC9/CC10, op symbols resolve at invocation)."
  [runtime op-name]
  (let [canonical-name (op-entry/canonical-op-name op-name)
        registered (op-registry runtime)]
    (or (get registered canonical-name)
        (throw (ex-info "Operation not found"
                        {:operation op-name
                         :canonical-operation canonical-name
                         :available (sort (keys registered))})))))

(s/fdef resolve-op
  :args (s/cat :runtime ::runtime :op-name any?)
  :ret map?)

(defn op-provenance
  "Return owner/provenance diagnostics for `runtime`'s CLI op registry as data.

  Maps each registered op name to `{:effective <winning contender> :shadowed
  [<lower contenders>] :contenders [<all, low-to-high>]}`; each contender names
  its `:owner`, `:layer`, `:value` (the op entry), and `:override?`/`:effective?`
  flags, so a caller sees which owner supplies each op — a built-in under the
  system owner, a workspace op under the direct owner — and which lower-layer
  entries an override shadows. Op entries carry the handler symbol as data, not a
  resolved function value (DELTA-OlrDrt-001.CC9)."
  [runtime]
  (core-registry/explain (op-store runtime)))

(s/fdef op-provenance
  :args (s/cat :runtime ::runtime)
  :ret map?)

(defn op!
  "Invoke a registered CLI operation with raw string argv from a root-level
  `strand <name>` invoke.

  The handler receives a context map with `:op/name`, `:op/argv`, `:op/runtime`,
  `:op/runtime-metadata`, and `:op/payloads` (defaulting to `{}`). The envelope
  arity threads any present `:cwd`, `:worktree-root`, `:git-common-dir`, and
  `:timeout` fields into `:op/cwd`, `:op/worktree-root`, `:op/git-common-dir`,
  and `:op/timeout`, and an envelope `:emit!` fn (supplied by the streaming
  socket transport for `:stream? true` ops) into `:op/emit!`. When the resolved
  op declares an `:arg-spec`, `:op/argv` and the attached payloads are parsed
  through `skein.api.cli.alpha/parse` and the result is supplied as `:op/args`;
  a parse failure throws before the handler runs. A clean trailing `--help`/`-h`
  flag (the final argv token, no other flags, no payloads) is rewritten to the
  op's help projection instead of running the handler, for every op class — the
  op detail, or a verb's sliced node when a verb token precedes the flag; retired
  `<op> help`/`about`/`prime` sugar and malformed `--help` shapes redirect loudly
  (DELTA-Dtf-002.CC3). Subcommand map results receive a
  canonical `:operation` label containing the registered op name and full
  resolved subcommand path. A handler-supplied `:operation`
  equal to the derived label is preserved; any other value, including explicit
  nil, fails loudly with the expected and actual labels. Raw-envelope ops (no
  `:arg-spec`) receive the context unchanged, still carrying the raw
  `:op/payloads` map."
  ([runtime op-name argv]
   (op! runtime op-name argv {}))
  ([runtime op-name argv envelope]
   (let [{fn-sym :fn name :name arg-spec :arg-spec :as entry} (resolve-op runtime op-name)
         argv (vec argv)]
     (if-let [alias (help/help-alias-result runtime entry argv envelope)]
       alias
       (let [payloads (or (:payloads envelope) {})
             ctx (cond-> {:op/name name
                          :op/argv argv
                          :op/runtime runtime
                          :op/runtime-metadata (:metadata runtime)
                          :op/payloads payloads}
                   (contains? envelope :cwd)
                   (assoc :op/cwd (:cwd envelope))
                   (contains? envelope :worktree-root)
                   (assoc :op/worktree-root (:worktree-root envelope))
                   (contains? envelope :git-common-dir)
                   (assoc :op/git-common-dir (:git-common-dir envelope))
                   (contains? envelope :timeout)
                   (assoc :op/timeout (:timeout envelope))
                   (contains? envelope :emit!)
                   (assoc :op/emit! (:emit! envelope))
                   (some? arg-spec)
                   (assoc :op/args (cli/parse arg-spec argv payloads)))
             result (with-spool-classloader runtime #((requiring-resolve fn-sym) ctx))
             parsed-args (:op/args ctx)]
         (if-not (and (map? result) (contains? parsed-args :subcommand))
           result
           (let [expected (operation-label name parsed-args)
                 actual (:operation result)]
             (cond
               (not (contains? result :operation)) (assoc result :operation expected)
               (= expected actual) result
               :else (throw (ex-info "Operation result label disagrees with dispatch"
                                     {:expected expected :actual actual}))))))))))

(s/fdef op!
  :args (s/or :default (s/cat :runtime ::runtime :op-name any? :argv any?)
              :with-envelope (s/cat :runtime ::runtime :op-name any?
                                    :argv any? :envelope map?))
  :ret any?)

;; --- private story helpers --------------------------------------------------

;; -- strand write helpers --

(defn- apply-edges! [tx id edges]
  (doseq [{:keys [to type attributes]} edges]
    (when-not (db/get-strand tx to)
      (throw (ex-info "Edge target strand not found" {:to to :type type})))
    (db/add-edge! tx {:from id :to to :type type :attributes (or attributes {})})))

(def ^:private update-patch-keys #{:title :state :attributes :edges})

(defn- reject-unknown-update-keys! [patch]
  (when-let [unknown (seq (remove update-patch-keys (keys patch)))]
    (throw (ex-info "Unknown strand update fields" {:fields (vec unknown)}))))

(defn- supersede-context [old-id replacement-id result]
  {:strand/id old-id
   :strand/old-id old-id
   :strand/replacement-id replacement-id
   :strand/before (get-in result [:old :before])
   :strand/after (get-in result [:old :after])
   :supersession/supersedes-edge (:supersedes-edge result)
   :supersession/rewired-dependencies (:rewired-dependencies result)})

;; -- result-shape validators --

(defn- require-archive-result! [result]
  (when-not (s/valid? ::specs/attribute-archive-result result)
    (throw (ex-info "Attribute archive result is invalid"
                    {:result result
                     :explain (s/explain-str ::specs/attribute-archive-result result)})))
  result)

(defn- require-omitted-attribute-descriptor! [descriptor]
  (when-not (s/valid? ::specs/omitted-attribute-descriptor descriptor)
    (throw (ex-info "Omitted attribute descriptor is invalid"
                    {:descriptor descriptor
                     :explain (s/explain-str ::specs/omitted-attribute-descriptor
                                             descriptor)})))
  descriptor)

(defn- require-lean-result! [result]
  (doseq [strand result
          [_ value] (:attributes strand)
          :when (:skein/omitted value)]
    (require-omitted-attribute-descriptor! value))
  result)

;; -- op registration helpers --

(defn- validate-op-arg-spec!
  "Structurally validate `arg-spec` for `op-name` through the CLI parser
  contract, returning it."
  [op-name arg-spec]
  (try
    (cli/validate! arg-spec)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info "Operation arg-spec is invalid"
                      (assoc (ex-data e) :operation (op-entry/canonical-op-name op-name))
                      e)))))

(defn- validate-op-returns!
  "Validate `returns` through the return-shape contract and its op alignment,
  returning it."
  [op-name arg-spec stream? returns]
  (try
    (return-shape/validate! returns)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info "Operation :returns declaration is invalid"
                      (assoc (ex-data e) :operation (op-entry/canonical-op-name op-name))
                      e))))
  (op-entry/validate-returns-alignment! op-name arg-spec stream? returns))

(defn- validated-op-entry
  "Validate one registration's inputs and assemble its registry entry.

  Normalization, structural metadata validation, and entry assembly are
  `op-entry` plumbing; the reaches into the promised `skein.api.cli.alpha`
  and `skein.api.return-shape.alpha` contracts happen here, because an
  internal namespace never requires an alpha namespace (SPEC-003.C19a)."
  [op-name opts fn-sym]
  (let [opts (op-entry/validate-op-metadata! (op-entry/normalize-op-opts opts))
        fn-sym (op-entry/validate-op-fn-symbol! fn-sym)
        stream? (boolean (:stream? opts))]
    (when (:doc opts)
      (op-entry/validate-op-doc! (:doc opts)))
    (when (some? (:arg-spec opts))
      (validate-op-arg-spec! op-name (:arg-spec opts))
      (check-arg-spec-refs! op-name (:arg-spec opts)))
    (op-entry/validate-op-classes! op-name opts)
    (when (some? (:arg-spec opts))
      (op-entry/validate-stream-leaf-deadlines! op-name stream? (:arg-spec opts)))
    (when (contains? opts :annotations)
      (validate-op-annotations! op-name (:annotations opts)))
    (when (contains? opts :returns)
      (validate-op-returns! op-name (:arg-spec opts) stream? (:returns opts)))
    (op-entry/assemble op-name opts fn-sym)))

(defn- validate-op-annotations!
  "Structurally validate a raw-envelope op's root `:annotations` metadata
  (DELTA-Dtf-002.MI1a) through the CLI annotation contract, returning it.

  The reach into `skein.api.cli.alpha` happens here for the same reason
  `validate-op-arg-spec!` does: an internal namespace never requires an alpha one
  (SPEC-003.C19a). The glossary-ref existence check for its `failure-modes` names
  runs separately in `check-op-glossary-refs!`.

  A SUPPLIED `:annotations` value must be a map (MI1a): the CLI validator treats
  a nil sub-map as \"absent\" (optional on an arg-spec node), so an explicit nil or
  any non-map at the op-metadata root is an author error rejected here."
  [op-name annotations]
  (when-not (map? annotations)
    (throw (ex-info "Operation :annotations metadata is invalid"
                    {:operation (op-entry/canonical-op-name op-name)
                     :reason :invalid-annotations
                     :annotations annotations})))
  (try
    (cli/validate-annotations! (op-entry/canonical-op-name op-name) annotations)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info "Operation :annotations metadata is invalid"
                      (assoc (ex-data e) :operation (op-entry/canonical-op-name op-name))
                      e)))))

(defn- arg-spec-failure-modes
  "Return every `failure-modes` outcome name referenced across `arg-spec`'s
  annotation sub-maps, walking the fractal node tree to every depth
  (DELTA-Lhc-001.CC5)."
  [arg-spec]
  (when (map? arg-spec)
    (into (vec (get-in arg-spec [:annotations :failure-modes]))
          (mapcat (fn [[_ nested]] (arg-spec-failure-modes nested)))
          (:subcommands arg-spec))))

(defn- entry-failure-modes
  "Return every `failure-modes` outcome name `entry` references.

  Gathers from the arg-spec's annotation sub-maps (Task 2) and, for a
  raw-envelope op that declares no arg-spec, from the entry's root `:annotations`
  metadata (MI1a) — the two annotation surfaces are mutually exclusive by
  construction (`validate-op-metadata!`), so this simply unions them."
  [entry]
  (into (vec (get-in entry [:annotations :failure-modes]))
        (arg-spec-failure-modes (:arg-spec entry))))

(defn- check-op-glossary-refs!
  "Fail loudly unless every `failure-modes` name `entry` references — across its
  arg-spec annotation sub-maps and its raw-envelope root `:annotations` metadata —
  points at an already-registered glossary outcome.

  The unconditional glossary-ref existence check (DELTA-Dtf-003.CC2), run at
  registration because that is where the runtime glossary is in hand. It enforces
  the load-order contract: an op's outcomes must be registered — from the owning
  spool's module reconcile or trusted config — before the op that references
  them."
  [runtime entry]
  (doseq [outcome-name (entry-failure-modes entry)]
    (when-not (glossary/outcome-registered? runtime outcome-name)
      (throw (ex-info "Operation failure-mode references an unregistered glossary outcome"
                      {:operation (:name entry)
                       :failure-mode outcome-name
                       :available-outcomes (mapv :name
                                                 (glossary/glossary-outcomes runtime))})))))

(defn- arg-spec-spec-refs
  "Return every declared arg `:spec` name across `arg-spec`'s flags and
  positionals, walking the fractal node tree to every depth
  (DELTA-Lhc-001.CC5)."
  [arg-spec]
  (when (map? arg-spec)
    (-> []
        (into (keep :spec) (vals (:flags arg-spec)))
        (into (keep :spec) (:positionals arg-spec))
        (into (mapcat (fn [[_ nested]] (arg-spec-spec-refs nested)))
              (:subcommands arg-spec)))))

(defn- check-arg-spec-refs!
  "Fail loudly unless every declared arg `:spec` in `arg-spec` names a
  registered clojure.spec.

  Runs at registration and publication validation, mirroring the glossary-ref
  discipline (SPEC-003.C67): a spool's specs load with its namespaces before
  its ops register, so an unresolved name here is an authoring error, not a
  load-order race. The ambient clojure.spec registry is process-global and
  monotonic, so no runtime is needed."
  [op-name arg-spec]
  (doseq [spec-name (arg-spec-spec-refs arg-spec)]
    (when-not (s/get-spec spec-name)
      (throw (ex-info "Declared arg :spec does not name a registered spec"
                      {:operation (op-entry/canonical-op-name op-name)
                       :spec spec-name})))))

;; -- op dispatch helpers --

(defn- operation-label
  "Return the canonical label for a parsed subcommand invocation.

  The registered op name and the full `:subcommand` path vector joined with
  spaces (DELTA-Lhc-002.CC5)."
  [op-name parsed-args]
  (str/join " " (into [op-name] (:subcommand parsed-args))))
