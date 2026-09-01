(ns me.notifications.attention
  "This repo's chime attention rules: HITL checkpoints, kanban completion, and parked runs.

  Developers bind how they are notified in gitignored init.local.clj with
  (chime/set-notifier! {:argv [...]})."
  (:require [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millhouse.spools.chime :refer [defrule]]
            [ct.spools.agent-run :as shuttle]))

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
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(def ^:private timestamp-state-version 1)
(def ^:private timestamp-failure-memory 100)

(defn- new-timestamp-state []
  {:logged-ts-parse-failures (atom [])})

(defn- logged-ts-parse-failures []
  (:logged-ts-parse-failures
   (runtime/spool-state (current/runtime) ::timestamp-state
                        {:version timestamp-state-version}
                        new-timestamp-state)))

(defn- strand-age-ms
  "Milliseconds since a strand's last mutation, parsing SQLite's UTC
  `yyyy-MM-dd HH:mm:ss` updated_at. Returns nil when absent or unparseable.

  A parse failure would silently disable the parked-run detector for that strand
  (its whole point is catching silent failures), so an unparseable timestamp is
  warned to stderr once per distinct value rather than swallowed — a timestamp
  format drift surfaces instead of defeating the detector unnoticed."
  [strand]
  (when-let [ts (:updated_at strand)]
    (try
      (- (System/currentTimeMillis)
         (-> (java.time.LocalDateTime/parse ts sqlite-timestamp-formatter)
             (.toInstant java.time.ZoneOffset/UTC)
             (.toEpochMilli)))
      (catch java.time.format.DateTimeParseException e
        (when-not (some #(= ts %) @(logged-ts-parse-failures))
          (swap! (logged-ts-parse-failures)
                 #(vec (take-last timestamp-failure-memory (conj % ts))))
          (binding [*out* *err*]
            (println (str "[attention] WARN parked-run detector could not parse strand updated_at;"
                          " expected UTC format yyyy-MM-dd HH:mm:ss; check the weaver's"
                          " updated_at source and reload after correcting it "
                          (pr-str {:strand (:id strand) :updated_at ts
                                   :exception/message (ex-message e)})))))
        nil))))

(defrule parked-run
  "Notify when a ready pending agent run has sat unclaimed past the threshold.

  This is the silent-parking detector: the morning incident left runs ready and
  pending forever because scan! launched them onto a nil executor. A run that is
  ready (blockers cleared), still `pending`, not tracked in-flight, and older
  than the threshold is one the launch path should have spawned but did not."
  [{:keys [strand ready-ids]}]
  (when (and (= "active" (:state strand))
             (= "true" (config-attr strand :agent-run/run))
             (= "pending" (config-attr strand :agent-run/phase))
             (contains? ready-ids (:id strand))
             (not (contains? (shuttle/in-flight-run-ids) (:id strand)))
             (when-let [age (strand-age-ms strand)]
               (>= age parked-run-threshold-ms)))
    {:title (str "Agent run parked: " (:title strand))
     :body (str "Agent run " (:id strand) " has been ready and pending for over "
                (quot parked-run-threshold-ms 60000) " minutes with no in-flight claim."
                " This is the silent-parking signature — verify the weaver's agent-run"
                " executors are healthy and the run was not dropped by a reload.")}))
