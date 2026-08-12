(ns quality.workspace-tests
  "Keep repository workspace tests in their own namespace and directory.

  The namespace/path mapping keeps opted-in tests together. A separate source
  scan catches tests that have not opted in yet: a relative literal beneath
  `.millstrand/` names this checkout's workspace authoring and therefore belongs in this
  directory too."
  (:require [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [quality.source-forms :as source-forms]))

(def ^:private workspace-test-directory "test/clojure/millstrand/ct/")
(def ^:private workspace-test-namespace-prefix "millstrand.ct.")

(s/def ::filename string?)
(s/def ::name symbol?)
(s/def ::row pos-int?)
(s/def ::namespace-definition (s/keys :req-un [::filename ::name ::row]))
(s/def ::namespace-definitions (s/coll-of ::namespace-definition))
(s/def ::analysis (s/keys :req-un [::namespace-definitions]))
(s/def ::test-root (s/and string? (complement str/blank?)))
(s/def ::finding string?)
(s/def ::findings (s/coll-of ::finding :kind vector?))

(defn- require-valid!
  [spec value label]
  (when-not (s/valid? spec value)
    (throw (ex-info (str label " does not conform to " spec)
                    {:spec spec :value value :explain (s/explain-data spec value)})))
  value)

(defn- normalize-path [filename]
  (some-> filename (str/replace "\\" "/")))

(defn- workspace-test-file?
  [filename]
  (let [path (normalize-path filename)]
    (boolean
     (and path
          (or (str/starts-with? path workspace-test-directory)
              (str/includes? path (str "/" workspace-test-directory)))))))

(defn- workspace-test-namespace?
  [ns-name]
  (str/starts-with? (str ns-name) workspace-test-namespace-prefix))

(defn- checked-in-workspace-path?
  [value]
  (and (string? value)
       (str/starts-with? value ".millstrand/")))

(defn- reference-sites
  "Return `{:line :path}` sites for literal paths into this checkout's
  `.millstrand` directory in `form`."
  [form line]
  (cond
    (checked-in-workspace-path? form)
    [{:line line :path form}]

    (coll? form)
    (let [line (or (:line (meta form)) line)]
      (mapcat #(reference-sites % line) (seq form)))))

(defn- namespace-findings
  "Return findings when the `millstrand.ct.*` namespace family and
  `test/clojure/millstrand/ct/` directory do not map to each other."
  [{:keys [namespace-definitions]}]
  (mapcat
   (fn [{:keys [filename name row]}]
     (let [workspace-file? (workspace-test-file? filename)
           workspace-ns? (workspace-test-namespace? name)]
       (cond
         (and workspace-ns? (not workspace-file?))
         [(str filename ":" row ": workspace test namespace `" name
               "` must be defined under `" workspace-test-directory "`")]

         (and workspace-file? (not workspace-ns?))
         [(str filename ":" row ": tests under `" workspace-test-directory
               "` must declare a `" workspace-test-namespace-prefix "*` namespace")]

         :else [])))
   namespace-definitions))

(defn- reference-findings
  "Return findings for checked-in `.millstrand` path sites outside the workspace
  test directory, including source read failures."
  [sites]
  (for [{:keys [filename line path read-error]} sites]
    (if read-error
      (str filename ": workspace-test scan could not read file: " read-error)
      (str filename ":" line ": direct workspace path `" path
           "` belongs under `" workspace-test-directory "`"))))

(defn- scan-references
  [test-root]
  (for [^java.io.File file (sort (file-seq (io/file test-root)))
        :when (and (.isFile file)
                   (str/ends-with? (.getName file) ".clj")
                   (not (workspace-test-file? (.getPath file))))
        site (try
               (map #(assoc % :filename (.getPath file))
                    (reference-sites (source-forms/read-all file) 1))
               (catch Exception e
                 [{:filename (.getPath file) :read-error (ex-message e)}]))]
    site))

(defn check
  "Return `::findings` for clj-kondo `::analysis` and the Clojure sources
  beneath `::test-root`.

  Fails loudly when either input is malformed or the test root is not a
  directory."
  [analysis test-root]
  (require-valid! ::analysis analysis "workspace-test analysis")
  (require-valid! ::test-root test-root "workspace-test root")
  (when-not (.isDirectory (io/file test-root))
    (throw (ex-info "workspace-test root is not a directory"
                    {:test-root test-root :expected :directory})))
  (let [result (vec (concat (namespace-findings analysis)
                            (reference-findings (scan-references test-root))))]
    (require-valid! ::findings result "workspace-test findings")))
