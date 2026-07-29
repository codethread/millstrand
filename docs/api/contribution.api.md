
-----
# <a name="skein.api.contribution.alpha">skein.api.contribution.alpha</a>


Authoring forms for owner-complete core runtime contributions.

  Each form defines an ordinary Clojure Var and collects one validated declaration
  while a runtime module source is evaluated. The retained declaration record is
  replayed for image modules, so source and image activation publish the same
  owner-complete partitions.




## <a name="skein.api.contribution.alpha/defhandler">`defhandler`</a>
``` clojure
(defhandler form-name doc opts argv & body)
```
Macro.

Define an event handler and collect its validated `:events` declaration.

  Options conform to `::handler-options`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L172-L183">Source</a></sub></p>

## <a name="skein.api.contribution.alpha/defhook">`defhook`</a>
``` clojure
(defhook form-name doc opts argv & body)
```
Macro.

Define a lifecycle hook and collect its validated `:hooks` declaration.

  Options conform to `::hook-options`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L159-L170">Source</a></sub></p>

## <a name="skein.api.contribution.alpha/defop">`defop`</a>
``` clojure
(defop form-name doc opts argv & body)
```
Macro.

Define an operation handler and collect its validated `:ops` declaration.

  Options conform to `::op-options`; `:override? true` records explicit override
  intent without entering the registry value.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L120-L133">Source</a></sub></p>

## <a name="skein.api.contribution.alpha/defpattern">`defpattern`</a>
``` clojure
(defpattern form-name doc opts argv & body)
```
Macro.

Define a weave handler and collect its validated `:patterns` declaration.

  Options conform to `::pattern-options` and require a named input `:spec`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L146-L157">Source</a></sub></p>

## <a name="skein.api.contribution.alpha/defquery">`defquery`</a>
``` clojure
(defquery form-name doc opts definition)
```
Macro.

Define a named query and collect its validated `:queries` declaration.

  Options conform to `::query-options`; `:override? true` records override intent.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L135-L144">Source</a></sub></p>

## <a name="skein.api.contribution.alpha/handler-declaration">`handler-declaration`</a>
``` clojure
(handler-declaration handler-key opts fn-sym)
```
Function.

Return a validated `:events` entry from `opts` conforming to `::handler-options`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L109-L118">Source</a></sub></p>

## <a name="skein.api.contribution.alpha/hook-declaration">`hook-declaration`</a>
``` clojure
(hook-declaration hook-key opts fn-sym)
```
Function.

Return a validated `:hooks` entry from `opts` conforming to `::hook-options`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L97-L107">Source</a></sub></p>

## <a name="skein.api.contribution.alpha/op-declaration">`op-declaration`</a>
``` clojure
(op-declaration op-name doc opts fn-sym)
```
Function.

Return a validated `:ops` entry.

  `opts` conforms to `::op-options`; `fn-sym` must be fully qualified. Override
  intent is collection metadata and is not stored in the entry.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L60-L72">Source</a></sub></p>

## <a name="skein.api.contribution.alpha/pattern-declaration">`pattern-declaration`</a>
``` clojure
(pattern-declaration pattern-name doc opts fn-sym)
```
Function.

Return a validated `:patterns` entry.

  `opts` conforms to `::pattern-options` and names the registered input spec.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L84-L95">Source</a></sub></p>

## <a name="skein.api.contribution.alpha/query-declaration">`query-declaration`</a>
``` clojure
(query-declaration _query-name opts definition)
```
Function.

Return a validated `:queries` entry.

  `opts` conforms to `::query-options`. Query compilation is the production
  grammar boundary; `:usage` and override intent are authoring metadata.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/contribution/alpha.clj#L74-L82">Source</a></sub></p>
