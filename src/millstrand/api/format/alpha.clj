(ns millstrand.api.format.alpha
  "Blessed `|`-margin doc-block helpers for any tier that publishes prose as data.

  Long strings in source hurt readability and IDE viewports; author them as
  `|`-margin blocks instead and reflow with these helpers. Both helpers
  validate their input against the promised qualified spec key
  `:millstrand.api.format.alpha/block` — a string in which at least one line
  carries a `|` — and fail loudly with the offending value in ex-data."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [millstrand.core.format :as format]))

(defn fill
  "Reflow a `|`-margin doc block into a vector of item strings.

  The bar marks column 0, a bare `|` line separates items, flush-left prose
  soft-wraps into one line per item, and any indentation past the bar keeps the
  whole item verbatim for command samples and other intentional layout. Throws
  when the input does not satisfy `::block`."
  [block]
  (when-not (s/valid? ::block block)
    (throw (ex-info "fill: no barred lines; ::block is a string with a |-margin line"
                    {:block block :explain (s/explain-data ::block block)})))
  (format/fill block))

(defn reflow
  "Soft-wrap a single-paragraph `|`-margin block into one string.

  The single-item companion to `fill` for a lone prose value; item and verbatim
  semantics do not apply — every barred line is trimmed and space-joined, so
  the result never contains a newline. Throws when the input does not satisfy
  `::block`, like `fill`."
  [block]
  (when-not (s/valid? ::block block)
    (throw (ex-info "reflow: no barred lines; ::block is a string with a |-margin line"
                    {:block block :explain (s/explain-data ::block block)})))
  (format/reflow block))

(s/def ::block
  (s/with-gen (s/and string? #(str/includes? % "|"))
    #(gen/fmap (fn [s] (str "|" s)) (gen/string-alphanumeric))))

(s/fdef fill
  :args (s/cat :block ::block)
  :ret (s/coll-of string? :kind vector?))

(s/fdef reflow
  :args (s/cat :block ::block)
  :ret string?
  :fn (fn [{:keys [ret]}] (not (str/includes? ret "\n"))))
