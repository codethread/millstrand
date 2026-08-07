(ns cutover.millstrand-preflight-cli
  "Parse the Millstrand preflight's standalone argv through the shared CLI parser.

  The shell wrapper remains the operator-facing command, but this namespace owns
  its text-bearing flag grammar and payload reference resolution. Keeping that
  boundary on `millstrand.api.cli.alpha` makes `:stdin` and `:payload/<name>`
  behave like every other repository CLI surface."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [millstrand.api.cli.alpha :as cli]))

(def ^:private preflight-arg-spec
  {:op "millstrand-preflight"
   :flags {:validate-inventory {:type :string}
           :inventory {:type :string}
           :fragment {:type :string}
           :workspace-root {:type :string}
           :runtime-commit {:type :string}
           ;; Kept as an unadvertised compatibility spelling. New callers use
           ;; --dry-run --workspace-root.
           :fixtures {:type :string}
           :dry-run {:type :boolean}
           :stdin {:type :boolean}
           :payload {:type :map}}
   :positionals []
   :hook-class :read
   :deadline-class :standard})

(def ^:private value-flags
  #{"--validate-inventory" "--inventory" "--workspace-root"
    "--runtime-commit" "--fixtures" "--payload"})

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

(defn- validate-shape!
  "Validate the standalone command shapes that arg-spec cannot express."
  [{:keys [validate-inventory inventory fragment fixtures workspace-root
           runtime-commit dry-run]}]
  (let [has-inventory? (some? inventory)
        has-validation? (some? validate-inventory)
        has-fragment? (some? fragment)
        legacy-fixture? (some? fixtures)]
    (cond
      has-fragment?
      (when-not (and (not has-validation?) (not has-inventory?)
                     (nil? runtime-commit) (not dry-run)
                     (or (some? workspace-root) legacy-fixture?))
        (fail! "--fragment requires --workspace-root"))

      has-validation?
      (when-not (and (not has-inventory?) (not legacy-fixture?)
                     (nil? workspace-root) (nil? runtime-commit) (not dry-run))
        (fail! "--validate-inventory cannot be combined with other modes"))

      dry-run
      (when-not (and has-inventory? (some? workspace-root) (not legacy-fixture?))
        (fail! "--dry-run requires --inventory and --workspace-root"))

      legacy-fixture?
      (when-not has-inventory?
        (fail! "--fixtures requires --inventory"))

      :else
      (when-not (and has-inventory? (nil? workspace-root))
        (fail! "live mode requires --inventory and no --workspace-root"))))
  true)

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
        (println (ex-message error)))
      (System/exit 2))))
