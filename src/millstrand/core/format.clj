(ns millstrand.core.format
  "Implementation of `|`-margin doc-block reflowing shared across tiers.

  Long prose authored in source (op payloads, `about` surfaces, config rule
  descriptions) reads best as one `|`-margin string: the bar marks column 0,
  plain newlines soft-wrap, a bare `|` line separates items, and indentation
  past the bar is preserved verbatim for command samples and other intentional
  layout. This is the single implementation; the blessed consumer surface is
  `millstrand.api.format.alpha`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- bar-content
  "Return a `|`-margin line's content (everything after the first bar), or nil
  when the line carries no bar (leading/trailing structural source whitespace)."
  [line]
  (when-let [i (str/index-of line \|)]
    (subs line (inc i))))

(defn- barred-lines
  "Return the bar contents of `block`, failing loudly when `block` is not a
  string or no line carries a bar: a bar-less block is authoring error (a
  dropped `|`), and returning empty output would silently delete the prose
  it was meant to carry."
  [block]
  (when-not (string? block)
    (throw (ex-info "|-margin block must be a string with at least one barred line"
                    {:block block :type (type block)})))
  (let [lines (keep bar-content (str/split-lines block))]
    (when (empty? lines)
      (throw (ex-info "|-margin block has no barred lines; every content line must start with |"
                      {:block block})))
    lines))

(defn fill
  "Reflow a `|`-margin doc block into a vector of item strings.

  Each line's content is whatever follows its first `|`, so the bar marks
  column 0 and the enclosing form may be indented freely. A bare `|` line
  separates items. Within an item, flush-left lines are prose soft-wrapped into
  a single line; if any line is indented past the bar the whole item is kept
  verbatim, so command samples and other intentional layout survive. Prose is
  the zero-marker default; indentation is what supplies structure. Throws when
  no line carries a bar — a bar-less block is an authoring error, not empty
  output."
  [block]
  (->> (barred-lines block)
       (partition-by str/blank?)
       (remove #(every? str/blank? %))
       (mapv (fn [lines]
               (if (some #(re-find #"^[ \t]+\S" %) lines)
                 (str/join "\n" lines)
                 (str/join " " (map str/trim lines)))))))

(defn reflow
  "Soft-wrap a single-paragraph `|`-margin block into one string.

  The single-item companion to `fill` for a lone prose value; item and verbatim
  semantics do not apply — every barred line is trimmed and space-joined.
  Throws when no line carries a bar, like `fill`."
  [block]
  (->> (barred-lines block)
       (remove str/blank?)
       (map str/trim)
       (str/join " ")))

(def ^:private placeholder-pattern
  #"\{([A-Za-z][A-Za-z0-9_-]*)(?::([^{}]+))?\}")

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

(defn- interpolate
  "Replace named placeholders in `text` from `scope`."
  [text scope template]
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

  Drops whitespace-only boundary lines, removes the first content line's leading
  spaces from every nonblank line, then preserves the remaining Markdown exactly.
  `{name}` renders a value from keyword or string key `name` in `scope`; `{name:json}`
  renders compact JSON. Throws for non-map scope, tabs in indentation, content less
  indented than the first line, missing values, and unsupported renderers."
  [template scope]
  (when-not (string? template)
    (throw (ex-info "prose: template must be a string" {:template template})))
  (when-not (map? scope)
    (throw (ex-info "prose: scope must be a map" {:scope scope})))
  (interpolate (dedent template) scope template))
