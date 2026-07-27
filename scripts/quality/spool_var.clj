(ns quality.spool-var
  "The spool-var slice of quality.conventions-check: PROP-Dsp-001.G6a.

  Under the def-spool convention a public var named `spool` in a
  module-loadable namespace *is* the module declaration, always (G5's
  reservation contract). This check is the guard against incidental
  shadowing inside this repo (Q2): it rejects a public `spool` var whose
  authored value does not match the `::spool` shape — a map whose keys are
  a non-empty subset of `:contribute`/`:reconcile`, each an entry-point
  symbol (`:ns` was dropped in the rename, G2).

  It is a structural repository guard, not a second runtime contract and
  not protection for external consumers: it reads the authored literal and
  never resolves a symbol or derefs a var. The `::spool` spec in
  `skein.api.spool.alpha` and the runtime's G5 validation stay
  authoritative everywhere (G6a). Entry-point values are authored as
  quoted symbols (`'my.ns/fn`), so the read form wraps them as
  `(quote sym)`; the shape check unwraps that to recover the symbol the
  runtime will see.

  Scope is the module-loadable roots — shipped spools under
  `spools/*/src` and the workspace's `.skein` config (local spools and
  `:file` modules). Engine `src/` and `test/` namespaces are never
  activated as modules, so a `spool` var there is unaffected and out of
  scope; private `spool` vars are ignored everywhere. Kept clj-kondo-free
  so the findings logic loads on the test classpath; file reading is the
  shared `quality.source-forms` surface conventions-check already scans."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [quality.source-forms :as source-forms]
            [skein.api.spool.alpha :as spool-api]))

(def ^:private entry-point-keys #{:contribute :reconcile})
(def ^:private public-var-forms #{'def 'defonce 'defn 'defmacro})

(def ^:private spools-root "spools")
(def ^:private config-root ".skein")

(defn- authored-symbol
  "Return the symbol an authored entry-point value names. Values are
  quoted symbols, so `'my.ns/fn` reads as `(quote my.ns/fn)`; anything
  else (fn literal, string, bare unquoted or computed form) returns nil."
  [form]
  (when (and (seq? form) (= 'quote (first form)) (= 2 (count form))
             (symbol? (second form)))
    (second form)))

(defn spool-value-problem
  "Return a short reason string when `value` — the literal read from a
    `(def spool …)` site — does not match the `::spool` shape, or nil when
    it conforms. Structural only: quoted source symbols are normalized to the
    values evaluation produces, then the authoritative runtime `::spool` spec
    decides validity; symbols are never resolved and vars are never deref'd
    (G6a)."
  [value]
  (when-not (map? value)
    (throw (ex-info "spool-value-problem expects a non-map guard upstream" {})))
  (let [normalized (into {} (map (fn [[field form]]
                                   [field (authored-symbol form)]))
                         value)]
    (when-not (s/valid? ::spool-api/spool normalized)
      (let [unknown (remove entry-point-keys (keys value))
            present (filter #(contains? value %) entry-point-keys)]
        (cond
          (seq unknown)
          (str "has unsupported key" (when (next unknown) "s") " "
               (str/join ", " (sort-by str unknown)))

          (empty? present)
          "declares neither :contribute nor :reconcile"

          :else
          (when-let [bad (first (remove #(authored-symbol (get value %)) present))]
            (str "entry point " bad
                 " must be a quoted symbol (e.g. `'my.ns/fn`)")))))))

(defn- value-problem
  "Reason string for any authored `value`, mapping a non-map to its own
  message before delegating the map cases to `spool-value-problem`."
  [value]
  (if (map? value)
    (spool-value-problem value)
    "authored value is not a map"))

(defn- declaration-site
  "Return a site when `form` declares the public var name `spool`.

  This only recognizes the var forms themselves. Traversal through evaluated
  positions belongs to `declaration-sites`; quoted data and deferred function
  bodies stay out of scope."
  [form]
  (when (and (seq? form)
             (contains? public-var-forms (first form))
             (symbol? (second form))
             (= "spool" (name (second form))))
    (let [form-kind (first form)
          tail (drop 2 form)
          [value has-value?] (cond
                               (not= 'def form-kind) [nil false]
                               (and (= 2 (count tail)) (string? (first tail)))
                               [(second tail) true]
                               (seq tail) [(first tail) true]
                               :else [nil false])]
      {:line (:line (meta form))
       :form-kind form-kind
       :private? (boolean (:private (meta (second form))))
       :value value
       :has-value? has-value?})))

;; Keep this evaluated/deferred traversal policy aligned with
;; skein.core.weaver.module-refresh/namespaces-in-form. This scanner is only the
;; structural pre-merge guard; runtime validation remains authoritative.
(def ^:private deferred-body-forms
  '#{comment defn defmacro fn fn* quote var})

(defn- declaration-sites
  "Return declaration sites in one reader form.

  Descend through evaluated positions, including ordinary call arguments,
  collection literals, and unrelated var initializers. Quoted data and
  deferred function/macro bodies are not evaluated while a module loads, so
  stop at those forms. This remains a structural repository check rather than
  a macro-expansion or runtime-resolution pass."
  [form]
  (if-let [site (declaration-site form)]
    [site]
    (cond
      (seq? form)
      (if (contains? deferred-body-forms (first form))
        []
        (mapcat declaration-sites (rest form)))

      (coll? form)
      (mapcat declaration-sites form)

      :else [])))

(defn def-spool-sites
  "Return declaration sites for every public-var form named `spool` in a
  top-level executable context read from `file`.

  A valid site uses `def`; `defonce`, `defn`, and `defmacro` sites are
  returned so `findings` can reject them as malformed declarations. The
  var name must be exactly `spool`; `def-spool` and `spooler` are not it.
  Evaluated forms and collection literals are traversed recursively, while
  quotes and deferred function/macro bodies are not.
  A docstring form (`(def spool \"doc\" value)`) reports the value, not
  the docstring."
  [^java.io.File file]
  (into [] (mapcat declaration-sites) (source-forms/read-all file)))

(defn findings
  "Turn `sites` ({:filename :line :form-kind :private? :value :has-value?
  :read-error :read-error/class :read-error/data})
  into finding strings. Public sites whose authored value fails the shape
  check, or that carry no value at all, are findings; private sites are
  ignored, and a file the scanner could not read is itself a finding."
  [sites]
  (for [{:keys [filename line form-kind private? value has-value? read-error]
         :as site} sites
        :let [error-class (:read-error/class site)
              error-data (:read-error/data site)
              finding
              (cond
                read-error
                (str filename ": spool-var scan could not read file: " read-error
                     (when error-class (str " [" error-class "]"))
                     (when (seq error-data) (str " data=" (pr-str error-data))))

                private? nil

                (not= 'def form-kind)
                (str filename ":" line ": public `spool` var must be authored with `def`,"
                     " not `" form-kind "`; a module declaration must satisfy ::spool"
                     " (PROP-Dsp-001.G6a)")

                (not has-value?)
                (str filename ":" line ": public `spool` var has no value;"
                     " a module declaration must satisfy ::spool (PROP-Dsp-001.G6a)")

                :else
                (when-let [problem (value-problem value)]
                  (str filename ":" line ": public `spool` var " problem
                       "; a module declaration must satisfy ::spool"
                       " (PROP-Dsp-001.G6a)")))]
        :when finding]
    finding))

(defn- directory-files!
  "List `root`, failing loudly when it is missing, not a directory, unreadable,
  or otherwise cannot be enumerated."
  [root]
  (let [^java.io.File root (io/file root)
        files (.listFiles root)]
    (when (nil? files)
      (throw (ex-info "spool-var scan root could not be enumerated"
                      {:root (.getPath root)
                       :operation :list-module-loadable-roots
                       :expected :readable-directory})))
    files))

(defn- module-loadable-roots
  "Return the roots where a repository namespace can be activated as a
  module: every shipped spool's `src` plus the `.skein` workspace config."
  []
  (let [spool-dirs (directory-files! spools-root)
        _ (directory-files! config-root)]
    (conj (vec (for [^java.io.File dir (sort spool-dirs)
                     :when (and (.isDirectory dir) (.isDirectory (io/file dir "src")))]
                 (.getPath (io/file dir "src"))))
          config-root)))

(defn- scan
  "Read every `.clj` under the module-loadable roots into sites, tagging
  each with its file and surfacing an unreadable file as a `:read-error`
  site rather than aborting the scan."
  []
  (for [root (module-loadable-roots)
        ^java.io.File file (sort (file-seq (io/file root)))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))
        site (try
               (map #(assoc % :filename (.getPath file)) (def-spool-sites file))
               (catch Exception e
                 [{:filename (.getPath file)
                   :read-error (ex-message e)
                   :read-error/class (.getName (class e))
                   :read-error/data (ex-data e)}]))]
    site))

(defn check
  "Run `findings` over the live tree's module-loadable roots."
  []
  (findings (scan)))
