;; ns name intentionally differs from the file path: this directory is a spool
;; root smoke approves as `smoke/authoring`, so the namespace is rooted at the
;; root's own `src`, not at the `test` lint root.
(ns ^{:clj-kondo/ignore [:namespace-name-mismatch]} millstrand.smoke.fixtures.authoring
  "Authoring-forms fixture module: one op, one query, and one paired resource.

  Smoke approves this directory as the `smoke/authoring` spool root and declares
  this namespace as a module, so the owner-complete publish path runs end to end:
  the forms below are the module's whole contribution, collected as its source
  loads, and omitting the module from a later init.clj removes every one of them
  by omission at the next refresh.

  The resource records its phases as strands rather than process state, so both
  `:open` and `:close` are observable from outside the weaver through an ordinary
  `strand list`. That makes removal-by-omission provable in both directions: the
  op and query disappear, and the close marker appears."
  (:require [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.weaver.alpha :as weaver]))

(def ^:private echo-arg-spec
  {:op "smoke-echo"
   :doc "Echo the given text back through the op result."
   :hook-class :read
   :deadline-class :standard
   :positionals [{:name :text :type :string :required? true :doc "Text to echo."}]})

(millstrand/defop smoke-echo
  "Echo the positional text back to the caller."
  {:arg-spec echo-arg-spec}
  [ctx]
  {:echoed (:text (:op/args ctx))})

(millstrand/defquery smoke-authored
  "Return strands owned by the authoring-forms fixture."
  {:usage "strand list --query smoke-authored"}
  [:= [:attr :owner] "authored"])

(defn open-smoke-marker!
  "Record the resource's open phase as a strand and return its handle."
  [{:keys [runtime]}]
  {:opened (:id (weaver/add! runtime {:title "smoke-authoring open"
                                      :attributes {:owner "authored"
                                                   :phase "open"}}))})

(defn close-smoke-marker!
  "Record the resource's close phase as a strand carrying the open handle."
  [{:keys [runtime resource]}]
  (weaver/add! runtime {:title "smoke-authoring close"
                        :attributes {:phase "close"
                                     :opened (:opened resource)}}))

(lifecycle/defresource smoke-marker
  "Own the fixture's open/close phase markers."
  {:open 'millstrand.smoke.fixtures.authoring/open-smoke-marker!
   :close 'millstrand.smoke.fixtures.authoring/close-smoke-marker!})
