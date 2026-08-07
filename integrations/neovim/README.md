# millstrand.nvim

Small Neovim helper for connecting [Conjure](https://github.com/Olical/conjure) to a running Millstrand weaver nREPL.

Requires Neovim with Lua support. On Neovim 0.10+ the plugin uses `vim.system`; on older versions it falls back to synchronous `vim.fn.system` for `mill weaver list`.

## Install with lazy.nvim

```lua
{
  dir = "/Users/ct/dev/projects/millstrand-src__repl/integrations/neovim",
  name = "millstrand.nvim",
  dependencies = { "Olical/conjure" },
  ft = { "clojure" },
  cmd = { "MillstrandConnect" },
}
```

For a cloned/plugin-manager path, replace `dir` with the appropriate `url` or local path.

## Minimal Conjure setup

Conjure must be loaded for Clojure buffers before `:MillstrandConnect` runs. A terse lazy.nvim setup:

```lua
{
  "Olical/conjure",
  ft = { "clojure" },
  init = function()
    vim.g["conjure#mapping#doc_word"] = false
    vim.g["conjure#client#clojure#nrepl#connection#auto_repl#enabled"] = false
  end,
  config = function()
    require("conjure.main")
    -- Optional if you use tree-sitter aware extraction in your config:
    -- require("conjure.extract")
  end,
}
```

If your config loads plugins from `FileType` autocommands, call `require("conjure.main")` for the `clojure` filetype and then bind `:MillstrandConnect`, for example:

```lua
vim.api.nvim_create_autocmd("FileType", {
  pattern = "clojure",
  callback = function(event)
    require("conjure.main")
    vim.keymap.set("n", "<localleader>sc", "<cmd>MillstrandConnect<cr>", {
      buffer = event.buf,
      desc = "Connect to Millstrand weaver",
    })
  end,
})
```

## clojure-lsp project root

Every spool in this repo is its own directory with its own `deps.edn` — `spools/*/deps.edn`, plus `.millstrand/spools/*/deps.edn`. nvim-lspconfig's default `root_markers` for `clojure_lsp` list `deps.edn` and `.git` at equal priority, so the nearest marker wins: opening `spools/workflow/src/millstrand/spools/workflow.clj` roots the server at `spools/workflow`. That directory's `deps.edn` is only `{:paths ["src"]}`, so Millstrand core never reaches the classpath and go-to-definition into `millstrand.core.*` silently fails.

Give `.git` its own higher-priority tier so the repo root always wins:

```lua
vim.lsp.config("clojure_lsp", {
  root_markers = {
    { ".git" },
    { "deps.edn", "project.clj", "build.boot", "shadow-cljs.edn", "bb.edn" },
  },
})
```

Nested tables are priority tiers, so the second one still roots checkouts that have no `.git`. From the repo root, clojure-lsp runs `clojure -A:test:dev -Spath`, and the `:test` alias in the top-level `deps.edn` carries every spool source directory — that is what puts the whole repo on one classpath.

A server that already started in the wrong place leaves a cache behind that survives the config change. Delete those once, then reopen a Clojure file:

```sh
rm -rf spools/*/.lsp spools/*/.clj-kondo .millstrand/spools/*/.lsp .millstrand/spools/*/.clj-kondo
```

The first open after that rebuilds the repo-root cache, which takes a while on a classpath this size.

## Usage

1. Ensure `mill` is on `$PATH` and running:

   ```sh
   mill start
   ```

2. Start a weaver in the target Millstrand workspace:

   ```sh
   mill weaver start
   ```

3. In Neovim, open a Clojure buffer and run:

   ```vim
   :MillstrandConnect
   ```

The command runs `mill weaver list`, decodes the JSON rows, shows running weavers with their friendly name, shortened config path, state, and nREPL endpoint, then runs:

```vim
:ConjureConnect <host> <port>
:ConjureEval (do (in-ns 'user) (require '[millstrand.repl :as repl]))
```

Errors are reported with `vim.notify` if `mill` is missing, the JSON is malformed, no weavers are running, the selected row lacks nREPL metadata, Conjure commands are unavailable, or Conjure connection/eval commands fail.

## Evaluating Millstrand forms from buffers

`:MillstrandConnect` connects Conjure and evaluates:

```clojure
(do (in-ns 'user) (require '[millstrand.repl :as repl]))
```

That puts the prompt in the neutral `user` namespace with `millstrand.repl` aliased, so `(repl/register-query! ...)` works without a further require. When evaluating forms from a file such as `.millstrand/init.clj`, the file's namespace still matters, so name the alias there too:

```clojure
(require '[millstrand.repl :as repl])

(repl/register-query! 'mine [:= [:attr :owner] "me"])  ; claim a live query
(repl/unregister-query! 'mine)                          ; retract it
```

For scratch examples you want to keep in a config or source file, put them in a `comment` block. Clojure ignores the block when loading the file, but Conjure can evaluate forms inside it:

```clojure
(comment
  (require '[millstrand.api.current.alpha :as current]
           '[millstrand.api.weaver.alpha :as weaver]
           '[clojure.pprint :refer [pprint]])

  (pprint (weaver/ready (current/runtime)))

  (def s (:id (weaver/add! (current/runtime) {:title "Try editor eval"
                                             :attributes {:owner "me"}})))
  (weaver/update! (current/runtime) s {:state "closed"}))
```

If you want unqualified registration verbs in a file, explicitly refer them:

```clojure
(require '[millstrand.repl :refer [register-query! unregister-query!]])

(comment
  (register-query! 'mine [:= [:attr :owner] "me"])
  (unregister-query! 'mine))
```
