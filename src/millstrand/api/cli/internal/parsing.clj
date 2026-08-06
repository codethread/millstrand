(ns millstrand.api.cli.internal.parsing
  "Token, payload, and declared-value parsing for CLI arg-specs."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [millstrand.api.cli.internal.shared :as shared]))

(defn coerce
  "Coerce a raw token to its declared argument type."
  [op arg type token]
  (case type
    :int
    (try
      (Long/parseLong token)
      (catch NumberFormatException _
        (shared/fail!
         :type-violation
         (str "Argument " arg " expects an integer, got " (pr-str token))
         {:op op :arg arg :type :int :token token})))

    :boolean-token
    (case token
      "true" true
      "false" false
      (shared/fail!
       :type-violation
       (str "Argument " arg " expects true or false, got " (pr-str token))
       {:op op :arg arg :type :boolean-token :token token}))

    ;; :string (and the default) passes the token through unchanged.
    token))

(defn check-required-flags
  "Fail for any required flag absent from the parsed accumulator."
  [op flags acc]
  (doseq [[flag-kw spec] flags
          :when (:required? spec)]
    (when-not (contains? acc flag-kw)
      (shared/fail!
       :missing-required
       (str "Missing required flag --" (name flag-kw))
       {:op op :flag (str "--" (name flag-kw))})))
  acc)

(defn assign-positionals
  "Bind ordered positional tokens, honouring a trailing variadic."
  [op acc pos-specs positionals]
  (let [variadic? (boolean (:variadic? (last pos-specs)))
        fixed-specs (if variadic?
                      (vec (butlast pos-specs))
                      (vec pos-specs))
        fixed-count (count fixed-specs)]
    (when (and (not variadic?)
               (> (count positionals) (count pos-specs)))
      (shared/fail!
       :trailing-tokens
       (str "Unexpected extra arguments: "
            (pr-str (vec (drop (count pos-specs) positionals))))
       {:op op
        :extra (vec (drop (count pos-specs) positionals))}))
    (let [acc (reduce
               (fn [result idx]
                 (let [spec (nth fixed-specs idx)
                       arg-name (:name spec)
                       token (nth positionals idx nil)]
                   (cond
                     (some? token)
                     (assoc result
                            arg-name
                            (coerce op
                                    (name arg-name)
                                    (:type spec :string)
                                    token))

                     (:required? spec)
                     (shared/fail!
                      :missing-required
                      (str "Missing required argument " (name arg-name))
                      {:op op :arg arg-name})

                     :else result)))
               acc
               (range fixed-count))]
      (if variadic?
        (let [spec (last pos-specs)
              arg-name (:name spec)
              rest-tokens (vec (drop fixed-count positionals))]
          (when (and (:required? spec) (empty? rest-tokens))
            (shared/fail!
             :missing-required
             (str "Missing required argument " (name arg-name))
             {:op op :arg arg-name}))
          (assoc acc
                 arg-name
                 (mapv #(coerce op
                                (name arg-name)
                                (:type spec :string)
                                %)
                       rest-tokens)))
        acc))))

(defn parse-argv
  "Fold argv into a raw arg map, leaving payload refs and :parse untouched."
  [arg-spec argv]
  (let [op (:op arg-spec)
        flags (:flags arg-spec)
        pos-specs (vec (:positionals arg-spec))]
    (loop [tokens (seq argv)
           acc {}
           seen #{}
           positionals []]
      (if-let [[token & more] tokens]
        (if (str/starts-with? token "--")
          (let [flag-kw (keyword (subs token 2))
                spec (get flags flag-kw)]
            (when-not spec
              (shared/fail!
               :unknown-flag
               (str "Unknown flag " token)
               {:op op
                :flag token
                :known-flags (mapv #(str "--" (name %))
                                   (sort (keys flags)))}))
            (case (:type spec :string)
              :boolean
              (do
                (when (contains? seen flag-kw)
                  (shared/fail!
                   :duplicate-flag
                   (str "Duplicate flag " token)
                   {:op op :flag token}))
                (recur more
                       (assoc acc flag-kw true)
                       (conj seen flag-kw)
                       positionals))

              :map
              (let [key-value (first more)]
                (when (or (nil? key-value)
                          (str/starts-with? key-value "--"))
                  (shared/fail!
                   :malformed-kv
                   (str "Flag " token " expects a key=value token")
                   {:op op :flag token :token key-value}))
                (let [idx (str/index-of key-value "=")]
                  (when (or (nil? idx) (zero? idx))
                    (shared/fail!
                     :malformed-kv
                     (str "Malformed key=value token "
                          (pr-str key-value)
                          " for "
                          token)
                     {:op op :flag token :token key-value}))
                  (recur (next more)
                         (update acc
                                 flag-kw
                                 (fnil assoc {})
                                 (subs key-value 0 idx)
                                 (subs key-value (inc idx)))
                         seen
                         positionals)))

              ;; Value flags: :string / :int / :boolean-token.
              (let [value-token (first more)]
                (when (nil? value-token)
                  (shared/fail!
                   :missing-value
                   (str "Flag " token " expects a value")
                   {:op op :flag token}))
                (let [value (coerce op
                                    token
                                    (:type spec :string)
                                    value-token)]
                  (if (:repeat? spec)
                    (recur (next more)
                           (update acc flag-kw (fnil conj []) value)
                           seen
                           positionals)
                    (do
                      (when (contains? seen flag-kw)
                        (shared/fail!
                         :duplicate-flag
                         (str "Duplicate flag " token)
                         {:op op :flag token}))
                      (recur (next more)
                             (assoc acc flag-kw value)
                             (conj seen flag-kw)
                             positionals)))))))
          (recur more acc seen (conj positionals token)))
        (assign-positionals op
                            (check-required-flags op flags acc)
                            pos-specs
                            positionals)))))

(defn payload-ref
  "Return the payload name a whole-value reference points at, else nil."
  [value]
  (cond
    (= value ":stdin") "stdin"
    (str/starts-with? value ":payload/")
    (subs value (count ":payload/"))
    :else nil))

(defn resolve-value
  "Replace whole-value payload references and track consumed names."
  [op payloads consumed value]
  (cond
    (string? value)
    (if-let [payload-name (payload-ref value)]
      (if (contains? payloads payload-name)
        (do
          (swap! consumed conj payload-name)
          (get payloads payload-name))
        (shared/fail!
         :missing-payload
         (str "No payload attached for reference " value)
         {:op op
          :ref value
          :payload-name payload-name
          :available (vec (sort (keys payloads)))}))
      value)

    (vector? value)
    (mapv #(resolve-value op payloads consumed %) value)

    (map? value)
    (reduce-kv
     (fn [result key nested-value]
       (assoc result key (resolve-value op payloads consumed nested-value)))
     {}
     value)

    :else value))

(defn resolve-payloads
  "Resolve payload references and reject missing or unused payloads."
  [op parsed payloads]
  (let [payloads (reduce-kv
                  (fn [result key value]
                    (assoc result (name key) value))
                  {}
                  payloads)
        consumed (atom #{})
        resolved (reduce-kv
                  (fn [result key value]
                    (assoc result key
                           (resolve-value op payloads consumed value)))
                  {}
                  parsed)
        unused (remove @consumed (keys payloads))]
    (when (seq unused)
      (shared/fail!
       :unused-payloads
       (str "Attached payloads not referenced by any argument: "
            (str/join ", " (sort unused)))
       {:op op :unused (vec (sort unused))}))
    resolved))

(defn parse-json
  "Parse one JSON value, adding argument context to failures."
  [op arg value]
  (try
    (json/read-str value)
    (catch Exception error
      (shared/fail!
       :malformed-json
       (str "Argument " (name arg) " is not valid JSON: "
            (ex-message error))
       {:op op :arg arg}))))

(defn parse-jsonl
  "Parse non-blank JSON Lines, adding argument and line context to failures."
  [op arg value]
  (->> (str/split-lines value)
       (map-indexed (fn [idx line] [(inc idx) line]))
       (remove (fn [[_ line]] (str/blank? line)))
       (mapv (fn [[line-number line]]
               (try
                 (json/read-str line)
                 (catch Exception error
                   (shared/fail!
                    :malformed-jsonl
                    (str "Argument "
                         (name arg)
                         " line "
                         line-number
                         " is not valid JSON: "
                         (ex-message error))
                    {:op op :arg arg :line line-number})))))))

(defn apply-parse
  "Apply a :json/:jsonl declaration to a scalar, repeat, or map value."
  [op arg kind value]
  (let [parse-value (case kind
                      :json #(parse-json op arg %)
                      :jsonl #(parse-jsonl op arg %))]
    (cond
      (string? value) (parse-value value)
      (vector? value) (mapv parse-value value)
      (map? value) (reduce-kv
                    (fn [result key nested-value]
                      (assoc result key (parse-value nested-value)))
                    {}
                    value)
      :else value)))

(defn parse-declarations
  "Collect each argument name and its declared parse kind."
  [arg-spec]
  (merge
   (reduce-kv
    (fn [result key spec]
      (if-let [parse-kind (:parse spec)]
        (assoc result key parse-kind)
        result))
    {}
    (:flags arg-spec))
   (reduce
    (fn [result spec]
      (if-let [parse-kind (:parse spec)]
        (assoc result (:name spec) parse-kind)
        result))
    {}
    (:positionals arg-spec))))

(defn apply-declared-parses
  "Apply every declared `:parse` kind of `arg-spec` to its resolved value."
  [arg-spec op resolved]
  (reduce-kv
   (fn [result arg kind]
     (if (contains? result arg)
       (update result arg #(apply-parse op arg kind %))
       result))
   resolved
   (parse-declarations arg-spec)))
