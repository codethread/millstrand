(ns millstrand.spools.cron
  "Userland recurrence layer over the weaver's durable scheduler wake primitive.

  Cron registers named jobs that fire on a fixed interval with uniform jitter.
  It owns no timing of its own: each registered job is a durable `cron/<id>`
  scheduler wake (`millstrand.api.scheduler.alpha`), so the cadence survives weaver
  restart and reload, and scheduler introspection is the single timing view. A
  caller registers a job by fully-qualified `:handler` symbol resolving to a
  `(fn [runtime] ..)`; the engine owns only the wake wiring, the job's
  last-result status, and a loud inspectable failure log. It is deliberately
  just recurrence — workflow/gate integration is intentionally out of scope.

  Delivery model. The scheduler dispatches a due `cron/<id>` wake to
  `fire-wake` on the weaver's shared serialized event lane. `fire-wake` stays
  tiny so it never holds the lane on job work: it reschedules the next wake and
  hands the job body off to a cron-owned execution executor, then returns so the
  scheduler completes the delivered wake. The job's own success/failure is
  recorded cron-side and never interrupts cadence. Delivery is at-least-once, so
  `:handler` bodies must tolerate duplicate fires (TEN-003, `SPEC-004.C101`).

  State is runtime-owned via `millstrand.api.runtime.alpha/spool-state`, so two
  runtimes in one JVM keep independent executors, job tables, and failure logs.
  The in-memory job table carries no cadence: collected `defjob` declarations
  converge through Cron's lifecycle effect after publication, while the durable
  wake in SQLite is the sole authority for when a job next fires. Trusted
  callers may still use `register!` directly."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.spool.alpha :refer [fail! poll-until! reject-unknown-keys!
                                           require-valid!]])
  (:import [java.time Instant]
           [java.util Random]
           [java.util.concurrent ExecutorService Executors
            ThreadFactory TimeUnit]))

(declare execute-job!)

(def ^:private state-version
  "Shape version for cron's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives module refresh, so a post-upgrade refresh
  would otherwise reuse a preserved map missing the new key and offload against
  a nil executor (docs/spools/writing-shared-spools.md 'Versioned spool state',
  SPEC-004.C95). The `state-shape-matches-declared-version` test fails loudly if
  `new-state` and this version drift apart."
  4)

(def job-kind
  "Owner-partitioned kind id for Cron job declarations."
  :millstrand.spools.cron/jobs)
(def ^:private repl-owner :millstrand.owner/repl)

(s/def ::id (s/or :keyword keyword?
                  :string (s/and string? (complement str/blank?))))
(s/def ::interval-ms pos-int?)
(s/def ::jitter-ms nat-int?)
(s/def ::handler qualified-symbol?)
(s/def ::job (s/keys :req-un [::id ::interval-ms ::handler] :opt-un [::jitter-ms]))
(s/def ::job-options
  (s/and map?
         #(every? #{:override?} (keys %))
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))
(s/def ::runtime #(and (map? %) (contains? % :spool-state)))
(s/def ::jobs (s/map-of keyword? ::job))
(s/def ::lifecycle-context
  (s/and map?
         #(s/valid? ::runtime (:runtime %))))
(s/def ::apply-context
  (s/and ::lifecycle-context
         #(s/valid? ::jobs (:desired %))
         #(s/valid? ::jobs (:actual %))))
(s/def ::reconciled #{:cron})
(s/def ::job-ids (s/coll-of keyword? :kind vector?))
(s/def ::reconcile-result
  (s/and map?
         #(= #{:reconciled :jobs} (set (keys %)))
         #(s/valid? ::reconciled (:reconciled %))
         #(s/valid? ::job-ids (:jobs %))))

(defn- ^ThreadFactory daemon-thread-factory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- new-state []
  (let [^ExecutorService executor (Executors/newSingleThreadExecutor
                                   (daemon-thread-factory "cron"))]
    {:executor executor
     ;; id -> {:id :interval-ms :jitter-ms :handler sym
     ;;        :last-result :last-fired-at :last-error}
     :jobs (atom {})
     :failure-log (atom [])
     :rng (Random.)
     ;; In-flight offloaded-job latch: a count incremented on the event lane in
     ;; `fire-wake` before submit and decremented in the executor task's finally.
     ;; `await-quiescent!` polls this atom to zero.
     :in-flight-count (atom 0)
     :close-fn (fn []
                 (.shutdownNow executor)
                 (when-not (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
                   (fail! "Cron executor did not stop" {})))}))

(defn- state [runtime]
  (runtime/spool-state runtime ::state {:version state-version} new-state))

(defn- ^ExecutorService executor [runtime] (:executor (state runtime)))
(defn- jobs-atom [runtime] (:jobs (state runtime)))
(defn- job-kinds [runtime]
  (runtime/spool-state runtime ::job-kinds registry/registry))
(defn- failure-log [runtime] (:failure-log (state runtime)))
(defn- ^Random rng [runtime] (:rng (state runtime)))

(defn- record-failure! [runtime entry]
  (let [full (assoc entry :at (str (Instant/now)))]
    (swap! (failure-log runtime) #(->> (conj (vec %) full) (take-last 100) vec))
    full))

(defn recent-failures
  "Return recorded cron failures for this runtime's weaver lifetime, oldest
  first — the same bounded-ring ordering as
  `millstrand.api.events.alpha/recent-failures`. Each entry carries `:kind` (`:run`
  for a `:handler` throw, `:offload` for an execution-executor rejection),
  `:job`, a `:message`, and `:at`."
  [runtime]
  @(failure-log runtime))

(defn- jitter-offset-ms
  "Return a uniform jitter offset in the range [-bound-ms, bound-ms].

  `rng` is a `java.util.Random`; pass a seeded one for deterministic tests. A
  zero or negative bound yields 0."
  [bound-ms ^Random rng]
  (if (pos? bound-ms)
    (long (Math/round (* (- (* 2.0 (.nextDouble rng)) 1.0) (double bound-ms))))
    0))

(defn- reschedule-delay-ms [interval-ms jitter-ms ^Random rng]
  (max 0 (+ (long interval-ms) (jitter-offset-ms jitter-ms rng))))

(defn- job-id [id]
  (cond
    (keyword? id) id
    (and (string? id) (not (str/blank? id))) (keyword id)
    :else (fail! "Cron job :id must be a keyword or non-blank string" {:id id})))

(defn- wake-key
  "The stable scheduler wake key owning job `id`'s cadence."
  [id]
  (str "cron/" (name id)))

(defn- wake-pending?
  [runtime id]
  (let [key (wake-key id)]
    (some #(= key (:key %)) (scheduler/pending runtime))))

(defn- resolve-symbol [role sym]
  (when-not (and (symbol? sym) (namespace sym))
    (fail! (str "Cron job " role " must be a fully qualified symbol") {role sym}))
  (or (requiring-resolve sym)
      (fail! (str "Cron job " role " cannot be resolved") {role sym})))

(defn- arm-wake!
  "Persist (replacing any existing) the `cron/<id>` wake at `now + interval +
  jitter`, keyed and payloaded so `fire-wake` can rediscover the job."
  [runtime id interval-ms jitter-ms]
  (let [^Instant now (runtime/now runtime)
        delay-ms (reschedule-delay-ms interval-ms jitter-ms (rng runtime))
        wake-at (.plusMillis now (long delay-ms))]
    (scheduler/schedule! runtime {:key (wake-key id)
                                  :wake-at wake-at
                                  :handler 'millstrand.spools.cron/fire-wake
                                  :payload {:job (name id)}})))

(defn- config-tuple [job]
  [(:interval-ms job) (or (:jitter-ms job) 0) (:handler job)])

(defn unregister!
  "Cancel a cron job's pending wake and remove it from `runtime`.

  Returns `{:unregistered id}` when the job existed (in-memory config or a
  pending `cron/<id>` wake), else `{:unregistered nil}` — the delta from
  `millstrand.api.events.alpha/unregister-handler!`, which echoes the key back whether or not
  a handler was registered. Cron reports absence because a job's existence spans
  two stores (the in-memory table and the durable wake), so a caller cannot infer
  it. The scheduler `cancel!` fails loudly on an unknown key, so the cancel is
  guarded behind a `pending` check for `cron/<id>` — a missing wake is tolerated
  while genuine scheduler errors still surface
  (`PLAN-cron-on-scheduler-001.R1`)."
  [runtime id]
  (let [id (job-id id)
        key (wake-key id)
        old @(jobs-atom runtime)
        pending? (wake-pending? runtime id)]
    (when pending?
      (scheduler/cancel! runtime key))
    (swap! (jobs-atom runtime) dissoc id)
    {:unregistered (when (or pending? (contains? old id)) id)}))

(defn- in-flight-count [runtime] (:in-flight-count (state runtime)))

(defn- inc-in-flight! [runtime]
  (swap! (in-flight-count runtime) inc))

(defn- dec-in-flight! [runtime]
  (swap! (in-flight-count runtime) dec))

(defn- execute-job!
  "Run job `id`'s resolved `:handler` on the execution executor, recording the
  result cron-side and always releasing the in-flight latch. Never reschedules —
  cadence was already persisted on the event lane in `fire-wake`."
  [runtime id]
  (try
    (when-let [job (get @(jobs-atom runtime) id)]
      (let [fired-at (str (Instant/now))]
        (try
          (let [handler-fn (resolve-symbol :handler (:handler job))
                result (handler-fn runtime)]
            (swap! (jobs-atom runtime) update id
                   (fn [j] (when j (assoc j :last-result result
                                         :last-fired-at fired-at
                                         :last-error nil)))))
          (catch Throwable t
            (record-failure! runtime {:kind :run :job id
                                      :message (ex-message t) :data (ex-data t)})
            (swap! (jobs-atom runtime) update id
                   (fn [j] (when j (assoc j :last-error (ex-message t)
                                         :last-fired-at fired-at))))))))
    (finally
      (dec-in-flight! runtime))))

(defn fire-wake
  "Scheduler wake handler for a `cron/<id>` fire, run on the shared event lane.

  Invoked by `millstrand.core.weaver.scheduler/run-fire!` with its context map. Stays
  tiny so it never holds the lane on job work (`PLAN-cron-on-scheduler-001.A2`):
  (1) decode `{:job id}`; (2) look up the in-memory job — absent means the job
  was unregistered, so return without rescheduling; (3) reschedule the next
  `cron/<id>` wake **before** offload, so the cadence is persisted even if the
  offload fails; (4) count the job in-flight and submit its `:handler` to the
  cron-owned execution executor, recording an executor rejection loudly cron-side
  without throwing; (5) return so the scheduler completes the delivered wake. The
  job body never runs on the lane."
  [{:keys [runtime payload]}]
  (when-let [job (get @(jobs-atom runtime) (job-id (:job payload)))]
    (let [id (:id job)]
      (arm-wake! runtime id (:interval-ms job) (:jitter-ms job))
      (inc-in-flight! runtime)
      (try
        (.submit (executor runtime) ^Runnable (fn [] (execute-job! runtime id)))
        (catch Throwable t
          ;; Any submit-time failure — the expected executor shutdown/reload race
          ;; (RejectedExecutionException) or an unexpected throw from a corrupt
          ;; executor — means `execute-job!` never runs to release the latch in its
          ;; finally. The next wake is already armed, so balance the increment we
          ;; took, record the dropped run loudly, and return rather than throwing
          ;; into the lane (`PLAN-cron-on-scheduler-001.R3`).
          (dec-in-flight! runtime)
          (record-failure! runtime {:kind :offload :job id :message (ex-message t)})))))
  nil)

(defn await-quiescent!
  "Block until every offloaded cron job on `runtime` has finished, then return
  `runtime`.

  The deterministic join for tests: because job bodies run off the event lane,
  `millstrand.test.alpha/await-quiescent!` returns before a job completes. The
  in-flight latch is incremented on the event lane in `fire-wake` before submit,
  so once the lane has quiesced any offloaded job is already counted. Polls the
  latch atom until the count reaches zero or the budget expires on the runtime
  Clock, throwing loudly on timeout (TEN-003), mirroring the event-lane join in
  `millstrand.test.alpha/await-quiescent!`. `opts` accepts `:timeout-ms` (a
  positive integer); unknown keys are rejected loudly. The default budget comes
  from `millstrand.spools.test-support/await-budget-ms`."
  ([runtime] (await-quiescent! runtime {}))
  ([runtime {:keys [timeout-ms] :as opts}]
   (reject-unknown-keys! "await-quiescent!" #{:timeout-ms} opts)
   (let [counter (in-flight-count runtime)
         timeout-ms (or timeout-ms
                        ((requiring-resolve 'millstrand.spools.test-support/await-budget-ms)))]
     (require-valid! ::timeout-ms timeout-ms
                     "await-quiescent! :timeout-ms must be a positive integer")
     (poll-until!
      (runtime/clock runtime)
      {:timeout-ms timeout-ms
       :poll-ms 5
       :check #(deref counter)
       :pred->result #(when (zero? %) runtime)
       :on-timeout #(fail! "Timed out awaiting cron quiescence"
                           {:timeout-ms timeout-ms :in-flight %})}))))

;; Public seam shape (clojure.spec)
;;
;; `::job` is the declared, discoverable source of truth for `register!`'s job
;; map — the contract downstream config authors write against — matching the
;; sibling reference spools such as delegation. `register!` gates each field
;; through `require-valid!` so the specs own the shape while the contextual
;; loud messages survive (failing value + allowed shape, TEN-003), and closes
;; the key set with `reject-unknown-keys!` since `s/keys` stays open. `:id`
;; accepts a keyword or non-blank string (coerced by `job-id`); `:handler` only
;; asserts a fully-qualified symbol here — `resolve-symbol` layers the
;; requiring-resolve check spec cannot express.
;; `await-quiescent!`'s single opt: the poll budget in milliseconds.
(s/def ::timeout-ms pos-int?)

(def ^:private job-keys
  "The closed key set of a `register!` job map (see `::job`)."
  #{:id :interval-ms :jitter-ms :handler})

(defn register!
  "Register (or replace) a named cron job on `runtime` as a durable wake.

  The `job` map is validated against the `::job` spec (a keyword/non-blank
  `:id`, positive `:interval-ms`, optional non-negative `:jitter-ms`, and a
  fully-qualified `:handler` symbol); unknown keys are rejected loudly.

  `job` keys:
  - `:id` — keyword or non-blank string identifying the job.
  - `:interval-ms` — positive integer base period between fires.
  - `:jitter-ms` — non-negative integer; each fire is offset by a uniform value
    in [-jitter, +jitter]. Optional, default 0.
  - `:handler` — fully-qualified symbol resolving to `(fn [runtime] ..)`, invoked
    on every fire. Its return value is recorded as `:last-result`; a thrown
    exception is recorded in `recent-failures` and does not stop the cadence.
    **Delta from `millstrand.api.scheduler.alpha/schedule!`'s `:handler`**, one layer
    down: that handler is the wake-delivery callback and takes the wake context
    map, while this one is the job body and takes the runtime. Cron writes the
    scheduler's own `:handler` (always `millstrand.spools.cron/fire-wake`) on the
    `cron/<id>` wake it arms; a caller never writes that key here.

  Re-registration preserves a pending `cron/<id>` wake when the cadence-defining
  `[interval-ms jitter-ms handler]` tuple is unchanged, or when the runtime has
  no in-memory config yet (fresh JVM adopting a durable wake). A changed tuple arms
  a fresh wake at `now + interval + jitter`; a missing pending wake also arms a
  fresh wake. Returns the job's status map."
  [runtime job]
  (when-not (map? job)
    (fail! "Cron register! job must be a map" {:job job}))
  (reject-unknown-keys! "Cron register!" job-keys job)
  (require-valid! ::id (:id job)
                  "Cron job :id must be a keyword or non-blank string")
  (require-valid! ::interval-ms (:interval-ms job)
                  "Cron job :interval-ms must be a positive integer")
  (require-valid! ::jitter-ms (or (:jitter-ms job) 0)
                  "Cron job :jitter-ms must be a non-negative integer")
  (require-valid! ::handler (:handler job)
                  "Cron job :handler must be a fully-qualified symbol")
  (let [id (job-id (:id job))
        interval (:interval-ms job)
        jitter (or (:jitter-ms job) 0)]
    (resolve-symbol :handler (:handler job))
    (let [old-entry (get @(jobs-atom runtime) id)
          pending? (wake-pending? runtime id)
          entry {:id id :interval-ms interval :jitter-ms jitter :handler (:handler job)}
          replace? (or (not pending?)
                       (and old-entry (not= (config-tuple old-entry) (config-tuple entry))))]
      (when replace?
        (arm-wake! runtime id interval jitter))
      (swap! (jobs-atom runtime) assoc id entry)
      (get @(jobs-atom runtime) id))))

(defn job-declaration
  "Return a validated Cron job declaration.

  `options` conforms to `::job-options`; `job` conforms to `::job` after the
  stable `id` is attached. Override intent remains collection metadata."
  [id options job]
  (require-valid! ::job-options options "Invalid Cron job options")
  (reject-unknown-keys! "Cron job declaration" job-keys job)
  (require-valid! ::job (assoc job :id id) "Invalid Cron job declaration"))

(defmacro defjob
  "Collect one cron job declaration for the current runtime module.

  `id` is the stable cron job key and `job` is the same literal map accepted by
  `register!`. The optional `options` map conforms to `::job-options`. The macro
  performs no scheduling itself; Cron's lifecycle effect applies the complete
  effective declaration after publication."
  ([id job]
   `(defjob ~id {} ~job))
  ([id options job]
   `(runtime/collect-entry! job-kind ~id (job-declaration ~id ~options ~job)
                            (select-keys ~options #{:override?}))))

(runtime/collect-kind! ::job-kinds
                       {:id job-kind
                        :entry-spec ::job
                        :binding-moment :cron/fire})

(defn desired-jobs
  "Return the effective Cron job declarations for a `::lifecycle-context`."
  [{:keys [runtime] :as context}]
  (require-valid! ::lifecycle-context context "Invalid Cron lifecycle context")
  (into {}
        (map (fn [[id job]]
               (let [normalized-id (job-id id)]
                 [normalized-id (assoc job :id normalized-id)])))
        (registry/effective (job-kinds runtime) job-kind)))

(defn actual-jobs
  "Return Cron's currently managed jobs for a `::lifecycle-context`."
  [{:keys [runtime] :as context}]
  (require-valid! ::lifecycle-context context "Invalid Cron lifecycle context")
  @(jobs-atom runtime))

(defn- apply-job-change!
  [runtime operation id declaration change!]
  (try
    (change!)
    (catch Throwable t
      (throw (ex-info "Cron job reconciliation failed"
                      {:job id
                       :operation operation
                       :declaration declaration
                       :wake-key (wake-key id)
                       :remedy
                       (format-alpha/reflow
                        "|Repair the named Cron declaration or durable wake,
                         |then refresh the owning module.")}
                      t)))))

(defn apply-jobs!
  "Converge Cron's managed jobs from a validated `::apply-context`."
  [{:keys [runtime desired actual] :as context}]
  (require-valid! ::apply-context context "Invalid Cron apply context")
  (let [removed (remove (set (keys desired)) (keys actual))]
    (doseq [id removed]
      (apply-job-change! runtime :remove id (get actual id)
                         #(unregister! runtime id)))
    (doseq [[id job] desired]
      (when (or (not= (config-tuple job) (some-> (get actual id) config-tuple))
                (not (wake-pending? runtime id)))
        (apply-job-change! runtime :apply id job
                           #(register! runtime (assoc job :id id)))))
    (require-valid! ::reconcile-result
                    {:reconciled :cron :jobs (vec (sort (keys desired)))}
                    "Invalid Cron reconciliation result")))

(defn remove-jobs!
  "Cancel every managed job for a validated `::lifecycle-context`."
  [{:keys [runtime] :as context}]
  (require-valid! ::lifecycle-context context "Invalid Cron lifecycle context")
  (doseq [id (keys @(jobs-atom runtime))]
    (unregister! runtime id))
  (require-valid! ::reconcile-result
                  {:reconciled :cron :jobs []}
                  "Invalid Cron removal result"))

(lifecycle/defreconcile scheduled-jobs
  "Keep durable Cron wakes converged on the effective published job registry."
  {:read-desired 'millstrand.spools.cron/desired-jobs
   :read-actual 'millstrand.spools.cron/actual-jobs
   :apply 'millstrand.spools.cron/apply-jobs!
   :on-removed 'millstrand.spools.cron/remove-jobs!
   :trigger-kinds #{job-kind}})

(defn jobs
  "Return the cron jobs registered on `runtime` as status maps, sorted by id.

  Each map carries `:id`, `:interval-ms`, `:jitter-ms`, the `:handler` symbol,
  and (once fired) `:last-result`/`:last-fired-at`/`:last-error`. When a job next
  fires lives in its durable `cron/<id>` wake — read scheduler introspection
  (`millstrand.api.scheduler.alpha/pending`), the single timing view."
  [runtime]
  (->> @(jobs-atom runtime) vals (sort-by (comp str :id)) vec))
