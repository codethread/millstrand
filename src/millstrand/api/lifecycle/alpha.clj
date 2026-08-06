(ns millstrand.api.lifecycle.alpha
  "Authoring forms and data contracts for module lifecycle effects.

  Lifecycle declarations are printable source facts collected with a module's
  owner-complete contribution. The coordinator resolves their fully qualified
  callable symbols before publication and owns all retained live state.
  Malformed declarations fail at definition; unresolved or non-callable
  symbols fail validation before the candidate image is published."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [require-valid!]]))

(def kinds
  "Closed lifecycle declaration kinds: `:seed`, `:resource`, and `:reconcile`."
  #{:seed :resource :reconcile})

(def phases
  "Closed lifecycle execution phases.

  The coordinator reports `:validate`, `:resolve`, `:open`, `:apply`, `:close`,
  `:remove`, or `:runtime-stop` when a projection has a phase."
  #{:validate :resolve :open :apply :close :remove :runtime-stop})

(def statuses
  "Closed lifecycle effect projection statuses.

  A projection is `:planned`, `:applied`, `:preserved`, `:retained`,
  `:degraded`, `:blocked`, `:removed`, or `:not-attempted`."
  #{:planned :applied :preserved :retained :degraded :blocked :removed
    :not-attempted})

(s/def ::effect-id keyword?)
(s/def ::kind kinds)
(s/def ::after (s/coll-of keyword? :kind set?))
(s/def ::callable qualified-symbol?)
(s/def ::scope #{:module :runtime})
(s/def ::trigger-kinds (s/coll-of keyword? :kind set?))

(s/def ::seed-options
  (s/and map?
         #(every? #{:apply :after} (keys %))
         #(= #{:apply} (set (keys (select-keys % [:apply]))))
         #(qualified-symbol? (:apply %))
         #(set? (get % :after #{}))))

(s/def ::resource-options
  (s/and map?
         #(every? #{:open :close :after :scope} (keys %))
         #(every? qualified-symbol? ((juxt :open :close) %))
         #(set? (get % :after #{}))
         #(contains? #{:module :runtime} (get % :scope :module))))

(s/def ::reconcile-options
  (s/and map?
         #(every? #{:read-desired :read-actual :apply :on-removed
                    :trigger-kinds :after}
                  (keys %))
         #(every? qualified-symbol?
                  ((juxt :read-desired :read-actual :apply :on-removed) %))
         #(set? (get % :trigger-kinds #{}))
         #(set? (get % :after #{}))))

(s/def ::declaration
  (s/or :seed
        (s/and #(= :seed (:kind %))
               #(s/valid? ::seed-options (dissoc % :kind)))
        :resource
        (s/and #(= :resource (:kind %))
               #(s/valid? ::resource-options (dissoc % :kind)))
        :reconcile
        (s/and #(= :reconcile (:kind %))
               #(s/valid? ::reconcile-options (dissoc % :kind)))))

(s/def ::phase phases)
(s/def ::status statuses)
(s/def ::result any?)
(s/def ::error map?)
(s/def ::effect-projection
  (s/keys :req-un [::kind ::status]
          :opt-un [::phase ::result ::error]))

(defn seed-declaration
  "Return a validated seed declaration from `opts`.

  `opts` requires `:apply`, a fully qualified callable symbol. It may include
  `:after`, a set of lifecycle effect ids that must run first."
  [opts]
  (require-valid! ::seed-options opts "defseed options are invalid")
  (assoc opts :kind :seed :after (get opts :after #{})))

(defn resource-declaration
  "Return a validated resource declaration from `opts`.

  `opts` requires fully qualified `:open` and `:close` callable symbols. It may
  include `:after`, a set of lifecycle effect ids, and `:scope`, either
  `:module` (the default) or `:runtime`."
  [opts]
  (require-valid! ::resource-options opts "defresource options are invalid")
  (assoc opts :kind :resource
         :after (get opts :after #{})
         :scope (get opts :scope :module)))

(defn reconcile-declaration
  "Return a validated reconcile declaration from `opts`.

  `opts` requires fully qualified `:read-desired`, `:read-actual`, `:apply`,
  and `:on-removed` callable symbols. It may include `:trigger-kinds` and
  `:after`, both sets of keywords."
  [opts]
  (require-valid! ::reconcile-options opts "defreconcile options are invalid")
  (assoc opts :kind :reconcile
         :after (get opts :after #{})
         :trigger-kinds (get opts :trigger-kinds #{})))

(defmacro defseed
  "Define and collect one process-lifetime idempotent seed effect.

  The form is `(defseed name doc {:apply qualified-symbol, :after #{ids}})`.
  `:after` is optional."
  [form-name doc opts]
  `(do
     (def ~form-name ~doc (seed-declaration ~opts))
     (runtime/collect-lifecycle! ~(keyword form-name) ~form-name)
     (var ~form-name)))

(defmacro defresource
  "Define and collect one paired resource acquisition and release effect.

  The form is `(defresource name doc {:open qualified-symbol,
  :close qualified-symbol, :scope :module-or-runtime, :after #{ids}})`.
  `:scope` defaults to `:module`; `:after` is optional."
  [form-name doc opts]
  `(do
     (def ~form-name ~doc (resource-declaration ~opts))
     (runtime/collect-lifecycle! ~(keyword form-name) ~form-name)
     (var ~form-name)))

(defmacro defreconcile
  "Define and collect one repeated desired-state reconciliation effect.

  The form is `(defreconcile name doc {:read-desired qualified-symbol,
  :read-actual qualified-symbol, :apply qualified-symbol,
  :on-removed qualified-symbol, :trigger-kinds #{keywords}, :after #{ids}})`.
  `:trigger-kinds` and `:after` are optional."
  [form-name doc opts]
  `(do
     (def ~form-name ~doc (reconcile-declaration ~opts))
     (runtime/collect-lifecycle! ~(keyword form-name) ~form-name)
     (var ~form-name)))
