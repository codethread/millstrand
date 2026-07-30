(ns hooks.project-rules
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
                (or (str/includes? filename "/spools/src/skein/spools/")
                    (str/includes? filename "\\spools\\src\\skein\\spools\\")))))

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

(defn defquery
  "Analyze `defquery` as a var definition so kondo resolves the query var.

  Rewrites `(defquery name docstring opts query-def)` into a `def` of the var
  with the docstring, evaluating opts and the query definition so their symbols
  are still checked, mirroring the macro's real `(def name docstring query-def)`
  expansion."
  [{:keys [node]}]
  (let [[_ name-node docstring-node opts-node query-node] (:children node)
        used (api/list-node (list (api/token-node 'do)
                                  (api/list-node
                                   (list (api/token-node 'identity) opts-node))
                                  query-node))
        def-node (api/list-node (list (api/token-node 'def) name-node docstring-node used))]
    {:node (with-meta def-node (meta node))}))

(defn defop
  "Analyze `defop` as a defn of the `<name>-op` handler var so kondo resolves the
  handler, its args, and body.

  Rewrites `(defop name docstring opts argv & body)` into a
  `(defn <name>-op docstring argv body...)` wrapped in a `do` that also evaluates
  opts so its symbols are still checked, mirroring the macro's real expansion."
  [{:keys [node]}]
  (let [[_ name-node docstring-node opts-node argv-node & body] (:children node)
        handler-node (api/token-node (symbol (str (api/sexpr name-node) "-op")))
        defn-node (api/list-node (list* (api/token-node 'defn) handler-node docstring-node argv-node body))
        used (api/list-node (list (api/token-node 'do)
                                  (api/list-node
                                   (list (api/token-node 'identity) opts-node))
                                  defn-node))]
    {:node (with-meta used (meta node))}))

(defn defexecutor
  "Analyze `defexecutor` as a defn of the `<name>-stalled?` handler var."
  [{:keys [node]}]
  (let [[_ name-node docstring-node opts-node argv-node & body] (:children node)
        handler-node (api/token-node (symbol (str (api/sexpr name-node) "-stalled?")))
        defn-node (api/list-node
                   (list* (api/token-node 'defn)
                          handler-node docstring-node argv-node body))
        used (api/list-node
              (list (api/token-node 'do)
                    (api/list-node (list (api/token-node 'identity) opts-node))
                    defn-node))]
    {:node (with-meta used (meta node))}))

(defn defrule
  "Analyze `defrule` as a defn of the `<name>-rule` handler var so kondo resolves
  the handler, its args, and body.

  Rewrites `(defrule name docstring argv & body)` into a
  `(defn <name>-rule docstring argv body...)`, mirroring the macro's real
  expansion."
  [{:keys [node]}]
  (let [[_ name-node docstring-node argv-node & body] (:children node)
        handler-node (api/token-node (symbol (str (api/sexpr name-node) "-rule")))
        defn-node (api/list-node (list* (api/token-node 'defn) handler-node docstring-node argv-node body))]
    {:node (with-meta defn-node (meta node))}))
