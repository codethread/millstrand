(ns hooks.project-rules
  "Enforce repository-specific source conventions during clj-kondo analysis."
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]))

(defn- sexpr [node]
  (try
    (api/sexpr node)
    (catch Exception _
      ::unreadable)))

(defn- reg-finding! [{:keys [node message type]}]
  (api/reg-finding! (assoc (meta node)
                           :type type
                           :message message)))

(defn- spool-file? [filename]
  (boolean (and filename
                (or (str/includes? filename "/spools/src/millstrand/spools/")
                    (str/includes? filename "\\spools\\src\\millstrand\\spools\\")))))

(defn ns-docstring
  "Require every namespace form to carry a docstring immediately after the ns symbol."
  [{:keys [node]}]
  (let [[_ns-form name-node maybe-doc] (:children node)]
    (when-not (string? (sexpr maybe-doc))
      (reg-finding! {:node (or name-node node)
                     :type :project/ns-docstring
                     :message "Namespace forms must include a docstring immediately after the ns symbol."})))
  {:node node})

(defn no-spool-module-atom
  "Forbid top-level atom/volatile state definitions in shipped spool namespaces."
  [{:keys [node filename]}]
  (let [[def-node name-node init-node] (:children node)
        def-op (sexpr def-node)
        init-expr (sexpr init-node)]
    (when (and (spool-file? filename)
               (#{'def 'defonce 'clojure.core/def 'clojure.core/defonce} def-op)
               (seq? init-expr)
               (#{'atom 'clojure.core/atom 'volatile! 'clojure.core/volatile!} (first init-expr)))
      (reg-finding! {:node (or name-node node)
                     :type :project/no-spool-module-atom
                     :message "Spool state must be runtime-owned; do not define module-level atoms/volatiles in spool namespaces."})))
  {:node node})

(defn- fn-keys-destructure? [form]
  (cond
    (map? form) (or (some #{:fn} (:keys form))
                    (some fn-keys-destructure? (concat (keys form) (vals form))))
    (coll? form) (some fn-keys-destructure? form)
    :else false))

(defn no-fn-keys-destructure
  "Forbid :keys destructuring of :fn, which shadows clojure.core/fn."
  [{:keys [node]}]
  (when (fn-keys-destructure? (sexpr node))
    (reg-finding! {:node node
                   :type :project/no-fn-keys-destructure
                   :message "Do not :keys-destructure :fn; bind it explicitly, e.g. {fn-sym :fn}."}))
  {:node node})
