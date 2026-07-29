
-----
# <a name="skein.spools.chime">skein.spools.chime</a>


Human-attention notification bridge for Skein graph events.

  Chime watches strand mutations, evaluates small userland rules, and sends
  attention notices through a workspace-bound local notifier command. It owns
  only weaver-lifetime runtime state and composes the public weaver/event API.




## <a name="skein.spools.chime/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous notifier worker threads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L123-L125">Source</a></sub></p>

## <a name="skein.spools.chime/contribute">`contribute`</a>
``` clojure
(contribute {:keys [runtime]})
```
Function.

Materialize Chime's rule kind for dependent module contributions.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L419-L425">Source</a></sub></p>

## <a name="skein.spools.chime/defrule">`defrule`</a>
``` clojure
(defrule name doc & args)
```
Macro.

Define a notification rule and collect its Chime declaration.

  Options conform to `::rule-options`; `:override? true` records explicit
  override intent.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L63-L80">Source</a></sub></p>

## <a name="skein.spools.chime/mutation-registration-barrier!">`mutation-registration-barrier!`</a>
``` clojure
(mutation-registration-barrier! _context)
```
Function.

Serialize a pending graph mutation after any in-progress rule registration.

  Installed as a synchronous pre-commit hook. Its return value is ignored.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L411-L417">Source</a></sub></p>

## <a name="skein.spools.chime/notifier">`notifier`</a>
``` clojure
(notifier)
```
Function.

Return the current notifier binding, or nil when none is bound.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L183-L186">Source</a></sub></p>

## <a name="skein.spools.chime/notify!">`notify!`</a>
``` clojure
(notify! notification)
```
Function.

Send one notification through the current binding.

  Returns an inspectable map immediately. Missing notifier is recorded as a loud
  failure instead of silently dropping the notification.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L225-L241">Source</a></sub></p>

## <a name="skein.spools.chime/on-event">`on-event`</a>
``` clojure
(on-event event)
```
Function.

Weaver event handler: scan graph changes for attention notifications.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L406-L409">Source</a></sub></p>

## <a name="skein.spools.chime/recent-failures">`recent-failures`</a>
``` clojure
(recent-failures)
```
Function.

Return the last 100 notifier, process, and rule failures for this weaver lifetime.

  Entries diverge from the blessed event-failure entry
  (`skein.api.events.alpha/recent-failures`) on two keys, because chime's
  failures carry no event context to describe them with:

  - `:kind` — `:notifier-missing`, `:process`, or `:rule`. The blessed entry has
    no counterpart; it discriminates on `:event/type`, which chime's failures do
    not have. Two of chime's three kinds are not throws at all, so the kind is
    the only thing that says what went wrong.
  - `:message` — present only when something threw, not `:exception/message`:
    a missing notifier and a non-zero notifier exit are failures without an
    exception to take a message from.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L140-L155">Source</a></sub></p>

## <a name="skein.spools.chime/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime], :as ctx})
```
Function.

Reconcile chime's engine and visible rule view for a module transition.

  Publication has already validated every owner partition. On an applied
  contribution: register the mutation-barrier pre-commit hook and the
  `:chime/engine` event handler, then baseline changed effective rules and
  publish the complete view — repeats stay idempotent because duplicate hook
  and handler keys replace prior entries (SPEC-004.C65/C76). On removal:
  unregister both, then publish an empty visible view; direct `register!`
  rules survive under the repl owner, so deactivation is view-level and a
  later reapplication re-baselines and republishes them. Every branch holds
  the visible-view monitor that scans, registration, and the mutation barrier
  share, so no mutation or event lane observes a half-applied transition. Any
  other contribution status fails loudly: the module kernel only reconciles
  applied and removed outcomes, so anything else is a caller error.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L446-L486">Source</a></sub></p>

## <a name="skein.spools.chime/register!">`register!`</a>
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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L259-L286">Source</a></sub></p>

## <a name="skein.spools.chime/reset-seen!">`reset-seen!`</a>
``` clojure
(reset-seen!)
```
Function.

Clear per-weaver notification deduplication and batch-scan state.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L157-L162">Source</a></sub></p>

## <a name="skein.spools.chime/rule-declaration">`rule-declaration`</a>
``` clojure
(rule-declaration rule-key options fn-sym)
```
Function.

Return a validated Chime rule declaration.

  `options` conforms to `::rule-options`; override intent remains collection
  metadata.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L48-L61">Source</a></sub></p>

## <a name="skein.spools.chime/rule-kind">`rule-kind`</a>




Owner-partitioned kind id for Chime notification rules.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L31-L33">Source</a></sub></p>

## <a name="skein.spools.chime/rules">`rules`</a>
``` clojure
(rules)
```
Function.

Return registered notification rules ordered by key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L288-L291">Source</a></sub></p>

## <a name="skein.spools.chime/scan!">`scan!`</a>
``` clojure
(scan! event)
(scan!)
```
Function.

Evaluate registered rules against currently affected strands.

  Rules receive `{:event .. :strand .. :ready-ids #{..}}`; `:ready-ids` is
  computed once per scan. Batch events and their per-strand fanout share a
  `:batch/id`, and only the first event of a batch triggers a scan.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L385-L404">Source</a></sub></p>

## <a name="skein.spools.chime/set-notifier!">`set-notifier!`</a>
``` clojure
(set-notifier! notifier)
```
Function.

Bind the local notifier command for this weaver lifetime.

  The binding is `{:argv [..]}`. Chime appends the notification title as the
  final argument and writes the body to stdin. Rebinding replaces the prior
  value; pass a valid binding after every weaver startup or config reload.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L173-L181">Source</a></sub></p>

## <a name="skein.spools.chime/spool">`spool`</a>




Entry-point declaration for the chime spool (PROP-Dsp-001 `def spool`
  convention).

  The refresh coordinator resolves `:contribute`/`:reconcile` from this public
  var at every module evaluation, so a consumer declares only a source target
  and world policy (`{:ns 'skein.spools.chime :spools [...]}`) and never mirrors
  the pair. Unqualified symbols resolve against this namespace; fn values are
  rejected (ADR-002.O1).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L488-L498">Source</a></sub></p>

## <a name="skein.spools.chime/unregister!">`unregister!`</a>
``` clojure
(unregister! name)
```
Function.

Unregister a notification rule by key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L293-L314">Source</a></sub></p>
