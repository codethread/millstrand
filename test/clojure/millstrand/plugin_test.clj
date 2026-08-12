(ns millstrand.plugin-test
  "Tests for weaver plugin/op registration and dispatch."
  (:require [clojure.test :refer [deftest is]]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha]
            [millstrand.spools.test-support :as test-support]))

(defn with-runtime [f]
  (test-support/with-runtime
    (fn [rt _config-dir]
      (f rt))))

(deftest old-plugin-and-bootstrap-surfaces-are-not-available
  (is (thrown? java.io.FileNotFoundException (require 'atom.plugin.alpha)))
  (is (thrown? java.io.FileNotFoundException (require 'atom.bootstrap.alpha)))
  (is (thrown? java.io.FileNotFoundException (require 'atom.prelude.alpha)))
  (is (nil? (ns-resolve 'millstrand.core.client 'load-plugin)))
  (is (nil? (ns-resolve 'millstrand.api.weaver.alpha 'load-plugin)))
  (is (nil? (ns-resolve 'millstrand.api.weaver.alpha 'plugins)))
  (is (nil? (ns-resolve 'millstrand.api.weaver.alpha 'plugin))))

(deftest runtime-loader-state-is-the-public-path
  (with-runtime
    (fn [rt]
      (is (= {:spools {} :families {}} (runtime/approved rt)))
      (is (= {} (:modules (runtime/status rt))))
      (is (= {} (:root/outcomes (runtime/status rt)))))))
