(ns millstrand.api.format.alpha
  "Blessed prose helpers for tiers that publish text as data.

  `prose` preserves authored Markdown layout while removing only source
  indentation and interpolating named values. The older `|`-margin helpers stay
  available for their established item and reflow contracts."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [millstrand.core.format :as format]))

(s/def ::template string?)
(s/def ::scope map?)

(def ^:private placeholder-pattern
  #"\{([A-Za-z][A-Za-z0-9_-]*)(?::([^{}]+))?\}")

(defn- require-valid!
  "Return `value` when it satisfies `spec`; otherwise fail with explanation."
  [spec value key message]
  (when-not (s/valid? spec value)
    (throw (ex-info message
                    {key value :explain (s/explain-data spec value)})))
  value)

(defn- trim-boundary-blank-lines
  "Return `lines` without whitespace-only leading or trailing lines."
  [lines]
  (->> lines
       (drop-while str/blank?)
       reverse
       (drop-while str/blank?)
       reverse
       vec))

(defn- baseline-indent
  "Return the leading-space prefix on the first content line of `lines`."
  [lines template]
  (let [indent (re-find #"^[ \t]*" (first lines))]
    (when (str/includes? indent "\t")
      (throw (ex-info "prose: tabs are not supported in baseline indentation"
                      {:template template :indent indent})))
    indent))

(defn- dedent-line
  "Remove `baseline` from one nonblank source line."
  [baseline line line-number template]
  (cond
    (str/blank? line) ""
    (re-find #"^[ \t]*\t" line)
    (throw (ex-info "prose: tabs are not supported in indentation"
                    {:template template :line line-number :text line}))
    (str/starts-with? line baseline) (subs line (count baseline))
    :else
    (throw (ex-info "prose: content is less indented than its first line"
                    {:template template
                     :line line-number
                     :baseline baseline
                     :text line}))))

(defn- dedent
  "Remove `template`'s first-content-line indentation from every content line."
  [template]
  (let [lines (trim-boundary-blank-lines (str/split template #"\n" -1))]
    (if (empty? lines)
      ""
      (let [baseline (baseline-indent lines template)]
        (->> lines
             (map-indexed #(dedent-line baseline %2 (inc %1) template))
             (str/join "\n"))))))

(defn- json-key
  "Render map keys without dropping keyword or symbol namespaces."
  [key]
  (if (instance? clojure.lang.Named key)
    (if-let [namespace-name (namespace key)]
      (str namespace-name "/" (name key))
      (name key))
    (str key)))

(defn- render-placeholder
  "Render one named placeholder from `scope`."
  [scope name renderer template]
  (let [keyword-key (keyword name)
        string-key name
        present? (or (contains? scope keyword-key)
                     (contains? scope string-key))
        value (if (contains? scope keyword-key)
                (get scope keyword-key)
                (get scope string-key))]
    (when-not present?
      (throw (ex-info "prose: placeholder has no value in scope"
                      {:template template :placeholder name})))
    (case renderer
      nil (str value)
      "json" (json/write-str value :key-fn json-key :escape-slash false)
      (throw (ex-info "prose: unsupported placeholder renderer"
                      {:template template :placeholder name :renderer renderer})))))

(defn- reject-malformed-placeholder!
  "Fail when `text` contains braces outside the supported placeholder grammar."
  [text template]
  (let [without-placeholders (str/replace text placeholder-pattern "")]
    (when-let [brace (re-find #"[{}]" without-placeholders)]
      (throw (ex-info "prose: malformed placeholder"
                      {:template template :placeholder brace}))))
  text)

(defn- interpolate
  "Replace named placeholders in `text` from `scope`."
  [text scope template]
  (reject-malformed-placeholder! text template)
  (let [matcher (re-matcher placeholder-pattern text)
        output (StringBuffer.)]
    (while (.find matcher)
      (.appendReplacement matcher output
                          (java.util.regex.Matcher/quoteReplacement
                           (render-placeholder scope (.group matcher 1)
                                               (.group matcher 2) template))))
    (.appendTail matcher output)
    (str output)))

(defn prose
  "Render an indentation-aware Markdown template with named interpolation.

  The first content line establishes the source indentation removed from every
  nonblank line; remaining whitespace, blank lines, Markdown, and line width are
  preserved. `{name}` interpolates `:name` or `\"name\"` from `scope`; `{name:json}`
  renders compact JSON. Throws with the offending template or scope when either
  input is invalid, or when rendering finds malformed indentation or placeholders."
  [template scope]
  (require-valid! ::template template :template
                  "prose: template must satisfy ::template")
  (require-valid! ::scope scope :scope
                  "prose: scope must satisfy ::scope")
  (interpolate (dedent template) scope template))

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
