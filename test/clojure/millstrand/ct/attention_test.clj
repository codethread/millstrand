(ns millstrand.ct.attention-test
  "Regression coverage for this repository's Chime attention rules."
  (:require [clojure.test :refer [deftest is]]
            [me.notifications.attention :as attention])
  (:import [java.time.format DateTimeParseException]))

(deftest parked-run-rule-fails-loudly-on-malformed-updated-at
  (let [strand {:id "run-123"
                :title "Malformed timestamp run"
                :state "active"
                :updated_at "not-a-sqlite-timestamp"
                :attributes {:harness/run "true"
                             :harness/status "ready"
                             :harness/substatus "pending"}}
        exception (try
                    (attention/parked-run-rule
                     {:strand strand :ready-ids #{(:id strand)}})
                    nil
                    (catch clojure.lang.ExceptionInfo cause
                      cause))]
    (is (instance? clojure.lang.ExceptionInfo exception))
    (is (= "Parked-run detector could not parse strand updated_at"
           (ex-message exception)))
    (is (= {:strand "run-123"
            :updated_at "not-a-sqlite-timestamp"}
           (ex-data exception)))
    (is (instance? DateTimeParseException (ex-cause exception)))))
