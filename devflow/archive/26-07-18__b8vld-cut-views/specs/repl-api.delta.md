# Delta: repl-api (views cut, b8vld)

Merged into `devflow/specs/repl-api.md` when this feature ships.

- Helper enumeration (around line 72): delete the `skein.api.views.alpha` bullet.
- Registration guidance paragraph (around line 81): drop "view" from the register-new-functions list and from the weaver-lifetime registration sentence.
- Config-require list (around line 83): remove `skein.api.views.alpha`.
- **SPEC-003.C59c:** "matching event/view/pattern conventions" becomes "matching event/pattern conventions".
- **SPEC-003.C59d:** reword "pull-based `wake-at` strand attributes plus views" to name named queries.
- Reload semantics (around line 133): remove views from the cleared-state list.
- Userland unwrapped-surface list (around line 204): remove views.
