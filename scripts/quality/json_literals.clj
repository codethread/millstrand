(ns quality.json-literals
  "The json-literals slice of quality.conventions-check: JSON hand-written
  as an escaped string literal.

  Escaped JSON is quote soup. The backslashes hide the shape, a mistyped
  key survives review because nothing reads the string, and the reader
  gets no help from their editor. Where `clojure.data.json/write-str`
  would produce the same text, the Clojure data is the better source:
  author `(json/write-str {:title \"x\"})` and let the serializer escape.

  The rule is deliberately narrow, so every finding converts without
  judgement. A literal is a finding only when all three hold:

  - it parses as a JSON object or array and contains a quoted string, so
    JSONL, fragments, and deliberately malformed fixtures are out of
    scope;
  - re-serializing it reproduces the source text character for
    character, so a literal whose spacing, key order, or trailing
    newline carries meaning is out of scope;
  - it is not a direct argument of `=` or `not=`, where the exact text
    is what the comparison asserts and rebuilding it from data would
    assert nothing.

  Kept clj-kondo-free so the findings logic loads on the test classpath;
  source reading is shared with the other authored-source slices."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [quality.source-forms :as source-forms]))

(def ^:private comparison-heads
  ;; A literal compared with these is the text under test.
  '#{= not=})

(def ^:private excerpt-limit 48)

(defn reproducible-json?
  "True when `s` is a JSON object or array carrying a quoted string that
  `json/write-str` re-serializes back to `s` character for character."
  [s]
  (and (or (str/starts-with? s "{") (str/starts-with? s "["))
       (str/includes? s "\"")
       (try
         (= s (json/write-str (json/read-str s)))
         (catch Exception _ false))))

(defn literal-sites
  "Return `{:line :text}` for every reproducible JSON literal in `form`.

  Strings carry no reader metadata, so `line` seeds the reported line and
  each enclosing collection that has one refines it. Direct string
  arguments of a comparison head are skipped; the rest of that form is
  still scanned, so an input literal nested inside an assertion is
  still found."
  [form line]
  (cond
    (string? form)
    (when (reproducible-json? form) [{:line line :text form}])

    (coll? form)
    (let [line (or (:line (meta form)) line)
          compared? (and (seq? form) (contains? comparison-heads (first form)))]
      (mapcat (fn [child]
                (when-not (and compared? (string? child))
                  (literal-sites child line)))
              (seq form)))))

(defn- excerpt
  "Return `text` shortened to `excerpt-limit` characters for a finding."
  [text]
  (if (<= (count text) excerpt-limit)
    text
    (str (subs text 0 excerpt-limit) "...")))

(defn findings
  "Turn `sites` (`{:filename :line :text}`, or `{:filename :read-error}`
  for a file the scanner could not read) into finding strings."
  [sites]
  (for [{:keys [filename line text read-error]} sites]
    (if read-error
      (str filename ": json-literals scan could not read file: " read-error)
      (str filename ":" line ": JSON hand-escaped as a string literal (" (excerpt text)
           "); `json/write-str` reproduces this text, so build it from Clojure data"))))

(defn- scan
  "Read every `.clj` under `roots` into sites, surfacing an unreadable
  file as its own site rather than aborting the scan."
  [roots]
  (for [root roots
        ^java.io.File file (sort (file-seq (io/file root)))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))
        site (try
               (map #(assoc % :filename (.getPath file))
                    (literal-sites (source-forms/read-all file) 1))
               (catch Exception e
                 [{:filename (.getPath file) :read-error (ex-message e)}]))]
    site))

(defn check
  "Run `findings` over the live tree's `roots`."
  [roots]
  (findings (scan roots)))
