(ns millstrand.core.weaver.config
  "Resolve Millstrand weaver workspace directories from an explicit selected workspace."
  (:require [clojure.java.io :as io]))

(defn- canonical-path [file]
  (.getCanonicalPath (io/file file)))

(defn- world-map [config-dir state-dir data-dir]
  {:config-dir config-dir
   :state-dir state-dir
   :data-dir data-dir
   :config-file (str config-dir "/config.json")
   :db-path (str data-dir "/millstrand.sqlite")})

(defn- require-dir! [value label]
  (when-not (seq (str value))
    (throw (ex-info (str "No Millstrand " label " selected; pass explicit workspace, state, and data dirs")
                    {:code :millstrand.config/missing-world-dir
                     :dir label})))
  (canonical-path value))

(defn world
  "Return the config, state, and data paths for an explicit weaver workspace.

  `config-dir`, `state-dir`, and `data-dir` are independent selected-workspace
  inputs supplied by mill or by tests/helpers that intentionally construct
  disposable workspaces. Calling without all three dirs fails loudly so Clojure
  entry points cannot silently derive runtime state from the workspace."
  ([]
   (throw (ex-info "No Millstrand workspace selected; pass explicit workspace, state, and data dirs"
                   {:code :millstrand.config/no-selected-world})))
  ([config-dir]
   (let [dir (require-dir! config-dir "workspace")]
     (world-map dir (str dir "/state") (str dir "/data"))))
  ([config-dir state-dir data-dir]
   (world-map (require-dir! config-dir "workspace")
              (require-dir! state-dir "state-dir")
              (require-dir! data-dir "data-dir"))))
