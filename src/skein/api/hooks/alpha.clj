(ns skein.api.hooks.alpha
  "Explicit-runtime API for registering and inspecting weaver lifecycle hooks.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns hook validation, function resolution, and
  registry state; synchronous invocation by later lifecycle gates lives in
  `skein.core.weaver.lifecycle`."
  (:require [clojure.string :as str]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.dispatch :as dispatch]))

(declare validate-hook-key! validate-hook-types! validate-hook-opts! validate-hook-fn!)

(defn register-hook!
  "Register or replace a lifecycle hook in `runtime` for selected hook types.

  `key` is the stable registry identity (keyword, symbol, or non-blank string):
  registering an existing key replaces that entry. `types` is a non-empty set of
  hook type keywords, and `fn-sym` a fully qualified symbol validated here as
  resolvable under the runtime's spool classloader. The entry stores only the
  symbol — every hook binds its callable at dispatch start, so a reload's fresh
  definition is the one that runs. `opts` may carry an integer `:order`
  (default 0) plus data-first metadata. Returns the registered entry."
  ([runtime key types fn-sym]
   (register-hook! runtime core-registry/repl-owner key types fn-sym {}))
  ([runtime key types fn-sym opts]
   (register-hook! runtime core-registry/repl-owner key types fn-sym opts))
  ([runtime owner key types fn-sym opts]
   (let [opts (validate-hook-opts! opts)
         entry {:key (validate-hook-key! key)
                :types (validate-hook-types! types)
                :fn fn-sym
                :order (get opts :order 0)
                :metadata (dissoc opts :order)}]
     (validate-hook-fn! runtime fn-sym)
     (core-registry/put-entry! (access/hook-store runtime) owner (:key entry) entry)
     entry)))

(defn unregister-hook!
  "Unregister a lifecycle hook by stable key from `runtime` and return that key.

  Unregistering an absent key is a no-op returning the validated key."
  ([runtime key]
   (unregister-hook! runtime core-registry/repl-owner key))
  ([runtime owner key]
   (let [key (validate-hook-key! key)]
     (core-registry/remove-entry! (access/hook-store runtime) owner key)
     key)))

(defn hooks
  "Return data-first lifecycle hook registry entries in execution order.

  Entries sort by `:order`, then printed key for a deterministic tie-break.
  Entries are data — the callable binds at dispatch from the `:fn` symbol —
  and any directly planted `:fn-value` is stripped so no function value
  leaves the registry."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (juxt :order (comp pr-str :key))
                 (vals (access/hook-registry runtime)))))

(defn hook-provenance
  "Return owner/provenance diagnostics for `runtime`'s lifecycle hook registry.

  Maps each hook key to `{:effective :shadowed :contenders}` (see
  `skein.core.weaver.core-registry/explain`); each contender names its `:owner`,
  `:layer`, and `:override?`/`:effective?` flags, and its `:value` hook entry
  has any directly planted `:fn-value` stripped, so no function value or
  internal handle leaves the registry (DELTA-OlrDrt-001.CC9)."
  [runtime]
  (core-registry/explain (access/hook-store runtime) #(dissoc % :fn-value)))

;; --- validating and resolving registration input ----------------------

(defn- validate-hook-key! [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Hook key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Hook key string must be non-blank" {:key key})))
  key)

(defn- validate-hook-types! [types]
  (when-not (set? types)
    (throw (ex-info "Hook types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Hook types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Hook types must be keywords" {:type type :types types}))))
  types)

(defn- validate-hook-opts! [opts]
  (let [opts (or opts {})]
    (when-not (map? opts)
      (throw (ex-info "Hook opts must be a map" {:opts opts})))
    (when-not (dispatch/data-first-value? opts)
      (throw (ex-info "Hook opts must contain only data-first values" {:opts opts})))
    (when (and (contains? opts :order) (not (integer? (:order opts))))
      (throw (ex-info "Hook :order must be an integer" {:order (:order opts)})))
    opts))

(defn- validate-hook-fn!
  "Fail loudly unless fn-sym currently resolves to a callable value.

  Registration-time validation only: the callable itself binds at dispatch,
  so this guards the direct-registration path against typos without freezing
  a resolved value into the entry."
  [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Hook function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (access/with-spool-classloader runtime #(requiring-resolve fn-sym))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Hook symbol must resolve to a callable value"
                      {:fn fn-sym :resolved-class (str (class value))})))
    fn-sym))
