(ns me.agents.delegation-contracts
  "Workspace-local defaults for the agent-run task and review contracts."
  (:require [millstrand.api.current.alpha :as current]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [ct.spools.agent-run :as agent-run]
            [ct.spools.delegation :as delegation]))

(defn open-contracts!
  "Bind this workspace's default task and review contracts."
  [{:keys [runtime]}]
  (current/with-runtime
    runtime
    (agent-run/set-default-review-contract! delegation/review-contract)
    (agent-run/set-default-task-contract! delegation/worker-contract)))

(defn close-contracts!
  "Clear this workspace's default task and review contracts."
  [{:keys [runtime]}]
  (current/with-runtime
    runtime
    (agent-run/set-default-review-contract! nil)
    (agent-run/set-default-task-contract! nil)))

(lifecycle/defresource delegation-contracts
  "Own this workspace's default task and review contracts."
  {:open 'me.agents.delegation-contracts/open-contracts!
   :close 'me.agents.delegation-contracts/close-contracts!})
