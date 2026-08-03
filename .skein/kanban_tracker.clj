(ns kanban-tracker
  "Seed the devflow<->kanban tracker binding from the shared adapter root."
  (:require [skein.api.lifecycle.alpha :as lifecycle]
            [ct.spools.devflow-kanban-adapter]))

;; Kanban accepts the tracker as process-lifetime configuration: it exposes no
;; unbind operation, so the idempotent seed makes that lifetime explicit and
;; re-establishes the binding on a new weaver generation. The projection and
;; the binding fn themselves ship with codethread/devflow-kanban-adapter.
(lifecycle/defseed devflow-tracker
  "Bind the canonical world's process-lifetime Devflow tracker."
  {:apply 'ct.spools.devflow-kanban-adapter/bind-devflow-tracker!})
