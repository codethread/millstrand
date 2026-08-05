(ns quality.workspace-tests
  "Keep repository workspace tests in their own namespace and directory.

  The namespace/path mapping keeps opted-in tests together. A separate source
  scan catches tests that have not opted in yet: a relative literal beneath
  `.skein/` names this checkout's workspace authoring and therefore belongs in this
  directory too."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [quality.source-forms :as source-forms]))

(def ^:private workspace-test-directory "test/skein/ct/")
(def ^:private workspace-test-namespace-prefix "skein.ct.")

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
       (str/starts-with? value ".skein/")))

(defn reference-sites
  "Return `{:line :path}` sites for literal paths into this checkout's
  `.skein` directory in `form`."
  [form line]
  (cond
    (checked-in-workspace-path? form)
    [{:line line :path form}]

    (coll? form)
    (let [line (or (:line (meta form)) line)]
      (mapcat #(reference-sites % line) (seq form)))))

(defn findings
  "Return findings when the `skein.ct.*` namespace family and
  `test/skein/ct/` directory do not map to each other."
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

(defn reference-findings
  "Return findings for checked-in `.skein` path sites outside the workspace
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
  "Return workspace-test boundary findings from clj-kondo `analysis` and the
  Clojure sources under `test-root`."
  [analysis test-root]
  (concat (findings analysis)
          (reference-findings (scan-references test-root))))
