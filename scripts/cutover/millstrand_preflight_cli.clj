(ns cutover.millstrand-preflight-cli
  "Parse the Millstrand preflight's standalone argv through the shared CLI parser.

  The shell wrapper remains the operator-facing command, but this namespace owns
  its text-bearing flag grammar and payload reference resolution. Keeping that
  boundary on `millstrand.api.cli.alpha` makes `:stdin` and `:payload/<name>`
  behave like every other repository CLI surface."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.cli.alpha :as cli]))

(def ^:private preflight-arg-spec
  {:op "millstrand-preflight"
   :flags {:validate-inventory {:type :string}
           :inventory {:type :string}
           :fragment {:type :string}
           :workspace-root {:type :string}
           :runtime-commit {:type :string}
           :plan {:type :boolean}
           :output {:type :string}
           :dry-run {:type :boolean}
           :stdin {:type :boolean}
           :payload {:type :map}}
   :positionals []
   :hook-class :read
   :deadline-class :standard})

(def ^:private value-flags
  #{"--validate-inventory" "--inventory" "--workspace-root"
    "--runtime-commit" "--fragment" "--plan" "--output" "--payload"})

(defn- non-blank-string?
  "Return true when `value` is a non-blank path or payload reference."
  [value]
  (and (string? value) (not (str/blank? value))))

(s/def ::path non-blank-string?)
(s/def ::validate-inventory ::path)
(s/def ::inventory ::path)
(s/def ::fragment ::path)
(s/def ::workspace-root ::path)
(s/def ::runtime-commit string?)
(s/def ::plan #(true? %))
(s/def ::output ::path)
(s/def ::dry-run #(true? %))
(s/def ::stdin boolean?)
(s/def ::payload map?)

(defn- excludes-keys?
  "Return true when `value` has none of the keys in `forbidden`."
  [forbidden value]
  (not-any? #(contains? value %) forbidden))

(s/def ::validate-mode
  (s/and
   (s/keys :req-un [::validate-inventory]
           :opt-un [::stdin ::payload])
   #(excludes-keys? #{:inventory :fragment :workspace-root :runtime-commit :dry-run}
                    %)))

(s/def ::dry-run-mode
  (s/and
   (s/keys :req-un [::dry-run ::inventory ::workspace-root]
           :opt-un [::runtime-commit ::stdin ::payload])
   #(excludes-keys? #{:validate-inventory :fragment} %)))

(s/def ::fragment-mode
  (s/and
   (s/keys :req-un [::fragment ::workspace-root]
           :opt-un [::stdin ::payload])
   #(excludes-keys? #{:validate-inventory :inventory :runtime-commit :dry-run} %)))

(s/def ::live-mode
  (s/and
   (s/keys :req-un [::inventory]
           :opt-un [::runtime-commit ::plan ::output ::stdin ::payload])
   #(excludes-keys? #{:validate-inventory :fragment :workspace-root :dry-run} %)))

(s/def ::plan-mode
  (s/and
   (s/keys :req-un [::plan ::inventory]
           :opt-un [::runtime-commit ::output ::stdin ::payload])
   #(excludes-keys? #{:validate-inventory :fragment :workspace-root :dry-run} %)))

(s/def ::preflight-arguments
  (s/or :validate-inventory ::validate-mode
        :dry-run ::dry-run-mode
        :fragment ::fragment-mode
        :plan ::plan-mode
        :live ::live-mode))

(defn- fail!
  "Raise a parser-front-end error with the supplied message."
  [message]
  (throw (ex-info message {:reason :invalid-preflight-arguments})))

(defn- payload-name-and-path
  "Return the named payload slot and file path from `name=path`."
  [token]
  (let [separator (str/index-of token "=")]
    (when (or (nil? separator) (zero? separator))
      (fail! (str "Malformed --payload value " (pr-str token)
                  "; expected name=path")))
    [(subs token 0 separator) (subs token (inc separator))]))

(defn- payload-arguments
  "Read dispatcher payload slots needed by the shared argv parser.

  The scan only discovers transport slots. The public preflight values are
  parsed and resolved by `cli/parse`; no flag value is interpreted here."
  [argv]
  (loop [tokens (seq argv)
         payloads {}
         seen-stdin? false]
    (if-let [token (first tokens)]
      (cond
        (= token "--stdin")
        (do
          (when seen-stdin?
            (fail! "Duplicate --stdin payload slot"))
          (recur (next tokens) (assoc payloads "stdin" (slurp *in*)) true))

        (= token "--payload")
        (let [spec (second tokens)]
          (when (nil? spec)
            (fail! "Missing value after --payload"))
          (let [[name path] (payload-name-and-path spec)]
            (when (contains? payloads name)
              (fail! (str "Duplicate payload slot " name)))
            (let [content (try
                            (slurp (io/file path))
                            (catch java.io.IOException error
                              (fail! (str "Failed to read --payload " name ": "
                                          (.getMessage error)))))]
              (recur (nnext tokens) (assoc payloads name content)
                     seen-stdin?))))

        (contains? value-flags token)
        (do
          (when (nil? (second tokens))
            (fail! (str "Missing value after " token)))
          (recur (nnext tokens) payloads seen-stdin?))

        :else
        (recur (next tokens) payloads seen-stdin?))
      payloads)))

(defn- invalid-shape!
  "Raise a shape error that includes the failing value and allowed spec form."
  [parsed]
  (let [explanation (s/explain-data ::preflight-arguments parsed)
        problem (first (::s/problems explanation))
        allowed (s/form ::preflight-arguments)]
    (throw (ex-info
            (str "Invalid preflight argument shape; value "
                 (pr-str (:val problem))
                 "; allowed shape "
                 (pr-str allowed))
            {:reason :invalid-preflight-shape
             :value (:val problem)
             :allowed allowed
             :problems (::s/problems explanation)}))))

(defn- validate-shape!
  "Validate the standalone command shape through its clojure.spec."
  [parsed]
  (when-not (s/valid? ::preflight-arguments parsed)
    (invalid-shape! parsed))
  true)

(defn- parser-error-message
  "Return a parser diagnostic with known flags when that context is available."
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

(defn -main
  "Parse preflight argv and print the normalized argument map as JSON."
  [& argv]
  (try
    (let [parsed (cli/parse preflight-arg-spec argv (payload-arguments argv))]
      (validate-shape! parsed)
      (println (json/write-str (json-safe parsed))))
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*]
        (println (parser-error-message error)))
      (System/exit 2))))
