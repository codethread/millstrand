(ns skein.core.weaver.module-graph
  "Pure module declaration, collection, and dependency-graph mechanics.

  Startup files stage declarations through a dynamic collector. The collector
  records both startup layers, makes `init.local.clj` shadow `init.clj`
  deterministically, and validates the complete winning graph before callers
  perform source loads, registry publication, or resource reconciliation."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [skein.core.format :as format]))

(def ^:private declaration-keys
  #{:ns :file :load :spools :after :required?})

(def ^:private entry-point-keys [:contribute :reconcile])

(def ^:private startup-layer-rank
  {:init 0 :init-local 1 :direct 2})

(def ^:dynamic *module-collector*
  "Dynamically bound staged startup declaration collector, or nil."
  nil)

(def ^:dynamic *startup-file*
  "Dynamically bound startup file datum while one startup file is evaluated."
  nil)

(def ^:dynamic *contribution-collector*
  "Dynamically bound authoring-form contribution collector, or nil."
  nil)

(def ^:dynamic ^:private *contribution-context*
  "Dynamically bound module/source context for contribution collection, or nil."
  nil)

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- normalize-symbol-coll [label values]
  (let [values (or values [])]
    (when-not (and (coll? values) (every? symbol? values))
      (fail! (str "Module " label " must be a collection of symbols")
             {:field label :value values}))
    (vec (distinct values))))

(defn- normalize-keyword-coll [label values]
  (let [values (or values [])]
    (when-not (and (coll? values) (every? keyword? values))
      (fail! (str "Module " label " must be a collection of keywords")
             {:field label :value values}))
    (vec (distinct values))))

(def ^:private removed-entry-point-message
  "Complete refusal message for a declaration that names an entry point."
  (format/reflow
   "|Module options no longer name entry points. Delete the key from the
    |declaration and declare the module's entry points in its namespace's
    |public spool var, as
    |(def spool {:contribute 'contribute :reconcile 'reconcile})."))

(def ^:private removed-entry-point-remedy
  "Actionable clause of that refusal, carried as its `:remedy` datum.

  Refusal data reaches operators through a refresh result's `:remedies`, which
  carries the directive alone rather than the whole message."
  (format/reflow
   "|Delete the key from the declaration and declare the module's entry points
    |in its namespace's public spool var, as
    |(def spool {:contribute 'contribute :reconcile 'reconcile})."))

(defn- require-no-entry-points!
  "Refuse a declaration that names an entry point, pointing at `def spool`.

  Every freshly authored declaration reaches here — startup collection, the
  direct `declare-module!` route, targeted `:declare`, and the public
  `runtime/module!` boundary, which routes opts its narrower `::module-opts`
  grammar rejects straight through this normalizer. So one message and one
  ex-data contract answer an author whatever surface they came through, and
  both name the removed keys and the convention that replaced them rather than
  reporting them as an unknown-key typo (DELTA-Dsp-003.CC1,
  DELTA-Dsp-004.CC1, TEN-003).

  This refusal is permanent, not a migration hint on a timer: it earns its keep
  for as long as the keys stay out of the grammar, because it is the only thing
  that tells an author holding a pre-cutover example where entry points went.
  Retained pre-cutover state is the part that expires: its reader
  `legacy-resolved-entry-points` carries that removal trigger
  (DELTA-Dsp-003.D3, DELTA-Dsp-004.D3a)."
  [key opts]
  (let [removed (filterv #(contains? opts %) entry-point-keys)]
    (when (seq removed)
      (fail! removed-entry-point-message
             {:reason :removed-module-opts-keys
              :module/key key
              :removed removed
              :remedy removed-entry-point-remedy}))))

(defn normalize-declaration
  "Validate and normalize one freshly authored module declaration.

  The key is independent of source identity. Options are closed, name exactly
  one `:ns` or workspace-relative `:file`, and carry normalized `:spools`,
  `:after`, and `:required?` values. `:load :image` (the only accepted `:load`
  value) trusts the already-loaded JVM image: it requires an `:ns` target and
  refresh never source-loads that module, so its entry points resolve from the
  namespace's `spool` var at evaluation.

  Entry-point keys are refused here, so no newly authored declaration reaches
  the coordinator carrying one. Declarations a live coordinator collected
  before the cutover are never re-normalized: they stay readable in retained
  state, where `legacy-resolved-entry-points` alone reads their entry points
  (DELTA-Dsp-004.CC1/D3)."
  [key opts]
  (when-not (keyword? key)
    (fail! "Module key must be a keyword" {:module/key key}))
  (when-not (map? opts)
    (fail! "Module options must be a map" {:module/key key :opts opts}))
  (require-no-entry-points! key opts)
  (when-let [unknown (seq (remove declaration-keys (keys opts)))]
    (fail! "Module options contain unknown keys"
           {:module/key key :unknown (vec (sort-by pr-str unknown))}))
  (when (= (contains? opts :ns) (contains? opts :file))
    (fail! "Module options require exactly one source target (:ns or :file)"
           {:module/key key :opts opts}))
  (when-let [ns-sym (:ns opts)]
    (when-not (and (symbol? ns-sym) (not (str/blank? (str ns-sym))))
      (fail! "Module :ns must be a non-blank symbol"
             {:module/key key :ns ns-sym})))
  (when-let [file (:file opts)]
    (when-not (and (string? file)
                   (not (str/blank? file))
                   (not (.isAbsolute (java.io.File. ^String file))))
      (fail! "Module :file must be a non-blank workspace-relative path"
             {:module/key key :file file})))
  (when (contains? opts :load)
    (when-not (= :image (:load opts))
      (fail! "Module :load accepts only :image"
             {:module/key key :load (:load opts) :allowed #{:image}}))
    (when (contains? opts :file)
      (fail! "Module :load :image accepts only an :ns source target"
             {:module/key key :load :image :file (:file opts)
              :allowed [:ns]})))
  (when (and (contains? opts :required?)
             (not (boolean? (:required? opts))))
    (fail! "Module :required? must be boolean"
           {:module/key key :required? (:required? opts)}))
  (cond-> (assoc opts
                 :spools (normalize-symbol-coll ":spools" (:spools opts))
                 :after (normalize-keyword-coll ":after" (:after opts))
                 :required? (boolean (:required? opts)))
    (nil? (:spools opts)) (assoc :spools [])
    (nil? (:after opts)) (assoc :after [])))

(defn collecting-modules?
  "Return true while startup files are staging a complete module graph."
  []
  (some? *module-collector*))

(defn with-startup-file
  "Call `f` with the current startup file/layer available to declarations."
  [startup-file f]
  (binding [*startup-file* startup-file]
    (f)))

(defn stage-module!
  "Stage one declaration in the active startup collector.

  A key may appear once per startup layer. A local declaration shadows a shared
  declaration and both remain visible in collection diagnostics."
  [key opts]
  (when-not *module-collector*
    (fail! "No module declaration collector is active" {:module/key key}))
  (let [declaration (normalize-declaration key opts)
        source (or *startup-file* {:name :direct :layer :direct})
        layer (:layer source)]
    (swap! *module-collector*
           (fn [collector]
             (when (get-in collector [:layers key layer])
               (fail! "Module key is declared more than once in one startup layer"
                      {:module/key key :layer layer :source source}))
             (assoc-in collector [:layers key layer]
                       {:module/key key
                        :module/declaration declaration
                        :source source})))
    {:module/key key :module/declaration declaration :staged? true}))

(defn- winning-declarations [layers]
  (into (sorted-map)
        (map (fn [[module-key declarations]]
               (let [[_ winner] (apply max-key (comp startup-layer-rank first)
                                       declarations)]
                 [module-key (:module/declaration winner)])))
        layers))

(defn- declaration-shadows [layers]
  (into (sorted-map)
        (keep (fn [[module-key declarations]]
                (when (< 1 (count declarations))
                  (let [ordered (->> declarations
                                     (sort-by (comp startup-layer-rank first))
                                     (mapv val))]
                    [module-key {:effective (peek ordered)
                                 :shadowed (pop ordered)}]))))
        layers))

(defn dependency-order
  "Return stable dependency-first module keys for complete `graph`.

  Unknown `:after` keys and cycles fail before any runtime mutation."
  [graph]
  (let [known (set (keys graph))]
    (doseq [[key declaration] graph
            dependency (:after declaration)
            :when (not (contains? known dependency))]
      (fail! "Module dependency names an unknown key"
             {:module/key key :dependency dependency :known (vec (sort known))}))
    (loop [remaining (into {} (map (fn [[key declaration]]
                                     [key (set (:after declaration))])) graph)
           resolved []]
      (if (empty? remaining)
        resolved
        (let [ready (->> remaining
                         (keep (fn [[key dependencies]]
                                 (when (set/subset? dependencies (set resolved)) key)))
                         (sort-by pr-str)
                         vec)]
          (when (empty? ready)
            (fail! "Module dependency graph contains a cycle"
                   {:cycle/keys (vec (sort-by pr-str (keys remaining)))
                    :dependencies remaining}))
          (recur (apply dissoc remaining ready) (into resolved ready)))))))

(defn validate-graph
  "Validate a complete freshly authored graph, returning it with its order.

  Every declaration is re-normalized, so this is a collection-time gate only: a
  graph a live coordinator retains from before the cutover keeps its
  entry-point keys and is ordered with `dependency-order` instead."
  [graph]
  (when-not (map? graph)
    (fail! "Module graph must be a map" {:graph graph}))
  (let [normalized (into (sorted-map)
                         (map (fn [[key declaration]]
                                [key (normalize-declaration key declaration)]))
                         graph)]
    {:graph normalized :order (dependency-order normalized)}))

(defn collect-modules
  "Run `load-startup-files!` under one layered collector and validate the graph.

  The callback returns startup file results. Declaration collection itself does
  not load module sources, publish registry entries, or run reconcilers."
  [load-startup-files!]
  (let [collector (atom {:layers {}})
        files (binding [*module-collector* collector]
                (load-startup-files!))
        layers (into (sorted-map)
                     (map (fn [[key declarations]]
                            [key (into (sorted-map) declarations)]))
                     (:layers @collector))
        graph (winning-declarations layers)
        {:keys [order]} (validate-graph graph)]
    {:graph graph
     :order order
     :layers layers
     :shadows (declaration-shadows layers)
     :files files}))

(defn affected-modules
  "Return selected module keys plus every transitive dependent, in graph order."
  [graph selected]
  (let [selected (set selected)
        dependents (reduce-kv
                    (fn [result key declaration]
                      (reduce (fn [m dependency]
                                (update m dependency (fnil conj #{}) key))
                              result
                              (:after declaration)))
                    {}
                    graph)
        affected (loop [pending (vec selected) found selected]
                   (if-let [key (peek pending)]
                     (let [new (set/difference (get dependents key #{}) found)]
                       (recur (into (pop pending) new) (into found new)))
                     found))]
    (filterv affected (dependency-order graph))))

(defn collecting-contribution?
  "Return true while a module source is collecting authoring-form entries."
  []
  (some? *contribution-collector*))

(defn- current-source-context []
  {:source/file (some-> *file* io/file .getCanonicalPath)
   :source/namespace (ns-name *ns*)})

(defn- require-collection-source! []
  (let [{expected-file :source/file
         expected-ns :source/namespace
         module-key :module/key} *contribution-context*
        {actual-file :source/file
         actual-ns :source/namespace} (current-source-context)]
    (when (or (not= expected-file actual-file)
              (and expected-ns (not= expected-ns actual-ns)))
      (fail! (str "Module " module-key
                  " authoring form evaluated from foreign namespace " actual-ns)
             {:reason :foreign-contribution-namespace
              :module/key module-key
              :namespace actual-ns
              :source/file actual-file
              :module/source {:file expected-file :namespace expected-ns}}))))

(defn collect-entry!
  "Record one authoring-form registry entry in the active module contribution.

  Repeating the same kind/key in one source evaluation replaces the earlier
  value deterministically. `:override? true` records explicit override intent.
  Outside contribution collection the form is passive, so code-only source
  reloads can define Vars without publishing declarations."
  ([kind-id entry-key value]
   (collect-entry! kind-id entry-key value {}))
  ([kind-id entry-key value {:keys [override?] :as opts}]
   (when-not (keyword? kind-id)
     (fail! "Contribution kind id must be a keyword" {:kind kind-id}))
   (when-let [unknown (seq (remove #{:override?} (keys opts)))]
     (fail! "Contribution entry options contain unknown keys"
            {:kind kind-id :key entry-key :unknown (vec unknown)}))
   (when-not (or (nil? override?) (boolean? override?))
     (fail! "Contribution :override? must be boolean"
            {:kind kind-id :key entry-key :override? override?}))
   (when *contribution-collector*
     (require-collection-source!)
     (swap! *contribution-collector*
            (fn [contribution]
              (cond-> (assoc-in contribution [kind-id :entries entry-key] value)
                override? (update-in [kind-id :overrides] (fnil conj #{}) entry-key)
                (false? override?)
                (update-in [kind-id :overrides] (fnil disj #{}) entry-key)))))
   value))

(defn with-contribution-collection
  "Call `f` while collecting entries from exactly one module source target."
  [context f]
  (let [collector (atom {})
        return (binding [*contribution-collector* collector
                         *contribution-context* context]
                 (f))]
    {:return return
     :contribution (update-vals @collector
                                #(update % :overrides (fnil set #{})))}))
