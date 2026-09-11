(ns me.workflows.merge-queue
  "Strict FIFO landing turns, driven by short workflow queue gates."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.workflow :as workflow]
            [millhouse.spools.executors.shell :as shell]
            [me.workflows.land :as land]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! poll-until!]]
            [millstrand.api.weaver.alpha :as weaver]))

(s/def ::non-blank (s/and string? (complement str/blank?)))
(s/def ::timeout-secs (s/and int? (complement neg?)))

(defn- with-guard [f]
  (let [rt (current/runtime)
        config-dir (get-in rt [:metadata :config-dir])
        {:keys [monitor]} (runtime/spool-state rt ::state {:version 1}
                                               #(hash-map :monitor (Object.)))]
    (when-not (s/valid? ::non-blank config-dir)
      (fail! "Merge queue requires a selected workspace" {:config-dir config-dir}))
    (locking monitor
      (with-open [file (java.io.RandomAccessFile.
                        (io/file config-dir ".land-merge-lock.acquire") "rw")
                  channel (.getChannel file)
                  _lock (.lock channel)]
        (f)))))

(defn- rows [kind active?]
  (weaver/list (current/runtime)
               (cond-> [:and [:= [:attr "kind"] kind]]
                 active? (conj [:= :state "active"])) {}))

(defn- entries []
  (let [active (rows "merge-queue-entry" true)
        sequences (mapv #(attr-get % :queue/sequence) active)]
    (when-not (and (every? nat-int? sequences)
                   (= (count sequences) (count (distinct sequences))))
      (fail! "Merge queue ordering is invalid"
             {:entries (mapv :id active) :sequences sequences}))
    (vec (sort-by #(attr-get % :queue/sequence) active))))

(defn- lock-row []
  (let [locks (rows "merge-lock" true)]
    (when (> (count locks) 1)
      (fail! "Multiple merge locks require explicit repair" {:locks (mapv :id locks)}))
    (when-let [lock (first locks)]
      (when-not (s/valid? ::non-blank (attr-get lock :land/run-id))
        (fail! "Merge lock has no run owner" {:lock (:id lock)}))
      lock)))

(defn- entry-for [run-id]
  (let [matches (filter #(= run-id (attr-get % :land/run-id)) (entries))]
    (when (> (count matches) 1)
      (fail! "Run has multiple queue reservations" {:run-id run-id}))
    (first matches)))

(defn- require-entry [id]
  (let [entry (weaver/show (current/runtime) id)]
    (when-not (= "merge-queue-entry" (attr-get entry :kind))
      (fail! "Expected a merge queue entry" {:entry id}))
    entry))

(defn- gate-for [run-id waiter]
  (first (filter #(= waiter (:gate %)) (workflow/ready run-id))))

(defn- patch! [patches]
  (batch/apply! (current/runtime)
                {:refs (into {} (map (fn [[id _]] [(keyword id) id])) patches)
                 :strands (mapv (fn [[id patch]] (assoc patch :ref (keyword id))) patches)
                 :edges []}))

(defn- next-sequence []
  (let [numbers (map #(attr-get % :queue/sequence) (rows "merge-queue-entry" false))]
    (when-not (every? nat-int? numbers)
      (fail! "Merge queue history contains an invalid sequence" {}))
    (inc (reduce max -1 numbers))))

(defn join!
  "Reserve a run's FIFO position at its merge-turn gate; repeat calls retain it."
  [run-id]
  (with-guard
    (fn []
      (or (entry-for run-id)
          (let [root (workflow/current-root run-id)
                gate (gate-for run-id "merge-turn")]
            (when-not (and root gate)
              (fail! "Join the merge queue at the merge-turn gate" {:run-id run-id}))
            (weaver/add!
             (current/runtime)
             {:title (str "Merge queue: " run-id)
              :attributes {:kind "merge-queue-entry"
                           :land/run-id run-id
                           :queue/root (:id root)
                           :queue/gate (:id gate)
                           :queue/sequence (next-sequence)
                           :queue/queued-at (str (runtime/now (current/runtime)))}}))))))

(defn grant!
  "Grant the head run's turn and close its queue gate without blocking a worker.

  Failure after lock creation retains the lock and reservation for retry in
  place. A non-head run simply remains waiting."
  [run-id]
  (with-guard
    (fn []
      (let [entry (entry-for run-id)
            gate (gate-for run-id "merge-turn")
            lock (lock-row)]
        (when (and entry gate
                   (= (:id entry) (:id (first (entries))))
                   (not (attr-get entry :queue/withdraw-reason))
                   (or (nil? lock) (= run-id (attr-get lock :land/run-id))))
          (when-not lock
            (weaver/add! (current/runtime)
                         {:title (str "Merge lock: " run-id)
                          :attributes {:kind "merge-lock" :land/run-id run-id
                                       :queue/entry (:id entry)}}))
          (workflow/complete! run-id {:step (:id gate) :by "merge-turn"}))))))

(defn release!
  "Close a completed turn's reservation and lock before closing its release gate.

  The queue writes share one batch. If workflow completion fails afterwards,
  retry recognizes the closed reservation and never releases another run's lock."
  [run-id]
  (with-guard
    (fn []
      (when-let [gate (gate-for run-id "merge-release")]
        (if-let [entry (entry-for run-id)]
          (let [lock (lock-row)]
            (when-not (= run-id (some-> lock (attr-get :land/run-id)))
              (fail! "Releasing a merge turn requires its own lock" {:run-id run-id}))
            (when (attr-get entry :queue/withdraw-reason)
              (fail! "Withdrawal is pending for this turn" {:entry (:id entry)}))
            (patch! {(:id entry) {:state "closed"
                                  :attributes {:queue/outcome "merged"
                                               :queue/released-at (str (runtime/now (current/runtime)))}}
                     (:id lock) {:state "closed"}}))
          (when-not (some #(and (= run-id (attr-get % :land/run-id))
                                (= "merged" (attr-get % :queue/outcome)))
                          (rows "merge-queue-entry" false))
            (fail! "No completed merge reservation for release retry" {:run-id run-id})))
        (workflow/complete! run-id {:step (:id gate) :by "merge-release"})))))

(defn- entry-view [entry lock]
  (let [run-id (attr-get entry :land/run-id)
        root (workflow/current-root run-id)]
    {:id (:id entry)
     :run-id run-id
     :state (:state entry)
     :outcome (attr-get entry :queue/outcome)
     :sequence (attr-get entry :queue/sequence)
     :queued-at (attr-get entry :queue/queued-at)
     :withdraw-reason (attr-get entry :queue/withdraw-reason)
     :holds-lock (= run-id (some-> lock (attr-get :land/run-id)))
     :run-state (if root "active" "missing")
     :updated-at (:updated_at root)
     :frontier (when root
                 (mapv (fn [step]
                         (assoc (select-keys step [:id :title :role :gate :checkpoint])
                                :error (attr-get (weaver/show (current/runtime) (:id step))
                                                 :gate/error)))
                       (workflow/ready run-id)))}))

(defn status
  "Report active FIFO order or one reservation, with current workflow evidence."
  ([]
   (let [lock (lock-row)]
     {:lock (when lock {:id (:id lock) :run-id (attr-get lock :land/run-id)})
      :entries (mapv (fn [index entry] (assoc (entry-view entry lock) :position index))
                     (range) (entries))}))
  ([id]
   (let [entry (require-entry id)
         queue (:entries (status))
         position (first (keep-indexed #(when (= id (:id %2)) %1) queue))]
     (assoc (entry-view entry (lock-row))
            :position position
            :ahead (if position (subvec queue 0 position) [])))))

(defn await-turn
  "Wait for a reservation to hold the turn or close; timeout preserves its place."
  [id timeout-secs]
  (when-not (s/valid? ::timeout-secs timeout-secs)
    (fail! "Queue await requires non-negative timeout seconds" {:timeout-secs timeout-secs}))
  (poll-until! (runtime/clock (current/runtime))
               {:timeout-ms (* 1000 timeout-secs)
                :poll-ms 1000
                :check #(status id)
                :pred->result #(when (or (:holds-lock %) (= "closed" (:state %))) %)
                :on-timeout #(assoc % :timeout true)}))

(defn- run-strands [root]
  (:strands (graph/subgraph (current/runtime) [(:id root)] {:type "parent-of"})))

(defn- abort-payload [root run-id reason]
  (let [params (assoc (attr-get root :workflow/context) :reason reason)]
    (when-not (s/valid? ::land/land-abort-params params)
      (fail! "Landing context cannot continue into abort" {:run-id run-id :context params}))
    (workflow/compile land/land-abort params
                      {:run-id run-id :family "land" :context params
                       :definition 'me.workflows.land/land-abort})))

(defn- close-and-abort! [entry lock root payload reason]
  (let [closeable (filter #(and (= "active" (:state %))
                                (contains? #{"root" "step" "checkpoint" "defer" "procedure"}
                                           (attr-get % :workflow/role)))
                          (run-strands root))
        patches (into {(:id entry) {:state "closed"
                                    :attributes {:queue/outcome "withdrawn"
                                                 :queue/withdraw-reason reason
                                                 :queue/released-at (str (runtime/now (current/runtime)))}}}
                      (map (fn [strand] [(:id strand) {:state "closed"}])) closeable)
        patches (cond-> patches
                  lock (assoc (:id lock) {:state "closed"}))]
    (batch/apply! (current/runtime)
                  {:refs (into {} (map (fn [[id _]] [(keyword id) id])) patches)
                   :strands (into (mapv (fn [[id patch]] (assoc patch :ref (keyword id))) patches)
                                  (:strands payload))
                   :edges (:edges payload)})))

(defn withdraw!
  "Stop a named landing and atomically replace it with abort bookkeeping.

  Any trusted agent may withdraw; no owner restriction or timeout eviction.
  Shell quiescence precedes release. A started irreversible gate requires
  reconciliation instead: cancelling a local client cannot undo a remote merge.
  A failed withdrawal keeps the reservation and lock, with shell gates frozen
  for inspection. Repair and retry those gates to resume the original landing."
  [id reason]
  (when-not (s/valid? ::non-blank reason)
    (fail! "Withdrawal requires a non-blank reason" {:entry id}))
  (with-guard
    (fn []
      (let [entry (require-entry id)
            run-id (attr-get entry :land/run-id)]
        (if (= "closed" (:state entry))
          (when-not (= "withdrawn" (attr-get entry :queue/outcome))
            (fail! "A completed merge cannot be withdrawn" {:entry id}))
          (let [root (workflow/current-root run-id)
                lock (lock-row)
                own-lock (when (= run-id (some-> lock (attr-get :land/run-id))) lock)]
            (when-not (and root (= (:id root) (attr-get entry :queue/root)))
              (fail! "Queue reservation no longer identifies the current root" {:entry id}))
            (let [payload (abort-payload root run-id reason)
                  stopped (shell/quiesce-run! run-id reason)
                  attempted (into #{} (keep #(when (:attempted? %) (:gate-id %))) (:gates stopped))]
              (when-let [gate (first (filter #(and (true? (attr-get % :land/irreversible))
                                                   (or (= "closed" (:state %))
                                                       (some? (attr-get % :shell/output))
                                                       (some? (attr-get % :shell/exit-code))
                                                       (contains? attempted (:id %))))
                                             (run-strands root)))]
                (fail! "Merge may already have been submitted; reconcile and resume this turn"
                       {:entry id :gate (:id gate) :run-id run-id}))
              (close-and-abort! entry own-lock root payload reason))))
        (status id)))))

(def ^:private queue-args
  {:op "merge-queue"
   :doc "Inspect or explicitly withdraw strict FIFO landing reservations."
   :subcommands
   {"join" {:doc "Reserve a run at its merge-turn gate; repeats retain its place."
            :hook-class :mutating :deadline-class :standard
            :positionals [{:name :run-id :required? true :spec ::non-blank}]}
    "status" {:doc "Show queue order or one entry with workflow progress."
              :hook-class :read :deadline-class :standard
              :positionals [{:name :entry-id :spec ::non-blank}]}
    "await" {:doc "Wait for a reserved turn; timeout never dequeues it."
             :hook-class :read :deadline-class :unbounded
             :flags {:timeout-secs {:type :int :spec ::timeout-secs
                                    :doc "Seconds to wait; defaults to 300."}}
             :positionals [{:name :entry-id :required? true :spec ::non-blank}]}
    "withdraw" {:doc "Stop a named landing and release its turn with an explicit reason."
                :hook-class :mutating :deadline-class :unbounded
                :flags {:reason {:type :string :required? true :spec ::non-blank}}
                :positionals [{:name :entry-id :required? true :spec ::non-blank}]}}})

(millstrand/defop merge-queue
  "Own strict FIFO reservations; ordinary landing progression uses workflow verbs."
  {:arg-spec queue-args
   :returns {:subcommands (into {} (map (fn [name] [name {:type :map :extra :json}]))
                                (keys (:subcommands queue-args)))}
   :prime (format-alpha/prose
           "
             Sign-off joins the queue automatically. Use workflow ready and await
             to drive the run. A failed head keeps its place and lock while repaired.
             Inspect merge-queue status for progress and merge-queue await ENTRY
             for a repeatable wait. Timeout never moves a reservation.

             Any trusted agent may withdraw another run with merge-queue withdraw
             ENTRY --reason REASON. Withdrawal stops merge work before releasing
             the turn. There is no automatic eviction or second merge approval.
           " {})}
  [ctx]
  (let [{:keys [subcommand run-id entry-id timeout-secs reason]} (:op/args ctx)]
    (case (first subcommand)
      "join" {:entry (join! run-id)}
      "status" (if entry-id (status entry-id) (status))
      "await" (await-turn entry-id (or timeout-secs 300))
      "withdraw" (withdraw! entry-id reason))))

(defn- gate-error [view]
  (when-let [error (attr-get (weaver/show (current/runtime) (:id view)) :gate/error)]
    {:gate (:id view) :error error}))

(workflow/defexecutor merge-turn
  "Wait for automatic FIFO admission and acquisition; failed gates expose their error."
  {}
  [view]
  (gate-error view))

(workflow/defexecutor merge-release
  "Release the completed merge turn automatically before housekeeping."
  {}
  [view]
  (gate-error view))

(defn scan!
  "Advance ready queue gates using short serialized mutations, never a worker wait."
  []
  (doseq [root (workflow/active-runs "land")
          :let [run-id (attr-get root :workflow/run-id)]
          gate (workflow/ready run-id)
          :when (and (contains? #{"merge-turn" "merge-release"} (:gate gate))
                     (nil? (gate-error gate)))]
    (try
      (case (:gate gate)
        "merge-turn" (do (join! run-id) (grant! run-id))
        "merge-release" (release! run-id))
      (catch Exception e
        (weaver/update! (current/runtime) (:id gate)
                        {:attributes {:gate/error (str (ex-message e)
                                                       (some->> (ex-data e) (str " ")))}}))))
  {:scanned true})

(defn on-event
  "Reconsider queue gates after graph mutations."
  [_event]
  (scan!))

(defn open-handler!
  "Register queue scanning and recover pending queue gates on activation."
  [{:keys [runtime]}]
  (events/register-handler! runtime :land/merge-queue
                            #{:strand/added :strand/updated :batch/applied
                              :strand/burned :strand/superseded}
                            'me.workflows.merge-queue/on-event {})
  (current/with-runtime runtime (scan!))
  {:registered :land/merge-queue})

(defn close-handler!
  "Remove the module's queue scanner; durable reservations remain."
  [{:keys [runtime]}]
  (events/unregister-handler! runtime :land/merge-queue)
  {:unregistered :land/merge-queue})

(lifecycle/defresource queue-handler
  "Drive durable FIFO queue gates on graph changes."
  {:open 'me.workflows.merge-queue/open-handler!
   :close 'me.workflows.merge-queue/close-handler!})
