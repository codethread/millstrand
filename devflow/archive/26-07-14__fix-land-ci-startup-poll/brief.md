# Land CI startup polling

## Problem

The land workflow starts `gh pr checks --watch --fail-fast` immediately after the push/PR step closes. GitHub may not have registered checks yet. In that state, `gh pr checks` exits with `no checks reported` instead of waiting, and the shell executor stamps `gate/error`.

## Scope

- Wait up to 180 seconds for checks to appear on the pushed branch HEAD.
- Start the existing `gh pr checks --watch --fail-fast` command once checks exist.
- Fail immediately on command, authentication, PR lookup, or CI failures.
- Fail with branch and expected-HEAD diagnostics if checks never appear.
- Add deterministic regression coverage without live GitHub calls or sleeps.

Sign-off checkpoint discoverability is a separate follow-up.
