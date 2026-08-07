(ns cutover.cli-contracts
  "Own the reusable shapes for cutover CLI paths, payloads, and whole-value refs.

  The command-specific parsers retain cross-entry rules such as duplicate names
  and the reserved `stdin` slot. These specs own the shape of each entry and
  the map delivered to the shared CLI parser."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn non-blank-string?
  "Return true when `value` is a non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(defn payload-name?
  "Return true when `value` is a valid named payload slot."
  [value]
  (and (string? value)
       (boolean (re-matches #"[A-Za-z][A-Za-z0-9_-]*" value))))

(defn payload-ref?
  "Return true when `value` is a valid `:payload/<name>` reference."
  [value]
  (and (string? value)
       (str/starts-with? value ":payload/")
       (payload-name? (subs value (count ":payload/")))))

(defn value-ref?
  "Return true when `value` is one of the whole-value payload references."
  [value]
  (or (= value ":stdin") (payload-ref? value)))

(defn path-or-ref?
  "Return true when `value` is a path or a valid whole-value reference."
  [value]
  (and (non-blank-string? value)
       (or (value-ref? value)
           (not (str/starts-with? value ":")))))

(s/def ::payload-name payload-name?)
(s/def ::payload-path non-blank-string?)
(s/def ::payload-entry (s/tuple ::payload-name ::payload-path))
(s/def ::payload-map (s/map-of ::payload-name string?))
(s/def ::payload-ref payload-ref?)
(s/def ::value-ref value-ref?)
(s/def ::path-or-ref path-or-ref?)
