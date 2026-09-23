(ns me.config
  "Select this repository's workspace-owned Millstrand declarations."
  (:require [me.jobs.nvd-scan :as nvd-scan]
            [me.notifications.attention :as attention]
            [me.policy.config :as policy]
            [me.workflows.common :as common]
            [me.workflows.explore :as explore]
            [me.workflows.fix :as fix]
            [me.workflows.land]
            [me.workflows.review :as review]
            [me.workflows.release :as release]
            [me.workflows.story :as story]
            [me.workflows.story-review :as story-review]
            [millhouse.spools.chime :as chime]
            [millhouse.spools.cron :as cron]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.spools.batteries :as batteries]))

(millstrand/use-op! batteries/runbook)

(millstrand/use-query! policy/run-active)
(millstrand/use-query! policy/kanban-feature-work)
(millstrand/use-query! policy/workflow-runs)
(millstrand/use-query! policy/work)

(millstrand/use-pattern! common/macros-demo)
(millstrand/use-pattern! common/delegate-pipeline)

(workflow/use-workflow! review/millstrand-review)
(workflow/use-workflow! story-review/story-review)
(workflow/use-workflow! story/story-fold)
(workflow/use-workflow! story/story-keep)
(workflow/use-workflow! story/story-wave)
(workflow/use-workflow! story/story)
(workflow/use-workflow! explore/explore)
(workflow/use-workflow! fix/fix)
(workflow/use-workflow! release/release)

(chime/use-rule! attention/hitl-checkpoint-ready-rule)
(chime/use-rule! attention/kanban-completed-rule)
(chime/use-rule! attention/parked-run-rule)
(cron/use-job! nvd-scan/nvd-scan)
