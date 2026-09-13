(ns me.workflows.land
  "Compatibility specs for the active pre-extraction Millstrand land run."
  (:require [clojure.spec.alpha :as s]
            [me.workflows.support :as support]))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (support/non-blank-string? v))

(s/def ::non-blank-string non-blank-string?)
(s/def ::body ::non-blank-string)
(s/def ::subject ::non-blank-string)
(s/def ::reason ::non-blank-string)
(s/def ::pr-number pos-int?)

(s/def ::land-abort-input
  (s/and (s/keys :req-un [::reason])
         #(every? #{:reason} (keys %))))

(s/def ::land-merge-input
  (s/and (s/keys :req-un [::pr-number ::subject ::body])
         #(every? #{:pr-number :subject :body} (keys %))))
