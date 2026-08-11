(ns hooks.millstrand
  "clj-kondo analysis hooks for Millstrand's public authoring forms."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private source-meta-keys
  [:filename :row :col :end-row :end-col])

(defn- source-meta [node]
  (select-keys (meta node) source-meta-keys))

(defn- invalid-hook-context!
  [hook message node offending-node offending-value]
  (throw (ex-info (str (name hook) " hook " message)
                  {:node (source-meta node)
                   :offending-node offending-node
                   :offending-value offending-value})))

(defn- hook-children
  [hook context child-count exact?]
  (let [node (when (map? context) (:node context))
        children (when (api/node? node) (:children node))
        invalid-child (when (sequential? children)
                        (some #(when-not (api/node? %) [::invalid %]) children))]
    (cond
      (not (map? context))
      (invalid-hook-context! hook "context must be a map" nil nil context)

      (not (api/list-node? node))
      (invalid-hook-context! hook "context must contain a list node"
                             node node node)

      (not (sequential? children))
      (invalid-hook-context! hook "context node must contain child nodes"
                             node node children)

      invalid-child
      (invalid-hook-context! hook "context node contains a non-node child"
                             node node (second invalid-child))

      (and exact? (not= child-count (count children)))
      (invalid-hook-context! hook
                             (str "context node must contain exactly " child-count
                                  " children")
                             node node children)

      (< (count children) child-count)
      (invalid-hook-context! hook
                             (str "context node must contain at least " child-count
                                  " children")
                             node node children)

      :else
      [node children])))

(defn- sexpr [node]
  (try
    (api/sexpr node)
    (catch Exception error
      (throw (ex-info "Unable to read a clj-kondo hook node"
                      {:node (source-meta node)
                       :offending-node node}
                      error)))))

(defn- symbol-name
  [hook name-node]
  (let [name (sexpr name-node)]
    (if (symbol? name)
      name
      (invalid-hook-context! hook "name must be a symbol"
                             name-node name-node name))))

(defn- defn-hook
  "Analyze a function-shaped authoring form as a `defn`."
  [{:keys [node]}]
  (let [[form-node name-node docstring-node opts-node argv-node & body] (:children node)
        defn-node (api/list-node
                   (list* (api/token-node 'defn)
                          name-node docstring-node argv-node body))
        used (api/list-node
              (list (api/token-node 'do)
                    (api/list-node
                     (list (api/token-node 'identity) form-node))
                    (api/list-node
                     (list (api/token-node 'identity) opts-node))
                    defn-node))]
    {:node (with-meta used (meta node))}))

(defn defop
  "Analyze a Millstrand `defop` call."
  [context]
  (let [[node children] (hook-children :defop context 5 false)
        [form-node name-node docstring-node opts-node argv-node & body] children
        name (symbol-name :defop name-node)
        handler-node (with-meta
                       (api/token-node (symbol (str name "-op")))
                       (meta name-node))
        defn-node (api/list-node
                   (list* (api/token-node 'defn)
                          handler-node docstring-node argv-node body))
        used (api/list-node
              (list (api/token-node 'do)
                    (api/list-node
                     (list (api/token-node 'identity) form-node))
                    (api/list-node
                     (list (api/token-node 'identity) opts-node))
                    defn-node))]
    {:node (with-meta used (meta node))}))

(defn defquery
  "Analyze a Millstrand `defquery` call."
  [context]
  (let [[node [form-node name-node docstring-node opts-node query-node]]
        (hook-children :defquery context 5 true)
        _ (symbol-name :defquery name-node)
        used (api/list-node
              (list (api/token-node 'do)
                    (api/list-node
                     (list (api/token-node 'identity) form-node))
                    (api/list-node
                     (list (api/token-node 'identity) opts-node))
                    query-node))
        def-node (api/list-node
                  (list (api/token-node 'def)
                        name-node docstring-node used))]
    {:node (with-meta def-node (meta node))}))

(defn defpattern
  "Analyze a Millstrand `defpattern` call."
  [context]
  (defn-hook context))

(defn defhook
  "Analyze a Millstrand `defhook` call."
  [context]
  (defn-hook context))

(defn defhandler
  "Analyze a Millstrand `defhandler` call."
  [context]
  (defn-hook context))

(defn defbin
  "Analyze a Millstrand `defbin` call as a Var definition."
  [{:keys [node]}]
  (let [[form-node name-node docstring-node opts-node] (:children node)
        value-node (api/list-node
                    (list (api/token-node 'do)
                          (api/list-node
                           (list (api/token-node 'identity) form-node))
                          (api/list-node
                           (list (api/token-node 'identity) opts-node))))
        def-node (api/list-node
                  (list (api/token-node 'def)
                        name-node docstring-node value-node))]
    {:node (with-meta def-node (meta node))}))
