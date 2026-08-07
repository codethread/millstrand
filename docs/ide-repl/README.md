# IDE REPL setup

Millstrand exposes each running weaver as an nREPL server. Editor integrations can connect to that nREPL directly, which lets you evaluate Clojure forms against the live weaver runtime.

This guide covers VS Code with [Calva](https://calva.io/), a popular Clojure extension. Unlike the Neovim integration in `integrations/neovim`, this is just a manual connection workflow; there is no Millstrand-specific VS Code plugin.

## Prerequisites

1. Install the Millstrand CLIs from the Millstrand checkout:

   ```sh
   make install
   ```

2. Install VS Code and the Calva extension.
3. Ensure `mill` and `strand` are on your shell `$PATH`.

## Start a weaver

For an explicit workspace, assign its directory once. Use the existing `.millstrand` or `.ms` marker, or another directory selected with `--workspace`:

```sh
workspace="/absolute/path/to/my-repo/.millstrand"
```

If the workspace has not been bootstrapped, run this once. `mill init` creates missing files and preserves existing ones:

```sh
mill init --workspace "$workspace"
```

Keep the generated `$workspace/init.clj` in place. It activates the Batteries spool. Put this guide's extra activation in the additive `$workspace/init.local.clj` file so the Batteries declaration remains intact.

Start mill in a durable terminal. The supervisor command has no workspace selector:

```sh
mill start
```

From the repository or Millstrand workspace you want to work with, start its weaver with the same workspace on every workspace-aware command:

```sh
mill weaver start --workspace "$workspace"
```

## Find the nREPL port

List the selected workspace's running weaver through mill:

```sh
mill weaver list --workspace "$workspace"
```

The output is JSON. Find the row for your workspace and use its `nrepl.host` and `nrepl.port` fields, for example:

```json
[
  {
    "name": "my-repo",
    "config_dir": "/path/to/my-repo/.millstrand",
    "state": "running",
    "nrepl": {"host": "127.0.0.1", "port": 51234}
  }
]
```

With `jq`, you can print just the endpoints:

```sh
mill weaver list --workspace "$workspace" | jq -r '.[] | select(.state == "running") | "\(.name)\t\(.config_dir)\t\(.nrepl.host):\(.nrepl.port)"'
```

## Connect from VS Code / Calva

1. Open VS Code.
2. Install or enable the **Calva: Clojure & ClojureScript Interactive Programming** extension.
3. Run the command palette action **Calva: Connect to a Running REPL Server in the Project**.
4. Choose **Clojure CLI** or **Generic nREPL** when prompted for the REPL type.
5. Enter the host and port from `mill weaver list`.

After Calva connects, evaluate this form once to land in the neutral `user` session namespace with `millstrand.repl` aliased:

```clojure
(do (in-ns 'user) (require '[millstrand.repl :as repl]))
```

Put behavior that should survive a restart in a module source file first:

```clojure
(ns my.workspace
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery mine
  "Return strands owned by me."
  {}
  [:= [:attr :owner] "me"])
```

Save the file as `$workspace/my-workspace.clj`. A workspace file module is loaded from the exact path relative to the selected workspace; the directory is not added to the classpath. Append this activation form to `$workspace/init.local.clj`; leave the generated `$workspace/init.clj` and its Batteries activation unchanged:

```clojure
;; init.local.clj — additive machine-local activation
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(runtime/module! (current/runtime) :my/workspace
  {:file "my-workspace.clj"})
```

The weaver loads `init.clj` and then `init.local.clj` at startup, so both Batteries and this module are active. After creating or changing `my-workspace.clj` or either startup file, evaluate this in the connected REPL to apply the complete module graph:

```clojure
(runtime/refresh! (current/runtime))
```

Evaluating an authoring form in the REPL defines its Var, but publishes nothing until the module collects it. That rule keeps the file's owner-complete declaration as the durable source. See [workspace modules and local spools](../spools/customisation.md#workspace-modules-and-local-spools) for the fuller layout and approval model.

For a live experiment, code and tests use the explicit-runtime registration functions. In this guide the nREPL is inside the weaver JVM, so the runtime-implied wrappers are convenient:

```clojure
(repl/register-query! 'scratch-query [:= [:attr :owner] "me"])
(repl/replace-query! 'scratch-query [:= [:attr :owner] "someone-else"])
(repl/unregister-query! 'scratch-query)
```

The example uses `scratch-query` because `mine` is already owned by the module above. `replace-query!` records intent to shadow an existing owner. `unregister-query!` removes only your entry and restores the value below it; registry verbs do not remove or change the `mine` Var. For ops, patterns, and hooks, redefining a handler function is the live hot loop under a stable contract, but help metadata stays stale until the registration is replaced. Queries are values, so replace the registration itself. Event handlers capture their function value at registration and also need a replacement to pick up a new body.

The connected nREPL used by Calva is an in-process session. A separate JVM connected through a client does not have an in-process runtime, so its registration wrappers fail with remediation; use the explicit `millstrand.core.client` bridge for that transport instead.

Reads and strand mutation stay on the `strand` CLI, or on the explicit-runtime `millstrand.api.*.alpha` verbs when you already hold a runtime:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.weaver.alpha :as weaver])

(weaver/ready (current/runtime))
```

## Evaluating from files

When evaluating forms from a file, the file's namespace still matters. Name the alias there too:

```clojure
(require '[millstrand.repl :as repl])

(comment
  (repl/register-query! 'scratch-query [:= [:attr :owner] "me"])
  (repl/unregister-query! 'scratch-query))
```

Stop the weaver when you are finished:

```sh
mill weaver stop --workspace "$workspace"
```
