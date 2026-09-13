# Task 5: skein.spools.guild versioned op declaration spool

**Document ID:** `TASK-Guild-005`

## TASK-Guild-005.P1 Scope

Type: AFK

Ship `skein.spools.guild`, the classpath reference spool that lets a repo's trusted config declare a versioned public weaver op API over the existing CLI operation registry, with introspection and loud structured deprecation, per DELTA-DaemonRuntime-002.D1/D4 and PROP-Guild-001.G4.

## TASK-Guild-005.P2 Must implement exactly

- **TASK-Guild-005.MI1:** New `spools/src/skein/spools/guild.clj` with an `ns` docstring, composing only public surfaces (`skein.api.weaver.alpha` op registration; spec validation mirroring the pattern-registry precedent in `src/skein/api/patterns/alpha.clj`).
- **TASK-Guild-005.MI2:** `(defop! name opts handler-fn-sym)` registers an op in the CLI operation registry. `name` follows the version-suffixed dotted convention (`gate.close.v1` — document, don't enforce a format). Registry names are simple unqualified handles (SPEC-004.C63a): no `/`-namespaced keywords or strings; the registry rejects them. `opts` supports `:doc` and optional `:spec` (a resolvable spec name validated against the op's parsed input before the handler runs; validation failure is a loud structured error). Unknown opt keys fail loudly (TEN-003, matching workflow builder style). Handlers are fully-qualified symbols resolved weaver-side, matching registry conventions (SPEC-004.C63a/b).
- **TASK-Guild-005.MI3:** `(deprecate! name {:replacement r})` replaces the op's handler with a stub that always throws `ex-info` with data `{:code :op/deprecated :op <name> :replacement <r>}` (plus `:since` when provided). A deprecated op must never return success. Deprecating an unregistered name fails loudly.
- **TASK-Guild-005.MI4:** `(install!)` registers a built-in `guild.describe` op returning JSON-safe data: guild name (the weaver's friendly name when reachable from runtime state, else the value passed to `install!`), active ops (name, doc, spec name when declared), and deprecated ops with replacements. Re-running `install!`/`defop!` replaces prior entries (reload-safe, like other registries).
- **TASK-Guild-005.MI5:** Tests in `test/skein/guild_test.clj` (temp workspace fixture like `test/skein/spools_test.clj` / `test/skein/shuttle_test.clj`): defop + invoke through the op registry; spec-invalid input fails loudly with structured data; `guild.describe` lists active and deprecated ops; deprecated op invocation throws the structured `:op/deprecated` error and never succeeds; unknown defop opts fail loudly.
- **TASK-Guild-005.MI6:** Wire `skein.guild-test` into `test/skein/test_runner.clj`: add it to both the `:require` list and the `run-tests` call (preserve the `skein.shuttle-test` ordering constraint noted in that file's comment).

## TASK-Guild-005.P3 Done when

- **TASK-Guild-005.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **TASK-Guild-005.DW2:** A `strand op`-style invocation path (weaver api `op` invocation in-process is sufficient) exercises a guild-declared op end to end, proving peers will reach these ops through the socket `op` operation without any new server surface.

## TASK-Guild-005.P4 Out of scope

- **TASK-Guild-005.OS1:** Engine/registry changes; any enforcement of version-name formats.
- **TASK-Guild-005.OS2:** The gate-adapter spool (deferred, PROP-Guild-001.S5/Q3); contract doc prose (task 6 — code docstrings only here).

## TASK-Guild-005.P5 References

- **TASK-Guild-005.REF1:** [daemon-runtime delta](../specs/daemon-runtime.delta.md) D1/D4; SPEC-004.C63a–c (op registry contract).
- **TASK-Guild-005.REF2:** `spools/src/skein/spools/workflow.clj` (builder opt-validation style), `src/skein/api/patterns/alpha.clj` (spec-validation precedent), `spools/README.md` (classpath vs approved-local-root placement rule).
