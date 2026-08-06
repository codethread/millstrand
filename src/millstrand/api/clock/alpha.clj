(ns millstrand.api.clock.alpha
  "Clock capability for runtime-owned time and sleeping."
  (:require [clojure.spec.alpha :as s])
  (:import [java.time Duration Instant]))

(declare ^:private system-clock-instance require-duration!)

;; The Clock capability is a plain map of two functions, deliberately not a
;; `defprotocol`. Re-evaluating a `defprotocol` mints a fresh backing interface,
;; so every clock the runtime already holds (its installed clock, any manual
;; clock, a clock mid-poll) stops satisfying the new protocol after a hot
;; reload. A validated map has no generated interface: it stays valid across a
;; reload of this namespace and grows new optional keys without breaking a
;; frozen method set. Build one with `clock`; read it through `now`/`sleep!`.

(s/def ::now-fn ifn?)
(s/def ::sleep-fn ifn?)
(s/def ::clock (s/keys :req-un [::now-fn ::sleep-fn]))
(s/def ::instant #(instance? Instant %))
(s/def ::duration
  (s/and #(instance? Duration %)
         #(not (.isNegative ^Duration %))))

(defn clock?
  "Return true when `x` is a Clock capability."
  [x]
  (s/valid? ::clock x))

(defn clock
  "Build a Clock capability from `now-fn` and `sleep-fn`.

  `now-fn` is a zero-arg fn returning the current `java.time.Instant`; `sleep-fn`
  takes a non-negative `java.time.Duration` and waits or advances by it. Fails
  loudly unless both are functions."
  [now-fn sleep-fn]
  (let [built {:now-fn now-fn :sleep-fn sleep-fn}]
    (when-not (clock? built)
      (throw (ex-info "Clock requires now-fn and sleep-fn functions"
                      {:now-fn now-fn :sleep-fn sleep-fn})))
    built))

(defn now
  "Return `clock`'s current `java.time.Instant`."
  [clock]
  ((:now-fn clock)))

(defn sleep!
  "Wait or advance `clock` by a non-negative `java.time.Duration`, then return nil."
  [clock duration]
  ((:sleep-fn clock) (require-duration! duration))
  nil)

(defn system-clock
  "Return the shared clock backed by the system wall clock and thread sleep."
  []
  system-clock-instance)

(s/fdef clock?
  :args (s/cat :x any?)
  :ret boolean?)

(s/fdef clock
  :args (s/cat :now-fn ifn? :sleep-fn ifn?)
  :ret ::clock)

(s/fdef now
  :args (s/cat :clock ::clock)
  :ret ::instant)

(s/fdef sleep!
  :args (s/cat :clock ::clock :duration ::duration)
  :ret nil?)

(s/fdef system-clock
  :args (s/cat)
  :ret ::clock)

(defn- require-duration!
  [duration]
  (when-not (s/valid? ::duration duration)
    (throw (ex-info "Clock sleep requires a non-negative java.time.Duration"
                    {:duration duration})))
  duration)

(def ^:private system-clock-instance
  (clock
   (fn [] (Instant/now))
   (fn [^Duration duration]
     (let [millis (try
                    (.toMillis duration)
                    (catch ArithmeticException cause
                      (throw (ex-info "Clock sleep duration is too large"
                                      {:duration duration}
                                      cause))))
           nanos (mod (.getNano duration) 1000000)]
       (Thread/sleep (long millis) (int nanos))
       nil))))
