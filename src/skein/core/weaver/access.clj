(ns skein.core.weaver.access
  "Shared low-level plumbing over a weaver runtime map.

  Datasource and registry accessors, JSON-row normalization, spool config-dir
  path resolution, the spool classloader boundary, and fully-qualified-symbol
  validation. Internal tier: the API and REPL layers reach through these instead
  of destructuring the runtime map's physical shape (TEN-007)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.core.db :as db]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.runtime :as weaver-runtime]))

(defn- normalize-row
  "Decode JSON-backed row fields returned by persistence."
  [row]
  (cond-> row
    (string? (:attributes row)) (update :attributes db/<-json)))

(defn normalize
  "Recursively decode persistence-shaped rows into Clojure data."
  [result]
  (cond
    (map? result) (into {} (map (fn [[k v]] [k (normalize v)])) (normalize-row result))
    (sequential? result) (mapv normalize result)
    :else result))

(defn ds
  "Return the runtime's JDBC datasource."
  [runtime]
  (:datasource runtime))

(defn query-registry
  "Return one immutable effective named-query snapshot."
  [runtime]
  (core-registry/effective (:query-store runtime)))

(defn query-store
  "Return the runtime's named-query owner-partition store."
  [runtime]
  (:query-store runtime))

(defn pattern-registry
  "Return one immutable effective weave-pattern snapshot."
  [runtime]
  (core-registry/effective (:pattern-store runtime)))

(defn pattern-store
  "Return the runtime's weave-pattern owner-partition store."
  [runtime]
  (:pattern-store runtime))

(defn op-registry
  "Return one immutable effective CLI-op snapshot."
  [runtime]
  (core-registry/effective (:op-store runtime)))

(defn op-store
  "Return the runtime's CLI-op owner-partition store."
  [runtime]
  (:op-store runtime))

(defn glossary-registry
  "Return the runtime's reload-cleared glossary-outcome registry atom."
  [runtime]
  (:glossary-registry runtime))

(defn help-transform-slot
  "Return the runtime's reload-cleared default-help-transform slot atom."
  [runtime]
  (:help-transform-slot runtime))

(defn hook-registry
  "Return one immutable effective lifecycle-hook snapshot."
  [runtime]
  (core-registry/effective (:hook-store runtime)))

(defn hook-store
  "Return the runtime's lifecycle-hook owner-partition store."
  [runtime]
  (:hook-store runtime))

(defn bin-registry
  "Return one immutable effective executable-declaration snapshot."
  [runtime]
  (core-registry/effective (:bin-store runtime)))

(defn bin-store
  "Return the executable-declaration owner-partition store."
  [runtime]
  (:bin-store runtime))

(defn approved-spool-sync-state
  "Return the runtime's approved-spool sync-state atom."
  [runtime]
  (:approved-spool-sync-state runtime))

(defn namespace-load-ledger
  "Return the runtime's append-only namespace-load ledger atom."
  [runtime]
  (:namespace-load-ledger runtime))

(defn event-system
  "Return the runtime's event system."
  [runtime]
  (:event-system runtime))

(defn handler-store
  "Return the runtime event system's event-handler owner-partition store."
  [runtime]
  (:handler-store (event-system runtime)))

(defn with-spool-classloader
  "Run f with the runtime bound and its spool classloader installed."
  [runtime f]
  (weaver-runtime/with-runtime-and-spool-classloader runtime f))

(defn config-dir
  "Return the runtime's selected config-dir path."
  [runtime]
  (get-in runtime [:metadata :config-dir]))

(defn spools-file
  "Return a named file beneath the runtime's selected config directory."
  ^java.io.File [runtime name]
  (io/file (config-dir runtime) name))

(defn release-marker
  "Return the runtime's resolved release marker and provenance."
  [runtime]
  (:release-marker runtime))

(defn expand-user-home
  "Expand a leading `~` or `~/` to the current user's home directory."
  [path]
  (cond
    (= "~" path) (System/getProperty "user.home")
    (str/starts-with? path "~/") (str (System/getProperty "user.home") (subs path 1))
    :else path))

(defn canonical-root
  "Resolve path against the runtime config-dir and return its canonical path."
  [runtime path]
  (let [expanded-path (expand-user-home path)
        file (io/file expanded-path)
        resolved (if (.isAbsolute file)
                   file
                   (io/file (config-dir runtime) expanded-path))]
    (.getCanonicalPath resolved)))

(defn source-checkout-root
  "Return the running weaver's mill-resolved skein source checkout root File.

  The sole resolution authority for `:skein/source-root` coordinates
  (SPEC-004.C50b): it delegates to the runtime module's resource-derived
  locator, never reading cwd, the config dir, or request/envelope state. Fails
  loudly (TEN-003) when the checkout is unavailable or its classpath resource
  does not identify a readable file checkout, so a source-root coordinate can
  never resolve against a missing or unreadable checkout."
  ^java.io.File []
  (let [^java.io.File checkout (weaver-runtime/source-checkout-root)]
    (when-not (and checkout (.isDirectory checkout) (.canRead checkout))
      (throw (ex-info "Skein source checkout is unavailable for source-root resolution"
                      {:reason :source-checkout-unavailable
                       :checkout (some-> checkout .getPath)})))
    checkout))

(defn cache-base
  "Return Skein's cache base for git-backed spool materialization."
  []
  (io/file (let [xdg-cache-home (System/getenv "XDG_CACHE_HOME")]
             (if (and (string? xdg-cache-home) (not (str/blank? xdg-cache-home)))
               xdg-cache-home
               (str (System/getProperty "user.home") java.io.File/separator ".cache")))))

(defn validate-fn-symbol!
  "Require fn-sym to be a fully qualified symbol, returning it or failing loudly."
  [label fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info (str label " function must be a fully qualified symbol") {:fn fn-sym})))
  fn-sym)
