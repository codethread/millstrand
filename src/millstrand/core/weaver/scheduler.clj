(ns millstrand.core.weaver.scheduler
  "Weaver-owned clock trigger: arm durable wakes and dispatch due handlers.

  This is the runtime half of the scheduler primitive (RFC-009,
  DELTA-weaver-scheduler-runtime-001); durable storage lives in
  `millstrand.core.db`. The subsystem owns exactly one runtime state, held through
  `millstrand.api.runtime.alpha/spool-state` under a declared version with a
  close-on-stop hook, and never a module-level atom.

  Lifecycle:
  - `rearm!` is the post-config entry point. Runtime startup and module resource
    reconciliation call it after selected startup files finish loading, so
    handlers supplied by configured modules are resolvable before any timer
    arms. It cancels the in-memory timer, discards stale in-flight claims, and
    rebuilds from durable pending rows.
  - Runtime stop closes the executor through the spool-state `:close-fn` before
    storage closes.

  Dispatch model (DELTA-weaver-scheduler-runtime-001.CC8): a single-thread
  scheduled executor drives timing only. When a wake is due it records the
  delivery attempt and offers a `:scheduler/fire` envelope onto the existing
  serialized event-system queue; handler invocation and every terminal state
  transition (complete/fail/cancel) run on the shared lane via `run-fire!`, so
  clock-triggered handlers and post-commit event handlers share one mutation
  lane. The only durable write on the timer thread is the atomic attempt
  increment, and it tolerates a row vanishing under a concurrent cancel. Precise
  re-arm (next non-in-flight pending row) is the only arming mechanism; there is
  deliberately no periodic safety tick (DELTA-weaver-scheduler-runtime-001.D3).

  Delivery is at-least-once (PROP-weaver-scheduler-001.NG2): an in-memory
  in-flight set suppresses double-dispatch within one process lifetime, and
  durable pending rows re-arm across restart/reload. Queue-full is transient —
  the durable wake stays pending, a bounded dispatch-failure signal is recorded,
  and the scheduler re-arms after a short backoff rather than dropping the wake.

  Handler invocation contract (DELTA-weaver-scheduler-runtime-001.CC9): a wake
  handler is a fully qualified symbol resolved in the runtime generation
  classloader and invoked with one context map. Its return value is ignored. The context map
  keys are:

    :runtime  - the weaver runtime the wake fired in
    :key      - the stable wake key (string)
    :wake-at  - the scheduled instant (java.time.Instant)
    :attempt  - delivery attempt count for this fire (long, >= 1)
    :handler  - the fully qualified handler symbol
    :payload  - the decoded payload map, or nil when none was scheduled

  Unresolvable handler symbols and handler exceptions are captured as failed
  fires in scheduler history and never propagate into the event worker, so one
  bad handler cannot kill the dispatch lane."
  (:require [millstrand.api.clock.alpha :as clock]
            [millstrand.core.db :as db])
  (:import [java.time Instant]
           [java.util.concurrent ArrayBlockingQueue Executors ScheduledExecutorService
            ScheduledFuture ThreadFactory TimeUnit]))

(def scheduler-state-version
  "Spool-state version tag for scheduler runtime state.

  Bumping this reinitializes (and closes) any preserved state whose shape no
  longer matches after a reload, per SPEC-004.C95/C96."
  3)

(def ^:private state-key ::state)

(def ^:private transient-retry-ms
  "Backoff before re-arming after an event-queue-full dispatch failure.

  A due wake blocked by a saturated event queue stays pending; re-arming with a
  short floor delay retries after the queue drains instead of spinning the timer
  thread against a full queue."
  500)

(def ^:private recent-dispatch-failure-limit
  "Maximum number of recent transient (queue-full) dispatch failures retained."
  100)

(declare dispatch-and-rearm!)

(defn- now-instant
  "Current Instant from the runtime clock seam.

  Reads the runtime's `:clock` slot directly rather than via
  `millstrand.core.weaver.runtime/now`: runtime requires this scheduler namespace, so
  a static require back would cycle."
  ^Instant [runtime]
  (clock/now (deref (:clock runtime))))

(defn- close-state!
  "Cancel the timer and shut the executor down, joining its thread."
  [state]
  (reset! (:closed? state) true)
  ;; :lock is a dedicated per-state (Object.) monitor (see state ctor); the rule only
  ;; recognises bare-symbol locks and can't see the stable Object behind the map get.
  #_{:splint/disable [lint/locking-object]}
  (locking (:lock state)
    (when-let [^ScheduledFuture fut @(:timer state)]
      (.cancel fut false)
      (reset! (:timer state) nil)))
  (let [^ScheduledExecutorService executor (:executor state)]
    (.shutdownNow executor)
    (.awaitTermination executor 2 TimeUnit/SECONDS))
  nil)

(defn- new-state
  "Allocate fresh scheduler runtime state with an idle timer thread."
  []
  (let [factory (reify ThreadFactory
                  (newThread [_ runnable]
                    (doto (Thread. ^Runnable runnable "millstrand-scheduler-timer")
                      (.setDaemon true))))
        state {:executor (Executors/newSingleThreadScheduledExecutor factory)
               :timer (atom nil)
               :in-flight (atom #{})
               ;; Durable rows are deliberately retained when their handler no
               ;; longer names live configured code. rearm! clears this parking
               ;; set after a repair so the same generation can deliver.
               :parked-wakes (atom {})
               :dispatch-failures (atom [])
               :closed? (atom false)
               :lock (Object.)}]
    (assoc state :close-fn (fn [] (close-state! state)))))

(defn state
  "Return this runtime's scheduler state, creating it once via spool-state.

  Uses `millstrand.api.runtime.alpha/spool-state` through `requiring-resolve` so this
  core module does not statically depend on the API tier (which would cycle back
  through the runtime). The state carries an explicit version and `:close-fn`, so
  a reload with a mismatched version closes the stale executor before reinit."
  [runtime]
  ((requiring-resolve 'millstrand.api.runtime.alpha/spool-state)
   runtime state-key {:version scheduler-state-version} new-state))

(defn dispatch-failures
  "Return recent transient (queue-full) dispatch failures, newest last."
  [runtime]
  @(:dispatch-failures (state runtime)))

(defn- handler-available?
  [handler]
  (when-let [namespace (find-ns (symbol (namespace handler)))]
    (let [resolved (ns-resolve namespace (symbol (name handler)))]
      (and resolved (ifn? (if (var? resolved) @resolved resolved))))))

(defn- orphaned-handler?
  [runtime handler]
  (some #(= (symbol (namespace handler)) (:namespace %))
        (some-> runtime :namespace-load-status deref :residuals)))

(defn wake-status
  "Return the offline status projection for durable pending scheduler wakes.

  A wake whose handler is absent or whose namespace is a load-ledger residual is
  retained and reported as `:parked`; no status read loads code or changes the
  durable row."
  [runtime]
  (let [parked @(:parked-wakes (state runtime))]
    (mapv (fn [wake]
            (let [handler (symbol (:handler wake))
                  park-reason (or (get parked [(:key wake) (:wake_at wake)])
                                  (when (or (orphaned-handler? runtime handler)
                                            (not (handler-available? handler)))
                                    :handler-unresolved))]
              (assoc wake :status (if park-reason :parked :pending)
                     :park/reason park-reason)))
          (db/pending-wakes (:datasource runtime)))))

(defn- park-wake! [st row reason]
  (swap! (:parked-wakes st) assoc [(:key row) (:wake_at row)] reason))

(defn- record-dispatch-failure! [state key message]
  (swap! (:dispatch-failures state)
         (fn [failures]
           (->> (conj failures {:key key
                                :error message
                                :at (str (Instant/now))})
                (take-last recent-dispatch-failure-limit)
                vec))))

(defn- fire-envelope [wake attempt]
  {:event/type :scheduler/fire
   :scheduler/key (:key wake)
   :scheduler/wake-at-millis (:wake_at wake)
   :scheduler/attempt attempt})

(defn- dispatch-due!*
  [runtime due-wakes-fn]
  (let [ds (:datasource runtime)
        st (state runtime)
        ^ArrayBlockingQueue queue (get-in runtime [:event-system :queue])
        due (due-wakes-fn ds (now-instant runtime))]
    (reduce
     (fn [acc wake]
       (let [key (:key wake)]
         (if (or (contains? @(:in-flight st) key)
                 (contains? @(:parked-wakes st) [key (:wake_at wake)]))
           acc
           (do
             (swap! (:in-flight st) conj key)
             (let [attempt (inc (:attempts wake))]
               (if (.offer queue (fire-envelope wake attempt))
                 (do
                    ;; Persist the delivery only after a successful enqueue, and
                    ;; claim the exact generation we selected (key + wake_at). A row
                    ;; cancelled or rescheduled in the race window returns nil here
                    ;; (no throw, and the replacement generation is never
                    ;; incremented); the enqueued envelope is discarded by run-fire!.
                   (db/mark-wake-attempt! ds key (:wake_at wake))
                   (update acc :dispatched inc))
                 (do
                   (swap! (:in-flight st) disj key)
                   (record-dispatch-failure! st key "event queue saturated; wake left pending")
                   (assoc acc :transient? true))))))))
     {:dispatched 0 :transient? false}
     due)))

(defn dispatch-due!
  "Enqueue a fire envelope for every due, not-in-flight wake at the current clock.

  Claims each wake in the in-flight set before offering onto the event queue so a
  later arm/tick cannot re-dispatch it. The delivery attempt number reported to
  the handler is the wake's persisted attempt count plus one, and the durable
  increment is written only after a successful enqueue — so a queue-full retry
  re-delivers the same attempt number instead of overcounting, and the first real
  delivery always observes attempt 1. A full event queue is transient: the claim
  is released, a dispatch failure is recorded, and the durable row stays pending
  for the next arm. The durable increment is generation-specific (key + the
  selected wake_at): a row that vanished or was rescheduled since the due select
  (an ordinary cancel/reschedule race) leaves it a no-op, so a replacement
  generation is never miscounted and its first real delivery still observes
  attempt 1; the stale envelope is dropped by run-fire!'s generation guard.
  Returns {:dispatched n :transient? bool}."
  [runtime]
  (dispatch-due!* runtime db/due-wakes))

(defn- schedule-tick!
  "Schedule the next timer tick after delay-ms, replacing any current timer.

  Assumes the caller does not already hold the state lock; takes it to serialize
  arming against dispatch/reload."
  [runtime delay-ms]
  (let [st (state runtime)]
    ;; :lock is the dedicated per-state (Object.) monitor; false positive, see close-state!.
    #_{:splint/disable [lint/locking-object]}
    (locking (:lock st)
      (when-not @(:closed? st)
        (when-let [^ScheduledFuture fut @(:timer st)]
          (.cancel fut false))
        (reset! (:timer st)
                (.schedule ^ScheduledExecutorService (:executor st)
                           ^Runnable (fn [] (dispatch-and-rearm! runtime))
                           (long delay-ms)
                           TimeUnit/MILLISECONDS))))))

(defn arm!
  "Cancel any pending timer and arm the next non-in-flight durable wake.

  Fires immediately (delay 0) for overdue rows. When every pending row is already
  in-flight, or none are pending, no timer is armed; the in-flight fire's
  completion re-arms via `run-fire!`."
  [runtime]
  (let [ds (:datasource runtime)
        st (state runtime)]
    ;; :lock is the dedicated per-state (Object.) monitor; false positive, see close-state!.
    #_{:splint/disable [lint/locking-object]}
    (locking (:lock st)
      (when-not @(:closed? st)
        (when-let [^ScheduledFuture fut @(:timer st)]
          (.cancel fut false)
          (reset! (:timer st) nil))
        (let [now-ms (.toEpochMilli (now-instant runtime))
              in-flight @(:in-flight st)
              parked @(:parked-wakes st)
              next (first (remove #(or (contains? in-flight (:key %))
                                       (contains? parked [(:key %) (:wake_at %)]))
                                  (db/pending-wakes ds)))]
          (when next
            (reset! (:timer st)
                    (.schedule ^ScheduledExecutorService (:executor st)
                               ^Runnable (fn [] (dispatch-and-rearm! runtime))
                               (long (max 0 (- (:wake_at next) now-ms)))
                               TimeUnit/MILLISECONDS)))))))
  nil)

(defn- dispatch-and-rearm!
  "Timer-thread body: dispatch due wakes, then re-arm (backing off on saturation).

  The dispatch/re-arm sequence is fully guarded: any unexpected throw records a
  dispatch failure and still re-arms on a short backoff, so a single failed tick
  can never silently stop the runtime's clock trigger for every pending wake
  until the next reload/restart (TEN-003). Ordinary cancel/reschedule races no
  longer throw here — `dispatch-due!` treats a vanished row as a no-op — but the
  guard remains the last-resort net for anything else."
  ([runtime]
   (dispatch-and-rearm! runtime {}))
  ([runtime {:keys [dispatch-due-fn]
             :or {dispatch-due-fn dispatch-due!}}]
   (try
     (if (:transient? (dispatch-due-fn runtime))
       (schedule-tick! runtime transient-retry-ms)
       (arm! runtime))
     (catch Throwable t
       (record-dispatch-failure! (state runtime) nil (or (ex-message t) (str (class t))))
       (try
         (schedule-tick! runtime transient-retry-ms)
         (catch Throwable _ nil))))))

(defn- register-pump!
  "Register the scheduler's synchronous due-check with the runtime clock-pump
  registry so `millstrand.test.alpha/advance!` can drive dispatch off an injected
  clock without waiting on the real timer thread.

  Pokes the runtime's `:clock-pumps` slot directly rather than calling
  `millstrand.core.weaver.runtime/register-clock-pump!`: runtime requires this
  namespace, so a static require back would cycle. Throws when the slot is
  absent so a malformed runtime fails loudly instead of silently disabling
  deterministic clock pumping."
  [runtime]
  (if-let [pumps (:clock-pumps runtime)]
    (swap! pumps assoc ::pump dispatch-due!)
    (throw (ex-info "Runtime has no :clock-pumps registry to register the scheduler pump"
                    {:pump ::pump})))
  nil)

(defn rearm!
  "Rebuild scheduler timers from durable pending rows after config load.

  Called post-startup and post-reload. Reload clears the event-system queue, so
  any fire envelope already queued is discarded; the in-flight set is therefore
  reset and rebuilt from pending rows to preserve at-least-once delivery. Also
  (re)registers the clock-consumer pump so deterministic tests can drive due
  dispatch off the runtime clock."
  [runtime]
  (let [st (state runtime)]
    ;; :lock is the dedicated per-state (Object.) monitor; false positive, see close-state!.
    #_{:splint/disable [lint/locking-object]}
    (locking (:lock st)
      (reset! (:in-flight st) #{})
      (reset! (:parked-wakes st) {})))
  (register-pump! runtime)
  (arm! runtime))

(defn run-fire!
  "Resolve and invoke a due wake's handler on the shared event lane.

  Called by the runtime event worker for a `:scheduler/fire` envelope, already
  under the runtime binding and generation classloader. Re-reads the durable row so a
  cancelled or rescheduled key is skipped; resolves the handler symbol in the
  generation classloader; invokes it with the documented context map; records
  completion or failure into scheduler history. Never throws into the worker: the
  key is always released from the in-flight set and the scheduler re-armed."
  [runtime envelope]
  (let [ds (:datasource runtime)
        st (state runtime)
        key (:scheduler/key envelope)]
    (try
      (let [row (db/get-pending-wake ds key)]
        ;; The two nil branches are distinct race outcomes (vanished vs. superseded
        ;; wake) whose separate rationale comments are the point; keep them apart.
        #_{:splint/disable [lint/identical-branches]}
        (cond
          ;; Cancelled or completed out from under us since dispatch.
          (nil? row) nil
          ;; Rescheduled after this fire was enqueued: a newer wake owns the key
          ;; now; leave it for the re-arm below rather than firing stale data.
          (not= (:scheduler/wake-at-millis envelope) (:wake_at row)) nil
          :else
          (let [handler-sym (symbol (:handler row))
                _ (when (or (orphaned-handler? runtime handler-sym)
                            (not (handler-available? handler-sym)))
                    (park-wake! st row :handler-unresolved)
                    (throw (ex-info "Scheduler wake handler is unavailable; wake parked"
                                    {:scheduler/parked? true
                                     :key key :handler handler-sym})))
                resolved (try
                           (requiring-resolve handler-sym)
                           (catch Throwable t
                             (throw (ex-info "Scheduler handler could not be resolved"
                                             {:key key :handler handler-sym}
                                             t))))
                handler-fn (if (var? resolved) @resolved resolved)]
            (when-not (ifn? handler-fn)
              (throw (ex-info "Scheduler handler symbol did not resolve to a callable value"
                              {:key key :handler handler-sym})))
            (try
              (handler-fn {:runtime runtime
                           :key key
                           :wake-at (Instant/ofEpochMilli (:wake_at row))
                           :attempt (:scheduler/attempt envelope)
                           :handler handler-sym
                           :payload (db/<-json (:payload row))})
              (db/complete-wake! ds row)
              (catch Throwable t
                (db/fail-wake! ds row (or (ex-message t) (str (class t)))))))))
      (catch Throwable t
        ;; Handler resolution failure (or a lost race with cancel/complete):
        ;; record it as a failed fire against the delivered generation when that
        ;; generation is still pending, so the failure is visible without
        ;; crashing the lane.
        (when-let [current (db/get-pending-wake ds key)]
          (when (and (not (:scheduler/parked? (ex-data t)))
                     (= (:scheduler/wake-at-millis envelope) (:wake_at current)))
            (try
              (db/fail-wake! ds current (or (ex-message t) (str (class t))))
              (catch Throwable _ nil)))))
      (finally
        (swap! (:in-flight st) disj key)
        (arm! runtime)))
    nil))
