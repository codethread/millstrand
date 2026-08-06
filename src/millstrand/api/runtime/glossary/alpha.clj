(ns millstrand.api.runtime.glossary.alpha
  "Explicit-runtime glossary of named failure outcomes for the discovery surface.

  A glossary outcome maps a qualified, stable name to a short canonical
  definition; the help projection (DELTA-Dtf-002.CC5) resolves per-verb
  `failure-modes` references against this one registry so lifecycle-failure prose
  is defined once and referenced by name, never restated per verb. Each owning
  spool reconciles its outcomes before help resolves the referencing ops (the
  load-order contract, DELTA-Dtf-003.CC2); trusted config may register outcomes
  directly. The registry is runtime-owned service state. Module refresh leaves
  direct entries intact, while an owning module reconciles its complete set.

  Discipline (TEN-000@1, no-migration alpha): outcome names are qualified and
  stable; `register-glossary-outcome!` fails loudly on a name collision, naming
  both registrants, and changed semantics require a **new name**, never a
  redefinition. `replace-glossary-outcome!` is the deliberate override and
  requires the name to already exist.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument; nothing here reads the published ambient runtime."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [reject-unknown-keys! require-valid!]]
            [millstrand.core.weaver.access :as access]))

(declare register-outcome replace-outcome validate-outcome!)

(def ^:private outcome-keys
  "The closed key set of a glossary-outcome declaration map."
  #{:name :definition :owner})

(defn- qualified-outcome-name?
  "True when `x` is a qualified, whitespace-free outcome name (`ns/term`).

  A qualified name carries at least one `/` with non-blank segments and no
  whitespace, keeping outcome names stable and collision-resistant across spools
  (e.g. `discovery/unavailable`)."
  [x]
  (boolean (and (string? x) (re-matches #"[^\s/]+(?:/[^\s/]+)+" x))))

(s/def ::name qualified-outcome-name?)
(s/def ::definition (s/and string? (complement str/blank?)))
(s/def ::owner (s/or :keyword keyword? :symbol symbol? :string string?))
(s/def ::outcome (s/keys :req-un [::name ::definition ::owner]))

(defn register-glossary-outcome!
  "Register `outcome` in `runtime`'s glossary and return it.

  `outcome` is a closed map `{:name :definition :owner}`: a qualified stable
  `:name` (`ns/term`), a non-blank `:definition`, and an `:owner` naming the
  registrant. Registering an already-registered name fails loudly, naming both
  the existing and attempting owners; use `replace-glossary-outcome!` for a
  deliberate override. The collision check runs inside the `swap!`, so two racing
  registrations of the same name cannot both clear a stale read."
  [runtime outcome]
  (let [outcome (validate-outcome! outcome)]
    (swap! (access/glossary-registry runtime) register-outcome outcome)
    outcome))

(s/fdef register-glossary-outcome!
  :args (s/cat :runtime map? :outcome ::outcome)
  :ret ::outcome)

(defn replace-glossary-outcome!
  "Replace an already-registered glossary `outcome`, failing loudly when absent.

  Same map shape as `register-glossary-outcome!`. This is the deliberate override
  for a name that already exists; unlike the register path it requires the name
  to be present. A changed-semantics redefinition still deserves a new name — this
  path exists for trusted config to re-seat an outcome it owns."
  [runtime outcome]
  (let [outcome (validate-outcome! outcome)]
    (swap! (access/glossary-registry runtime) replace-outcome outcome)
    outcome))

(s/fdef replace-glossary-outcome!
  :args (s/cat :runtime map? :outcome ::outcome)
  :ret ::outcome)

(defn glossary-outcomes
  "Return `runtime`'s registered glossary outcomes as full maps, sorted by name.

  The read/introspection projection: reads the runtime store explicitly, never
  the published ambient singleton."
  [runtime]
  (->> @(access/glossary-registry runtime)
       vals
       (sort-by :name)
       vec))

(s/fdef glossary-outcomes
  :args (s/cat :runtime map?)
  :ret (s/coll-of ::outcome :kind vector?))

(defn outcome-registered?
  "True when `runtime`'s glossary carries an outcome named `name`.

  The existence predicate the op-registration glossary-ref check
  (DELTA-Dtf-003.CC2) consults for every `failure-modes` reference."
  [runtime name]
  (contains? @(access/glossary-registry runtime) name))

(s/fdef outcome-registered?
  :args (s/cat :runtime map? :name any?)
  :ret boolean?)

;; --- validation and owner-guarded recording ---------------------------------

(defn- validate-outcome!
  "Return `outcome` after validating its closed shape, or fail loudly.

  Rejects a non-map, unknown keys, and a malformed name/definition/owner up
  front (an unqualified or blank name, a blank definition) — the validate half of
  the validate-then-record pattern."
  [outcome]
  (when-not (map? outcome)
    (require-valid! map? outcome "Glossary outcome must be a map"))
  (reject-unknown-keys! "register-glossary-outcome!" outcome-keys outcome)
  (require-valid! ::outcome outcome "Glossary outcome has an invalid shape"))

(defn- register-outcome
  "Registry `swap!` update fn: record `outcome` under its name, failing loudly
  when the name is already registered, naming both owners.

  Living inside the `swap!` keeps the collision check and the write atomic, so
  two racing registrations of one name cannot both clear a stale read."
  [registry {:keys [name owner] :as outcome}]
  (when-let [existing (get registry name)]
    (throw (ex-info "Glossary outcome already registered"
                    {:outcome name
                     :existing-owner (:owner existing)
                     :attempted-owner owner})))
  (assoc registry name outcome))

(defn- replace-outcome
  "Registry `swap!` update fn: replace `outcome`, failing loudly when its name is
  not already registered."
  [registry {:keys [name] :as outcome}]
  (when-not (contains? registry name)
    (throw (ex-info "Glossary outcome not registered; cannot replace"
                    {:outcome name
                     :available (vec (sort (keys registry)))})))
  (assoc registry name outcome))
