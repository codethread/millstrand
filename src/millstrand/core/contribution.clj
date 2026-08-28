(ns millstrand.core.contribution
  "Constructs and validates collected declarations for Millstrand's core kinds."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.query :as query]))

(def ^:private op-option-keys
  #{:arg-spec :returns :stream? :about :prime :override?})

(s/def ::override? boolean?)
(s/def ::op-options
  (s/and map?
         #(every? op-option-keys (keys %))
         #(contains? % :arg-spec)
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))
(s/def ::query-options
  (s/and map?
         #(every? #{:usage :override?} (keys %))
         #(or (not (contains? % :usage))
              (and (string? (:usage %)) (not (str/blank? (:usage %)))))
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))
(s/def ::pattern-options
  (s/and map?
         #(every? #{:spec :override?} (keys %))
         #(contains? % :spec)
         #(or (keyword? (:spec %)) (symbol? (:spec %)))
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))
(s/def ::hook-options
  (s/and map?
         #(every? #{:types :order :metadata :override?} (keys %))
         #(and (set? (:types %)) (seq (:types %)) (every? keyword? (:types %)))
         #(or (not (contains? % :order)) (integer? (:order %)))
         #(or (not (contains? % :metadata)) (map? (:metadata %)))
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))
(s/def ::handler-options
  (s/and map?
         #(every? #{:types :metadata :override?} (keys %))
         #(and (set? (:types %)) (seq (:types %)) (every? keyword? (:types %)))
         #(or (not (contains? % :metadata)) (map? (:metadata %)))
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))

(def ^:private bin-option-keys
  #{:executable :build :override?})

(def ^:private bin-anchors #{:family :root})

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- relative-path? [value]
  (and (non-blank-string? value)
       (not (.isAbsolute (java.io.File. ^String value)))))

(defn- valid-bin-executable? [value]
  (or (non-blank-string? value)
      (and (vector? value)
           (= 2 (count value))
           (contains? bin-anchors (first value))
           (relative-path? (second value)))))

(s/def ::bin-options
  (s/and map?
         #(every? bin-option-keys (keys %))
         #(contains? % :executable)
         #(valid-bin-executable? (:executable %))
         #(or (not (contains? % :build))
              (and (vector? (:build %))
                   (seq (:build %))
                   (every? non-blank-string? (:build %))))
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))

(s/def ::op-entry
  (s/and map?
         #(every? #{:name :fn :provenance :doc :arg-spec :returns :stream?
                    :about :prime}
                  (keys %))
         #(every? (partial contains? %) [:name :fn :provenance :arg-spec])))
(s/def ::query-entry
  (s/and #(try
            (query/validate-query-def! %)
            true
            (catch clojure.lang.ExceptionInfo _ false))
         (s/or :where vector? :detailed map?)))
(s/def ::pattern-entry
  (s/and map? #(= #{:name :fn :input-spec :doc} (set (keys %)))))
(s/def ::hook-entry
  (s/and map? #(= #{:key :types :fn :order :metadata} (set (keys %)))))
(s/def ::event-entry
  (s/and map? #(= #{:key :types :fn :metadata} (set (keys %)))))

(s/def ::bin-entry
  (s/and map?
         #(every? #{:name :doc :executable :provenance :source/file :build}
                  (keys %))
         #(every? (partial contains? %) [:name :doc :executable :provenance])
         #(s/valid? string? (:doc %))
         #(or (not (contains? % :source/file))
              (non-blank-string? (:source/file %)))))

(defn op-declaration
  "Return a validated `:ops` entry.

  `opts` conforms to `::op-options`; `fn-sym` must be fully qualified. Override
  intent is collection metadata and is not stored in the entry."
  [op-name doc opts fn-sym]
  (require-valid! ::op-options opts "defop options are invalid")
  (require-valid!
   ::op-entry
   (weaver/validate-op-entry!
    (cond-> {:name (name op-name)
             :fn fn-sym
             :provenance (symbol (namespace fn-sym))
             :doc doc}
      true (merge (dissoc opts :override?))))
   "defop declaration is invalid"))

(defn query-declaration
  "Return a validated `:queries` entry.

  `opts` conforms to `::query-options`. Query compilation is the production
  grammar boundary; `:usage` and override intent are authoring metadata."
  [_query-name opts definition]
  (require-valid! ::query-options opts "defquery options are invalid")
  (require-valid! ::query-entry definition "defquery declaration is invalid"))

(defn pattern-declaration
  "Return a validated `:patterns` entry.

  `opts` conforms to `::pattern-options` and names the registered input spec."
  [pattern-name doc opts fn-sym]
  (require-valid! ::pattern-options opts "defpattern options are invalid")
  (require-valid! ::pattern-entry
                  {:name (name pattern-name)
                   :fn fn-sym
                   :input-spec (:spec opts)
                   :doc doc}
                  "defpattern declaration is invalid"))

(defn hook-declaration
  "Return a validated `:hooks` entry from `opts` conforming to `::hook-options`."
  [hook-key opts fn-sym]
  (require-valid! ::hook-options opts "defhook options are invalid")
  (require-valid! ::hook-entry
                  {:key hook-key
                   :types (:types opts)
                   :fn fn-sym
                   :order (get opts :order 0)
                   :metadata (get opts :metadata {})}
                  "defhook declaration is invalid"))

(defn handler-declaration
  "Return a validated `:events` entry from `opts` conforming to `::handler-options`."
  [handler-key opts fn-sym]
  (require-valid! ::handler-options opts "defhandler options are invalid")
  (require-valid! ::event-entry
                  {:key handler-key
                   :types (:types opts)
                   :fn fn-sym
                   :metadata (get opts :metadata {})}
                  "defhandler declaration is invalid"))

(defn- declaration-source-file []
  (when-not (or (nil? *file*) (= "NO_SOURCE_FILE" *file*))
    (let [file (io/file *file*)
          loader (.getContextClassLoader (Thread/currentThread))
          resource (when-not (.isAbsolute ^java.io.File file)
                     (io/resource *file* loader))]
      (cond
        (.isAbsolute ^java.io.File file) (.getCanonicalPath ^java.io.File file)
        resource (.getCanonicalPath (io/file resource))
        :else
        (throw (ex-info "Bin declaration source file cannot be resolved"
                        {:reason :bin/declaration-source-unresolved
                         :source/file *file*
                         :classloader (str loader)
                         :accepted [:absolute-file :classloader-resource]}))))))

(defn bin-declaration
  "Return a validated `:bins` entry for executable `bin-name`.

  The result conforms to `::bin-entry`. The executable spelling and optional
  argv recipe are retained as authored; path resolution and filesystem
  readiness belong to the plan read path."
  [bin-name doc opts provenance]
  (when (and (map? opts)
             (vector? (:executable opts))
             (seq (:executable opts))
             (not (contains? bin-anchors (first (:executable opts)))))
    (throw (ex-info (str "defbin options are invalid: executable anchor "
                         (pr-str (first (:executable opts)))
                         " is invalid; expected :family or :root")
                    {:anchor (first (:executable opts))
                     :allowed (vec (sort bin-anchors))})))
  (require-valid! string? doc "defbin doc is invalid")
  (require-valid! ::bin-options opts "defbin options are invalid")
  (let [source-file (declaration-source-file)]
    (require-valid!
     ::bin-entry
     (cond-> {:name (name bin-name)
              :doc doc
              :executable (:executable opts)
              :provenance provenance}
       source-file (assoc :source/file source-file)
       (contains? opts :build) (assoc :build (:build opts)))
     "defbin declaration is invalid")))
