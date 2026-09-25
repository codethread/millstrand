(ns me.notifications.attention
  "This repo's chime attention rules: HITL checkpoints, kanban completion, and parked runs.

  Developers bind how they are notified in gitignored init.local.clj with
  (chime/set-notifier! {:argv [...]})."
  (:require [millhouse.chime :refer [defrule]]))

(defn- config-attr
  "Read strand attribute k, tolerating keyword- or string-keyed maps."
  [strand k]
  (let [attrs (:attributes strand)]
    (or (get attrs k) (get attrs (subs (str k) 1)))))

(defrule hitl-checkpoint-ready
  "Notify when a human-in-the-loop workflow checkpoint is ready to decide."
  [{:keys [strand ready-ids]}]
  (when (and (= "active" (:state strand))
             (= "checkpoint" (config-attr strand :workflow/role))
             (= "human" (config-attr strand :workflow/checkpoint-kind))
             (contains? ready-ids (:id strand)))
    {:title (str "HITL checkpoint ready: " (:title strand))
     :body (str "Checkpoint " (:id strand) " is ready for human attention.")}))

(defrule kanban-completed
  "Notify when a kanban card reaches the explicit done outcome."
  [{:keys [strand]}]
  (when (and (= "closed" (:state strand))
             (= "true" (config-attr strand :kanban/card))
             (= "done" (config-attr strand :kanban/outcome)))
    {:title (str "Kanban done: " (:title strand))
     :body (str "Kanban card " (:id strand) " completed fully.")}))

(def ^:private parked-run-threshold-ms
  "How long a ready, unclaimed pending run may sit before it counts as silently
  parked rather than momentarily between scans."
  (* 5 60 1000))

(def ^:private sqlite-timestamp-formatter
  (-> (java.time.format.DateTimeFormatter/ofPattern "uuuu-MM-dd HH:mm:ss")
      (.withResolverStyle java.time.format.ResolverStyle/STRICT)))

(defn- strand-age-ms
  "Return milliseconds since a strand's last mutation.

  Parse SQLite's UTC `yyyy-MM-dd HH:mm:ss` updated_at at this boundary. Return
  nil when the value is absent; throw with strand and raw timestamp context when
  it is malformed."
  [strand]
  (when-let [timestamp (:updated_at strand)]
    (try
      (- (System/currentTimeMillis)
         (-> (java.time.LocalDateTime/parse timestamp sqlite-timestamp-formatter)
             (.toInstant java.time.ZoneOffset/UTC)
             (.toEpochMilli)))
      (catch java.time.format.DateTimeParseException cause
        (throw (ex-info "Parked-run detector could not parse strand updated_at"
                        {:strand (:id strand)
                         :updated_at timestamp}
                        cause))))))

(defrule parked-run
  "Notify when a ready pending Harnesses run has sat unclaimed past the threshold.

  This is the silent-parking detector: a run that is ready (blockers cleared),
  still pending, and older than the threshold is one the launch path should
  have scheduled but did not."
  [{:keys [strand ready-ids]}]
  (when (and (= "active" (:state strand))
             (= "true" (config-attr strand :harness/run))
             (= "ready" (config-attr strand :harness/status))
             (= "pending" (config-attr strand :harness/substatus))
             (contains? ready-ids (:id strand))
             (when-let [age (strand-age-ms strand)]
               (>= age parked-run-threshold-ms)))
    {:title (str "Harness run parked: " (:title strand))
     :body (str "Harness run " (:id strand) " has been ready and pending for over "
                (quot parked-run-threshold-ms 60000) " minutes without a scheduler transition."
                " This is the silent-parking signature — verify the weaver's Harnesses"
                " executors are healthy and the run was not dropped by a reload.")}))
