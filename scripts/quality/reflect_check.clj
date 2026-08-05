(ns quality.reflect-check
  "Compile Skein namespaces with reflection warnings promoted to failure."
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

(defn -main [& _]
  (let [roots {"src" "skein"
               "spools/batteries/src" "skein/spools"
               "spools/chime/src" "skein/spools"
               "spools/cron/src" "skein/spools"
               "spools/workflow/src" "skein/spools"
               "spools/unsafe-text-search/src" "skein/spools"
               "spools/guild/src" "skein/spools"}
        namespaces (sort (mapcat (fn [[root subdir]]
                                   (namespaces-under root subdir))
                                 roots))
        compile-dir (.toFile (java.nio.file.Files/createTempDirectory "skein-reflect-check" (make-array java.nio.file.attribute.FileAttribute 0)))
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
