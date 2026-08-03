(ns skein.core.terse
  "Shared internal guards for the terse strand!/query surface.

  The downstream userland-only ergonomics layer exposes a 2-arg `strand!` whose
  second argument is attributes-only, plus a `query` that accepts either a
  registered query handle or an ad hoc query form. The tiny guards that make
  those overloads safe (rejecting core strand fields passed as attributes, and
  distinguishing a named query handle from an ad hoc form) live here, in
  internal core, rather than widening any `skein.api.*.alpha` blessed surface
  (TEN-004)."
  (:require [clojure.string]))

(def ^:private strand-core-keys
  "Core strand fields that must never be smuggled through the attributes map."
  #{:title :state :attributes :edges})

(defn reject-core-attribute-keys!
  "Return `attributes`, failing loudly when it carries core strand fields.

  Guards the 2-arg `strand!` overload, which treats its second argument as
  attributes-only: core fields (`:title`/`:state`/`:attributes`/`:edges`) there
  are a caller mistake, not attribute data, so they fail rather than silently
  becoming attributes (TEN-003)."
  [attributes]
  (when (map? attributes)
    (when-let [core-keys (seq (filter strand-core-keys (keys attributes)))]
      (throw (ex-info "Two-argument strand! treats the second argument as attributes; pass lifecycle fields as the third argument"
                      {:keys (vec core-keys)}))))
  attributes)

(defn named-query?
  "Return true when `query-or-def` is a registered query handle (symbol/keyword).

  A truthy result routes to the named-query path; anything else is treated as an
  ad hoc query definition form."
  [query-or-def]
  (or (symbol? query-or-def) (keyword? query-or-def)))
