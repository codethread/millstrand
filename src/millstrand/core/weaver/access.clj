(ns millstrand.core.weaver.access
  "Shared low-level plumbing over a weaver runtime map.

  Datasource and registry accessors, JSON-row normalization, the generation
  classloader boundary, and fully-qualified-symbol
  validation. Internal tier: the API and REPL layers reach through these instead
  of destructuring the runtime map's physical shape (TEN-007)."
  (:require [clojure.string :as str]
            [millstrand.core.db :as db]
            [millstrand.core.weaver.core-registry :as core-registry]
            [millstrand.core.weaver.runtime :as weaver-runtime]))

(defn- normalize-row
  "Decode JSON-backed row fields returned by persistence."
  [row]
  (cond-> row
    (string? (:attributes row)) (update :attributes db/<-json)))

(defn normalize
  "Recursively decode persistence-shaped rows into Clojure data."
  [result]
  (cond
    (map? result) (into {} (map (fn [[k v]] [k (normalize v)])) (normalize-row result))
    (sequential? result) (mapv normalize result)
    :else result))

(defn ds
  "Return the runtime's JDBC datasource."
  [runtime]
  (:datasource runtime))

(defn query-registry
  "Return one immutable effective named-query snapshot."
  [runtime]
  (core-registry/effective (:query-store runtime)))

(defn query-store
  "Return the runtime's named-query owner-partition store."
  [runtime]
  (:query-store runtime))

(defn pattern-registry
  "Return one immutable effective weave-pattern snapshot."
  [runtime]
  (core-registry/effective (:pattern-store runtime)))

(defn pattern-store
  "Return the runtime's weave-pattern owner-partition store."
  [runtime]
  (:pattern-store runtime))

(defn op-registry
  "Return one immutable effective CLI-op snapshot."
  [runtime]
  (core-registry/effective (:op-store runtime)))

(defn op-store
  "Return the runtime's CLI-op owner-partition store."
  [runtime]
  (:op-store runtime))

(defn glossary-registry
  "Return the runtime's reload-cleared glossary-outcome registry atom."
  [runtime]
  (:glossary-registry runtime))

(defn help-transform-slot
  "Return the runtime's reload-cleared default-help-transform slot atom."
  [runtime]
  (:help-transform-slot runtime))

(defn hook-registry
  "Return one immutable effective lifecycle-hook snapshot."
  [runtime]
  (core-registry/effective (:hook-store runtime)))

(defn hook-store
  "Return the runtime's lifecycle-hook owner-partition store."
  [runtime]
  (:hook-store runtime))

(defn bin-registry
  "Return one immutable effective executable-declaration snapshot."
  [runtime]
  (core-registry/effective (:bin-store runtime)))

(defn bin-store
  "Return the executable-declaration owner-partition store."
  [runtime]
  (:bin-store runtime))

(defn event-system
  "Return the runtime's event system."
  [runtime]
  (:event-system runtime))

(defn handler-store
  "Return the runtime event system's event-handler owner-partition store."
  [runtime]
  (:handler-store (event-system runtime)))

(defn with-generation-classloader
  "Run `f` with the runtime bound and its generation classloader installed."
  [runtime f]
  (weaver-runtime/with-runtime-and-generation-classloader runtime f))

(defn config-dir
  "Return the runtime's selected config-dir path."
  [runtime]
  (get-in runtime [:metadata :config-dir]))

(defn expand-user-home
  "Expand a leading `~` or `~/` to the current user's home directory."
  [path]
  (cond
    (= "~" path) (System/getProperty "user.home")
    (str/starts-with? path "~/") (str (System/getProperty "user.home") (subs path 1))
    :else path))

(defn validate-fn-symbol!
  "Require fn-sym to be a fully qualified symbol, returning it or failing loudly."
  [label fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info (str label " function must be a fully qualified symbol") {:fn fn-sym})))
  fn-sym)
