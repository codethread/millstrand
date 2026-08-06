(ns millstrand.api.runtime.internal.spools-edn
  "Structural read/edit plumbing for a runtime's primary `spools.edn`.

  The editor rewrites only the top-level `:spools` map span, so header
  comments and any other top-level text survive an edit, and every write is
  an atomic same-directory rename. The alpha module's `upsert-spool-entry!`
  and `remove-spool-entry!` compose these steps and own the seam's contract
  (SPEC-004.C39c)."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [millstrand.core.weaver.spool-sync :as spool-sync])
  (:import [java.io PushbackReader StringReader]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- skip-comment [content offset]
  (let [newline (.indexOf ^String content "\n" (int offset))]
    (if (neg? newline) (count content) (inc newline))))

(defn- skip-trivia [content offset]
  (loop [offset offset]
    (if (< offset (count content))
      (let [ch (.charAt ^String content offset)]
        (cond
          (or (Character/isWhitespace ch) (= \, ch)) (recur (inc offset))
          (= \; ch) (recur (skip-comment content offset))
          :else offset))
      offset)))

(defn- scan-spools-map-span
  "Return the source span of the top-level `:spools` map."
  [content]
  (let [length (count content)]
    (loop [offset 0
           stack []
           string? false
           escaped? false
           comment? false]
      (when (< offset length)
        (let [ch (.charAt ^String content offset)]
          (cond
            comment?
            (recur (inc offset) stack false false (not= \newline ch))

            string?
            (cond
              escaped? (recur (inc offset) stack true false false)
              (= \\ ch) (recur (inc offset) stack true true false)
              (= \" ch) (recur (inc offset) stack false false false)
              :else (recur (inc offset) stack true false false))

            (= \; ch)
            (recur (inc offset) stack false false true)

            (= \" ch)
            (recur (inc offset) stack true false false)

            (#{\{ \[ \(} ch)
            (recur (inc offset) (conj stack ch) false false false)

            (#{\} \] \)} ch)
            (recur (inc offset) (pop stack) false false false)

            (and (= [\{] stack)
                 (= \: ch)
                 (str/starts-with? (subs content offset) ":spools")
                 (let [end (+ offset (count ":spools"))]
                   (or (= end length)
                       (let [next (.charAt ^String content end)]
                         (or (Character/isWhitespace next)
                             (#{\, \{ \[ \( \} \] \)} next))))))
            (let [start (skip-trivia content (+ offset (count ":spools")))
                  open (.indexOf ^String content "{" (int start))
                  prefix (when-not (neg? open) (subs content start open))]
              (when-not (and (< start length)
                             (not (neg? open))
                             (or (empty? prefix)
                                 (re-matches #"#:[^\s,{}\[\]()]+" prefix)))
                (throw (ex-info "spools.edn :spools value must be a map form"
                                {:offset start})))
              (loop [cursor (inc open)
                     depth 1
                     string? false
                     escaped? false
                     comment? false]
                (when-not (< cursor length)
                  (throw (ex-info "spools.edn :spools map is unterminated" {:offset start})))
                (let [current (.charAt ^String content cursor)]
                  (cond
                    comment? (recur (inc cursor) depth false false (not= \newline current))
                    string? (cond
                              escaped? (recur (inc cursor) depth true false false)
                              (= \\ current) (recur (inc cursor) depth true true false)
                              (= \" current) (recur (inc cursor) depth false false false)
                              :else (recur (inc cursor) depth true false false))
                    (= \; current) (recur (inc cursor) depth false false true)
                    (= \" current) (recur (inc cursor) depth true false false)
                    (= \{ current) (recur (inc cursor) (inc depth) false false false)
                    (= \} current) (if (= 1 depth)
                                     [start (inc cursor)]
                                     (recur (inc cursor) (dec depth) false false false))
                    :else (recur (inc cursor) depth false false false)))))

            :else
            (recur (inc offset) stack false false false)))))))

(defn read-primary
  "Read the primary `spools.edn` file into `{:content :exists?}`.

  A missing file reads as `{:content nil :exists? false}`; an unreadable one
  throws with the file path in ex-data."
  [file]
  (if (.exists ^java.io.File file)
    (try
      {:content (slurp file)
       :exists? true}
      (catch java.io.IOException error
        (throw (ex-info "spools.edn is malformed or unreadable"
                        {:kind :shared :file (.getPath ^java.io.File file)}
                        error))))
    {:content nil :exists? false}))

(defn parse-primary
  "Parse a `read-primary` result into the config map.

  A missing file parses as `{:spools {}}`. Malformed EDN and trailing content
  after the first form both throw with the file path in ex-data."
  [{:keys [content exists?]} file]
  (if exists?
    (try
      (let [eof ::eof
            reader (PushbackReader. (StringReader. content))
            config (edn/read reader)
            remainder (slurp reader)]
        (try
          (when-not (= eof (edn/read {:eof eof} (PushbackReader. (StringReader. remainder))))
            (throw (ex-info "spools.edn contains trailing content"
                            {:trailing-content (str/trim remainder)})))
          config
          (catch RuntimeException error
            (throw (ex-info (str "spools.edn contains malformed trailing content: "
                                 (ex-message error))
                            (merge {:trailing-content (str/trim remainder)}
                                   (ex-data error))
                            error)))))
      (catch RuntimeException error
        (throw (ex-info (str "spools.edn is malformed or unreadable: " (ex-message error))
                        (merge {:kind :shared :file (.getPath ^java.io.File file)}
                               (ex-data error))
                        error))))
    {:spools {}}))

(defn- render-spools-map [spools]
  (binding [*print-namespace-maps* false]
    (str/trimr (with-out-str (pp/pprint spools)))))

(defn- replace-spools-map [content spools]
  (let [[start end] (scan-spools-map-span content)]
    (when-not start
      (throw (ex-info "spools.edn requires :spools map" {})))
    (str (subs content 0 start) (render-spools-map spools) (subs content end))))

(defn- atomic-write! [^java.io.File file content]
  (let [parent (.toPath (.getParentFile file))
        tmp (Files/createTempFile parent ".spools.edn-" ".tmp" (make-array FileAttribute 0))]
    (try
      (spit (.toFile tmp) content)
      (Files/move tmp (.toPath file)
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (Files/deleteIfExists tmp)))))

(defn write-primary!
  "Validate `config` through sync's stage-1 contract, then write it atomically.

  An existing file keeps everything outside its `:spools` map untouched; a new
  file is rendered whole. `original` is the `read-primary` result the edit was
  computed from."
  [file original config]
  (spool-sync/validate-shared-spools-config! file config)
  (let [content (if (:exists? original)
                  (replace-spools-map (:content original) (:spools config))
                  (str (render-spools-map config) "\n"))]
    (atomic-write! file content)))
