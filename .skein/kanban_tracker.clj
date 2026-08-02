(ns kanban-tracker
  "Bind this repo's kanban card projection to devflow.

  The projection reads the generic workflow engine surface rather than a
  devflow facade: devflow v15 removed its runtime facade, and the generic
  `current-root`/`ready` answer the same questions for any devflow version."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.lifecycle.alpha :as lifecycle]
            [skein.api.spool.alpha :as spool]
            [skein.spools.workflow :as workflow]
            [ct.spools.kanban :as kanban]))

(s/def ::run-id (s/and string? (complement str/blank?)))
(s/def ::projection :ct.spools.kanban/tracker-projection)

(defn- active-stage
  "Return the active root's non-blank stage, or nil when no root is active."
  [runtime run-id]
  (when-let [root (current/with-runtime runtime (workflow/current-root run-id))]
    (let [stage (spool/attr-get root :devflow/stage)]
      (when-not (and (string? stage) (not (str/blank? stage)))
        (spool/fail! "Active devflow root must carry a non-blank devflow/stage"
                     {:run-id run-id :root root :stage stage}))
      stage)))

(defn devflow-projection
  "Project a devflow run into kanban's `::projection` tracker shape.

  An absent active root is the accepted no-active-run state: nil status and no
  steps. Kanban validates the same projection again at its strategy boundary."
  [runtime run-id]
  (spool/require-valid! ::run-id run-id "Devflow tracker run id must be a non-blank string")
  (let [stage (active-stage runtime run-id)]
    (spool/require-valid!
     ::projection
     {:status stage
      :ready (if stage
               (current/with-runtime runtime (workflow/ready run-id))
               [])}
     "Devflow tracker projection must match its owning spec")))

(s/fdef devflow-projection
  :args (s/cat :runtime any? :run-id ::run-id)
  :ret ::projection)

;; Kanban v16 accepts the tracker as process-lifetime configuration: it exposes
;; no unbind operation, so omission cannot claim module-lifetime cleanup. The
;; idempotent seed makes that lifetime explicit and re-establishes the binding
;; on a new weaver generation.
(defn bind-devflow-tracker!
  "Bind devflow as this runtime's required process-lifetime Kanban tracker."
  [{:keys [runtime]}]
  (kanban/set-tracker! runtime
                       {:name "devflow"
                        :project 'kanban-tracker/devflow-projection})
  {:bound :kanban-tracker})

(lifecycle/defseed devflow-tracker
  "Bind the canonical world's process-lifetime Devflow tracker."
  {:apply 'kanban-tracker/bind-devflow-tracker!})
