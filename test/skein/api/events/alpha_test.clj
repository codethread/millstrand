(ns skein.api.events.alpha-test
  "Seam contract coverage for the events API.

  The broad behavior lock — entry shape and replace-by-key round-trips,
  asynchronous dispatch, failure record capture, reload interplay — is
  deliberately retained in `skein.weaver-test` and `skein.spools-test`,
  not duplicated here. This namespace pins what those locks skip: the loud
  registration seam (every validated piece rejects with its own error and
  leaves the registry untouched), unregistration idempotence, deterministic
  registry ordering across mixed key types, and seam spec conformance."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [skein.api.events.alpha :as events]
            [skein.test.alpha :as t])
  (:import [clojure.lang ExceptionInfo]))

(defn capture-event
  "Handler fixture: registration only needs a resolvable callable."
  [_event]
  nil)

(def not-callable
  "Non-callable var pinning the resolution contract."
  42)

(def ^:private handler-sym
  "Qualified symbol of the fixture handler, as registration wants it."
  'skein.api.events.alpha-test/capture-event)

(deftest registration-rejects-each-invalid-piece-and-leaves-the-registry-untouched
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (thrown-with-msg? ExceptionInfo #"key must be a keyword, symbol, or string"
                            (events/register-handler! rt 42 #{:strand/added} handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"key string must be non-blank"
                            (events/register-handler! rt "  " #{:strand/added} handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"types must be a set"
                            (events/register-handler! rt :k [:strand/added] handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"types must be non-empty"
                            (events/register-handler! rt :k #{} handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"types must be keywords"
                            (events/register-handler! rt :k #{"strand/added"} handler-sym)))
      (is (thrown-with-msg? ExceptionInfo #"must be a fully qualified symbol"
                            (events/register-handler! rt :k #{:strand/added} 'unqualified)))
      (is (thrown-with-msg? ExceptionInfo #"could not be resolved"
                            (events/register-handler! rt :k #{:strand/added}
                                                      'no.such.ns/handler)))
      (is (thrown-with-msg? ExceptionInfo #"must resolve to a callable value"
                            (events/register-handler!
                             rt :k #{:strand/added}
                             'skein.api.events.alpha-test/not-callable)))
      (is (thrown-with-msg? ExceptionInfo #"metadata must be a map"
                            (events/register-handler! rt :k #{:strand/added} handler-sym
                                                      [:not-a-map])))
      (is (thrown-with-msg? ExceptionInfo #"metadata must contain only data-first values"
                            (events/register-handler! rt :k #{:strand/added} handler-sym
                                                      {:opaque (Object.)})))
      (is (= [] (events/handlers rt))
          "a rejected registration never partially mutates the registry"))))

(deftest replacement-validates-like-registration-and-returns-the-data-first-entry
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (thrown-with-msg? ExceptionInfo #"Event handler not registered; cannot replace"
                            (events/replace-handler! rt :absent #{:strand/added} handler-sym)))
      (events/register-handler! rt :swap #{:strand/added} handler-sym)
      (is (= {:key :swap :types #{:strand/burned} :fn handler-sym :metadata {:doc "swapped"}}
             (events/replace-handler! rt :swap #{:strand/burned} handler-sym
                                      {:doc "swapped"}))
          "replacement returns register-handler!'s shape, with no resolved fn value")
      (is (thrown-with-msg? ExceptionInfo #"must resolve to a callable value"
                            (events/replace-handler!
                             rt :swap #{:strand/added}
                             'skein.api.events.alpha-test/not-callable))))))

(deftest unregistration-is-idempotent-and-validates-its-key
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (= {:unregistered :never-registered}
             (events/unregister-handler! rt :never-registered)))
      (is (thrown-with-msg? ExceptionInfo #"key must be a keyword, symbol, or string"
                            (events/unregister-handler! rt 42))))))

(defn- registration-accepts?
  "True when `register-handler!` accepts the pieces without throwing."
  [rt key types fn-sym]
  (try
    (events/register-handler! rt key types fn-sym)
    true
    (catch ExceptionInfo _ false)))

(deftest seam-specs-agree-with-the-seam-validators
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      ;; ::key and ::types state the full grammar, so spec and seam verdicts
      ;; must be identical over representative inputs.
      (doseq [key [:kw-key "string-key" 'sym-key 42 "  " nil]]
        (is (= (s/valid? ::events/key key)
               (registration-accepts? rt key #{:strand/added} handler-sym))
            (pr-str key)))
      (doseq [types [#{:strand/added} #{:a :b} [:strand/added] #{} #{"a"} nil]]
        (is (= (s/valid? ::events/types types)
               (registration-accepts? rt :types-probe types handler-sym))
            (pr-str types)))
      ;; ::fn and ::metadata state necessary shapes; resolution and the
      ;; data-first walk are semantics the body alone owns, so spec-invalid
      ;; must imply seam-rejected (never the reverse).
      (doseq [fn-sym ['unqualified "not-a-symbol" nil]]
        (is (not (s/valid? ::events/fn fn-sym)) (pr-str fn-sym))
        (is (not (registration-accepts? rt :fn-probe #{:strand/added} fn-sym))
            (pr-str fn-sym))))))

(deftest failure-record-spec-pins-the-promised-key-set
  ;; The live record round-trip (dispatch writes it, `recent-failures` reads
  ;; it back) is locked in `skein.weaver-test`; this pins the spec against
  ;; that record's documented shape.
  (is (s/valid? ::events/failure-record
                {:handler/key :fails
                 :handler/fn 'skein.weaver-test/failing-event
                 :event/id "evt-1"
                 :event/type :strand/updated
                 :exception/message "handler failed"
                 :failed/at "2026-07-19T12:00:00Z"}))
  (is (not (s/valid? ::events/failure-record {:handler/key :fails}))))

(deftest handler-provenance-reports-owners-without-leaking-fn-values
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (events/register-handler! rt :h #{:strand/added} handler-sym)
      (let [{:keys [effective shadowed contenders]} (get (events/handler-provenance rt) :h)]
        (is (= :skein.owner/repl (:owner effective))
            "a direct registration is attributed to the REPL owner")
        (is (= :direct (:layer effective)))
        (is (true? (:effective? effective)))
        (is (= [] shadowed))
        (is (not-any? #(contains? (:value %) :fn-value) contenders)
            "no contender leaks the resolved function value")
        (is (= handler-sym (get-in effective [:value :fn]))
            "the handler symbol stays as data")))))

(deftest registry-reads-are-deterministic-across-mixed-key-types
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (doseq [key [:kw-key "string-key" 'sym-key]]
        (events/register-handler! rt key #{:strand/added} handler-sym))
      (let [entries (events/handlers rt)]
        (is (= (sort-by pr-str [:kw-key "string-key" 'sym-key]) (mapv :key entries))
            "entries sort by printed key, stable across key types")
        (is (every? #(s/valid? ::events/handler-entry %) entries))
        (is (not-any? :fn-value entries)
            "the resolved function value never leaves the registry")))))
