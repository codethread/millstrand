(ns millstrand.core.weaver.help
  "Built-in `help` op wiring and the canonical help-envelope projection.

  This surface is core, not alpha: it owns the reserved help-alias token check
  (`help-alias-result`), the canonical help projection (`op-envelope`,
  `verb-envelope`, `op-catalog`), the built-in `help` op handler
  (`op-help-handler`), and its registrar (`register-built-in-ops!`). It lives in
  core because both `millstrand.api.weaver.alpha` (the alias check inside `op!`) and
  `millstrand.core.weaver.socket` (invoke-path dispatch) consume `help-alias-result`,
  and core must not require an alpha namespace.

  Help is not hand-authored: it is one declared, versioned schema, uniformly
  projected (SPEC-002.C39, DELTA-Dtf-001.D1). Every response is the response
  envelope `{schema-version, operation, source, glossary, node}` (the no-arg
  catalog is the same schema family, `{schema-version, ops[]}`), and `node` is
  the uniform fractal node (`{name, doc, invocation, returns, hook-class,
  deadline-class, use-when, notes, failure-modes, children}`) at every depth,
  recursing to the arg-spec's declared depth. `hook-class`/`deadline-class` are
  node keys with per-kind null semantics (DELTA-Lhc-003.CC1): class strings on
  invocable leaf node and `null` on interior nodes and subcommand-op roots.
  The projection normalizes today's registry data — the op envelope, the
  arg-spec `explain`
  (SPEC-003.C64/C65), and the per-case return-shape `explain` (SPEC-003.C60b)
  — into that schema; nothing here re-models or hand-writes usage.

  `source` is the op-wide handler pointer resolved best-effort at projection
  (DELTA-Dtf-002.CC2): `requiring-resolve` under the runtime spool classloader,
  then the resolved var's `:file`/`:line` mapped to a readable on-disk path, or
  `null` in the three best-effort cases. `glossary` is the referenced-term closure
  of the returned subtree, resolved once against the runtime glossary
  (DELTA-Dtf-002.CC5). The load graph pins its other reaches:
  `millstrand.core.weaver.access` requires the runtime and socket namespaces back to
  this one, so both the op-registry and glossary-registry reads use the runtime
  map's keys directly, and everything below `access` in the graph can
  only reach it and the alpha module dynamically — the `requiring-resolve` calls
  here (`register-op!`, `resolve-op`, the handler pointer, and
  `access/with-generation-classloader`) are call-time reaches into the public surface,
  the same idiom the socket transport uses for `op!`.

  Every `help` invocation renders through the registered default help transform
  when one is present, else emits the raw canonical envelope (DELTA-Dtf-001.CC4).
  The transform is the runtime-owned at-most-one slot
  (`millstrand.api.runtime.help-transform.alpha`), read here off the runtime map's
  `:help-transform-slot` key directly — the blessed accessor sits above this
  namespace in the load graph, like the op- and glossary-registry reads. It
  receives the full envelope and returns the string the CLI relays; a throwing
  transform fails loudly naming it (never a silent fallback, TEN-003), and
  `--json` always bypasses the slot so a broken transform never bricks help.
  `about`/`prime` output is never transformed.

  Besides the `help` catalog/detail projection, this namespace owns the two
  builtin arity-1 meta-verbs `about` and `prime` (DELTA-Dtf-002.CC6): each
  resolves one op and returns its declared `:about`/`:prime` prose beside the same
  op-wide `source`, failing loudly on missing prose or a verb path."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [millstrand.api.cli.alpha :as cli]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.return-shape.alpha :as return-shape]
            [millstrand.api.spec.alpha :as spec-alpha]
            [millstrand.core.weaver.core-registry :as core-registry]))

(def ^:private schema-version
  "Positive integer versioning the help-schema contract itself.

  Bumps only when the envelope/node shape changes; independent of release/build
  identity and of `protocol_version` (DELTA-Dtf-001.D3). Version 3 describes
  the uniform declared-op schema: every operation projects its arg-spec and
  `operation` carries only `name`, `provenance`, and `stream?`."
  3)

(defn- registered-op-entries
  "Return `runtime`'s registered op entries sorted by canonical name.

  Reads one immutable owner-registry snapshot directly to avoid closing the
  internal require graph through the access namespace."
  [runtime]
  (mapv val (sort-by key (core-registry/effective (:op-store runtime)))))

(defn- resolve-entry
  "Resolve the registry entry for `op-name` (a raw positional string).

  A call-time reach into the alpha surface (everything below `access` in the
  load graph can only reach it dynamically); the loud not-found error carries
  the available names."
  [runtime op-name]
  ((requiring-resolve 'millstrand.api.weaver.alpha/resolve-op)
   runtime (symbol op-name)))

(defn- readable-source-file
  "Resolve a var's `:file` metadata to a canonical readable on-disk path, or nil.

  An absolute `:file` is checked directly; a classpath-relative `:file` (source
  loaded off the classpath) is resolved through the thread context classloader's
  resource lookup — a `file:` URL yields the on-disk path, while a `jar:` or other
  URL (AOT, packaged code) yields nil. Nil whenever the path is absent, non-string,
  or does not name a readable regular file (DELTA-Dtf-002.CC2)."
  [file]
  (when (string? file)
    (let [direct (io/file file)
          on-disk (if (.isAbsolute direct)
                    direct
                    (when-let [url (io/resource file)]
                      (when (= "file" (.getProtocol url))
                        (io/file (.toURI url)))))]
      (when (and on-disk (.isFile on-disk) (.canRead on-disk))
        (.getCanonicalPath on-disk)))))

(defn- source-pointer
  "Build the `{file, line}` source pointer from a resolved handler var, or nil.

  Nil in the best-effort cases the wire contract allows (DELTA-Dtf-002.CC2): the
  var carries no `:file`/`:line`, or `:file` does not name a readable on-disk file.
  Runs outside the resolve guard, so an unexpected failure here is not a resolve
  failure and propagates loudly (TEN-003)."
  [var]
  (let [{:keys [file line]} (meta var)]
    (when (and (integer? line) (pos? line))
      (when-let [path (readable-source-file file)]
        {:file path :line line}))))

(defn- resolve-op-source
  "Best-effort op-wide handler `source` pointer for `entry`, resolved at
  projection (DELTA-Dtf-002.CC2).

  Resolves `entry`'s stored handler symbol via `requiring-resolve` under
  `runtime`'s spool classloader (so a synced-spool handler resolves and its
  on-disk source is found through the same classloader), then reads the resolved
  var's `:file`/`:line`. Always returns a value: `nil` in exactly the three
  best-effort cases — `requiring-resolve` fails (throws or yields nil), the var
  carries no `:file`/`:line`, or `:file` is not a readable on-disk file — else
  `{file, line}`. Only the resolve step is guarded; any other failure is unrelated
  and propagates loudly rather than masquerading as a null source."
  [runtime entry]
  ((requiring-resolve 'millstrand.core.weaver.access/with-generation-classloader)
   runtime
   (fn []
     (when-let [var (try (requiring-resolve (:fn entry))
                         (catch Exception _ nil))]
       (source-pointer var)))))

(defn- operation-facts
  "Project the op-wide envelope metadata for one registry entry.

  These are the facts that are not per-verb — `name` and the registry envelope
  metadata — so they live in the response envelope, never on the recursive node
  (DELTA-Dtf-001.CC1). `hook-class`/`deadline-class` are per-leaf node keys and
  no longer envelope facts (DELTA-Lhc-003.CC1)."
  [entry]
  {:name (:name entry)
   :provenance (str (:provenance entry))
   :stream? (:stream? entry)})

(defn- node
  "Assemble one uniform fractal node.

  Every key is always present with the defined empty/null semantics
  (DELTA-Dtf-001.CC2), so one recursive renderer needs no per-level branches.
  Authored annotations are read from `annotations`, the arg-spec node's
  `{:use-when :notes :failure-modes}` sub-map (nil for a node with none, e.g. a
  catalog summary node); `failure-modes` carries outcome-name
  references only, and the envelope resolves their definitions once
  (DELTA-Dtf-002.CC5). `hook-class`/`deadline-class` are class strings on
  invocable leaf nodes and nil elsewhere (DELTA-Lhc-003.CC1)."
  [name doc invocation returns annotations hook-class deadline-class children]
  {:name name
   :doc (or doc "")
   :invocation invocation
   :returns returns
   :hook-class hook-class
   :deadline-class deadline-class
   :use-when (vec (:use-when annotations))
   :notes (vec (:notes annotations))
   :failure-modes (vec (:failure-modes annotations))
   :children children})

(defn- leaf-class
  "Return the projected class string from one invocable leaf's metadata."
  [raw-node key]
  (some-> (get raw-node key) name))

(defn- node-doc
  "The declared doc for an op's root node.

  Prefers the arg-spec's doc (the node is that arg-spec's projection,
  DELTA-Dtf-003.CC1), falling back to the op doc; `node` renders `nil` as `\"\"`."
  [entry explained]
  (or (:doc explained) (:doc entry)))

(defn- projected-arg
  "Embed the spec projection on one rendered flag/positional help entry.

  A rendered entry whose source arg spec declared `:spec` accretes the blessed
  projection fields (SPEC-003.C23c): `spec-forms`, `contract`, and `template` —
  the same vocabulary every adopting wire surface speaks. The projection reads
  the live spec registry at envelope build so the view tracks the current
  spec; an unresolvable name FAILS LOUDLY (`discovery/arg-spec-unresolved`,
  TEN-003) rather than silently dropping the declared contract."
  [entry rendered raw-spec]
  (if-let [spec-name (:spec raw-spec)]
    (let [bundle (try
                   (spec-alpha/projection spec-name)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :spec/unregistered (:reason (ex-data e)))
                       (throw (ex-info
                               "Declared arg :spec is not a registered spec at help projection"
                               {:code "discovery/arg-spec-unresolved"
                                :operation (:name entry)
                                :arg (:name rendered)
                                :spec (pr-str spec-name)}
                               e))
                       (throw e))))]
      (assoc rendered
             :spec-forms (get bundle "spec-forms")
             :contract (get bundle "contract")
             :template (get bundle "template")))
    rendered))

(defn- projected-flags
  "Project `explained-flags` help entries, enriching each declared `:spec`.

  Rendered flag entries carry only the flag's bare name, so the declared spec
  ident is recovered from the raw node's `:flags` map by that name."
  [entry raw explained-flags]
  (let [raw-flags (into {} (map (fn [[k v]] [(name k) v])) (:flags raw))]
    (mapv #(projected-arg entry % (get raw-flags (:name %))) explained-flags)))

(defn- projected-positionals
  "Project `explained-positionals` help entries, enriching each declared
  `:spec`; rendered entries align with the raw `:positionals` vector by order."
  [entry raw explained-positionals]
  (mapv #(projected-arg entry %1 %2) explained-positionals (:positionals raw)))

(defn- arg-node
  "Project one arg-spec node into the uniform fractal node, recursing over its
  declared subcommands to any depth (DELTA-Lhc-001.CC5).

  `raw` is the arg-spec node (the source of annotations, leaf class metadata,
  and declared arg `:spec` idents), `explained` its `cli/explain` projection,
  and `returns` the return-tree node mirroring this position (nil when the op
  declares none) — an interior return node routes to the children, a leaf case
  renders."
  [entry node-name doc raw explained returns]
  (let [interior? (contains? raw :subcommands)
        routed-returns? (and (map? returns) (contains? returns :subcommands))]
    (node node-name doc
          {:mode "declared"
           :flags (projected-flags entry raw (:flags explained))
           :positionals (projected-positionals entry raw (:positionals explained))}
          (when (and (not interior?) (some? returns) (not routed-returns?))
            (return-shape/explain returns))
          (:annotations raw)
          (when-not interior? (leaf-class raw :hook-class))
          (when-not interior? (leaf-class raw :deadline-class))
          (mapv (fn [{child-name :name :as child-explained}]
                  (arg-node entry child-name (:doc child-explained)
                            (get-in raw [:subcommands child-name])
                            child-explained
                            (when routed-returns?
                              (get-in returns [:subcommands child-name]))))
                (:subcommands explained)))))

(defn- op-node
  "Project one op registry entry into its root fractal node.

  Leaves carry their own flags/positionals, routed return case, and class
  strings; interior nodes carry children and null classes. Op-wide facts stay
  in the envelope, never here."
  [entry]
  (let [arg-spec (:arg-spec entry)
        returns (when (contains? entry :returns) (:returns entry))
        explained (cli/explain arg-spec)]
    (arg-node entry (:name entry) (node-doc entry explained)
              arg-spec explained returns)))

(defn- summary-node
  "Project the shallow catalog node for one op (DELTA-Dtf-001.CC3).

  `name` and `doc` populated; `invocation` at its declared mode with empty
  flags/positionals; `returns` null, annotations `[]`, `children` `[]`.
  Classes follow the node rule (DELTA-Lhc-003.CC1): populated only when the
  summary node is itself the leaf, and null for subcommand-op roots."
  [entry]
  (let [arg-spec (:arg-spec entry)
        explained (cli/explain arg-spec)
        leaf? (not (contains? arg-spec :subcommands))]
    (node (:name entry) (node-doc entry explained)
          {:mode "declared"
           :flags [] :positionals []}
          nil
          nil
          (when leaf? (leaf-class arg-spec :hook-class))
          (when leaf? (leaf-class arg-spec :deadline-class))
          [])))

(defn- referenced-outcomes
  "Every glossary outcome name `node` or any descendant references, in
  first-seen order.

  Walks the fractal `children` recursively, collecting each node's
  `failure-modes` name refs (DELTA-Dtf-001.CC2) — the referenced-term set the
  envelope glossary closes over."
  [node]
  (into (vec (:failure-modes node))
        (mapcat referenced-outcomes)
        (:children node)))

(defn- node-glossary
  "Resolve the referenced-term closure of `node` against `runtime`'s glossary.

  Returns `{name → definition}` for every outcome name referenced anywhere under
  `node`, resolved once (DELTA-Dtf-002.CC5). Slicing narrows the closure because
  `node` is the returned subtree. `register-op!`'s glossary-ref check
  (DELTA-Dtf-002.CC7) validates refs at registration only; the op-registry and
  glossary-registry are separate cells a runtime reload clears independently, so a
  referenced name can be absent at projection time. An unresolved name FAILS LOUDLY
  (`discovery/glossary-ref-unresolved`, TEN-003) rather than dropping from the
  closure. The registry read uses the runtime map's `:glossary-registry` key
  directly, since the blessed accessor sits above this namespace in the load graph."
  [runtime entry node]
  (let [registry @(:glossary-registry runtime)
        names (referenced-outcomes node)
        unresolved (into [] (comp (remove #(contains? registry %)) (distinct)) names)]
    (when (seq unresolved)
      (throw (ex-info "Help glossary reference unresolved at projection"
                      {:code "discovery/glossary-ref-unresolved"
                       :operation (:name entry)
                       :node (:name node)
                       :unresolved-outcomes unresolved})))
    (into {}
          (map (fn [name] [name (:definition (get registry name))]))
          names)))

(defn- envelope
  "Build a canonical detail help envelope carrying `node`.

  `source` is the op-wide handler pointer resolved best-effort at projection
  (DELTA-Dtf-002.CC2). `glossary` is the referenced-term closure of `node`
  resolved against `runtime`'s glossary (DELTA-Dtf-001.CC1, DELTA-Dtf-002.CC5)."
  [runtime entry node]
  {:schema-version schema-version
   :operation (operation-facts entry)
   :source (resolve-op-source runtime entry)
   :glossary (node-glossary runtime entry node)
   :node node})

(defn- op-envelope
  "Build the canonical detail help envelope for one op registry entry."
  [runtime entry]
  (envelope runtime entry (op-node entry)))

(defn- node-at-path
  "Slice a projected node tree to the node a verb path names
  (DELTA-Lhc-001.CC6).

  Walks `root`'s children token by token; interior and leaf nodes are both
  valid targets. A token that names no child fails loudly with the canonical
  error context (`:op`, `:path`, `:token`, `:available`)."
  [entry root path]
  (loop [node root
         walked []]
    (if (= (count walked) (count path))
      node
      (let [token (nth path (count walked))
            child (some #(when (= token (:name %)) %) (:children node))]
        (when-not child
          (throw (ex-info "Help verb not found"
                          {:op (:name entry)
                           :path walked
                           :token token
                           :available (mapv :name (:children node))})))
        (recur child (conj walked token))))))

(defn- path-envelope
  "Build the detail envelope sliced to the node a verb path names.

  Op-wide facts (`operation`, `source`) are unchanged; `node` narrows to the
  named node — the same fractal shape at any depth, interior nodes included
  (DELTA-Lhc-001.CC6) — and `glossary` narrows with it to that subtree's
  referenced outcomes."
  [runtime entry path]
  (envelope runtime entry (node-at-path entry (op-node entry) path)))

(defn- catalog-entry
  "Project one op registry entry into a shallow catalog envelope entry.

  `{operation, source, node}` with the same structure as the detail envelope
  but a summary node (DELTA-Dtf-001.CC3); op-wide facts stay in `operation` and
  `source` (resolved best-effort as in the detail envelope, DELTA-Dtf-002.CC2),
  never merged onto the node."
  [runtime entry]
  {:operation (operation-facts entry)
   :source (resolve-op-source runtime entry)
   :node (summary-node entry)})

(defn- op-catalog
  "Build the versioned no-arg catalog `{schema-version, ops[]}` for `runtime`."
  [runtime]
  {:schema-version schema-version
   :ops (mapv #(catalog-entry runtime %) (registered-op-entries runtime))})

(defn- registered-transform
  "The runtime's registered default help transform, or nil.

  Reads the runtime map's `:help-transform-slot` cell directly — the blessed
  accessor (`millstrand.core.weaver.access/help-transform-slot`) sits above this
  namespace in the load graph, so requiring it would close a cycle, exactly as
  for the op- and glossary-registry reads."
  [runtime]
  @(:help-transform-slot runtime))

(defrecord VerbatimResult [text])

(defn verbatim-result
  "Wrap a default help transform's rendered string as a verbatim transport result.

  The transform returns the string the CLI relays VERBATIM — JSON or text, the
  transform's choice (DELTA-Dtf-002.CC1). Wrapping it lets the socket transport
  flag the response frame so the thin client prints the string byte-for-byte
  rather than re-encoding it as a JSON-quoted string (SPEC-002.C4/C36, TEN-006).
  Untransformed help and the `--json` floor stay the raw canonical envelope map,
  unwrapped and relayed as ordinary single-result JSON."
  [text]
  (->VerbatimResult text))

(defn verbatim-result?
  "True when `x` is a wrapped verbatim help result (see `verbatim-result`)."
  [x]
  (instance? VerbatimResult x))

(defn verbatim-text
  "The wrapped string of a `verbatim-result` (see `verbatim-result`)."
  [x]
  (:text x))

(defn- render-help
  "Render a help `envelope` through the registered default transform, or return
  the raw envelope.

  With `json?` true, or with no transform registered, returns the raw canonical
  envelope map (the `--json` floor and the default, DELTA-Dtf-001.CC4). A
  registered transform receives the full envelope plus the caller's terminal
  capabilities and returns the string the CLI relays; that string is wrapped as
  a `verbatim-result` so the transport relays it
  byte-faithfully (DELTA-Dtf-002.CC1). A throwing transform fails loudly naming
  its owner (`discovery/help-transform-failed`, TEN-003) — never a silent fallback
  — while `--json` always bypasses the slot so a broken transform never bricks
  help."
  [runtime envelope presentation json?]
  (if json?
    envelope
    (if-let [{:keys [transform owner]} (registered-transform runtime)]
      (verbatim-result
       (try
         (transform envelope presentation)
         (catch Throwable t
           (throw (ex-info "Default help transform failed"
                           {:code "discovery/help-transform-failed"
                            :transform owner}
                           t)))))
      envelope)))

(def ^:private help-flag-tokens
  "The `--help`/`-h` flag forms the weaver rewrites to the `help` op.

  The dash-prefixed subset of `millstrand.api.cli.alpha/reserved-subcommand-names`:
  only the flag forms trigger the trailing rewrite, while the bare word `help`
  stays reserved but flows to normal parsing (DELTA-Dtf-002.CC3)."
  (into #{} (filter #(str/starts-with? % "-")) cli/reserved-subcommand-names))

(def ^:private retired-sugar-tokens
  "Bare-word meta-verbs whose `<op> <word>` verb-position sugar is retired.

  `help`/`about`/`prime` were the migration-only `<op> help`/`about`/`prime`
  sugar (TEN-000@1); in verb position they are no longer rewritten and fail with
  a loud redirect to `strand help <op>` (DELTA-Dtf-002.CC3/CC5). The redirect is
  suppressed when the op declares a real subcommand by that name (e.g. a spool's
  own `about`/`prime` verb) or when the op is itself the meta-verb, since then
  the word is a legitimate argument that flows to normal parsing."
  #{"help" "about" "prime"})

(defn- declared-subcommand?
  "True when `entry`'s arg-spec declares a subcommand named `token`."
  [entry token]
  (contains? (get-in entry [:arg-spec :subcommands]) token))

(defn- help-grammar-redirect!
  "Fail loudly, redirecting a retired-sugar or malformed `--help` shape to the
  canonical `strand help <op>` grammar (DELTA-Dtf-001.CC5, DELTA-Dtf-002.CC3)."
  [entry detail]
  (throw (ex-info (str detail " Run `strand help " (:name entry) "` instead.")
                  {:code "discovery/help-grammar"
                   :operation (:name entry)})))

(defn help-alias-result
  "Resolve a post-op `--help` shape to a help projection, or govern the retired
  `<op> help` grammar, before the target op's handler or hooks run.

  A **clean trailing** `--help`/`-h` — the final argv token, with no other flag
  token before it and no attached payloads — rewrites to the `help` op for every
  op class (flat or subcommand). It resolves to the SAME node as
  `strand help <op> <verb...>`: a bare `<op> --help` yields the op's detail
  envelope, and verb tokens before the flag narrow it to the node that path
  names, composing to any declared depth (DELTA-Lhc-002.CC6) — a token naming
  no child fails loudly with the canonical error context, so `spool add <url>
  --help` fails naming `add`'s children as none, never silently parsing. Both
  `op!` and the socket transport consult this before hook gating, so the
  rewrite is a read-class projection and the target op's mutating hooks never
  fire.

  Everything else is not a rewrite. The bare word `help`/`about`/`prime` in verb
  position is the retired `<op> help`/`about`/`prime` sugar (TEN-000@1) and fails
  with the loud redirect to `strand help <op>` — unless the op declares a real
  subcommand by that name or is itself the meta-verb (then the word is a
  legitimate argument and flows to normal parsing). Any other `--help`/`-h` shape
  (a non-final flag, another flag alongside it, or attached payloads) likewise
  fails with the loud redirect rather than reaching a handler. A shape with no
  `--help`/`-h` and no retired verb returns nil and flows through normal parsing.

  Supersedes SPEC-004.C63e's subcommand-only sole-token alias. `help`/`-h`/
  `--help` remain reserved subcommand names (`help-flag-tokens` derives from
  `millstrand.api.cli.alpha/reserved-subcommand-names`), so the rewrite shadows
  nothing. `runtime` is passed so the rewritten projection resolves the envelope
  glossary closure and renders through the registered default help transform
  exactly as the `help` op does (DELTA-Dtf-002.CC1/CC5); the `--help` rewrite
  carries no `--json`, so its only bypass is `strand help <op> --json`."
  [runtime entry argv envelope]
  (let [argv (vec argv)
        payloads (or (:payloads envelope) {})
        trailing-flag? (and (seq argv) (contains? help-flag-tokens (peek argv)))
        clean-trailing? (and trailing-flag?
                             (not-any? #(str/starts-with? % "-") (pop argv))
                             (empty? payloads))
        head (first argv)]
    (cond
      clean-trailing?
      (let [verbs (pop argv)]
        (if (empty? verbs)
          (render-help runtime (op-envelope runtime entry)
                       (select-keys envelope [:is-tty :tty-col]) false)
          (render-help runtime (path-envelope runtime entry (vec verbs))
                       (select-keys envelope [:is-tty :tty-col]) false)))

      (and (contains? retired-sugar-tokens head)
           (not (contains? retired-sugar-tokens (:name entry)))
           (not (declared-subcommand? entry head)))
      (help-grammar-redirect!
       entry (str "`strand " (:name entry) " " head "` is retired sugar."))

      (some help-flag-tokens argv)
      (help-grammar-redirect!
       entry "`--help` must be the final token with no other flags or payloads.")

      :else nil)))

(defn op-help-handler
  "Project the op registry as canonical help, rendered through the transform slot.

  With no positional op name, build the versioned catalog `{schema-version,
  ops[]}` of shallow per-op envelopes sorted by name. With one op name, build
  that op's detail envelope `{schema-version, operation, source, glossary,
  node}`. With an op name and trailing verb tokens, slice `node` to the node
  that path names, live to the arg-spec's declared depth — interior nodes are
  valid targets (DELTA-Lhc-001.CC6). Unknown op names fail loudly through
  `resolve-op` (carrying available names); a token naming no child fails loudly
  with the canonical error context.

  The result is then rendered through the registered default help transform
  (`render-help`); `--json` bypasses the slot back to the raw envelope. `--json`
  is leading-only within the help surface (DELTA-Dtf-001.CC4): a non-leading
  `--json` fails loudly and redirects to the canonical `strand help --json ...`
  grammar."
  [ctx]
  (let [runtime (:op/runtime ctx)
        {:keys [op verbs json]} (:op/args ctx)]
    (when (and json (not= "--json" (first (:op/argv ctx))))
      (throw (ex-info "`--json` must lead the help surface. Run `strand help --json ...` instead."
                      {:code "discovery/help-grammar"})))
    (render-help runtime
                 (cond
                   (and op (seq verbs)) (path-envelope runtime (resolve-entry runtime op)
                                                       (vec verbs))
                   op (op-envelope runtime (resolve-entry runtime op))
                   :else (op-catalog runtime))
                 {:is-tty (:op/is-tty ctx) :tty-col (:op/tty-col ctx)}
                 (boolean json))))

(def ^:private help-arg-spec
  "Arg-spec for the built-in `help` op: a leading `--json` flag, an optional op
  name, and an optional trailing verb path.

  This makes `help` the first parser-consuming op, so `op!` parses its argv and
  supplies the resolved positionals as `:op/args`. Trailing `verbs` slice the
  detail envelope's node to the node that path names, to any declared depth
  (DELTA-Lhc-001.CC6). `--json` is the sole opt-out to the raw canonical
  envelope and is leading-only (the handler rejects a non-leading `--json`);
  no other flags are valid on the help surface (DELTA-Dtf-001.CC4)."
  {:op "help"
   :doc "Show the help catalog, one op's detail envelope, or one node's slice."
   :flags {:json {:type :boolean
                  :doc (format-alpha/reflow
                        "|Bypass any registered help transform and emit the raw
                         |canonical envelope JSON. Leading-only.")}}
   :positionals [{:name :op
                  :type :string
                  :required? false
                  :doc (format-alpha/reflow
                        "|Optional op name; when given, return that op's detail
                         |envelope instead of the catalog.")}
                 {:name :verbs
                  :type :string
                  :required? false
                  :variadic? true
                  :doc (format-alpha/reflow
                        "|Optional subcommand path; slices the detail envelope's
                         |node to the node the tokens name, at any depth.")}]
   :hook-class :read
   :deadline-class :standard})

(def ^:private operation-return-shape
  "Declared return shape for the op-wide `operation` map (DELTA-Dtf-001.CC1);
  per-leaf classes are node keys, not envelope facts (DELTA-Lhc-003.CC1)."
  {:type :map
   :required {:name :string
              :provenance :string
              :stream? :boolean}})

(def ^:private node-return-shape
  "Declared return shape for the uniform fractal `node` (DELTA-Dtf-001.CC2).

  `returns` and `children` items are `:json` here: the per-case return-shape
  explain and the recursive child nodes are arbitrary JSON-safe data, not a
  fixed leaf shape. `hook-class`/`deadline-class` are class strings on leaf
  nodes and null on interior and subcommand-root nodes (DELTA-Lhc-003.CC1)."
  {:type :map
   :required {:name :string
              :doc :string
              :invocation {:type :map
                           :required {:mode :string
                                      :flags {:type :collection :items :json}
                                      :positionals {:type :collection :items :json}}}
              :returns :json
              :hook-class [:nullable :string]
              :deadline-class [:nullable :string]
              :use-when {:type :collection :items :string}
              :notes {:type :collection :items :string}
              :failure-modes {:type :collection :items :string}
              :children {:type :collection :items :json}}})

(def ^:private help-return-shape
  "Declared return shape for the `help` op.

  One shape covers both the detail envelope `{schema-version, operation, source,
  glossary, node}` and the versioned no-arg catalog `{schema-version, ops[]}` —
  the same schema family (DELTA-Dtf-001.CC1/CC3). `source` is `:json` so it
  accepts both `null` and a `{file, line}` map."
  {:type :map
   :required {:schema-version :integer}
   :optional {:operation operation-return-shape
              :source :json
              :glossary {:type :map :extra :json}
              :node node-return-shape
              :ops {:type :collection
                    :items {:type :map
                            :required {:operation operation-return-shape
                                       :source :json
                                       :node node-return-shape}}}}})

(defn- meta-verb-result
  "Project one op's declared `field` prose beside the op-wide `source`.

  The shared body of the arity-1 `about`/`prime` meta-verbs (DELTA-Dtf-002.CC6).
  A verb path (extra positionals past the op name) fails loudly and redirects to
  `help` (DELTA-Dtf-003.CC4); missing or blank declared prose fails loudly with
  the `discovery/unavailable` outcome (DELTA-Dtf-001.CC7, TEN-003), never empty
  success. Returns `{field prose, source}` — a JSON object, never a bare string,
  so keys may be added later without a breaking conversion. `source` resolves as
  in the help envelope (DELTA-Dtf-002.CC2); the prose is never transformed."
  [ctx field]
  (let [runtime (:op/runtime ctx)
        {:keys [op verbs]} (:op/args ctx)]
    (when (seq verbs)
      (throw (ex-info (str "`" (name field) "` is op-level and takes no verb. "
                           "Run `strand help " op " " (str/join " " verbs) "` instead.")
                      {:code "discovery/help-grammar"
                       :operation op
                       :verbs (vec verbs)})))
    (let [entry (resolve-entry runtime op)
          prose (get entry field)]
      (when-not (and (string? prose) (not (str/blank? prose)))
        (throw (ex-info (str "Operation declares no " (name field) " prose")
                        {:code "discovery/unavailable"
                         :operation op
                         :field field})))
      {field prose
       :source (resolve-op-source runtime entry)})))

(defn op-about-handler
  "Return one op's declared `:about` prose beside its op-wide `source`."
  [ctx]
  (meta-verb-result ctx :about))

(defn op-prime-handler
  "Return one op's declared `:prime` prose beside its op-wide `source`."
  [ctx]
  (meta-verb-result ctx :prime))

(defn- meta-verb-arg-spec
  "Arg-spec for a builtin meta-verb: one required op name and a reserved trailing
  variadic that captures a verb path so the handler can redirect it (arity-1,
  DELTA-Dtf-003.CC4)."
  [op field]
  {:op op
   :doc (str "Show one op's declared " field " prose.")
   :positionals [{:name :op
                  :type :string
                  :required? true
                  :doc (format-alpha/reflow
                        (str "|Op name whose declared " field " prose to return."))}
                 {:name :verbs
                  :type :string
                  :required? false
                  :variadic? true
                  :doc (format-alpha/reflow
                        (str "|Reserved: " field " is op-level, so a trailing verb
                             |fails loudly and redirects to `help`."))}]
   :hook-class :read
   :deadline-class :standard})

(def ^:private about-arg-spec (meta-verb-arg-spec "about" "about"))
(def ^:private prime-arg-spec (meta-verb-arg-spec "prime" "prime"))

(defn- meta-verb-return-shape
  "Declared return shape for a meta-verb: `{<field> string, source json}`
  (DELTA-Dtf-001.CC7). `source` is `:json` so it accepts both `null` and a
  `{file, line}` map."
  [field]
  {:type :map
   :required {field :string
              :source :json}})

(defn register-built-in-ops!
  "Install Millstrand-provided CLI operations into the runtime op registry.

  Registers `help` and its two meta-verb siblings `about`/`prime`
  (DELTA-Dtf-002.CC6) through the public `register-op!` path, so all three are
  replaceable and maskable like any op, under the system owner partition.
  Resolves `register-op!` at call time: this namespace sits below `access` in the
  load graph, so a static require of the alpha module would close a cycle — the
  same constraint that made `millstrand.core.weaver.runtime` reach the previous alpha
  registrar dynamically. Built-in ops register under the system owner in the
  defaults layer, so a workspace replacement must state explicit override intent."
  [runtime]
  (let [register-op! (requiring-resolve 'millstrand.api.weaver.alpha/register-op!)]
    (register-op! runtime core-registry/system-owner 'help
                  {:doc (:doc help-arg-spec)
                   :arg-spec help-arg-spec
                   :returns help-return-shape}
                  'millstrand.core.weaver.help/op-help-handler)
    (register-op! runtime core-registry/system-owner 'about
                  {:doc (:doc about-arg-spec)
                   :arg-spec about-arg-spec
                   :returns (meta-verb-return-shape :about)}
                  'millstrand.core.weaver.help/op-about-handler)
    (register-op! runtime core-registry/system-owner 'prime
                  {:doc (:doc prime-arg-spec)
                   :arg-spec prime-arg-spec
                   :returns (meta-verb-return-shape :prime)}
                  'millstrand.core.weaver.help/op-prime-handler)))
