(ns quality.spool-tiers
  "The spool-tier slice of quality.conventions-check: shipped spool
  sources build on `skein.api.*.alpha`, never `skein.core.*`, and the
  designed exception is nominal — the unsafe-namespace convention of
  'Unsafe spools' in docs/spools/writing-shared-spools.md.

  A namespace is unsafe iff some segment of its name is `unsafe` or
  `unsafe-*`. Four rules, all held at zero findings over `spools/*/src`:
  `skein.core.*` is used only from unsafe-named namespaces; an
  unsafe-named namespace that touches no `skein.core.*` is stale and
  loses the name; a safe namespace never requires another spool's
  unsafe namespace (no cross-repo lockstep, so the breakage contract
  cannot be encapsulated away); and the ns docstring leads with
  `UNSAFE:` exactly when the name is unsafe. Local workspace spools
  under `.skein/spools/` are trusted config accepting the compatibility
  cost (SPEC-004.C40) and are out of scope, as are the sibling fences
  owned elsewhere: `skein.api.*.internal` by `quality.api-form`. Kept
  clj-kondo-free. The findings logic loads on the test classpath; the
  caller supplies kondo's analysis data."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private spools-root "spools")

(defn spool-dirs
  "Return the set of spool names under `spools-root` that ship source
  (a `src` directory)."
  []
  (set (for [^java.io.File dir (.listFiles (io/file spools-root))
             :when (and (.isDirectory dir) (.isDirectory (io/file dir "src")))]
         (.getName dir))))

(defn- file-spool
  "Return the spool name owning `filename` when it sits under a
  `spools/<name>/src/` root, nil otherwise."
  [filename]
  (second (re-find #"(?:^|/)spools/([^/]+)/src/" (str filename))))

(defn- core-ns?
  "True for `skein.core` and every namespace beneath it."
  [ns-sym]
  (boolean (re-matches #"skein\.core(?:\..+)?" (str ns-sym))))

(defn unsafe-ns?
  "True when some segment of `ns-sym` is `unsafe` or `unsafe-*` — the
  nominal marker of the unsafe-spool convention."
  [ns-sym]
  (boolean (some #(re-matches #"unsafe(?:-.+)?" %)
                 (str/split (str ns-sym) #"\."))))

(defn- spool-ns-defs
  "Return the namespace-definition rows defined under a spool in
  `spools`, each with the owning spool assoc'd as `:spool`."
  [analysis spools]
  (for [{:keys [filename] :as row} (:namespace-definitions analysis)
        :let [spool (file-spool filename)]
        :when (contains? spools spool)]
    (assoc row :spool spool)))

(defn- spool-usages
  "Return the usage rows (`:namespace-usages` and `:var-usages`) whose
  file sits under a spool in `spools`, each with the owning spool
  assoc'd as `:spool`."
  [analysis spools]
  (for [{:keys [filename] :as row} (concat (:namespace-usages analysis)
                                           (:var-usages analysis))
        :let [spool (file-spool filename)]
        :when (contains? spools spool)]
    (assoc row :spool spool)))

(defn findings
  "Enforce the unsafe-namespace convention over kondo analysis, scoped
  to sources under the spools named in `spools`. See the ns docstring
  for the four rules; ownership of a used namespace is resolved from
  the analysis' own namespace definitions, so the same kondo run must
  scan every shipped spool root."
  [analysis spools]
  (let [ns-defs (spool-ns-defs analysis spools)
        usages (spool-usages analysis spools)
        owner (into {} (map (juxt :name :spool)) ns-defs)
        core-using (into #{} (comp (filter #(core-ns? (:to %))) (map :from))
                         usages)]
    (concat
     (for [{:keys [from to filename row]} usages
           :when (and (core-ns? to) (not (unsafe-ns? from)))]
       (str filename ":" row ": `" from "` uses the internal namespace `" to
            "`; spools build on `skein.api.*.alpha` (SPEC-005.C5), or carry"
            " an unsafe-named namespace per docs/spools/writing-shared-spools.md"))
     (for [{:keys [from to filename row spool]} usages
           :when (and (unsafe-ns? to)
                      (not (unsafe-ns? from))
                      (not= spool (owner to)))]
       (str filename ":" row ": `" from "` requires the unsafe namespace `" to
            "` from another spool; the breakage contract does not encapsulate"
            " across spools (docs/spools/writing-shared-spools.md)"))
     (for [{:keys [name filename row]} ns-defs
           :when (and (unsafe-ns? name) (not (core-using name)))]
       (str filename ":" row ": `" name "` is unsafe-named but uses no"
            " `skein.core.*`; drop the unsafe marker"))
     (for [{:keys [name doc filename row]} ns-defs
           :let [marked? (str/starts-with? (str doc) "UNSAFE:")]
           :when (not= marked? (unsafe-ns? name))]
       (if marked?
         (str filename ":" row ": `" name "` docstring leads with `UNSAFE:`"
              " but the name carries no unsafe segment; the marker is the name")
         (str filename ":" row ": unsafe-named `" name "` docstring must lead"
              " with `UNSAFE:` naming the internal namespaces it requires"))))))

(defn check
  "Run `findings` over the live tree's shipped spools."
  [analysis]
  (findings analysis (spool-dirs)))
