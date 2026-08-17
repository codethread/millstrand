---

# <a name="millstrand.api.millstrand.alpha">millstrand.api.millstrand.alpha</a>

Authoring forms for Millstrand's owner-complete core kinds.

Every family has an inert definition, a typed use form, and a bang shorthand that defines and selects. Definitions attach a reusable descriptor to the exact authored Var; only selection contributes to a module collector. The imperative runtime registration functions and `collect-kind!` remain the direct low-level surface.

## <a name="millstrand.api.millstrand.alpha/defbin">`defbin`</a>

```clojure
(defbin & args)
```

Macro.

Define an inert bin declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L76-L85">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defbin!">`defbin!`</a>

```clojure
(defbin! & args)
```

Macro.

Define and select a bin declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L76-L85">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defhandler">`defhandler`</a>

```clojure
(defhandler & args)
```

Macro.

Define an inert handler declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L64-L74">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defhandler!">`defhandler!`</a>

```clojure
(defhandler! & args)
```

Macro.

Define and select a handler declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L64-L74">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defhook">`defhook`</a>

```clojure
(defhook & args)
```

Macro.

Define an inert hook declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L52-L62">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defhook!">`defhook!`</a>

```clojure
(defhook! & args)
```

Macro.

Define and select a hook declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L52-L62">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defop">`defop`</a>

```clojure
(defop & args)
```

Macro.

Define an inert op declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L20-L29">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defop!">`defop!`</a>

```clojure
(defop! & args)
```

Macro.

Define and select an op declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L20-L29">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defpattern">`defpattern`</a>

```clojure
(defpattern & args)
```

Macro.

Define an inert pattern declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L41-L50">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defpattern!">`defpattern!`</a>

```clojure
(defpattern! & args)
```

Macro.

Define and select a pattern declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L41-L50">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defquery">`defquery`</a>

```clojure
(defquery & args)
```

Macro.

Define an inert query declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L31-L39">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/defquery!">`defquery!`</a>

```clojure
(defquery! & args)
```

Macro.

Define and select a query declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L31-L39">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/use-bin!">`use-bin!`</a>

```clojure
(use-bin! & args)
```

Macro.

Select one or more bin declaration Vars; return them as a vector.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L76-L85">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/use-handler!">`use-handler!`</a>

```clojure
(use-handler! & args)
```

Macro.

Select one or more handler declaration Vars; return them as a vector.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L64-L74">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/use-hook!">`use-hook!`</a>

```clojure
(use-hook! & args)
```

Macro.

Select one or more hook declaration Vars; return them as a vector.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L52-L62">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/use-op!">`use-op!`</a>

```clojure
(use-op! & args)
```

Macro.

Select one or more op declaration Vars; return them as a vector.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L20-L29">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/use-pattern!">`use-pattern!`</a>

```clojure
(use-pattern! & args)
```

Macro.

Select one or more pattern declaration Vars; return them as a vector.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L41-L50">Source</a></sub></p>

## <a name="millstrand.api.millstrand.alpha/use-query!">`use-query!`</a>

```clojure
(use-query! & args)
```

Macro.

Select one or more query declaration Vars; return them as a vector.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/millstrand/alpha.clj#L31-L39">Source</a></sub></p>
