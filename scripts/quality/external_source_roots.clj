(ns quality.external-source-roots
  "Resolve required external source roots for repository quality gates."
  (:require [clojure.java.io :as io]))

(def ^:private millhouse-resources
  ["millhouse/spools/workflow.clj"
   "millhouse/spools/chime.clj"
   "millhouse/spools/cron.clj"
   "millhouse/spools/executors/code.clj"
   "millhouse/spools/executors/shell.clj"])

(defn- required-source-root
  [resource-path]
  (let [^java.net.URL url
        (or (io/resource resource-path)
            (throw (ex-info (str "Required Millhouse source resource " resource-path
                                 " is missing; expected a file URL beneath an ancestor directory named src")
                            {:resource resource-path
                             :allowed-source-root "ancestor directory named src"})))]
    (when-not (= "file" (.getProtocol url))
      (throw (ex-info (str "Required Millhouse source resource " resource-path
                           " is not directory-backed; expected a file URL beneath an ancestor directory named src")
                      {:resource resource-path
                       :url (str url)
                       :allowed-source-root "file URL beneath an ancestor directory named src"})))
    (or (some (fn [^java.io.File candidate]
                (when (= "src" (.getName candidate))
                  candidate))
              (take-while some?
                          (iterate #(.getParentFile ^java.io.File %)
                                   (.getParentFile (io/file (.toURI url))))))
        (throw (ex-info (str "Required Millhouse source resource " resource-path
                             " has no source-root ancestor; expected an ancestor directory named src")
                        {:resource resource-path
                         :url (str url)
                         :allowed-source-root "ancestor directory named src"})))))

(defn millhouse-source-roots
  "Return every required, distinct, directory-backed Millhouse source root.

  Missing resources, non-file resources, and resources outside a `src` root
  fail rather than shrinking quality coverage."
  []
  (->> millhouse-resources
       (map required-source-root)
       distinct
       vec))
