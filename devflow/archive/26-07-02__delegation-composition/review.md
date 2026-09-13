# RFC-013 implementation review

## Findings

- **should-fix** — `spools/shuttle/src/skein/spools/shuttle.clj:628` / `spools/shuttle/src/skein/spools/shuttle.clj:885`: `review!` and `strand op agent review` still default to `generic-review-contract`, and the op only receives repo policy if the caller manually passes `--contract`. RFC-013.REC1/REC2 call for the review recipe to consume the one authoritative repo policy value (`delegation-policy-text`) rather than relying on hand-supplied prompt text. This leaves `agent review` as an unconnected consumer and lets reviewer contract text drift from the config-owned home.

## Validation

- `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test` completed with **0 failures, 0 errors** (`Ran 283 tests containing 1635 assertions`). The run emitted background shuttle thread stack traces during `skein.shuttle-test` / `skein.treadle-test`, but the test runner reported success.

## Verdict

request-changes

## Fixes applied

- fixed: `delegate-pipeline` no longer passes a possible `nil` step into `workflow/workflow` when `accept` is false; the workflow form is now built from an explicit step vector.
- fixed: added regression coverage for `delegate-pipeline` with `accept` omitted/false and per-task `max-attempts` passthrough.
- skipped: no original reviewer blocking/should-fix/nit list was available to action because the review file was absent at run start.

## Coordinator fixes applied

- Fixed the replacement reviewer's should-fix: shuttle gains `set-default-review-contract!` (weaver-lifetime, like harness aliases); `config/install!` sets it to `delegation-policy-text`, so `review!` and `strand op agent review` consume the authoritative policy text by default while explicit `:contract`/`--contract` still overrides. Regression tests in shuttle and config suites.
- Also fixed (found live, outside review scope): `agent ps --active` was rejected by the new parse-argv flag handling ("Unknown flag"); bare-flag handling restored with regression coverage.
- Pipeline observation for the record: the original review run (i2jus) exited 0 with empty output, so its gate closed with no findings anywhere — gate success does not verify contract fulfillment. Recorded in RFC outcomes as a follow-up (result-validation hook for treadle/shuttle).
