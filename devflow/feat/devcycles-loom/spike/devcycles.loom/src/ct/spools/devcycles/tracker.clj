(ns ct.spools.devcycles.tracker
  "Devflow↔kanban tracker binding as a shared module.

  SPIKE — illustrative, not loaded. The one repair over skein-src's
  workspace version: reconcile branches on status, so omitting the
  module clears the singleton tracker slot instead of rebinding it
  (SPEC-004.C46b)."
  (:require [ct.spools.devflow :as devflow]
            [ct.spools.kanban :as kanban]
            [skein.api.spool.alpha :as spool]))

(defn devflow-projection
  "Project a devflow run onto kanban's tracker shape: active stage as
  status, ready frontier as steps."
  [runtime run-id]
  {:status (devflow/active-stage runtime run-id)
   :ready (devflow/ready-steps runtime run-id)})

(defn reconcile
  "Bind (or on removal, clear) the runtime's singleton tracker slot."
  [{:keys [runtime status]}]
  (case status
    :applied (kanban/set-tracker!
              runtime {:name "devflow"
                       :project 'ct.spools.devcycles.tracker/devflow-projection})
    :removed (kanban/clear-tracker! runtime "devflow")
    ;; GAP: kanban exposes set-tracker! but no clear-tracker! today — the
    ;; removal branch needs a kanban accretion (or the slot stays sticky
    ;; and the :removed branch must be an explicit documented no-effect
    ;; branch per SPEC-004.C46b).
    (spool/fail! ::unknown-reconcile-status {:status status}))
  {:reconciled :devcycles-tracker})

(def spool {:reconcile 'reconcile})
