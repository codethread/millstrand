(ns quality.spool-var
  "The spool-var slice of quality.conventions-check.

  A public `spool` var in a module-loadable namespace is a removed grammar
  and is always a finding. This structural repository guard reads authored
  source without resolving symbols or dereferencing vars.

  Scope is the module-loadable roots — shipped spools under
  `spools/*/src` and the workspace's `.skein` config (local spools and
  `:file` modules). Engine `src/` and `test/` namespaces are never
  activated as modules, so a `spool` var there is unaffected and out of
  scope; private `spool` vars are ignored everywhere. Kept clj-kondo-free
  so the findings logic loads on the test classpath; file reading is the
  shared `quality.source-forms` surface conventions-check already scans."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [quality.source-forms :as source-forms]
            [millstrand.api.format.alpha :as format-alpha]))
(def ^:private public-var-forms #{'def 'defonce 'defn 'defmacro})

(def ^:private spools-root "spools")
(def ^:private config-root ".skein")

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
;; millstrand.core.weaver.module-refresh/namespaces-in-form. This scanner is only the
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
  into finding strings. Every public site is a finding because the module
  entry-point convention has been removed. Private sites are ignored, and a
  file the scanner could not read is itself a finding."
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

                :else
                (str filename ":" line ": "
                     (format-alpha/reflow
                      "|Public `spool` var uses the removed module entry-point
                       |convention; use contribution and lifecycle authoring
                       |forms")))]
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
