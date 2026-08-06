(ns regen-surface-baseline
  "One-shot regenerator for test/millstrand/surface_baseline.edn.

  Runs the (private) config-test capture against the live .skein/policy/config.clj and
  pretty-prints the resulting surface to the frozen baseline. Kept out of the
  test suite: the golden is a committed snapshot the suite compares against, not
  something it rewrites. Run via `clojure -X:test regen-surface-baseline/run`
  so runtime add-libs sees the parent deps basis exactly as shard C does (`-X`
  also bypasses the :test alias's test-runner :main-opts)."
  (:require [clojure.pprint :as pprint]
            [millstrand.ct.config-test]))

(defn run [_]
  (let [capture @(requiring-resolve 'millstrand.ct.config-test/capture-config-surface)
        surface (capture ".skein/policy/config.clj")]
    (spit "test/millstrand/surface_baseline.edn"
          (with-out-str (pprint/pprint surface)))
    (println "wrote test/millstrand/surface_baseline.edn")))
