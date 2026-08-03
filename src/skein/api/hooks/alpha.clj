(ns skein.api.hooks.alpha
  "Explicit-runtime API for registering and inspecting weaver lifecycle hooks.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns hook validation, function resolution, and
  registry state; synchronous invocation by later lifecycle gates lives in
  `skein.core.weaver.lifecycle`."
  (:require [clojure.spec.alpha :as s]
            [skein.api.spool.alpha :as spool]
            [skein.core.specs :as specs]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.core-registry :as core-registry]))

(declare validate-hook-fn! require-hook-registration! public-hook-entry
         public-hook-provenance validated-hook-entry)

(defn register-hook!
  "Register a lifecycle hook in `runtime` for selected hook types.

  `key` is the stable registry identity (keyword, symbol, or non-blank string):
  re-registering a key this owner already holds replaces that entry, while a key
  another owner supplies collides loudly — `replace-hook!` is the deliberate
  override for it. `types` is a non-empty set of hook type keywords, and
  `fn-sym` a fully qualified symbol validated here as resolvable under the
  runtime's spool classloader. The entry stores only the symbol — every hook
  binds its callable at dispatch start, so a reload's fresh definition is the one
  that runs. `opts` may carry an integer `:order` (default 0) plus data-first
  metadata. Registration input and the returned entry conform to
  `::skein.core.specs/hook-registration` and `::skein.core.specs/hook-entry`,
  respectively. Returns the registered entry."
  ([runtime key types fn-sym]
   (register-hook! runtime core-registry/repl-owner key types fn-sym {}))
  ([runtime key types fn-sym opts]
   (register-hook! runtime core-registry/repl-owner key types fn-sym opts))
  ([runtime owner key types fn-sym opts]
   (let [entry (validated-hook-entry runtime key types fn-sym opts)]
     (core-registry/put-entry! (access/hook-store runtime) owner (:key entry) entry)
     entry)))

(defn replace-hook!
  "Replace an already-registered lifecycle hook, failing loudly when absent.

  Same signature and return shape as `register-hook!`. This is the deliberate
  override for a key that already exists; unlike `register-hook!` it requires the
  key to be present. When another owner supplies the key — a module-published
  hook, say — the override intent is recorded, which is what lets the direct
  entry keep shadowing the original across `runtime/refresh!`.
  `unregister-hook!` retracts the shadow and the shadowed entry becomes effective
  again. Hooks bind their callable at dispatch start, so iterating a body under a
  stable contract needs no registry call at all; reach for this when the types,
  order, metadata, or the symbol itself change."
  ([runtime key types fn-sym]
   (replace-hook! runtime core-registry/repl-owner key types fn-sym {}))
  ([runtime key types fn-sym opts]
   (replace-hook! runtime core-registry/repl-owner key types fn-sym opts))
  ([runtime owner key types fn-sym opts]
   (let [entry (validated-hook-entry runtime key types fn-sym opts)
         registered (access/hook-registry runtime)]
     (when-not (contains? registered (:key entry))
       (throw (ex-info "Hook not registered; cannot replace"
                       {:hook (:key entry)
                        :available (sort-by pr-str (keys registered))})))
     (core-registry/replace-entry! (access/hook-store runtime) owner (:key entry) entry)
     entry)))

(defn unregister-hook!
  "Retract `owner`'s own lifecycle hook registration for `key` from `runtime`.

  Removal reaches only into the calling owner's partition, so it is the
  counterpart of `replace-hook!` rather than a way to delete another owner's
  hook: retracting a shadow restores the shadowed entry as effective, and
  retracting a fresh claim leaves the key unregistered. Unregistering a key this
  owner never registered is an idempotent no-op. Returns `{:unregistered
  <key>}`."
  ([runtime key]
   (unregister-hook! runtime core-registry/repl-owner key))
  ([runtime owner key]
   (let [key (spool/require-valid! :skein.hook/key key
                                   "Hook key must be a keyword, symbol, or string")]
     (core-registry/remove-entry! (access/hook-store runtime) owner key)
     {:unregistered key})))

(defn hooks
  "Return data-first lifecycle hook registry entries in execution order.

  Entries sort by `:order`, then printed key for a deterministic tie-break.
  Entries are data — the callable binds at dispatch from the `:fn` symbol —
  and any directly planted `:fn-value` is stripped so no function value
  leaves the registry. Each returned entry conforms to
  `::skein.core.specs/hook-entry`."
  [runtime]
  (mapv public-hook-entry
        (sort-by (juxt :order (comp pr-str :key))
                 (vals (access/hook-registry runtime)))))

(defn hook-provenance
  "Return owner/provenance diagnostics for `runtime`'s lifecycle hook registry.

  Maps each hook key to `{:effective :shadowed :contenders}` (see
  `skein.core.weaver.core-registry/explain`); each contender names its `:owner`,
  `:layer`, and `:override?`/`:effective?` flags, and its `:value` hook entry
  has any directly planted `:fn-value` stripped, so no function value or
  internal handle leaves the registry (DELTA-OlrDrt-001.CC9). The returned map
  conforms to `::skein.core.specs/hook-provenance`."
  [runtime]
  (public-hook-provenance
   (core-registry/explain (access/hook-store runtime) public-hook-entry)))

(defn- validated-hook-entry
  "Validate one registration's inputs and assemble its registry entry.

  The shared entrance for `register-hook!` and `replace-hook!`: the registration
  shape, then the hook symbol's resolvability, then the entry itself, so a
  malformed registration never reaches the registry."
  [runtime key types fn-sym opts]
  (require-hook-registration! {:key key :types types :fn fn-sym :opts opts})
  (validate-hook-fn! runtime fn-sym)
  (let [entry {:key key
               :types types
               :fn fn-sym
               :order (get opts :order 0)
               :metadata (dissoc opts :order)}]
    (spool/require-valid! ::specs/hook-entry entry "Hook registry entry is invalid")
    entry))

(defn- hook-registration-message
  "Return a caller-facing diagnostic for an already-rejected registration."
  [{:keys [key types opts] fn-sym :fn}]
  (cond
    (not (s/valid? :skein.hook/key key))
    "Hook key must be a keyword, symbol, or string"
    (not (s/valid? :skein.hook/types types))
    (cond
      (not (set? types)) "Hook types must be a set"
      (empty? types) "Hook types must be non-empty"
      :else "Hook types must be keywords")
    (not (s/valid? :skein.hook/fn fn-sym))
    "Hook function must be a fully qualified symbol"
    (not (s/valid? :skein.hook/opts opts))
    (cond
      (not (map? opts)) "Hook opts must be a map"
      (and (contains? opts :order)
           (not (integer? (:order opts))))
      "Hook :order must be an integer"
      :else "Hook opts must contain only data-first values")
    :else "Hook registration input is invalid"))

(defn- require-hook-registration!
  "Validate the complete registration spec before deriving diagnostics."
  [registration]
  (try
    (spool/require-valid! ::specs/hook-registration
                          registration
                          "Hook registration input is invalid")
    (catch clojure.lang.ExceptionInfo error
      (throw (ex-info (hook-registration-message registration)
                      (ex-data error)
                      error)))))

(defn- public-hook-entry [entry]
  (let [entry (dissoc entry :fn-value)]
    (spool/require-valid! ::specs/hook-entry
                          entry
                          "Hook registry entry is invalid")
    entry))

(defn- public-hook-provenance [provenance]
  (spool/require-valid! ::specs/hook-provenance
                        provenance
                        "Hook provenance is invalid")
  provenance)

;; --- resolving registration input --------------------------------------

(defn- validate-hook-fn!
  "Fail loudly unless fn-sym currently resolves to a callable value.

  Registration-time validation only: the callable itself binds at dispatch,
  so this guards the direct-registration path against typos without freezing
  a resolved value into the entry."
  [runtime fn-sym]
  (let [resolved (access/with-spool-classloader runtime #(requiring-resolve fn-sym))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Hook symbol must resolve to a callable value"
                      {:fn fn-sym :resolved-class (str (class value))})))
    fn-sym))
