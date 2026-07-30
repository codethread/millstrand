
-----
# <a name="skein.api.skein.alpha">skein.api.skein.alpha</a>


Authoring forms for Skein's owner-complete core kinds.

  Each form defines an ordinary Clojure Var and collects one validated declaration
  while a runtime module source is evaluated. The retained declaration record is
  replayed for image modules, so source and image activation publish the same
  owner-complete partitions.




## <a name="skein.api.skein.alpha/defhandler">`defhandler`</a>
``` clojure
(defhandler form-name doc opts argv & body)
```
Macro.

Define an event handler and collect its validated `:events` declaration.

  Options require non-empty event `:types`; `:override? true` records explicit
  override intent.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/skein/alpha.clj#L72-L85">Source</a></sub></p>

## <a name="skein.api.skein.alpha/defhook">`defhook`</a>
``` clojure
(defhook form-name doc opts argv & body)
```
Macro.

Define a lifecycle hook and collect its validated `:hooks` declaration.

  Options require non-empty event `:types`; `:override? true` records explicit
  override intent.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/skein/alpha.clj#L57-L70">Source</a></sub></p>

## <a name="skein.api.skein.alpha/defop">`defop`</a>
``` clojure
(defop form-name doc opts argv & body)
```
Macro.

Define an operation handler and collect its validated `:ops` declaration.

  Options require `:arg-spec`; `:override? true` records explicit override intent
  without entering the registry value.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/skein/alpha.clj#L12-L26">Source</a></sub></p>

## <a name="skein.api.skein.alpha/defpattern">`defpattern`</a>
``` clojure
(defpattern form-name doc opts argv & body)
```
Macro.

Define a weave handler and collect its validated `:patterns` declaration.

  Options require a named input `:spec`; `:override? true` records explicit
  override intent.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/skein/alpha.clj#L42-L55">Source</a></sub></p>

## <a name="skein.api.skein.alpha/defquery">`defquery`</a>
``` clojure
(defquery form-name doc opts definition)
```
Macro.

Define a named query and collect its validated `:queries` declaration.

  Options accept `:usage`; `:override? true` records explicit override intent.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/skein/alpha.clj#L28-L40">Source</a></sub></p>
