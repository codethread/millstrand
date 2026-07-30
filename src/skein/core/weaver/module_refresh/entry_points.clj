(ns skein.core.weaver.module-refresh.entry-points
  "Reject the withdrawn public `spool` entry-point convention."
  (:require [clojure.spec.alpha :as s]
            [skein.core.format :as format]))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn module-namespace
  "Return the namespace symbol loaded for a module, or nil.

  `:ns` and image targets use their declared namespace; a `:file` target uses
  the single namespace declared by the loaded file."
  [declaration context]
  (or (:ns declaration) (:source/namespace context)))

(defn reject-public-spool!
  "Fail when a module namespace exposes the withdrawn public `spool` var."
  [module-key module-ns]
  (when (and module-ns
             (find-ns module-ns)
             (contains? (ns-publics module-ns) 'spool))
    (fail! (format/reflow
            "|Module namespace exposes the removed public spool entry point.
             |Delete `def spool`; publish registry entries with skein/defop,
             |skein/defquery, skein/defpattern, skein/defhook, or
             |skein/defhandler, and declare live effects with the lifecycle
             |authoring forms.")
           {:reason :removed-def-spool
            :module/key module-key
            :module/namespace module-ns})))

(s/def ::module-key keyword?)
(s/def ::declaration
  (s/and map? #(or (nil? (:ns %)) (symbol? (:ns %)))))
(s/def ::context
  (s/and map?
         #(or (nil? (:source/namespace %))
              (symbol? (:source/namespace %)))))
(s/fdef module-namespace
  :args (s/cat :declaration ::declaration :context ::context)
  :ret (s/nilable symbol?))

(s/fdef reject-public-spool!
  :args (s/cat :module-key ::module-key :module-ns (s/nilable symbol?))
  :ret nil?)
