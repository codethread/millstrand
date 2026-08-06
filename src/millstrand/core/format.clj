(ns millstrand.core.format
  "Implementation of `|`-margin doc-block reflowing shared across tiers.

  Long prose authored in source (op payloads, `about` surfaces, config rule
  descriptions) reads best as one `|`-margin string: the bar marks column 0,
  plain newlines soft-wrap, a bare `|` line separates items, and indentation
  past the bar is preserved verbatim for command samples and other intentional
  layout. This is the single implementation; the blessed consumer surface is
  `millstrand.api.format.alpha`."
  (:require [clojure.string :as str]))

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
