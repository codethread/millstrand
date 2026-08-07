(ns cutover.millstrand-coordinator-cli
  "Parse the disposable MSR-15 coordinator contract through the shared CLI parser.

  The coordinator is deliberately dry-run-only in this repository. Its shell
  wrapper consumes the normalized JSON and exercises lifecycle-shaped checks
  against a disposable fixture, while this namespace owns all public text
  argument and whole-value payload semantics."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cutover.cli-contracts :as contracts]
            [millstrand.api.cli.alpha :as cli]))

(def ^:private coordinator-arg-spec
  {:op "millstrand-coordinator"
   :flags {:dry-run {:type :boolean :doc "Run only against disposable fixture state."}
           :inventory {:type :string :required? true :spec ::contracts/path-or-ref
                       :doc "Typed MSR-14 inventory JSON."}
           :preparation-index {:type :string :required? true :spec ::contracts/path-or-ref
                               :doc "Typed preparation-index JSON."}
           :preparation-index-sha256 {:type :string :required? true
                                      :spec ::contracts/path-or-ref
                                      :doc "Expected canonical index hash."}
           :workspace-root {:type :string :required? true :spec ::contracts/path-or-ref
                            :doc "Disposable fixture root."}
           :runtime-commit {:type :string :spec ::contracts/path-or-ref
                            :doc "Expected landed runtime SHA."}
           :output {:type :string :spec ::contracts/path-or-ref
                    :doc "Evidence JSON output path."}
           :payload {:type :map :spec ::contracts/payload-map
                     :doc "Named whole-value payload files."}
           :stdin {:type :boolean :doc "Attach stdin as the stdin payload."}}
   :positionals []
   :hook-class :read
   :deadline-class :unbounded})

(def ^:private value-flags
  #{"--inventory" "--preparation-index" "--preparation-index-sha256"
    "--workspace-root" "--runtime-commit" "--output" "--payload"})

(s/def ::inventory ::contracts/path-or-ref)
(s/def ::preparation-index ::contracts/path-or-ref)
(s/def ::preparation-index-sha256 ::contracts/path-or-ref)
(s/def ::workspace-root ::contracts/path-or-ref)
(s/def ::runtime-commit #(or (nil? %)
                             (and (string? %)
                                  (boolean (re-matches #"[0-9a-f]{40}" %)))))
(s/def ::output ::contracts/path-or-ref)
(s/def ::dry-run true?)
(s/def ::stdin boolean?)
(s/def ::payload ::contracts/payload-map)

(defn- excludes-keys?
  "Return true when `value` has none of `forbidden` keys."
  [forbidden value]
  (not-any? #(contains? value %) forbidden))

(s/def ::coordinator-arguments
  (s/and
   (s/keys :req-un [::dry-run ::inventory ::preparation-index
                    ::preparation-index-sha256 ::workspace-root]
           :opt-un [::runtime-commit ::output ::stdin ::payload])
   #(excludes-keys? #{} %)))

(defn- fail!
  "Raise a parser-front-end error with `message`."
  [message]
  (throw (ex-info message {:reason :invalid-coordinator-arguments})))

(defn- payload-name-and-path
  "Return the named payload slot and path from `name=path`."
  [token]
  (let [separator (str/index-of token "=")]
    (when (or (nil? separator) (zero? separator) (= separator (dec (count token))))
      (fail! (str "Malformed --payload value " (pr-str token)
                  "; expected name=path")))
    (let [entry [(subs token 0 separator) (subs token (inc separator))]]
      (when-not (s/valid? ::contracts/payload-entry entry)
        (fail! (str "Malformed --payload value " (pr-str token)
                    "; expected a non-blank name and path")))
      entry)))

(defn- payload-arguments
  "Read transport payload slots for the shared whole-value parser."
  [argv]
  (loop [tokens (seq argv)
         payloads {}
         seen-stdin? false]
    (if-let [token (first tokens)]
      (cond
        (= token "--stdin")
        (do
          (when seen-stdin? (fail! "Duplicate --stdin payload slot"))
          (recur (next tokens) (assoc payloads "stdin" (slurp *in*)) true))

        (= token "--payload")
        (let [spec (second tokens)]
          (when (nil? spec) (fail! "Missing value after --payload"))
          (let [[name path] (payload-name-and-path spec)]
            (when (contains? payloads name)
              (fail! (str "Duplicate payload slot " name)))
            (when (= name "stdin")
              (fail! "Payload slot stdin is reserved for --stdin"))
            (let [content (try
                            (slurp (io/file path))
                            (catch java.io.IOException error
                              (fail! (str "Failed to read --payload " name ": "
                                          (.getMessage error)))))]
              (recur (nnext tokens) (assoc payloads name content) seen-stdin?))))

        (contains? value-flags token)
        (do
          (when (nil? (second tokens))
            (fail! (str "Missing value after " token)))
          (recur (nnext tokens) payloads seen-stdin?))

        :else
        (recur (next tokens) payloads seen-stdin?))
      (do
        (when-not (s/valid? ::contracts/payload-map payloads)
          (fail! "Payload slots do not match the cutover payload map contract"))
        payloads))))

(defn- parser-error-message
  "Return a parser diagnostic with known flags when available."
  [error]
  (let [data (ex-data error)]
    (if-let [known-flags (seq (:known-flags data))]
      (str (ex-message error) "; allowed flags: " (str/join ", " known-flags))
      (ex-message error))))

(defn- json-safe
  "Convert parsed keyword keys to JSON object member names."
  [parsed]
  (into {}
        (map (fn [[key value]] [(name key) value]))
        parsed))

(defn- validate-shape!
  "Validate the coordinator's dry-run-only argument shape."
  [parsed]
  (when-not (s/valid? ::coordinator-arguments parsed)
    (let [explanation (s/explain-data ::coordinator-arguments parsed)]
      (throw (ex-info
              (str "Invalid coordinator argument shape; value "
                   (pr-str (:val (first (::s/problems explanation)))))
              {:reason :invalid-coordinator-shape
               :problems (::s/problems explanation)}))))
  parsed)

(defn -main
  "Parse coordinator argv and print its normalized argument map as JSON."
  [& argv]
  (try
    (let [parsed (-> (cli/parse coordinator-arg-spec argv (payload-arguments argv))
                     validate-shape!
                     json-safe)]
      (println (json/write-str parsed)))
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*]
        (println (parser-error-message error)))
      (System/exit 2))))
