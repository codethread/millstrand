(ns quality.reflect-check
  "Compile Millstrand namespaces with reflection warnings promoted to failure."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- clj-file->ns [root file]
  (let [root-path (.toPath (io/file root))
        file-path (.toPath file)
        rel (str (.relativize root-path file-path))]
    (-> rel
        (str/replace #"\.clj$" "")
        (str/replace #"[/\\]" ".")
        (str/replace "_" "-")
        symbol)))

(defn- namespaces-under [root subdir]
  (let [dir (io/file root subdir)]
    ;; A missing configured root must fail the gate, not silently shrink the
    ;; compiled namespace set to a subset that still exits 0.
    (when-not (.isDirectory dir)
      (binding [*out* *err*]
        (println "reflect-check: configured source root does not exist:" (str dir)))
      (System/exit 1))
    (->> (file-seq dir)
         (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))
         (map #(clj-file->ns root %)))))

(defn- resource-source-root [resource-path]
  (when-let [url (io/resource resource-path)]
    (when (= "file" (.getProtocol url))
      (some (fn [^java.io.File candidate]
              (when (= "src" (.getName candidate))
                candidate))
            (take-while some?
                        (iterate #(.getParentFile ^java.io.File %)
                                 (.getParentFile (io/file (.toURI url)))))))))

(defn- millhouse-source-roots []
  (keep resource-source-root
        ["millhouse/spools/workflow.clj"
         "millhouse/spools/chime.clj"
         "millhouse/spools/cron.clj"
         "millhouse/spools/executors/code.clj"
         "millhouse/spools/executors/shell.clj"]))

(defn -main [& _]
  (let [roots {"src" "millstrand"
               "spools/batteries/src" "millstrand/spools"
               "spools/chime/src" "millstrand/spools"
               "spools/cron/src" "millstrand/spools"
               "spools/workflow/src" "millstrand/spools"
               "spools/unsafe-text-search/src" "millstrand/spools"
               "examples/guild/src" "skein/examples"}
        namespaces (sort (concat
                          (mapcat (fn [[root subdir]]
                                    (namespaces-under root subdir))
                                  roots)
                          (mapcat #(namespaces-under % "")
                                  (distinct (millhouse-source-roots)))))
        compile-dir (.toFile (java.nio.file.Files/createTempDirectory "millstrand-reflect-check" (make-array java.nio.file.attribute.FileAttribute 0)))
        warnings (atom [])
        original-err *err*
        warning-err (proxy [java.io.Writer] []
                      (write
                        ([s]
                         (when (str/includes? s "Reflection warning")
                           (swap! warnings conj s))
                         (.write original-err s))
                        ([s off len]
                         (let [chunk (subs s off (+ off len))]
                           (when (str/includes? chunk "Reflection warning")
                             (swap! warnings conj chunk))
                           (.write original-err s off len))))
                      (flush [] (.flush original-err))
                      (close [] nil))]
    (try
      (binding [*warn-on-reflection* true
                *compile-path* (.getAbsolutePath compile-dir)
                *err* warning-err]
        (doseq [ns-sym namespaces]
          (require ns-sym :reload)
          (compile ns-sym)))
      (finally
        (doseq [file (reverse (file-seq compile-dir))]
          (io/delete-file file true))))
    (when (seq @warnings)
      (binding [*out* *err*]
        (println "Reflection warnings detected:" (count @warnings)))
      (System/exit 1))))
