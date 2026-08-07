
-----
# <a name="millhouse.spools.chime">millhouse.spools.chime</a>


Human-attention notification bridge for Millstrand graph events.

  Chime watches strand mutations, evaluates small userland rules, and sends
  attention notices through a workspace-bound local notifier command. It owns
  only weaver-lifetime runtime state and composes the public weaver/event API.




## <a name="millhouse.spools.chime/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous notifier worker threads.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L118-L120">Source</a></sub></p>

## <a name="millhouse.spools.chime/close-engine!">`close-engine!`</a>
``` clojure
(close-engine! {:keys [runtime resource], :as context})
```
Function.

Close Chime's atomic engine boundary for a validated lifecycle context.

  `context` conforms to `::lifecycle-context`; its `:resource` conforms to
  `::engine-handle`, and the return value conforms to `::lifecycle-result`.

  A failed close restores the active cluster before surfacing the failure. The
  retained resource handle can therefore be retried without exposing a
  half-closed handler, barrier, or rule view.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L515-L550">Source</a></sub></p>

## <a name="millhouse.spools.chime/defrule">`defrule`</a>
``` clojure
(defrule name doc & args)
```
Macro.

Define a notification rule and collect its Chime declaration.

  Options conform to `::rule-options`; `:override? true` records explicit
  override intent.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L64-L81">Source</a></sub></p>

## <a name="millhouse.spools.chime/engine">`engine`</a>




Own Chime's handler, mutation barrier, and visible rule view atomically.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L552-L555">Source</a></sub></p>

## <a name="millhouse.spools.chime/mutation-registration-barrier!">`mutation-registration-barrier!`</a>
``` clojure
(mutation-registration-barrier! _context)
```
Function.

Serialize a pending graph mutation after any in-progress rule registration.

  Installed as a synchronous pre-commit hook. Its return value is ignored.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L408-L414">Source</a></sub></p>

## <a name="millhouse.spools.chime/notifier">`notifier`</a>
``` clojure
(notifier)
```
Function.

Return the current notifier binding, or nil when none is bound.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L178-L181">Source</a></sub></p>

## <a name="millhouse.spools.chime/notify!">`notify!`</a>
``` clojure
(notify! notification)
```
Function.

Send one notification through the current binding.

  Returns an inspectable map immediately. Missing notifier is recorded as a loud
  failure instead of silently dropping the notification.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L222-L238">Source</a></sub></p>

## <a name="millhouse.spools.chime/on-event">`on-event`</a>
``` clojure
(on-event event)
```
Function.

Weaver event handler: scan graph changes for attention notifications.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L403-L406">Source</a></sub></p>

## <a name="millhouse.spools.chime/open-engine!">`open-engine!`</a>
``` clojure
(open-engine! {:keys [runtime], :as context})
```
Function.

Open Chime's atomic engine boundary for a validated lifecycle context.

  `context` conforms to `::lifecycle-context`; the returned handle conforms to
  `::engine-handle`.

  The handler, mutation barrier, and visible rule view change under their
  shared monitor. A failed open compensates back to the inactive boundary so a
  lifecycle retry never inherits a half-open engine.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L489-L513">Source</a></sub></p>

## <a name="millhouse.spools.chime/recent-failures">`recent-failures`</a>
``` clojure
(recent-failures)
```
Function.

Return the last 100 notifier, process, and rule failures for this weaver lifetime.

  Entries diverge from the blessed event-failure entry
  (`millstrand.api.events.alpha/recent-failures`) on two keys, because chime's
  failures carry no event context to describe them with:

  - `:kind` — `:notifier-missing`, `:process`, or `:rule`. The blessed entry has
    no counterpart; it discriminates on `:event/type`, which chime's failures do
    not have. Two of chime's three kinds are not throws at all, so the kind is
    the only thing that says what went wrong.
  - `:message` — present only when something threw, not `:exception/message`:
    a missing notifier and a non-zero notifier exit are failures without an
    exception to take a message from.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L135-L150">Source</a></sub></p>

## <a name="millhouse.spools.chime/register!">`register!`</a>
``` clojure
(register! name fn-symbol)
```
Function.

Register or replace a notification rule.

  `fn-symbol` names a function receiving `{:event .. :strand ..}` and returning
  nil or `{:title .. :body ..}`. Currently matching strands become the rule's
  initial seen baseline, so durable conditions do not notify after registration
  even when they have never notified before. Mutations serialized after
  registration notify normally.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L256-L283">Source</a></sub></p>

## <a name="millhouse.spools.chime/reset-seen!">`reset-seen!`</a>
``` clojure
(reset-seen!)
```
Function.

Clear per-weaver notification deduplication and batch-scan state.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L152-L157">Source</a></sub></p>

## <a name="millhouse.spools.chime/rule-declaration">`rule-declaration`</a>
``` clojure
(rule-declaration rule-key options fn-sym)
```
Function.

Return a validated Chime rule declaration.

  `options` conforms to `::rule-options`; override intent remains collection
  metadata.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L49-L62">Source</a></sub></p>

## <a name="millhouse.spools.chime/rule-kind">`rule-kind`</a>




Owner-partitioned kind id for Chime notification rules.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L32-L34">Source</a></sub></p>

## <a name="millhouse.spools.chime/rules">`rules`</a>
``` clojure
(rules)
```
Function.

Return registered notification rules ordered by key.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L285-L288">Source</a></sub></p>

## <a name="millhouse.spools.chime/scan!">`scan!`</a>
``` clojure
(scan! event)
(scan!)
```
Function.

Evaluate registered rules against currently affected strands.

  Rules receive `{:event .. :strand .. :ready-ids #{..}}`; `:ready-ids` is
  computed once per scan. Batch events and their per-strand fanout share a
  `:batch/id`, and only the first event of a batch triggers a scan.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L382-L401">Source</a></sub></p>

## <a name="millhouse.spools.chime/set-notifier!">`set-notifier!`</a>
``` clojure
(set-notifier! notifier)
```
Function.

Bind the local notifier command for this weaver lifetime.

  The binding is `{:argv [..]}`. Chime appends the notification title as the
  final argument and writes the body to stdin. Rebinding replaces the prior
  value; pass a valid binding after every weaver startup or config reload.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L168-L176">Source</a></sub></p>

## <a name="millhouse.spools.chime/unregister!">`unregister!`</a>
``` clojure
(unregister! name)
```
Function.

Unregister a notification rule by key.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/8f386b09fb8e8506a3c38105dce8e8552142dbf8/spools/chime/src/millhouse/spools/chime.clj#L290-L311">Source</a></sub></p>
