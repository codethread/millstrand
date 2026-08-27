(ns millstrand.api.weaver.internal.op-entry
  "Registration-time validation and entry construction for weaver CLI ops.

  Plumbing behind `millstrand.api.weaver.alpha`'s `register-op!`/`replace-op!`: it
  normalizes the metadata argument, validates the structural fields, checks
  returns/arg-spec routing alignment, and assembles the registry entry with
  derived provenance and explicit leaf classes. These are plain public defns on an
  internal tier (SPEC-005.C5b); the alpha module composes them into its
  registration story and owns the reaches into the `millstrand.api.cli.alpha` and
  `millstrand.api.return-shape.alpha` contracts (an internal namespace never
  requires an alpha namespace, SPEC-003.C19a), so everything here is
  tier-free: only clojure and `millstrand.core.*`."
  (:require [clojure.string :as str]
            [millstrand.core.query :as query]
            [millstrand.core.weaver.access :as access]))

(defn canonical-op-name
  "Return the registry key string for `op-name` (a simple symbol or keyword)."
  [op-name]
  (query/canonical-query-name op-name))

(defn validate-op-fn-symbol!
  "Require `fn-sym` to be a fully qualified handler symbol, returning it."
  [fn-sym]
  (access/validate-fn-symbol! "Operation" fn-sym))

(defn validate-op-doc!
  "Require `doc` to be a non-blank string, returning it."
  [doc]
  (when-not (and (string? doc) (not (str/blank? doc)))
    (throw (ex-info "Operation doc must be a non-blank string" {:doc doc})))
  doc)

(def op-metadata-keys
  "Metadata keys a caller may supply to register-op!/replace-op!.

  `:about`/`:prime` are optional non-blank prose strings the `about`/`prime`
  meta-verbs project (DELTA-Dtf-002.CC4). Classes remain accepted here only so
  registration can report the migration error that classes belong on leaves."
  #{:doc :arg-spec :returns :stream? :deadline-class :hook-class
    :about :prime})

(def op-deadline-classes
  "Accepted :deadline-class values."
  #{:standard :unbounded})

(def op-hook-classes
  "Accepted :hook-class values."
  #{:read :mutating})

(defn normalize-op-opts
  "Coerce a register-op! metadata argument into an options map.

  A map is the full metadata map; nil is the no-metadata case used by the
  arity that intentionally demonstrates the missing-arg-spec failure."
  [opts]
  (cond
    (nil? opts) {}
    (map? opts) opts
    :else (throw (ex-info "Operation metadata must be an options map"
                          {:opts opts}))))

(defn validate-op-metadata!
  "Validate a normalized op metadata map, returning it.

  Rejects caller-supplied `:provenance` (registry-recorded from the handler
  namespace), unknown keys, and malformed `:stream?`/`:deadline-class`/
  `:hook-class` values."
  [opts]
  ;; Provenance is registry-recorded from the handler namespace; a caller must
  ;; never assert it. Reject it explicitly so the error is unambiguous even
  ;; though it would also trip the unknown-key check below.
  (when (contains? opts :provenance)
    (throw (ex-info
            "Operation :provenance is registry-recorded and cannot be supplied by the caller"
            {:provenance (:provenance opts)})))
  (when-let [unknown (seq (remove op-metadata-keys (keys opts)))]
    (throw (ex-info "Operation metadata contains unknown keys" {:keys (vec unknown)})))
  (when (and (contains? opts :stream?) (not (boolean? (:stream? opts))))
    (throw (ex-info "Operation :stream? must be a boolean" {:stream? (:stream? opts)})))
  (when (and (contains? opts :deadline-class)
             (not (op-deadline-classes (:deadline-class opts))))
    (throw (ex-info "Operation :deadline-class must be :standard or :unbounded"
                    {:deadline-class (:deadline-class opts)})))
  (when (and (contains? opts :hook-class)
             (not (op-hook-classes (:hook-class opts))))
    (throw (ex-info "Operation :hook-class must be :read or :mutating"
                    {:hook-class (:hook-class opts)})))
  (doseq [key [:about :prime]
          :when (contains? opts key)
          :let [value (get opts key)]]
    (when-not (and (string? value) (not (str/blank? value)))
      (throw (ex-info (str "Operation " key " must be a non-blank prose string")
                      {key value}))))
  opts)

(defn validate-op-classes!
  "Require one canonical class source for `op-name`, returning `opts`.

  Arg-spec operations declare both classes on every leaf and never in
  registration metadata. Node-level failures carry the canonical routing
  context (DELTA-Lhc-001.CC2/CC3, DELTA-Lhc-002.CC1)."
  [op-name opts]
  (let [op (canonical-op-name op-name)
        context (fn [path extra]
                  (merge {:operation op :op op :path path
                          :token nil :available []}
                         extra))]
    (let [arg-spec (:arg-spec opts)]
      (when-let [classes (seq (filter #(contains? opts %)
                                      [:hook-class :deadline-class]))]
        (throw (ex-info
                "Arg-spec operation classes belong on leaves, not registration metadata"
                (context [] {:fields (vec classes)}))))
      (letfn [(walk! [node path]
                (if-let [subcommands (:subcommands node)]
                  (doseq [[subcommand child] subcommands]
                    (walk! child (conj path subcommand)))
                  (doseq [key [:hook-class :deadline-class]]
                    (when-not (contains? node key)
                      (throw (ex-info (str "Operation leaf requires " key)
                                      (context path {:field key})))))))]
        (walk! arg-spec [])))
    opts))

(defn invalid-returns!
  "Throw a canonicalized `:returns` validation error for `op-name`."
  [op-name reason message data]
  (throw (ex-info message
                  (merge {:operation (canonical-op-name op-name)
                          :reason reason}
                         data))))

(defn stream-return-case?
  "True when `return-case` is a stream case (a map declaring `:stream`)."
  [return-case]
  (and (map? return-case) (contains? return-case :stream)))

(defn validate-return-case-alignment!
  "Require one return case's stream marker to match the op's `stream?` flag."
  [op-name stream? return-case context]
  (when (not= stream? (stream-return-case? return-case))
    (invalid-returns! op-name
                      :return-stream-misalignment
                      "Operation :returns does not align with :stream?"
                      (assoc context :stream? stream? :returns return-case))))

(defn- align-returns-node!
  "Recursively align one arg-spec node with its return node at `path`."
  [op-name stream? arg-node return-node path]
  (let [arg-subcommands (:subcommands arg-node)
        return-subcommands (when (and (map? return-node)
                                      (contains? return-node :subcommands))
                             (:subcommands return-node))]
    (if arg-subcommands
      (do
        (when-not return-subcommands
          (invalid-returns! op-name
                            :return-routing-misalignment
                            "Subcommand operation :returns must declare :subcommands"
                            {:path path :returns return-node}))
        (let [expected (set (keys arg-subcommands))
              actual (set (keys return-subcommands))]
          (when-not (= expected actual)
            (invalid-returns! op-name
                              :return-subcommand-misalignment
                              "Operation :returns subcommands must exactly match :arg-spec"
                              {:path path
                               :expected-subcommands (vec (sort expected))
                               :actual-subcommands (vec (sort actual))})))
        (doseq [[subcommand arg-child] arg-subcommands]
          (align-returns-node! op-name stream? arg-child
                               (get return-subcommands subcommand)
                               (conj path subcommand))))
      (do
        (when return-subcommands
          (invalid-returns! op-name
                            :return-routing-misalignment
                            "Operation :returns routes :subcommands at an arg-spec leaf"
                            {:path path :returns return-node}))
        (validate-return-case-alignment! op-name stream? return-node {:path path})))))

(defn validate-returns-alignment!
  "Require the `returns` declaration's routing to align with the op, returning it.

  Checks that `:subcommands` routing mirrors the arg-spec's node tree exactly at
  every depth (DELTA-Lhc-001.CC4) and that each leaf case's stream marker aligns
  with `stream?`; misalignments carry the node `:path`. Purely structural: the
  return-shape contract itself is validated in alpha, which owns the reach into
  `millstrand.api.return-shape.alpha`."
  [op-name arg-spec stream? returns]
  (align-returns-node! op-name stream? arg-spec returns [])
  returns)

(defn validate-stream-leaf-deadlines!
  "Require every leaf `:deadline-class` of a stream op to be
  `:unbounded`, returning `arg-spec`.

  Streams stay explicitly unbounded (DELTA-Lhc-001.CC2): a stream-class op's
  leaf may not opt into a standard deadline."
  [op-name stream? arg-spec]
  (when (and stream? (map? arg-spec))
    (letfn [(walk! [node path]
              (if-let [subcommands (:subcommands node)]
                (doseq [[subcommand child] subcommands]
                  (walk! child (conj path subcommand)))
                (when (not= :unbounded (:deadline-class node))
                  (throw (ex-info
                          "Stream operation leaves must declare :deadline-class :unbounded"
                          {:operation (canonical-op-name op-name)
                           :reason :stream-leaf-deadline
                           :path path
                           :deadline-class (:deadline-class node)})))))]
      (walk! arg-spec [])))
  arg-spec)

(defn assemble
  "Assemble the registry entry for already-validated registration inputs.

  Provenance is derived from the handler symbol's namespace. Arg-spec leaf
  classes remain on the arg-spec.
  Pure assembly: the caller has already validated the metadata map,
  the handler symbol, and any declared doc, arg-spec, and returns."
  [op-name opts fn-sym]
  (let [stream? (boolean (:stream? opts))]
    (cond-> {:name (canonical-op-name op-name)
             :fn fn-sym
             :stream? stream?
             :provenance (symbol (namespace fn-sym))}
      (:doc opts) (assoc :doc (:doc opts))
      true (assoc :arg-spec (:arg-spec opts))
      (contains? opts :returns) (assoc :returns (:returns opts))
      (:about opts) (assoc :about (:about opts))
      (:prime opts) (assoc :prime (:prime opts)))))
