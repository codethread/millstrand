(ns millstrand.core.weaver.pool-basis
  "Build the immutable shared basis for one JVM-pool host.

  This namespace is intentionally effect-free beyond tools.deps basis
  resolution. It validates a frozen manifest, resolves each member using that
  member's own config and source roots, and constructs one shared loader. It
  never opens runtime state, storage, metadata, sockets, or endpoints."
  (:require [clojure.spec.alpha :as s]
            [millstrand.core.weaver.basis :as basis]))

(defn- invalid-manifest!
  [message manifest spec-key]
  (throw
   (ex-info message
            {:value manifest
             :explain (s/explain-data spec-key manifest)})))

(defn- require-manifest!
  [manifest spec-key message]
  (when-not (s/valid? spec-key manifest)
    (invalid-manifest! message manifest spec-key))
  manifest)

(defn- require-distinct-members!
  [manifest members path-key message]
  (let [paths (mapv path-key members)]
    (when-not (= (count paths) (count (distinct paths)))
      (throw (ex-info message {:manifest manifest :paths paths}))))
  manifest)

(defn validate-launch-manifest
  "Validate and return a closed serving launch manifest.

  Paths must already be absolute canonical paths. Members retain the frozen
  order supplied by Mill. This function does not inspect or open any path."
  [manifest]
  (-> manifest
      (require-manifest! :millstrand.jvm-pool/launch-manifest
                         "pool launch manifest violates its closed contract")
      (require-distinct-members! (:members manifest) :config-dir
                                 "pool launch manifest contains duplicate members")))

(defn validate-probe-manifest
  "Validate and return a closed private replacement-probe manifest.

  A probe manifest has private runtime paths and original source authorities;
  accepting it does not copy, read, or mutate either path."
  [manifest]
  (-> manifest
      (require-manifest! :millstrand.jvm-pool/probe-manifest
                         "pool probe manifest violates its closed contract")
      (require-distinct-members! (:members manifest) :original-config-dir
                                 "pool probe manifest contains duplicate members")))

(defn validate-probe-result
  "Validate and return a closed private replacement-probe result."
  [result]
  (require-manifest! result :millstrand.jvm-pool/probe-result
                     "pool probe result violates its closed contract"))

(defn- runtime-coordinate-for
  [manifest runtime-coordinate]
  (or runtime-coordinate
      {:local/root (:millstrand-source manifest)}))

(defn- member-basis
  [member runtime-coordinate]
  (basis/create-generation-basis
   (:config-dir member)
   runtime-coordinate
   {:dependency-source-workspace (:source-cwd member)}))

(defn- probe-member-basis
  [member runtime-coordinate]
  (basis/create-generation-basis
   (:probe-config-dir member)
   runtime-coordinate
   {:dependency-source-workspace (:original-source-cwd member)}))

(defn- distinct-roots
  [member-bases]
  (reduce (fn [{:keys [seen roots]} member-basis]
            (reduce (fn [{:keys [seen roots] :as result} root]
                      (if (contains? seen root)
                        result
                        {:seen (conj seen root)
                         :roots (conj roots root)}))
                    {:seen seen :roots roots}
                    (get-in member-basis [:basis :classpath-roots])))
          {:seen #{} :roots []}
          member-bases))

(defn- pool-fingerprint-value
  [manifest members]
  {:format :millstrand.jvm-pool-basis/v1
   :jvm-pool (:jvm-pool manifest)
   :members (mapv (fn [{:keys [config-dir generation-basis]}]
                    {:config-dir config-dir
                     :basis-fingerprint (:fingerprint generation-basis)
                     :classpath-roots
                     (get-in generation-basis [:basis :classpath-roots])})
                  members)})

(defn- pool-basis-result
  [manifest members]
  (let [classpath-roots (:roots (distinct-roots
                                 (mapv :generation-basis members)))]
    {:pool/name (:jvm-pool manifest)
     :host/id (or (:host-id manifest) (:candidate-host-id manifest))
     :host/generation-id (or (:host-generation-id manifest)
                             (:candidate-host-generation-id manifest))
     :membership/revision (or (:membership-revision manifest)
                              (:probe-id manifest))
     :members members
     :classpath-roots classpath-roots
     :fingerprint (basis/basis-fingerprint
                   (pool-fingerprint-value manifest members))
     :classloader (basis/create-classloader classpath-roots)}))

(defn- require-pool-basis!
  [pool-basis]
  (when-not (s/valid? :millstrand.core.specs/pool-basis pool-basis)
    (throw (ex-info "constructed pool basis violates its closed contract"
                    {:value pool-basis
                     :explain (s/explain-data
                               :millstrand.core.specs/pool-basis pool-basis)})))
  pool-basis)

(defn create-pool-basis
  "Resolve a serving manifest into one shared pool basis.

  Every member is resolved independently using its own config directory and
  source cwd. The resulting classpath is member-ordered, exact-root
  deduplicated, and loaded by one classloader. Member bases remain available
  in `:members` for refresh and diagnostics. An optional runtime coordinate
  overrides the Millstrand source-derived coordinate for tests and launchers."
  ([manifest]
   (create-pool-basis manifest nil))
  ([manifest runtime-coordinate]
   (let [manifest (validate-launch-manifest manifest)
         runtime-coordinate (runtime-coordinate-for manifest runtime-coordinate)
         members (mapv (fn [member]
                         {:config-dir (:config-dir member)
                          :generation-basis
                          (member-basis member runtime-coordinate)})
                       (:members manifest))]
     (require-pool-basis! (pool-basis-result manifest members)))))

(defn create-probe-pool-basis
  "Resolve a private probe manifest into one shared candidate pool basis.

  Each copied config is read from its private probe path, while relative
  dependency and source roots are rebased against that member's original
  source cwd. The returned member keys retain original config identities for
  result matching; no probe runtime or serving artifact is opened."
  ([manifest]
   (create-probe-pool-basis manifest nil))
  ([manifest runtime-coordinate]
   (let [manifest (validate-probe-manifest manifest)
         runtime-coordinate (runtime-coordinate-for manifest runtime-coordinate)
         members (mapv (fn [member]
                         {:config-dir (:original-config-dir member)
                          :generation-basis
                          (probe-member-basis member runtime-coordinate)})
                       (:members manifest))]
     (require-pool-basis! (pool-basis-result manifest members)))))
