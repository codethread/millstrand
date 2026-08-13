(ns millstrand.api.authoring.alpha
  "Shared declaration and typed-selection boundary for authoring families.

  Declaration Vars carry a closed protocol-1 `::declaration` in their metadata.
  Registry selections accept only Vars from the expected family, validate their
  descriptors and kind entries, normalize them through `::selection`, and then
  pass trusted values to the existing module collector.

  The public spec sources are `::declaration`, `::registry-use-options`,
  `::builder-bindings`, `::expansion-plan`, `::selection`, and
  `::selected-vars`. `defauthoring` consults the builder and plan specs during
  macro expansion; declaration installation and selection consult the remaining
  specs at their named boundaries."
  (:require [clojure.core.specs.alpha]
            [clojure.spec.alpha :as s]
            [millstrand.api.runtime.alpha :as runtime]))

(def ^:private protocol-version 1)
(def ^:private declaration-keys
  #{:protocol :family :channel :kind :key :entry :var})
(def ^:private expansion-plan-keys
  #{:name :definition :kind :key :entry :use-options})
(def ^:private registry-use-option-keys #{:override?})
(def ^:private selection-keys #{:kind :key :entry :use-options})

(defn- data-first-value? [value]
  (cond
    (or (nil? value)
        (string? value)
        (number? value)
        (keyword? value)
        (symbol? value)
        (boolean? value)
        (char? value)
        (inst? value)
        (uuid? value)) true
    (map? value) (and (every? data-first-value? (keys value))
                      (every? data-first-value? (vals value)))
    (or (vector? value) (set? value) (list? value))
    (every? data-first-value? value)
    :else false))

(defn- entry-key? [value]
  (or (keyword? value)
      (symbol? value)
      (string? value)
      (integer? value)
      (and (vector? value) (every? entry-key? value))))

(s/def ::protocol #(= protocol-version %))
(s/def ::family qualified-keyword?)
(s/def ::channel #{:registry :lifecycle})
(s/def ::kind keyword?)
(s/def ::key entry-key?)
(s/def ::entry data-first-value?)
(s/def ::var qualified-symbol?)
(s/def ::declaration
  (s/and (s/keys :req-un [::protocol ::family ::channel ::kind ::key ::entry
                          ::var])
         #(= declaration-keys (set (keys %)))))

(s/def ::override? boolean?)
(s/def ::registry-use-options
  (s/and map?
         #(every? registry-use-option-keys (keys %))
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))
(s/def ::use-options ::registry-use-options)

(s/def ::builder-bindings
  (s/and vector?
         seq
         #(simple-symbol? (first %))
         :clojure.core.specs.alpha/param-list))

(s/def ::expansion-plan
  (s/and map?
         #(= expansion-plan-keys (set (keys %)))
         #(simple-symbol? (:name %))
         #(seq? (:definition %))))

(s/def ::selection
  (s/and (s/keys :req-un [::kind ::key ::entry ::use-options])
         #(= selection-keys (set (keys %)))
         #(s/valid? ::registry-use-options (:use-options %))))
(s/def ::selected-vars (s/coll-of var? :kind vector? :min-count 1))

(defn- reject! [message reason offending extras]
  (throw (ex-info message (merge {:reason reason} offending extras))))

(defn- spec-rejection! [message reason spec value offending extras]
  (reject! message reason offending
           (merge {:value value :spec spec :explain (s/explain-data spec value)}
                  extras)))

(defn- conform! [spec value message reason form grammar]
  (let [conformed (s/conform spec value)]
    (if (= ::s/invalid conformed)
      (spec-rejection! message reason spec value {:form form}
                       {:grammar grammar})
      conformed)))

(defn validate-registry-use-options!
  "Return registry selection options conforming to `::registry-use-options`.

  The map is closed to boolean `:override?`. Rejections name the authored form
  and the allowed option keys."
  [options form]
  (when-not (s/valid? ::registry-use-options options)
    (spec-rejection!
     "Registry authoring selection options are invalid"
     :invalid-registry-use-options ::registry-use-options options {:form form}
     {:allowed-option-keys registry-use-option-keys}))
  options)

(defn prepare-registry-declaration!
  "Validate and return a protocol-1 registry `::declaration` before definition.

  `entry-spec` is the owning registry kind's registered entry spec. The entry is
  checked once here, followed by the closed descriptor. Macro expansions call
  this before executing their `def` or `defn`, so invalid authored values do not
  replace an existing Var."
  [family kind key entry var-symbol entry-spec form]
  (when-not (and (qualified-keyword? entry-spec) (s/get-spec entry-spec))
    (reject! "Registry authoring kind has no registered entry spec"
             :missing-entry-spec {:form form :value entry-spec}
             {:kind kind :entry-spec entry-spec}))
  (when-not (s/valid? entry-spec entry)
    (spec-rejection! "Registry authoring entry is invalid"
                     :invalid-kind-entry entry-spec entry {:form form}
                     {:kind kind :entry-spec entry-spec}))
  (let [declaration {:protocol protocol-version
                     :family family
                     :channel :registry
                     :kind kind
                     :key key
                     :entry entry
                     :var var-symbol}]
    (when-not (s/valid? ::declaration declaration)
      (spec-rejection! "Registry authoring declaration is invalid"
                       :invalid-declaration ::declaration declaration
                       {:form form}
                       {:expected-channel :registry
                        :expected-protocol protocol-version}))
    declaration))

(defn install-declaration!
  "Attach a validated `::declaration` to `target`; return the Var.

  Callers validate through `prepare-registry-declaration!` before defining the
  Var. This installation step trusts that normalized descriptor and performs no
  second boundary validation."
  [target declaration]
  (alter-meta! target assoc ::declaration declaration)
  target)

(defn- resolved-var-symbol [target]
  (let [{var-ns :ns var-name :name} (meta target)]
    (symbol (str (ns-name var-ns)) (str var-name))))

(defn- resolve-selected-var! [namespace symbol form family]
  (let [resolved (ns-resolve namespace symbol)]
    (when-not resolved
      (reject! "Authoring selection symbol does not resolve"
               :unresolved-symbol {:symbol symbol :form form}
               {:expected-family family :expected-channel :registry}))
    (when-not (var? resolved)
      (reject! "Authoring selection symbol does not resolve to a Var"
               :non-var-reference {:symbol symbol :form form :value resolved}
               {:expected-family family :expected-channel :registry}))
    resolved))

(defn- descriptor-reason [declaration]
  (cond
    (not (map? declaration)) :missing-declaration
    (not= protocol-version (:protocol declaration)) :protocol-mismatch
    (not= :registry (:channel declaration)) :wrong-channel
    :else :invalid-declaration))

(defn- validate-selected-descriptor! [target symbol family form entry-spec]
  (let [declaration (::declaration (meta target))]
    (when-not (s/valid? ::declaration declaration)
      (spec-rejection!
       "Selected Var has an invalid authoring declaration"
       (descriptor-reason declaration) ::declaration declaration
       {:symbol symbol :form form}
       {:expected-family family
        :expected-channel :registry
        :expected-protocol protocol-version}))
    (when-not (= :registry (:channel declaration))
      (reject! "Selected Var belongs to a different authoring channel"
               :wrong-channel {:symbol symbol :form form :value declaration}
               {:expected-channel :registry :channel (:channel declaration)}))
    (when-not (= family (:family declaration))
      (reject! "Selected Var belongs to a different authoring family"
               :wrong-family {:symbol symbol :form form :value declaration}
               {:expected-family family :family (:family declaration)}))
    (when-not (= (:var declaration) (resolved-var-symbol target))
      (reject! "Selected Var does not match its authoring declaration"
               :wrong-declaration-var {:symbol symbol :form form
                                       :value declaration}
               {:expected-var (resolved-var-symbol target)}))
    (let [entry-spec (or entry-spec (:kind declaration))]
      (when-not (and (qualified-keyword? entry-spec) (s/get-spec entry-spec))
        (reject! "Selected registry kind has no registered entry spec"
                 :missing-entry-spec {:symbol symbol :form form
                                      :value entry-spec}
                 {:kind (:kind declaration) :entry-spec entry-spec}))
      (when-not (s/valid? entry-spec (:entry declaration))
        (spec-rejection! "Selected Var carries an invalid registry entry"
                         :invalid-kind-entry entry-spec (:entry declaration)
                         {:symbol symbol :form form}
                         {:kind (:kind declaration) :entry-spec entry-spec})))
    declaration))

(defn- normalize-selection! [declaration options symbol form]
  (let [selection {:kind (:kind declaration)
                   :key (:key declaration)
                   :entry (:entry declaration)
                   :use-options options}]
    (when-not (s/valid? ::selection selection)
      (spec-rejection! "Authoring selection did not normalize"
                       :invalid-selection ::selection selection
                       {:symbol symbol :form form}
                       {:kind (:kind declaration)}))
    selection))

(defn- reject-duplicates! [selections symbols form]
  (let [duplicates (->> selections
                        (map (juxt :kind :key))
                        frequencies
                        (keep (fn [[entry-id count]]
                                (when (< 1 count) entry-id)))
                        vec)]
    (when (seq duplicates)
      (reject! "One authoring use form selects a registry entry more than once"
               :duplicate-selection {:form form :value duplicates}
               {:symbols symbols :duplicates duplicates}))))

(defn- collect-selections! [selections]
  (doseq [{:keys [kind key entry use-options]} selections]
    (runtime/collect-entry! kind key entry use-options)))

(defn select-registry!
  "Resolve, validate, and collect one typed registry selection.

  `symbols` are the quoted Var references supplied to a generated use macro.
  Every Var and protocol-1 `::declaration` is validated before collection;
  `entry-spec` is consulted for every selected entry. Pass nil to derive it from
  each descriptor's qualified `:kind`. The normalized values pass
  through `::selection`, duplicates fail before collection, and the returned Var
  vector is checked with `::selected-vars`.

  Generated families derive the spec from each qualified kind keyword; domain
  authors register that keyword as the kind's entry spec before defining the
  family. Built-in families pass their distinct registered specs explicitly."
  [family entry-spec namespace form options symbols]
  (let [options (validate-registry-use-options! options form)
        targets (mapv #(resolve-selected-var! namespace % form family) symbols)
        declarations (mapv (fn [target symbol]
                             (validate-selected-descriptor!
                              target symbol family form entry-spec))
                           targets symbols)
        selections (mapv #(normalize-selection! %1 options %2 form)
                         declarations symbols)]
    (reject-duplicates! selections symbols form)
    (when-not (s/valid? ::selected-vars targets)
      (spec-rejection! "Authoring selection returned invalid Vars"
                       :invalid-selected-vars ::selected-vars targets
                       {:form form} {}))
    (collect-selections! selections)
    targets))

(defn- definition-name [definition]
  (when (and (seq? definition)
             (contains? '#{def defn clojure.core/def clojure.core/defn}
                        (first definition)))
    (second definition)))

(defn- validate-expansion-plan! [plan form]
  (let [plan (conform! ::expansion-plan plan
                       "Authoring builder returned an invalid expansion plan"
                       :invalid-expansion-plan form
                       "{:name :definition :kind :key :entry :use-options}")
        defined-name (definition-name (:definition plan))]
    (when-not (= (:name plan) defined-name)
      (reject! "Authoring plan name must match its definition"
               :definition-name-mismatch {:form form :value plan}
               {:expected-name (:name plan) :defined-name defined-name
                :grammar "definition must be def or defn at :name"}))
    plan))

(defn expand-registry-use
  "Return the expansion for a generated typed registry use macro.

  Expansion rejects arbitrary value expressions and requires one or more Var
  symbols. The optional leading literal map is evaluated and checked against
  `::registry-use-options` by `select-registry!`."
  [family entry-spec namespace form args]
  (let [[options symbols] (if (map? (first args))
                            [(first args) (next args)]
                            [{} args])]
    (when (empty? symbols)
      (reject! "Authoring use form requires one or more Var symbols"
               :invalid-use-grammar {:form form}
               {:grammar "([{:override? boolean}] declaration-var+)"}))
    (when-let [value (first (remove symbol? symbols))]
      (reject! "Authoring use form accepts only Var symbols"
               :invalid-use-grammar {:form form :value value}
               {:grammar "([{:override? boolean}] declaration-var+)"}))
    `(select-registry! ~family ~entry-spec '~namespace '~form
                       ~options '~(vec symbols))))

(defn reject-definition-use-options!
  "Return nil for empty options; reject selection options on an inert definition.

  `defauthoring` emits this check before the generated definition executes."
  [options form]
  (when (seq options)
    (reject! "Definition-only authoring form rejects selection options"
             :selection-options-on-definition {:form form :value options}
             {:allowed-option-keys #{}})))

(defn expand-definition
  "Return one generated inert or define-and-use expansion.

  The builder result is conformed with `::expansion-plan` and its `:name` is
  checked against the exact Var defined by `:definition`. Declaration and use
  options are validated before the definition executes. Inert definitions
  require empty use options; bang definitions select once and still return the
  installed Var."
  [mode family namespace form plan]
  (let [{:keys [name definition kind key entry use-options]}
        (validate-expansion-plan! plan form)
        var-symbol (symbol (str namespace) (str name))
        target `(var ~name)
        kind-binding (gensym "kind")
        key-binding (gensym "key")
        entry-binding (gensym "entry")
        options-binding (gensym "options")
        declaration-binding (gensym "declaration")]
    (when (and (= :define mode) (map? use-options) (seq use-options))
      (reject! "Definition-only authoring form rejects selection options"
               :selection-options-on-definition {:form form
                                                 :value use-options}
               {:allowed-option-keys #{}}))
    `(let [~kind-binding ~kind
           ~key-binding ~key
           ~entry-binding ~entry
           ~options-binding (validate-registry-use-options! ~use-options '~form)
           ~declaration-binding (prepare-registry-declaration!
                                 ~family ~kind-binding ~key-binding ~entry-binding
                                 '~var-symbol ~kind-binding '~form)]
       ~(when (= :define mode)
          `(reject-definition-use-options! ~options-binding '~form))
       ~definition
       (install-declaration! ~target ~declaration-binding)
       ~(when (= :define-and-use mode)
          `(select-registry! ~family ~kind-binding '~namespace '~form
                             ~options-binding
                             ['~name]))
       ~target)))

(defmacro defauthoring
  "Define an open registry family's inert, typed-use, and bang macros.

  `(defauthoring noun [mode & user-bindings] & plan-body)` conforms the binding
  vector with `::builder-bindings`. `mode` receives `:define` for `def<noun>`
  and `:define-and-use` for `def<noun>!`; callers supply only `user-bindings`.
  The builder returns a closed `::expansion-plan` whose definition names the
  exact simple `:name`. The family's qualified keyword is derived from this
  namespace and `noun`; its qualified kind keyword must name the registered
  entry spec consulted by generated definitions and selections."
  [noun builder-bindings & plan-body]
  (let [form &form
        namespace (ns-name *ns*)]
    (when-not (simple-symbol? noun)
      (reject! "defauthoring noun must be a simple symbol"
               :invalid-defauthoring-noun {:form form :value noun}
               {:grammar "(defauthoring noun [mode & user-bindings] & plan-body)"}))
    (let [_ (conform! ::builder-bindings builder-bindings
                      "defauthoring builder bindings are invalid"
                      :invalid-builder-bindings form
                      "[mode & ordinary-defmacro-bindings]")
          mode-binding (first builder-bindings)
          user-bindings (subvec builder-bindings 1)
          family (keyword (str namespace) (name noun))
          define-name (symbol (str "def" noun))
          use-name (symbol (str "use-" noun "!"))
          bang-name (symbol (str "def" noun "!"))
          inert-doc (str "Define an inert " noun " declaration; return its Var.")
          use-doc (str "Select one or more " noun " declaration Vars; return them as a vector.")
          bang-doc (str "Define and select a " noun " declaration; return its Var.")]
      `(do
         (defmacro ~define-name
           ~inert-doc
           ~user-bindings
           (let [~mode-binding :define
                 plan# (do ~@plan-body)]
             (expand-definition :define ~family (ns-name *ns*) ~'&form plan#)))
         (defmacro ~use-name
           ~use-doc
           [& args#]
           (expand-registry-use ~family nil (ns-name *ns*) ~'&form args#))
         (defmacro ~bang-name
           ~bang-doc
           ~user-bindings
           (let [~mode-binding :define-and-use
                 plan# (do ~@plan-body)]
             (expand-definition :define-and-use ~family (ns-name *ns*)
                                ~'&form plan#)))))))
