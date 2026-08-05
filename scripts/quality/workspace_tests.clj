(ns quality.workspace-tests
  "Keep repository workspace tests in their own namespace and directory."
  (:require [clojure.string :as str]))

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

(defn check
  "Return workspace-test namespace/path findings from clj-kondo `analysis`."
  [analysis]
  (findings analysis))
