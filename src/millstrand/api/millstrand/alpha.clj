(ns millstrand.api.millstrand.alpha
  "Authoring forms for Millstrand's owner-complete core kinds.

  Each form defines an ordinary Clojure Var and collects one validated declaration
  while a runtime module source is evaluated. The retained declaration record is
  replayed for image modules, so source and image activation publish the same
  owner-complete partitions."
  (:require [millstrand.api.runtime.alpha :as runtime]
            [millstrand.core.contribution :as contribution]))

(defmacro defop
  "Define an operation handler and collect its validated `:ops` declaration.

  Options require `:arg-spec`; `:override? true` records explicit override intent
  without entering the registry value."
  [form-name doc opts argv & body]
  (let [handler-name (symbol (str form-name "-op"))
        fn-sym (symbol (str (ns-name *ns*)) (str handler-name))]
    `(do
       (defn ~handler-name ~doc ~argv ~@body)
       (runtime/collect-entry! :ops ~(str form-name)
                               (contribution/op-declaration
                                '~form-name ~doc ~opts '~fn-sym)
                               (select-keys ~opts #{:override?}))
       (var ~handler-name))))

(defmacro defquery
  "Define a named query and collect its validated `:queries` declaration.

  Options accept `:usage`; `:override? true` records explicit override intent."
  [form-name doc opts definition]
  `(do
     (def ~form-name ~doc
       (contribution/query-declaration '~form-name ~opts ~definition))
     (runtime/collect-entry! :queries
                             ~(str form-name)
                             ~form-name
                             (select-keys ~opts #{:override?}))
     (var ~form-name)))

(defmacro defpattern
  "Define a weave handler and collect its validated `:patterns` declaration.

  Options require a named input `:spec`; `:override? true` records explicit
  override intent."
  [form-name doc opts argv & body]
  (let [fn-sym (symbol (str (ns-name *ns*)) (str form-name))]
    `(do
       (defn ~form-name ~doc ~argv ~@body)
       (runtime/collect-entry! :patterns ~(str form-name)
                               (contribution/pattern-declaration
                                '~form-name ~doc ~opts '~fn-sym)
                               (select-keys ~opts #{:override?}))
       (var ~form-name))))

(defmacro defhook
  "Define a lifecycle hook and collect its validated `:hooks` declaration.

  Options require non-empty event `:types`; `:override? true` records explicit
  override intent."
  [form-name doc opts argv & body]
  (let [fn-sym (symbol (str (ns-name *ns*)) (str form-name))]
    `(do
       (defn ~form-name ~doc ~argv ~@body)
       (runtime/collect-entry! :hooks ~(keyword form-name)
                               (contribution/hook-declaration
                                ~(keyword form-name) ~opts '~fn-sym)
                               (select-keys ~opts #{:override?}))
       (var ~form-name))))

(defmacro defhandler
  "Define an event handler and collect its validated `:events` declaration.

  Options require non-empty event `:types`; `:override? true` records explicit
  override intent."
  [form-name doc opts argv & body]
  (let [fn-sym (symbol (str (ns-name *ns*)) (str form-name))]
    `(do
       (defn ~form-name ~doc ~argv ~@body)
       (runtime/collect-entry! :events ~(keyword form-name)
                               (contribution/handler-declaration
                                ~(keyword form-name) ~opts '~fn-sym)
                               (select-keys ~opts #{:override?}))
       (var ~form-name))))

(defmacro defbin
  "Define an executable declaration and collect its validated `:bins` entry.

  `:executable` names a command, a declaring-file-relative path, or a closed
  `[:family path]`/`[:root path]` anchor. An optional `:build` is an argv vector;
  `:override? true` records explicit override intent."
  [form-name doc opts]
  `(do
     (def ~form-name ~doc
       (contribution/bin-declaration '~form-name ~doc ~opts '~(ns-name *ns*)))
     (runtime/collect-entry! :bins ~(str form-name)
                             ~form-name
                             (select-keys ~opts #{:override?}))
     (var ~form-name)))
