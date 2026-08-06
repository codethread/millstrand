(ns millstrand.core.weaver.dispatch
  "Event-queue submission and the shared data-first value predicate.

  Internal tier over the runtime event system: post-commit fanout in the weaver
  API and the public events API both submit through `enqueue!`, and both the
  events and hooks APIs guard registration payloads with `data-first-value?`, so
  neither alpha namespace depends on the other."
  (:require [millstrand.core.weaver.access :as access]))

(defn data-first-value?
  "Return true when value is composed only of JSON-safe scalars and collections."
  [value]
  (cond
    (or (nil? value)
        (string? value)
        (number? value)
        (keyword? value)
        (symbol? value)
        (boolean? value)
        (inst? value)
        (uuid? value)) true
    (map? value) (and (every? data-first-value? (keys value))
                      (every? data-first-value? (vals value)))
    (or (vector? value) (set? value)) (every? data-first-value? value)
    :else false))

(defn enqueue!
  "Submit an event to the runtime event system, validating the envelope shape."
  [runtime event]
  (when-not (map? event)
    (throw (ex-info "Event must be a map" {:event event})))
  (doseq [k [:event/type :event/id :event/at :event/source]]
    (when-not (contains? event k)
      (throw (ex-info "Event requires key" {:key k :event event}))))
  (when-not (keyword? (:event/type event))
    (throw (ex-info "Event :event/type must be a keyword" {:event/type (:event/type event)})))
  (when-not (.offer ^java.util.concurrent.BlockingQueue (:queue (access/event-system runtime)) event)
    (throw (ex-info "Event queue is full" {:event/type (:event/type event) :event/id (:event/id event)})))
  {:enqueued true :event/id (:event/id event) :event/type (:event/type event)})
