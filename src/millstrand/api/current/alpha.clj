(ns millstrand.api.current.alpha
  "Isolated convenience facade for reading and scoping the current Millstrand runtime.

  This namespace is the blessed public facade for trusted in-process config,
  spool, and REPL code that must capture the active weaver runtime explicitly and
  then pass it to `millstrand.api.*.alpha` functions. It never falls back to client or
  connected REPL state.

  The public interfaces are identified by the `s/fdef`s and the `::runtime`
  spec at the foot of this file: a runtime is an opaque non-nil handle — hold
  it and pass it on, never reach inside it."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.core.weaver.runtime :as weaver-runtime]))

(defn runtime-or-nil
  "Return the thread-bound or published in-process weaver runtime, or nil.

  The non-throwing sibling of `runtime`, and the only sanctioned way to *probe*
  for an ambient runtime without fabricating one. Trusted resolvers that want to
  fall back to another source (a caller-held binding, a connected client, a loud
  error with their own message) branch on this nil instead of catching
  `runtime`'s exception as control flow. This is the single ambient-runtime read
  point: callers must not reach into `millstrand.core.weaver.runtime` internals."
  []
  (or weaver-runtime/*runtime*
      @weaver-runtime/current-runtime))

(defn runtime
  "Return the thread-bound or published in-process weaver runtime.

  Trusted startup, reload, and nREPL contexts bind a per-thread runtime. Daemon
  processes also publish one ambient runtime for legacy REPL ergonomics. When
  neither exists, fail loudly. Use `runtime-or-nil` when a missing runtime is a
  branch rather than an error."
  []
  (or (runtime-or-nil)
      (throw (ex-info (format-alpha/reflow
                       "|No active Millstrand weaver runtime; scope one with
                        |(with-runtime rt ...) or with-runtime*, or run
                        |inside a started or published weaver.")
                      {:millstrand/runtime :absent}))))

(defn with-runtime*
  "Call `thunk` with `runtime` bound as the thread-local ambient runtime.

  Trusted in-process code uses this to scope a chosen runtime for a dynamic
  extent so nested `(runtime)` reads and explicit-runtime callees agree on the
  same runtime without threading it through every call."
  [runtime thunk]
  (when (nil? runtime)
    (throw (ex-info "Cannot scope a nil Millstrand runtime" {:millstrand/runtime :nil})))
  (weaver-runtime/with-runtime-binding runtime thunk))

(defmacro with-runtime
  "Evaluate `body` with `runtime` bound as the thread-local ambient runtime."
  [runtime & body]
  `(with-runtime* ~runtime (fn [] ~@body)))

;; --- Interface specs (SPEC-003.C19a) ---
;;
;; The runtime handle is deliberately opaque: non-nil is the whole public
;; shape promise, and `with-runtime*`'s nil guard is its runtime-checked
;; twin. Structure beyond that belongs to `millstrand.core.weaver.runtime`.

(s/def ::runtime some?)

(s/fdef runtime-or-nil
  :args (s/cat)
  :ret (s/nilable ::runtime))

(s/fdef runtime
  :args (s/cat)
  :ret ::runtime)

(s/fdef with-runtime*
  :args (s/cat :runtime ::runtime :thunk ifn?))
