(ns me.workflows.support
  "Small helpers for repository-owned workflow definitions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(defn script
  "Return the frozen source of a named workspace script."
  [name]
  (let [resource-name (str "workflows/scripts/" name)
        resource (io/resource resource-name)]
    (when-not resource
      (throw (ex-info "Workspace workflow script is missing"
                      {:resource resource-name})))
    (slurp resource)))
