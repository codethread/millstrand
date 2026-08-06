(ns millstrand.spools.guild
  "Reference spool for declaring a versioned public weaver operation API.

  Guild ops are ordinary CLI operations registered in the weaver op registry.
  Names are documented as dotted, version-suffixed handles such as
  `gate.close.v1`; the underlying registry requires simple unqualified handles
  and therefore rejects namespaced keyword or symbol names. Optional input specs
  validate the parsed op input before the declared handler runs. Deprecation
  replaces an op with a stub that always fails loudly with structured data."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spec.alpha :as spec-alpha]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.api.spool.alpha :as spool :refer [fail! require-valid!]]))

(def ^:private state-version
  "Bump whenever new-state's key set changes."
  1)

(defn- new-state []
  {:guild-ops (atom {})
   :deprecated-ops (atom {})
   :fallback-guild-name (atom nil)})

(defn- state [runtime]
  (runtime/spool-state runtime ::state {:version state-version} new-state))

(defn- guild-ops [runtime] (:guild-ops (state runtime)))
(defn- deprecated-ops [runtime] (:deprecated-ops (state runtime)))
(defn- fallback-guild-name [runtime] (:fallback-guild-name (state runtime)))

(def ^:private declaration-kind :millstrand.spools.guild/declarations)
(def ^:private declaration-owner :millstrand.spools.guild/defaults)

(s/def ::declaration
  (s/and map?
         #(contains? #{:active :deprecated} (:status %))
         #(string? (:name %))))

(defn- new-declarations []
  (doto (registry/registry)
    (registry/declare-kind! {:id declaration-kind
                             :entry-spec ::declaration
                             :binding-moment :operation-dispatch})))

(defn- declarations-handle [runtime]
  (runtime/spool-state runtime ::declarations {:version 1} new-declarations))

(defn- declaration-entries [runtime]
  (merge
   (into {}
         (map (fn [[name entry]]
                [name (assoc entry :status :active)]))
         @(guild-ops runtime))
   (into {}
         (map (fn [[name entry]]
                [name (assoc entry :name name :status :deprecated)]))
         @(deprecated-ops runtime))))

(defn- publish-declarations! [runtime]
  (let [entries (declaration-entries runtime)
        handle (declarations-handle runtime)]
    (if (seq entries)
      (registry/replace-owner! handle declaration-kind declaration-owner
                               {:layer :spools
                                :entries entries
                                :overrides #{}})
      (registry/remove-owner! handle declaration-kind declaration-owner))))

(def ^:private register-op-opt-keys #{:doc :input-spec :returns :hook-class :deadline-class})
(def ^:private deprecate-opt-keys #{:replacement :since})

(s/def ::hook-class #{:read :mutating})
(s/def ::deadline-class #{:standard :unbounded})
(s/def ::doc string?)
(s/def ::input-spec (s/or :keyword keyword? :symbol symbol?))
(s/def ::runtime #(and (map? %) (contains? % :spool-state)))
(s/def ::lifecycle-context (s/keys :req-un [::runtime]))
(s/def ::reset #{:guild})
(s/def ::reset-result
  (s/and map?
         #(= #{:reset} (set (keys %)))
         #(s/valid? ::reset (:reset %))))
;; :returns shape authority is millstrand.api.return-shape.alpha at op registration;
;; this spec owns only presence-with-value.
(s/def ::returns some?)

(s/def ::register-op-opts
  (s/keys :req-un [::hook-class ::deadline-class]
          :opt-un [::doc ::input-spec ::returns]))

(defn- require-spec-name! [spec-name]
  (when-not (or (keyword? spec-name) (symbol? spec-name))
    (fail! "Guild op input spec must be a keyword or symbol" {:input-spec spec-name}))
  (when (= ::s/unknown (s/form spec-name))
    (fail! "Guild op input spec is not registered" {:input-spec spec-name}))
  spec-name)

(defn- parse-input [input]
  (if (some? input)
    (json/read-str input :key-fn keyword)
    {}))

(defn- spec-name-str
  "The canonical wire spelling of a spec name: `ns/name`, no leading colon —
  the same spelling the help projection's `spec` field uses (SPEC-003.C23c)."
  [spec-name]
  (if (keyword? spec-name)
    (subs (str spec-name) 1)
    (str spec-name)))

(defn- input-spec-summary [spec-name]
  (when spec-name
    {:input-spec (spec-name-str spec-name)}))

(defn- validate-input! [name spec-name input]
  (when spec-name
    (require-spec-name! spec-name)
    (when-not (s/valid? spec-name input)
      (let [bundle (spec-alpha/projection spec-name)]
        (fail! "Guild op input failed spec validation"
               {:code :operation/input-invalid
                :operation name
                :spec (get bundle "spec")
                :spec-forms (get bundle "spec-forms")
                :contract (get bundle "contract")
                :template (get bundle "template")
                :explain (spec-alpha/explain-text spec-name input)}))))
  input)

(defn- op-registered? [runtime name]
  (try
    (weaver/resolve-op runtime name)
    true
    (catch clojure.lang.ExceptionInfo _
      false)))

(defn- op-arg-spec
  "Return a parser arg-spec for a guild op.

  A declared `:input-spec` rides the generic arg `:spec` convention
  (SPEC-003.C70): `strand help <op>` then projects the input contract, so
  guild needs no shape-discovery verb of its own."
  [name doc hook-class deadline-class input-spec]
  {:op (clojure.core/name name)
   :doc doc
   :positionals [(cond-> {:name :input
                          :type :string
                          :doc "Optional JSON object input."}
                   input-spec (assoc :spec input-spec))]
   :hook-class hook-class
   :deadline-class deadline-class})

(def ^:private guild-arg-spec
  {:op "guild"
   :doc "Introspect this weaver's guild operation API."
   :subcommands {"list" {:doc "List this weaver's active and deprecated guild ops."
                         :hook-class :read
                         :deadline-class :standard}}})

(def ^:private guild-returns
  {:subcommands
   {"list" {:type :map
            :required {:guild [:nullable :string]
                       :operation :string
                       :active
                       {:type :collection
                        :items {:type :map
                                :required {:name :string}
                                :optional {:doc :string :input-spec :string}}}
                       :deprecated
                       {:type :collection
                        :items {:type :map
                                :required {:name :string :replacement :json}
                                :optional {:doc :string :since :string}}}}}}})

(defn- register-or-replace-op!
  "Upsert a guild op in the weaver registry.

  The guild owns its op lifecycle through its own state atoms and (re)declares
  ops as it registers, deprecates, and reloads; the registry's loud-collision
  default is the wrong policy here, so re-declaration is an explicit replace."
  ([runtime name doc handler-sym arg-spec returns]
   (let [metadata (cond-> {:doc doc :arg-spec arg-spec}
                    (some? returns) (assoc :returns returns))]
     (if (op-registered? runtime name)
       (weaver/replace-op! runtime name metadata handler-sym)
       (weaver/register-op! runtime name metadata handler-sym))))
  ([runtime name doc handler-sym returns hook-class deadline-class input-spec]
   (register-or-replace-op! runtime name doc handler-sym
                            (op-arg-spec name doc hook-class deadline-class input-spec)
                            returns)))

(defn dispatch-op
  "Dispatch a guild-declared operation after parsing and validating input."
  [{:op/keys [name args] :as ctx}]
  (let [{:keys [handler input-spec]} (or (get @(guild-ops (:op/runtime ctx)) name)
                                         (fail! "Guild op is not registered" {:operation name}))
        input (validate-input! name input-spec (parse-input (:input args)))]
    ((requiring-resolve handler) (assoc ctx :guild/input input))))

(defn deprecated-op
  "Fail loudly for a deprecated guild operation."
  [{:op/keys [name] :as ctx}]
  (let [{:keys [replacement since]} (or (get @(deprecated-ops (:op/runtime ctx)) name)
                                        (fail! "Guild op is not deprecated" {:operation name}))]
    (fail! "Guild op is deprecated"
           (cond-> {:code :operation/deprecated
                    :operation name
                    :replacement replacement}
             since (assoc :since since)))))

(defn register-op!
  "Register a guild operation in `runtime`'s CLI operation registry.

  `name` is a simple unqualified registry handle, conventionally dotted and
  version-suffixed such as `gate.close.v1`. `opts` must satisfy the owning
  `::register-op-opts` spec: caller-supplied leaf `:hook-class` (`:read` or
  `:mutating`) and `:deadline-class` (`:standard` or `:unbounded`), plus
  optional `:doc`, `:input-spec`, and `:returns`; unknown options fail
  loudly. Guild supplies no class defaults.
  `:returns` is the shared registry return-shape declaration, not a
  Guild-specific schema. `fn-sym` must be a fully qualified symbol resolving in
  the weaver JVM. The handler receives the usual op context plus parsed JSON
  input at `:guild/input`.

  A declared `:input-spec` is discoverable over the wire: it rides the generic
  arg `:spec` convention (SPEC-003.C70), so `strand help <op>` projects the
  registered spec's contract and template, and invalid input fails with the
  same projection fields plus explain text."
  [runtime name opts fn-sym]
  (when-not (map? opts)
    (fail! "Guild op opts must be a map" {:opts opts}))
  (spool/reject-unknown-keys! "guild/register-op!" register-op-opt-keys opts)
  (when-not (s/valid? ::register-op-opts opts)
    (fail! "Guild op opts failed spec validation"
           {:opts opts
            :spec (spec-name-str ::register-op-opts)
            :explain (s/explain-str ::register-op-opts opts)}))
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (fail! "Guild op handler must be a fully qualified symbol" {:handler fn-sym}))
  (requiring-resolve fn-sym)
  (when-let [input-spec (:input-spec opts)]
    (require-spec-name! input-spec))
  (let [registered (register-or-replace-op! runtime name (:doc opts)
                                            'millstrand.spools.guild/dispatch-op (:returns opts)
                                            (:hook-class opts) (:deadline-class opts)
                                            (:input-spec opts))
        entry (cond-> {:name (:name registered)
                       :handler fn-sym
                       :hook-class (:hook-class opts)
                       :deadline-class (:deadline-class opts)}
                (:doc opts) (assoc :doc (:doc opts))
                (:input-spec opts) (assoc :input-spec (:input-spec opts))
                (contains? opts :returns) (assoc :returns (:returns opts)))]
    (swap! (guild-ops runtime) assoc (:name entry) entry)
    (swap! (deprecated-ops runtime) dissoc (:name entry))
    (publish-declarations! runtime)
    entry))

(defn- guild-name [name]
  (when-not (or (and (symbol? name) (nil? (namespace name)))
                (and (keyword? name) (nil? (namespace name))))
    (fail! "Guild op names must be simple symbols or keywords" {:operation name}))
  (clojure.core/name name))

(defn deprecate!
  "Replace a registered guild operation in `runtime` with a loud deprecation stub.

  `opts` requires `:replacement` and may include `:since`. Deprecated ops never
  return success; invocation throws ex-info with `:code :operation/deprecated`."
  [runtime name opts]
  (when-not (map? opts)
    (fail! "Guild deprecation opts must be a map" {:opts opts}))
  (spool/reject-unknown-keys! "guild/deprecate!" deprecate-opt-keys opts)
  (when-not (contains? opts :replacement)
    (fail! "Guild deprecation requires :replacement" {:opts opts}))
  (let [op-name (guild-name name)
        entry (or (get @(guild-ops runtime) op-name)
                  (fail! "Guild op is not registered" {:operation name}))
        deprecated (select-keys opts [:replacement :since])]
    (register-or-replace-op! runtime name (:doc entry)
                             'millstrand.spools.guild/deprecated-op (:returns entry)
                             (:hook-class entry) (:deadline-class entry)
                             ;; a deprecation stub never validates input, so it
                             ;; stops projecting the retired input contract
                             nil)
    (swap! (guild-ops runtime) dissoc (:name entry))
    (swap! (deprecated-ops runtime) assoc (:name entry) (assoc deprecated :doc (:doc entry)))
    (publish-declarations! runtime)
    (assoc deprecated :name (:name entry))))

(defn set-fallback-guild-name!
  "Record `guild-name` as the fallback guild name in `runtime`'s state.

  The guild name is normally read from runtime metadata; the fallback covers
  contexts without it. The `guild-state` resource resets the fallback when its
  module opens or closes; preserving a healthy resource preserves the current
  fallback. Passing nil clears the fallback; a non-nil value must be a
  non-blank string and anything else fails loudly with the offending value."
  [runtime guild-name]
  (when (and (some? guild-name) (not (and (string? guild-name) (not (str/blank? guild-name)))))
    (fail! "Guild name must be a non-blank string" {:guild/name guild-name}))
  (reset! (fallback-guild-name runtime) guild-name)
  guild-name)

(defn reset-guild!
  "Reset Guild's runtime-owned declarations during module open and close.

  `context` must conform to `::lifecycle-context`; the result conforms to
  `::reset-result`. Guild is the irregular lifecycle boundary: both transitions
  clear its active and deprecated operations, fallback name, and published
  declaration owner. The coordinator retains this callable for omission cleanup."
  [context]
  (let [{:keys [runtime]}
        (require-valid! ::lifecycle-context context "Invalid Guild lifecycle context")]
    (reset! (guild-ops runtime) {})
    (reset! (deprecated-ops runtime) {})
    (reset! (fallback-guild-name runtime) nil)
    (publish-declarations! runtime)
    (require-valid! ::reset-result {:reset :guild} "Invalid Guild reset result")))

(millstrand/defop guild
  "Return JSON-safe metadata describing the registered Guild API."
  {:arg-spec guild-arg-spec
   :returns guild-returns
   :stream? false}
  [{:op/keys [runtime runtime-metadata]}]
  {:guild (or (:name runtime-metadata)
              @(fallback-guild-name runtime))
   :active (mapv (fn [[name {:keys [doc input-spec]}]]
                   (cond-> {:name name}
                     doc (assoc :doc doc)
                     input-spec (merge (input-spec-summary input-spec))))
                 (sort-by key @(guild-ops runtime)))
   :deprecated (mapv (fn [[name {:keys [replacement since doc]}]]
                       (cond-> {:name name :replacement replacement}
                         doc (assoc :doc doc)
                         since (assoc :since since)))
                     (sort-by key @(deprecated-ops runtime)))})

(lifecycle/defresource guild-state
  "Own Guild's reset and publication boundary for the module lifetime."
  {:open 'millstrand.spools.guild/reset-guild!
   :close 'millstrand.spools.guild/reset-guild!})
