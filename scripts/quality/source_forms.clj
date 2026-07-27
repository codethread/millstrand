(ns quality.source-forms
  "Shared source reading for the quality.conventions-check slices.

  Slices that judge authored source rather than clj-kondo analysis all
  need the same read: the full source reader surface (auto-resolved
  keywords, syntax quote, tagged literals) with evaluation off, so a
  namespace that would not load still lints. Kept clj-kondo-free so the
  slices that use it load on the test classpath."
  (:require [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]))

(defn read-all
  "Read every top-level form in `file`.

  Collection forms carry the reader's `:line` metadata; nothing is
  evaluated, and aliases and tagged literals are read rather than
  resolved truthfully."
  [^java.io.File file]
  (let [rdr (reader-types/indexing-push-back-reader (slurp file) 1 (.getPath file))
        opts {:eof ::eof :read-cond :allow :features #{:clj}}]
    (binding [*ns* (the-ns 'user)
              reader/*read-eval* false
              ;; Aliased ::kw/name forms only need to read, not resolve
              ;; truthfully; map every alias to a throwaway namespace.
              reader/*alias-map* (fn [alias] (symbol (str "quality.source-forms." alias)))
              reader/*default-data-reader-fn* (fn [_tag value] value)]
      (loop [forms []]
        (let [form (reader/read opts rdr)]
          (if (= ::eof form)
            forms
            (recur (conj forms form))))))))
