(ns millstrand.api.process.internal
  "Implementation plumbing for the explicit-runtime process API.

  This namespace is internal to `millstrand.api.process.alpha`; its public vars
  are not part of the alpha contract."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn- keyword-frame
  "Convert one JSON object tree to the internal keyword-keyed shape."
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[key item]]
                 [(keyword (str/replace (if (keyword? key) (name key) key)
                                        "_" "-"))
                  (keyword-frame item)]))
          value)

    (sequential? value) (mapv keyword-frame value)
    :else value))

(defn wire-keys
  "Convert keyword-keyed API data into the string-keyed control wire shape."
  [value]
  (cond
    (map? value) (into {} (map (fn [[key item]]
                                 [(if (keyword? key) (subs (str key) 1) (str key))
                                  (wire-keys item)])) value)
    (sequential? value) (mapv wire-keys value)
    :else value))

(defn validate-record!
  "Validate and return one Mill wire process record."
  [record]
  (let [decoded (-> (keyword-frame record)
                    (update :owner keyword)
                    (update :phase keyword)
                    (update :output #(-> %
                                         (assoc :stdout-ref (or (:stdout-ref %)
                                                                (:stdout_ref %))
                                                :stderr-ref (or (:stderr-ref %)
                                                                (:stderr_ref %)))
                                         (dissoc :stdout_ref :stderr_ref)))
                    (dissoc :launch_failure))]
    (when-not (s/valid? :millstrand.core.specs/process-record decoded)
      (throw (ex-info "Mill returned a malformed process record"
                      {:code "process/malformed-record"
                       :record decoded
                       :explain (s/explain-data
                                 :millstrand.core.specs/process-record decoded)})))
    decoded))
