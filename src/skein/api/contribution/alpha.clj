(ns skein.api.contribution.alpha
  "Authoring forms for owner-complete core runtime contributions.

  Each form defines an ordinary Clojure Var and collects one validated declaration
  while a runtime module source is evaluated. The retained declaration record is
  replayed for image modules, so source and image activation publish the same
  owner-complete partitions."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [require-valid!]]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.query :as query]))

(def ^:private op-option-keys
  #{:arg-spec :returns :stream? :deadline-class :hook-class :about :prime
    :annotations :override?})

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

(s/def ::op-entry
  (s/and map?
         #(every? #{:name :fn :provenance :doc :arg-spec :returns :stream?
                    :deadline-class :hook-class :about :prime :annotations}
                  (keys %))
         #(every? (partial contains? %) [:name :fn :provenance])))
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

(defmacro defop
  "Define an operation handler and collect its validated `:ops` declaration.

  Options conform to `::op-options`; `:override? true` records explicit override
  intent without entering the registry value."
  [form-name doc opts argv & body]
  (let [handler-name (symbol (str form-name "-op"))
        fn-sym (symbol (str (ns-name *ns*)) (str handler-name))]
    `(do
       (defn ~handler-name ~doc ~argv ~@body)
       (runtime/collect-entry! :ops ~(str form-name)
                               (op-declaration '~form-name ~doc ~opts '~fn-sym)
                               (select-keys ~opts #{:override?}))
       (var ~handler-name))))

(defmacro defquery
  "Define a named query and collect its validated `:queries` declaration.

  Options conform to `::query-options`; `:override? true` records override intent."
  [form-name doc opts definition]
  `(do
     (def ~form-name ~doc (query-declaration '~form-name ~opts ~definition))
     (runtime/collect-entry! :queries ~(str/replace (str form-name) #"-query$" "")
                             ~form-name (select-keys ~opts #{:override?}))
     (var ~form-name)))

(defmacro defpattern
  "Define a weave handler and collect its validated `:patterns` declaration.

  Options conform to `::pattern-options` and require a named input `:spec`."
  [form-name doc opts argv & body]
  (let [fn-sym (symbol (str (ns-name *ns*)) (str form-name))]
    `(do
       (defn ~form-name ~doc ~argv ~@body)
       (runtime/collect-entry! :patterns ~(str form-name)
                               (pattern-declaration '~form-name ~doc ~opts '~fn-sym)
                               (select-keys ~opts #{:override?}))
       (var ~form-name))))

(defmacro defhook
  "Define a lifecycle hook and collect its validated `:hooks` declaration.

  Options conform to `::hook-options`."
  [form-name doc opts argv & body]
  (let [fn-sym (symbol (str (ns-name *ns*)) (str form-name))]
    `(do
       (defn ~form-name ~doc ~argv ~@body)
       (runtime/collect-entry! :hooks ~(keyword form-name)
                               (hook-declaration ~(keyword form-name) ~opts '~fn-sym)
                               (select-keys ~opts #{:override?}))
       (var ~form-name))))

(defmacro defhandler
  "Define an event handler and collect its validated `:events` declaration.

  Options conform to `::handler-options`."
  [form-name doc opts argv & body]
  (let [fn-sym (symbol (str (ns-name *ns*)) (str form-name))]
    `(do
       (defn ~form-name ~doc ~argv ~@body)
       (runtime/collect-entry! :events ~(keyword form-name)
                               (handler-declaration ~(keyword form-name) ~opts '~fn-sym)
                               (select-keys ~opts #{:override?}))
       (var ~form-name))))
