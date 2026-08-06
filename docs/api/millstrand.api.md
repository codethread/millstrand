
-----
# <a name="millstrand.api.millstrand.alpha">millstrand.api.millstrand.alpha</a>


Authoring forms for Millstrand's owner-complete core kinds.

  Each form defines an ordinary Clojure Var and collects one validated declaration
  while a runtime module source is evaluated. The retained declaration record is
  replayed for image modules, so source and image activation publish the same
  owner-complete partitions.




## <a name="millstrand.api.millstrand.alpha/defbin">`defbin`</a>
``` clojure
(defbin form-name doc opts)
```
Macro.

Define an executable declaration and collect its validated `:bins` entry.

  `:executable` names a command, a declaring-file-relative path, or a closed
  `[:family path]`/`[:root path]` anchor. An optional `:build` is an argv vector;
  `:override? true` records explicit override intent.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L86-L99">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defhandler">`defhandler`</a>
``` clojure
(defhandler form-name doc opts argv & body)
```
Macro.

Define an event handler and collect its validated `:events` declaration.

  Options require non-empty event `:types`; `:override? true` records explicit
  override intent.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L71-L84">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defhook">`defhook`</a>
``` clojure
(defhook form-name doc opts argv & body)
```
Macro.

Define a lifecycle hook and collect its validated `:hooks` declaration.

  Options require non-empty event `:types`; `:override? true` records explicit
  override intent.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L56-L69">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defop">`defop`</a>
``` clojure
(defop form-name doc opts argv & body)
```
Macro.

Define an operation handler and collect its validated `:ops` declaration.

  Options require `:arg-spec`; `:override? true` records explicit override intent
  without entering the registry value.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L11-L25">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defpattern">`defpattern`</a>
``` clojure
(defpattern form-name doc opts argv & body)
```
Macro.

Define a weave handler and collect its validated `:patterns` declaration.

  Options require a named input `:spec`; `:override? true` records explicit
  override intent.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L41-L54">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defquery">`defquery`</a>
``` clojure
(defquery form-name doc opts definition)
```
Macro.

Define a named query and collect its validated `:queries` declaration.

  Options accept `:usage`; `:override? true` records explicit override intent.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L27-L39">Source</a></sub></p>
