# Task 3: Author skein.macros.ops defop

**Document ID:** `TASK-Srm-003`

## TASK-Srm-003.P1 Scope

Type: AFK

Add `skein.macros.ops`: a `defop` macro that fuses a CLI op's handler `defn`, its arg-spec, and its conventions metadata into one block, remembering it per-namespace for an `install-ops!` that registers it through the blessed op API. Mirror the `defpattern` remember-then-install shape. Do not convert `config.clj` — that is task 6.

## TASK-Srm-003.P2 Must implement exactly

- **TASK-Srm-003.MI1:** New file `.skein/spools/macros/src/skein/macros/ops.clj`, namespace `skein.macros.ops` with an `ns` docstring. Same structural shape as `patterns.clj`: `defonce ^:private` registry atom, `remember-op!`, `install-ops!` (no-arg and explicit-`ns-sym` arities), the `defop` macro, and an accessor for remembered entries (MI6).
- **TASK-Srm-003.MI2:** `defop` signature carries a name symbol, a docstring, an options map, the handler arg vector, and the handler body. It expands to a real top-level `(defn <name>-op <docstring> <argv> <body...>)` plus a `remember-op!` recording the op name, the fully-qualified handler symbol (`<current-ns>/<name>-op`), the resolved op metadata, and the conventions data. The handler var name must be `<name>-op` so today's handler symbols (`config/devflow-start-op`, ...) are unchanged.
- **TASK-Srm-003.MI3:** The options map accepts `:arg-spec` as either a named arg-spec var/symbol or an inline arg-spec map (RFC-020.Q1), and passes through extra op-metadata keys such as `:deadline-class` (needed for `flow-await`'s `:deadline-class :unbounded`). It also carries conventions metadata (e.g. `:convention {...}` / the `{:name :help ...}` fields the `devflow-conventions` `:ops` listing needs) which is remembered but not passed to `register-op!`.
- **TASK-Srm-003.MI4:** `install-ops!` resolves the runtime via `skein.api.current.alpha/current` and registers each remembered op through `skein.api.weaver.alpha/register-op!` with op metadata equivalent to today's `(op-metadata arg-spec)` result (`{:doc ... :arg-spec ...}`) plus any extra metadata keys, and the remembered handler symbol. It returns registration metadata shaped like today's `install!` `:ops` vector so `config/install!` keeps its return value.
- **TASK-Srm-003.MI5:** Preserve `register-op!`'s loud-collision contract: registering two ops with the same name still fails loudly. Registration stays deferred to `install-ops!`; nothing registers at macroexpansion.
- **TASK-Srm-003.MI6:** Expose the remembered entries for a namespace as ordered conventions data (at least the `{:name :help}` fields, plus any authored extra fields) so task 7 can derive the `devflow-conventions` `:ops` listing.
- **TASK-Srm-003.MI7:** Fail loudly at macroexpansion on a non-symbol name, a missing/non-string docstring, or a missing arg-spec, throwing an `ex-info` naming the op. Follow the `defpattern` guard style.
- **TASK-Srm-003.MI8:** Unit tests in `test/skein/macros/ops_test.clj`: a `defop` block defines the `<name>-op` var and remembers the entry with the right handler symbol/metadata/conventions data; both the named-arg-spec and inline-arg-spec forms work; an extra metadata key such as `:deadline-class` survives to registration; `install-ops!` registers into an isolated `:publish? false` runtime and a double registration fails loudly; the guards throw.

## TASK-Srm-003.P3 Done when

- **TASK-Srm-003.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" flock -w 3600 /tmp/skein-test.lock clojure -M:test` is green, including the new tests.
- **TASK-Srm-003.DW2:** The macro expands to a real, greppable `(defn <name>-op ...)` a reader can jump to; the handler symbol remembered for registration is fully qualified to the calling namespace.
- **TASK-Srm-003.DW3:** One atomic commit; nothing pushed; no generated artifacts in `git status --short`.

## TASK-Srm-003.P4 Out of scope

- **TASK-Srm-003.OS1:** Editing `config.clj`, deleting its op vector, or the conventions derivation (tasks 6 and 7).
- **TASK-Srm-003.OS2:** `defquery` and `defrule` (tasks 2 and 4).

## TASK-Srm-003.P5 References

- **TASK-Srm-003.REF1:** [PLAN-Srm-001.PH2](../skein-readability-macros.plan.md), PLAN-Srm-001.A1/A3/A4, PLAN-Srm-001.TC2/TC3.
- **TASK-Srm-003.REF2:** `.skein/spools/macros/src/skein/macros/patterns.clj`; `register-op!` in `src/skein/api/weaver/alpha.clj`; today's op handlers, arg-specs, `op-metadata`, and the `install!` op vector in `.skein/config.clj`.
- **TASK-Srm-003.REF3:** RFC-020.P6.1 authoring sketch; RFC-020.Q1 (arg-spec form); RFC-020.Q3 (per-namespace remembering).
