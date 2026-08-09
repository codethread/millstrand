(ns ct.adapters.help
  "Repo-owned module reconciliation helper.

  `reconcile-help-transform` is not a branch adapter: it is this canonical
  world's config-election of the batteries reference help transform, kept here
  beside the batteries module ordering so the election has an owner."
  (:require [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.runtime.help-transform.alpha :as help-transform]))

(defn reconcile-help-transform [{:keys [runtime]}]
  (help-transform/register-builtin! runtime)
  {:registered :help-transform})

(defn close-help-transform! [{:keys [runtime]}]
  (help-transform/unregister-default-help-transform! runtime 'millstrand.spools.batteries)
  {:unregistered :help-transform})

(lifecycle/defresource batteries-help-transform
  "Own this world's batteries help-transform election for the module lifetime."
  {:open 'ct.adapters.help/reconcile-help-transform
   :close 'ct.adapters.help/close-help-transform!})
