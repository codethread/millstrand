(ns millstrand.spools.batteries.internal.primer
  "Runtime-scoped primer settings and the shipped orientation text."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [millstrand.api.format.alpha :as format]
            [millstrand.api.runtime.alpha :as runtime]))

(s/def ::append string?)
(s/def ::replace string?)
(s/def ::options
  (s/and (s/keys :opt-un [::append ::replace])
         #(every? #{:append :replace} (keys %))
         #(not (and (contains? % :append) (contains? % :replace)))))

(def ^:private state-version 1)

(defn new-state
  "Return fresh primer state; its key set is versioned across refreshes."
  []
  {:options (atom {})})

(defn state
  "Return the selected runtime's primer state."
  [rt]
  (runtime/spool-state rt ::state {:version state-version} new-state))

(defn render-default
  "Render orientation with validated source and canonical reference paths."
  [source]
  (let [reference (io/file source "docs" "reference.md")]
    (when-not (.isFile reference)
      (throw (ex-info "Millstrand canonical reference is not an available regular file"
                      {:code "batteries/primer-reference-unavailable"
                       :reference (.getPath reference)})))
    (format/prose
     "
       # Millstrand / strand

       Millstrand is a local runtime for coordinating work through strands.
       `mill` manages workspaces and Weavers; `strand` invokes the commands
       registered by the selected workspace.

       Start with `strand --help`, then `strand help` to discover its commands.
       Use `strand help <op>` for flags and arguments, `strand prime <op>` for
       working discipline, and `strand about <op>` for the detailed manual.

       When building workspace config, spools, or REPL workflows, read the
       canonical reference below. Its paths belong to the running Weaver's
       Millstrand source, not the caller's working directory.

       Millstrand source: {source}
       Millstrand reference: {reference}
       "
     {:source source :reference (.getCanonicalPath reference)})))
