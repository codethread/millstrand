(ns ct.spools.devcycles.attention
  "Shared attention rules over workflow/agent-run/kanban vocabulary.

  SPIKE — illustrative, not loaded. The workspace version used the local
  `defrule` macro; a shared spool cannot (NG6), so rules ship as an
  owner-complete contribution into chime's rule kind. Two rules shown;
  the real lift carries all seven."
  (:require [skein.api.spool.alpha :as spool]))

(defn hitl-checkpoint-ready
  "Chime when a human checkpoint enters the ready frontier."
  [{:keys [strand]}]
  (when (and (= "checkpoint" (spool/attr strand "workflow/role"))
             (= "human" (spool/attr strand "workflow/checkpoint-kind")))
    {:title "Human checkpoint ready" :body (:title strand)}))

(defn gate-error
  "Chime when any gate stamps gate/error."
  [{:keys [strand]}]
  (when (spool/attr strand "gate/error")
    {:title "Gate errored" :body (:title strand)}))

(defn contribute
  "Publish the shared rules into chime's owner-partitioned rule kind."
  [_runtime]
  {:skein.spools.chime/rules
   {:hitl-checkpoint-ready {:key :hitl-checkpoint-ready
                            :fn 'ct.spools.devcycles.attention/hitl-checkpoint-ready}
    :gate-error {:key :gate-error
                 :fn 'ct.spools.devcycles.attention/gate-error}}})
;; GAP: the parked-run threshold (5 min in the workspace version) has no home as
;; consumer-tunable data — rule fns take no config. Options: spool-state seeded
;; by reconcile, or a params map on the rule entry; the rule kind supports
;; neither today, so the threshold ships as a constant in v1.

(def spool {:contribute 'contribute})
