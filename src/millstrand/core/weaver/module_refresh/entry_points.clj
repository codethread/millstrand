(ns millstrand.core.weaver.module-refresh.entry-points
  "Reject the withdrawn public `spool` entry-point convention."
  (:require [clojure.spec.alpha :as s]))

(defn module-namespace
  "Return the namespace symbol loaded for a module, or nil.

  `:ns` and image targets use their declared namespace; a `:file` target uses
  the single namespace declared by the loaded file."
  [declaration context]
  (or (:ns declaration) (:source/namespace context)))

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
