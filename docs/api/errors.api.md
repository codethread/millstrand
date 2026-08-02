
-----
# <a name="skein.api.errors.alpha">skein.api.errors.alpha</a>


Error factories for op, query, and spool authors: the handful of `ex-data`
  keys the CLI renders as affordances, made discoverable and checked where the
  error is thrown.

  Everything an op throws reaches a terminal through the weaver's error
  envelope, which promotes `:code` and carries the rest of the map as the
  error's details. Three detail keys earn a rendering of their own —
  `:available`, `:try`, and `:canonical-query` — and every other key is
  preserved verbatim in the details JSON. Those three were folklore:
  load-bearing, undocumented, and quietly shape-sensitive. A `:try` that is a
  vector never reaches pretty mode's `try:` line, and an `:available` holding
  numbers renders as an empty list, both without a word of complaint. The
  factories here name the keys, check their shapes, and say what each one buys
  the person reading the failure.

  They are a convenience, never a gate. Any op stays free to throw a bare
  `ex-info` and render fine; no key here is required by the wire; and nothing
  in this namespace closes a vocabulary. The CLI is affordance-driven and
  switches on no code (SPEC-005.C7 keeps codes and message text non-contract
  on purpose), so teaching it a new key means renderer work and tests in
  `cli/internal/errfmt`, not a new entry here.

  `skein.api.spool.alpha/fail!` remains the single throwing seam and the
  general escape hatch for an error that carries no affordance at all; every
  factory below funnels through it.




## <a name="skein.api.errors.alpha/conflict!">`conflict!`</a>
``` clojure
(conflict! message details)
(conflict! message details cause)
```
Function.

Throw an error for a request the current state refuses, and say how to get
  out of it.

  `details` must carry `:try`, the command that resolves the conflict — a held
  lock, a stale generation, a branch behind its remote. Pretty mode renders it
  as a trailing `try: <command>` line; plain and json modes keep it as an
  ordinary detail. Requiring it is deliberate: a conflict the reader cannot
  act on is the shape this factory exists to stop shipping. `:code` behaves as
  it does for `not-found!`.

  Every other key is the author's own and reaches the terminal untouched in
  the details JSON. Never returns.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/errors/alpha.clj#L141-L157">Source</a></sub></p>

## <a name="skein.api.errors.alpha/invalid-argument!">`invalid-argument!`</a>
``` clojure
(invalid-argument! message details)
(invalid-argument! message details cause)
```
Function.

Throw an error rejecting a value, saying both what was rejected and what
  would have been accepted.

  `details` must carry `:token`, the offending value, and at least one of
  `:expected` (free-form prose or a value, rendered as an ordinary detail) or
  `:available` (a collection of names, rendered as its own section). That pair
  is the whole point: a rejection that does not say what is valid sends the
  reader back to the docs. `:try` and `:code` behave as they do for
  `not-found!`.

  `:token` here is held to no shape at all — a rejected argument is as often a
  number, a map, or `nil` as a name, and the factory that refuses to carry the
  value is worse than the message it improves. Only a `:token` that reaches
  the client as text and appears in `message` can feed pretty mode's
  did-you-mean, so a name still buys the most.

  Every other key is the author's own and reaches the terminal untouched in
  the details JSON. Never returns.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/errors/alpha.clj#L108-L135">Source</a></sub></p>

## <a name="skein.api.errors.alpha/not-found!">`not-found!`</a>
``` clojure
(not-found! message details)
(not-found! message details cause)
```
Function.

Throw a not-found error naming what was looked for and, where the set is
  enumerable, what exists instead.

  `details` must carry `:token`, the name that was not found — always known at
  the throw site, and the value pretty mode's did-you-mean ranks the list
  against. Supply `:available` (a collection of names) whenever the valid set
  can be enumerated: the CLI prints it as its own section, so the reader sees
  the answer without reaching for help. `:try` adds a trailing `try: <command>`
  line in pretty mode and rides along as an ordinary detail elsewhere.

  Leave `:code` out unless the surface has a consumer-facing name of its own.
  Codes are free-form and non-contract, nothing switches on them, and an
  absent code lets the weaver infer one — including the `query/not-found` a
  failed canonical-query lookup owes its callers (SPEC-004.C36b), which an
  explicit code would silently replace.

  Every other key is the author's own and reaches the terminal untouched in
  the details JSON. Never returns.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/errors/alpha.clj#L77-L102">Source</a></sub></p>

## <a name="skein.api.errors.alpha/remedy">`remedy`</a>
``` clojure
(remedy details command)
```
Function.

Return `details` with `command` stamped under `:try`.

  The named door to the remediation affordance for an error that does not come
  from a factory above — a bare `ex-info`, or a `skein.api.spool.alpha/fail!`
  call gaining a way out. Fails loudly on a blank or non-string `command`,
  because a `:try` the renderer cannot read drops back into the ordinary
  detail rows rather than announcing itself, and on a `details` that is not a
  map, which `assoc` would otherwise turn into one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/errors/alpha.clj#L163-L177">Source</a></sub></p>
