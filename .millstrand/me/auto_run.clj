(ns me.auto-run
  "Activate bounded automatic pickup using this repository's delivery workflow."
  (:require [clojure.java.io :as io]
            [ct.spools.codethread.auto-run :as auto-run]
            [ct.spools.codethread.auto-run-worktree]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/use-op! auto-run/auto-run)

(defn open!
  "Configure two Sol-high automatic delivery slots for this repository."
  [{:keys [runtime]}]
  (auto-run/configure!
   runtime
   {:repo (.getCanonicalPath
           (.getParentFile (io/file (get-in runtime [:metadata :config-dir]))))
    :seat "sol-high"
    :effort "high"
    :workflow "auto-full-land"
    :workflows #{"auto-full-land"}
    :prepare 'ct.spools.codethread.auto-run-worktree/prepare!
    :enabled? true
    :max-running 2
    :interval-ms 15000}))

(defn close!
  "Stop new admissions without stopping any accepted worker."
  [{:keys [runtime]}]
  (auto-run/stop! runtime))

(lifecycle/defresource! auto-run-dispatcher
  "Own repository automatic pickup for the module lifetime."
  {:open 'me.auto-run/open!
   :close 'me.auto-run/close!})
