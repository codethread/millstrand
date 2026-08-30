(ns millstrand.core.weaver.basis
  "Compose and launch one immutable tools.deps basis for a Weaver generation."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.core.specs])
  (:import [clojure.lang DynamicClassLoader]
           [java.io PushbackReader]
           [java.math BigDecimal BigInteger]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private reserved-lib 'io.millstrand/millstrand)
(def ^:private selected-aliases [:millstrand/weaver :millstrand/local])
(def ^:private dependency-keys
  #{:deps :replace-deps :extra-deps :override-deps :default-deps})
(def ^:private derived-path-keys
  #{:paths :extra-paths :replace-paths :classpath-overrides
    :local/root :deps/root :git/dir})

(defn ^:dynamic *create-basis*
  "Function used to invoke `clojure.tools.deps/create-basis`."
  [options]
  ((requiring-resolve 'clojure.tools.deps/create-basis) options))

(defn- diagnostic-ex
  [message stage source-path cause coordinate]
  (ex-info message
           {:status :invalid-dependency-config
            :stage stage
            :source-path source-path
            :message message
            :cause cause
            :coordinate coordinate}))

(defn dependency-diagnostic
  "Return the closed dependency diagnostic carried by `throwable`, or nil.

  The returned shape is owned by `:millstrand.core.specs/dependency-diagnostic`
  (DELTA-DnsRuntime-001.CC5)."
  [throwable]
  (let [diagnostic (select-keys (ex-data throwable)
                                [:status :stage :source-path :message :cause
                                 :coordinate])]
    (when (s/valid? :millstrand.core.specs/dependency-diagnostic diagnostic)
      diagnostic)))

(defn- read-deps!
  [path required?]
  (let [file (io/file path)
        canonical-path (.getPath (.getCanonicalFile file))]
    (cond
      (not (.exists file))
      (when required?
        (let [message (str "required dependency file does not exist: "
                           canonical-path)]
          (throw (diagnostic-ex message :deps-read canonical-path message nil))))

      (not (.isFile file))
      (let [message (str "dependency path is not a readable regular file: "
                         canonical-path)]
        (throw (diagnostic-ex message :deps-read canonical-path message nil)))

      (not (.canRead file))
      (let [message (str "dependency file is not readable: " canonical-path)]
        (throw (diagnostic-ex message :deps-read canonical-path message nil)))

      :else
      (try
        (with-open [reader (PushbackReader. (io/reader file))]
          (let [value (edn/read {:eof ::eof} reader)
                trailing (edn/read {:eof ::eof} reader)]
            (when (= ::eof value)
              (throw (ex-info "dependency file is empty" {})))
            (when-not (= ::eof trailing)
              (throw (ex-info "dependency file contains trailing forms" {})))
            (when-not (map? value)
              (throw (ex-info "dependency file must contain one EDN map" {})))
            {:path canonical-path :deps value}))
        (catch Throwable throwable
          (if (dependency-diagnostic throwable)
            (throw throwable)
            (let [cause (or (ex-message throwable) (pr-str throwable))
                  message (str "cannot read dependency file " canonical-path)]
              (throw (diagnostic-ex message :deps-read canonical-path cause nil)))))))))

(defn- selected-aliases-for
  [sources]
  (let [aliases (apply merge (map #(get-in % [:deps :aliases] {}) sources))]
    (filterv #(contains? aliases %) selected-aliases)))

(defn- reserved-coordinate
  [deps-map aliases]
  (some (fn [[dependency-key dependencies]]
          (when (map? dependencies)
            (when-let [value (get dependencies reserved-lib)]
              {:value value :dependency-key dependency-key})))
        (concat
         (select-keys deps-map dependency-keys)
         (mapcat #(select-keys (get-in deps-map [:aliases %] {}) dependency-keys)
                 aliases))))

(defn- reject-reserved!
  [sources aliases]
  (doseq [{:keys [path deps]} sources]
    (when-let [{:keys [value]} (reserved-coordinate deps aliases)]
      (let [message "reserved dependency io.millstrand/millstrand is supplied by Mill"]
        (throw (diagnostic-ex message :deps-read path message
                              {:lib reserved-lib :value value})))))
  nil)

(declare canonical-edn)

(defn- utf8-compare
  [left right]
  (let [^bytes left-bytes (.getBytes ^String left StandardCharsets/UTF_8)
        ^bytes right-bytes (.getBytes ^String right StandardCharsets/UTF_8)
        length (min (alength left-bytes) (alength right-bytes))]
    (loop [index 0]
      (if (= index length)
        (compare (alength left-bytes) (alength right-bytes))
        (let [comparison (compare (bit-and 0xff (aget left-bytes index))
                                  (bit-and 0xff (aget right-bytes index)))]
          (if (zero? comparison)
            (recur (inc index))
            comparison))))))

(defn- canonical-items
  [values]
  (sort utf8-compare (map canonical-edn values)))

(defn canonical-edn
  "Encode `value` as deterministic, whitespace-minimal EDN.

  Map entries and set members use UTF-8 byte ordering of their canonical EDN.
  Values outside the untagged EDN domain fail loudly
  (DELTA-DnsRuntime-001.CC6)."
  [value]
  (cond
    (map? value)
    (str "{" (str/join " "
                       (mapcat (fn [[key item]]
                                 [(canonical-edn key) (canonical-edn item)])
                               (sort-by (comp canonical-edn key) utf8-compare value))) "}")

    (set? value)
    (str "#{" (str/join " " (canonical-items value)) "}")

    (vector? value)
    (str "[" (str/join " " (map canonical-edn value)) "]")

    (list? value)
    (str "(" (str/join " " (map canonical-edn value)) ")")

    (or (nil? value) (boolean? value) (string? value) (char? value)
        (keyword? value) (symbol? value) (integer? value) (ratio? value)
        (float? value) (double? value) (instance? BigDecimal value)
        (instance? BigInteger value))
    (pr-str value)

    :else
    (throw (ex-info "value is outside the canonical EDN domain"
                    {:value value :class (some-> value class str)}))))

(defn basis-fingerprint
  "Return the SHA-256 identity of one canonical generation-basis value."
  [fingerprint-value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String (canonical-edn fingerprint-value)
                                   StandardCharsets/UTF_8))]
    (str "sha256:" (str/join (map #(format "%02x" (bit-and 0xff %)) digest)))))

(defn- without-derived-paths
  [value]
  (cond
    (map? value)
    (into (empty value)
          (keep (fn [[key item]]
                  (when-not (contains? derived-path-keys key)
                    [key (without-derived-paths item)])))
          value)

    (vector? value)
    (mapv without-derived-paths value)

    (list? value)
    (apply list (map without-derived-paths value))

    (set? value)
    (set (map without-derived-paths value))

    (sequential? value)
    (doall (map without-derived-paths value))

    :else value))

(defn- fingerprint-value
  [sources aliases basis]
  {:sources (mapv #(select-keys % [:kind :deps]) sources)
   :aliases aliases
   :reserved-runtime {:lib reserved-lib :identity :mill-supplied}
   :resolved-libs (without-derived-paths (:libs basis))
   :argmap (without-derived-paths (:argmap basis))})

(defn- source-path-for-coordinate
  [sources coordinate]
  (some (fn [{:keys [path deps]}]
          (when (some #(= coordinate %)
                      (mapcat vals
                              (keep #(when (map? %) %)
                                    (concat (vals (select-keys deps dependency-keys))
                                            (mapcat (comp vals #(select-keys % dependency-keys))
                                                    (vals (:aliases deps)))))))
            path))
        sources))

(defn- resolution-diagnostic
  [throwable sources]
  (let [data (ex-data throwable)
        lib (or (:lib data) (:dep data))
        value (or (:coord data) (:coordinate data))
        coordinate (when (and (symbol? lib) (some? value))
                     {:lib lib :value value})
        source-path (or (source-path-for-coordinate sources value)
                        (:path (first sources)))
        cause (or (ex-message throwable) (pr-str throwable))]
    (diagnostic-ex "cannot resolve Weaver dependency basis"
                   :deps-resolve source-path cause coordinate)))

(defn- generation-classloader
  [classpath-roots]
  (let [loader (DynamicClassLoader. (.getContextClassLoader
                                     (Thread/currentThread)))]
    (doseq [root classpath-roots]
      (.addURL loader (.toURL (.toURI (io/file root)))))
    loader))

(defn create-generation-basis
  "Compose and describe the immutable basis for one Weaver generation.

  `workspace` is the selected workspace directory. `runtime-coordinate` is the
  paired `io.millstrand/millstrand` coordinate supplied by Mill. The result is
  validated against `:millstrand.core.specs/generation-basis` before return
  (DELTA-DnsRuntime-001.CC2-CC8)."
  [workspace runtime-coordinate]
  (let [workspace-path (.getPath (.getCanonicalFile (io/file workspace)))
        project (read-deps! (str (io/file workspace-path "deps.edn")) true)
        extra (read-deps! (str (io/file workspace-path "deps.local.edn")) false)
        sources (cond-> [(assoc project :kind :project)]
                  extra (conj (assoc extra :kind :extra)))
        aliases (selected-aliases-for sources)
        reserved-deps {reserved-lib runtime-coordinate}]
    (reject-reserved! sources aliases)
    (try
      (let [basis (*create-basis*
                   {:dir workspace-path
                    :root :standard
                    :user nil
                    :project (:deps project)
                    :extra (:deps extra)
                    :aliases aliases
                    :args {:extra-deps reserved-deps}})
            basis-projection {:libs (:libs basis)
                              :classpath-roots (vec (:classpath-roots basis))
                              :argmap (:argmap basis)}
            result {:sources sources
                    :aliases aliases
                    :reserved-deps reserved-deps
                    :basis basis-projection
                    :fingerprint (basis-fingerprint
                                  (fingerprint-value sources aliases
                                                     basis-projection))
                    :classloader (generation-classloader
                                  (:classpath-roots basis-projection))}]
        (when-not (s/valid? :millstrand.core.specs/generation-basis result)
          (throw (ex-info "constructed generation basis violates its contract"
                          {:explain (s/explain-data
                                     :millstrand.core.specs/generation-basis
                                     result)})))
        result)
      (catch Throwable throwable
        (if (dependency-diagnostic throwable)
          (throw throwable)
          (throw (resolution-diagnostic throwable sources)))))))

(defn- take-launch-option
  [args option]
  (let [index (.indexOf ^java.util.List args option)]
    (when (neg? index)
      (throw (ex-info (str "missing required basis launch option " option)
                      {:args args :option option})))
    (when (= index (dec (count args)))
      (throw (ex-info (str option " requires a value")
                      {:args args :option option})))
    [(nth args (inc index))
     (into (subvec args 0 index) (subvec args (+ index 2)))]))

(defn- json-safe
  [value]
  (cond
    (symbol? value) (str value)
    (keyword? value) (name value)
    (map? value) (into {} (map (fn [[key item]]
                                 [(cond
                                    (keyword? key) (str/replace (name key) "-" "_")
                                    (symbol? key) (str key)
                                    :else key)
                                  (json-safe item)])) value)
    (coll? value) (mapv json-safe value)
    :else value))

(defn- write-diagnostic!
  [path diagnostic]
  (spit path (json/write-str (json-safe diagnostic))))

(defn -main
  "Build the selected-workspace basis and enter the Weaver runtime.

  Mill supplies `--millstrand-source`; all remaining arguments are relayed to
  `millstrand.core.weaver.runtime/start-with-generation-basis!`."
  [& argv]
  (let [[workspace args] (take-launch-option (vec argv) "--workspace")
        [source args] (take-launch-option args "--millstrand-source")
        [diagnostic-path runtime-args]
        (take-launch-option args "--dependency-diagnostic")]
    (try
      (let [generation-basis
            (create-generation-basis
             workspace
             {:local/root (.getPath (.getCanonicalFile (io/file source)))})
            thread (Thread/currentThread)]
        (.setContextClassLoader thread (:classloader generation-basis))
        ((requiring-resolve
          'millstrand.core.weaver.runtime/start-with-generation-basis!)
         generation-basis runtime-args))
      (catch Throwable throwable
        (when-let [diagnostic (dependency-diagnostic throwable)]
          (write-diagnostic! diagnostic-path diagnostic))
        (throw throwable)))))
