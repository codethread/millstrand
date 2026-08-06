(ns millstrand.api.runtime.internal.shapes
  "Component sub-specs behind millstrand.api.runtime.alpha's promised result shapes.

  Every spec here registers an alpha-qualified key: published qualified keys
  keep their `:millstrand.api.runtime.alpha/*` names wherever the defining code
  lands (SPEC-003.C19a). The alpha module owns and documents the top-level
  interface specs; these are the component shapes they compose."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn exact-keys?
  "True when `value` is a map whose key set equals `allowed` exactly."
  [allowed value]
  (and (map? value) (= allowed (set (keys value)))))

;; --- code-reload result components

(s/def :millstrand.api.runtime.alpha/ns symbol?)
(s/def :millstrand.api.runtime.alpha/file (s/and string? (complement str/blank?)))

;; --- spools.edn write-seam components

(s/def :millstrand.api.runtime.alpha/spool-write-status #{:inserted :updated :removed})

;; --- reload-code! result components

(s/def :millstrand.api.runtime.alpha/reload-namespace
  (s/and #(exact-keys? #{:ns :file} %)
         #(s/valid? :millstrand.api.runtime.alpha/ns (:ns %))
         #(s/valid? :millstrand.api.runtime.alpha/file (:file %))))
(s/def :millstrand.api.runtime.alpha/namespaces
  (s/coll-of :millstrand.api.runtime.alpha/reload-namespace :kind vector?))
(s/def :millstrand.api.runtime.alpha/canonical-root
  (s/and string? (complement str/blank?)))

;; --- spool-state option components

(s/def :millstrand.api.runtime.alpha/migrate-fn ifn?)
