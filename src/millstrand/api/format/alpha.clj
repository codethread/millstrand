(ns millstrand.api.format.alpha
  "Blessed prose helpers for tiers that publish text as data.

  `prose` preserves authored Markdown layout while removing only source
  indentation and interpolating named values. The older `|`-margin helpers stay
  available for their established item and reflow contracts."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [millstrand.core.format :as format]))

(defn prose
  "Render an indentation-aware Markdown template with named interpolation.

  The first content line establishes the source indentation removed from every
  nonblank line; remaining whitespace, blank lines, Markdown, and line width are
  preserved. `{name}` interpolates `:name` or `\"name\"` from `scope`; `{name:json}`
  renders compact JSON. Throws with the offending template or scope when either
  input is invalid, or when rendering finds malformed indentation or placeholders."
  [template scope]
  (when-not (string? template)
    (throw (ex-info "prose: template must be a string" {:template template})))
  (when-not (map? scope)
    (throw (ex-info "prose: scope must be a map" {:scope scope})))
  (format/prose template scope))

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

(s/def ::template string?)
(s/def ::scope map?)

(s/fdef prose
  :args (s/cat :template ::template :scope ::scope)
  :ret string?)

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
