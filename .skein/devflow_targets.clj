(ns devflow-targets
  "Bind devflow's pluggable decomposition for this workspace.

  Devflow v17 authors task/card breakdowns through `workflow/defer` selection
  points, ships strand-native targets, and publishes the unbound stage
  templates (`tasks-open`, `decompose-open`). This module is the consumer
  side of that seam: a kanban-backed card-authoring target, a decompose
  binding that offers it beside the shipped strand target, and a seed that
  re-points the routed `:decompose` name at the binding (the direct registry
  layer wins over spool-published names).

  Activation requires devflow >= v17."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.lifecycle.alpha :as lifecycle]
            [ct.spools.devflow :as devflow]
            [skein.spools.workflow :as workflow]))

(defn- non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(s/def ::feature non-blank-string?)
(s/def ::feature-card-reviewer non-blank-string?)
(s/def ::epic-card-reviewer non-blank-string?)
(s/def ::review-cwd non-blank-string?)
(s/def ::kanban-cards-params (s/keys :req-un [::feature]))
(s/def ::decompose-params
  (s/keys :req-un [::feature ::feature-card-reviewer ::epic-card-reviewer]
          :opt-un [::review-cwd]))

(workflow/defworkflow kanban-cards
  "Author devflow implementation cards on this workspace's kanban board."
  {:entrypoints #{:call}
   :param-spec ::kanban-cards-params
   :defaults {}}
  (workflow/workflow
    (fn [{:keys [feature]}] (str "Author kanban cards for " feature))
    (workflow/step :author-kanban-cards
                   (fn [{:keys [feature]}]
                     (str "Author kanban implementation cards for " feature))
                   :self
                   :attributes {"workflow/artifact" "kanban cards"
                                "devflow/guide" "decompose"
                                "workflow/instruction"
                                (format-alpha/reflow
                                 "|Author the epic and feature cards on this repo's kanban board
                                  |(`strand kanban add ...`), following `strand devflow guidance
                                  |decompose` for the cold-card contract. The created card strand
                                  |ids are the card ids the review handoff expects.")})))

(workflow/defworkflow decompose-with-kanban
  "Devflow's decompose stage offering strand cards and this repo's kanban board."
  {:entrypoints #{:continue :call}
   :param-spec ::decompose-params
   :defaults {}}
  (workflow/bind-defers devflow/decompose-open
                        {:author-cards #{:author-card-strands :kanban-cards}}))

(defn repoint-decompose!
  "Re-point the routed :decompose name at this workspace's kanban-aware binding."
  [{:keys [runtime]}]
  (current/with-runtime runtime
    (workflow/register-workflow! :decompose 'devflow-targets/decompose-with-kanban))
  {:decompose 'devflow-targets/decompose-with-kanban})

(lifecycle/defseed devflow-decompose-binding
  "Route land-proposal's :decompose continuation through the kanban-aware binding."
  {:apply 'devflow-targets/repoint-decompose!})
