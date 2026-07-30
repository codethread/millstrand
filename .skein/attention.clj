(ns attention
  "This repo's chime attention rules: what the devflow considers worth a
  human's attention. The chime engine is vocabulary-agnostic; these rules own
  the workflow, agent-run, gate, and kanban knowledge. Developers bind how they are
  notified in gitignored init.local.clj with (chime/set-notifier! {:argv [...]})."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.chime :refer [defrule]]
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

(defrule agent-failure
  "Notify when an agent run has failed or exhausted its attempts."
  [{:keys [strand]}]
  (let [phase (config-attr strand :agent-run/phase)]
    (when (contains? #{"failed" "exhausted"} phase)
      {:title (str "Agent run " phase ": " (:title strand))
       :body (str "Strand " (:id strand) " entered agent-run/phase " phase
                  (when-let [error (config-attr strand :agent-run/error)]
                    (str "\n\n" error)))})))

(defrule gate-error
  "Notify when a workflow gate is stamped with a gate error."
  [{:keys [strand]}]
  (when-let [error (config-attr strand :gate/error)]
    {:title (str "Gate error: " (:title strand))
     :body (str "Strand " (:id strand) " has gate/error:\n\n" error)}))

(defrule kanban-started
  "Notify when a kanban card is claimed and work starts."
  [{:keys [strand]}]
  (when (and (= "active" (:state strand))
             (= "true" (config-attr strand :kanban/card))
             (= "claimed" (config-attr strand :kanban/lane)))
    {:title (str "Kanban started: " (:title strand))
     :body (str "Kanban card " (:id strand) " has been claimed and work has started.")}))

(defrule kanban-completed
  "Notify when a kanban card reaches the explicit done outcome."
  [{:keys [strand]}]
  (when (and (= "closed" (:state strand))
             (= "true" (config-attr strand :kanban/card))
             (= "done" (config-attr strand :kanban/outcome)))
    {:title (str "Kanban done: " (:title strand))
     :body (str "Kanban card " (:id strand) " completed fully.")}))

(defn- failed-blocker?
  "Return true when strand is an active failed/exhausted blocker."
  [strand]
  (and (= "active" (:state strand))
       (contains? #{"failed" "exhausted"} (config-attr strand :agent-run/phase))))

(defn- active-descendants
  "Return active strands below a kanban card over parent-of, including the card."
  [rt root-id]
  (->> (:strands (graph/subgraph rt [root-id] {:type "parent-of"}))
       (filter #(= "active" (:state %)))
       vec))

(defn- blocking-failures
  "Return failed/exhausted depends-on blockers for active strands under root."
  [rt root-id]
  (let [work (active-descendants rt root-id)
        work-ids (set (map :id work))
        blocker-ids (->> (:edges (graph/subgraph rt (vec work-ids) {:type "depends-on"}))
                         (filter #(contains? work-ids (:from_strand_id %)))
                         (map :to_strand_id)
                         distinct)]
    (->> blocker-ids
         (map #(weaver/show rt %))
         (filter failed-blocker?)
         (sort-by :id)
         vec)))

(defrule kanban-blocked
  "Notify when active card work is blocked by failed/exhausted delegated work."
  [{:keys [strand]}]
  (when (and (= "active" (:state strand))
             (= "true" (config-attr strand :kanban/card)))
    (let [rt (current/runtime)
          blockers (blocking-failures rt (:id strand))]
      (when (seq blockers)
        {:title (str "Kanban blocked: " (:title strand))
         :body (str "Kanban card " (:id strand)
                    " is blocked by failed/exhausted work:\n"
                    (str/join "\n" (map #(str "- " (:id %) " " (:title %)
                                              " (" (config-attr % :agent-run/phase) ")")
                                        blockers)))}))))

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
      (catch Exception e
        (when-not (some #(= ts %) @(logged-ts-parse-failures))
          (swap! (logged-ts-parse-failures)
                 #(vec (take-last timestamp-failure-memory (conj % ts))))
          (binding [*out* *err*]
            (println (str "[attention] WARN parked-run detector could not parse strand updated_at;"
                          " the detector is disabled for this strand "
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
