(ns millstrand.core.weaver.bins-test
  "Tests for bin declaration, planning, and executable publication."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.weaver.bins :as bins]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.core-registry :as core-registry]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.core.db-test :as db-test]
            [millstrand.spools.test-support :as test-support]))

(def delete-tree! test-support/delete-tree!)

(defn temp-world []
  (let [root (java.io.File/createTempFile "tdx" "")]
    (.delete root)
    (.mkdirs root)
    (let [workspace (io/file root "config")
          state-dir (io/file root "state")
          data-dir (io/file root "data")]
      (.mkdirs workspace)
      (weaver-config/world (.getCanonicalPath workspace)
                           (.getCanonicalPath state-dir)
                           (.getCanonicalPath data-dir)))))

(defn with-runtime
  ([f] (with-runtime nil f))
  ([start-options f]
   (let [db-file (db-test/temp-db-file)
         world (or (:world start-options) (temp-world))
         rt (weaver-runtime/start! db-file (assoc (or start-options {}) :world world :publish? false))]
     (try
       (weaver-runtime/with-runtime-binding rt #(f rt db-file))
       (finally
         (weaver-runtime/stop! rt)
         (db-test/delete-sqlite-family! db-file)
         (delete-tree! (io/file (:config-dir world))))))))

(deftest built-in-bins-op-lists-an-empty-registry
  (with-runtime
    (fn [rt _]
      (is (= {:operation "bins list" :bins []}
             (weaver/op! rt 'bins ["list"]))))))

(deftest bins-list-does-not-plan-unanchorable-declarations
  (with-runtime
    (fn [rt _]
      (core-registry/replace-owner!
       (:bin-store rt) :workspace
       {:layer :workspace
        :entries {"unanchored"
                  {:name "unanchored"
                   :doc "An anchor resolved only by planning."
                   :executable [:family "bin/unanchored"]
                   :provenance 'demo/bins
                   :source/file "/tmp/no-approved-root/module.clj"
                   :build ["make" "unanchored"]}}
        :overrides #{}})
      (let [listed (weaver/op! rt 'bins ["list"])
            planned (try
                      (weaver/op! rt 'bins ["plan" "unanchored"])
                      nil
                      (catch clojure.lang.ExceptionInfo throwable
                        throwable))]
        (is (= "[:family \"bin/unanchored\"]"
               (get-in listed [:bins 0 :executable])))
        (is (= :bin/anchor-unresolved (-> planned ex-data :reason)))
        (is (s/valid? :millstrand.core.weaver.bins/list-result listed))
        (is (instance? clojure.lang.ExceptionInfo planned))))))

(deftest bins-plan-rejects-invalid-selectors
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"bins plan bin selector is invalid"
                            (bins/plan rt 42)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"bins plan bin selector is invalid"
                            (bins/plan rt nil))))))

(defn- write-local-spool-module!
  ([workspace root-lib ns-sym body]
   (write-local-spool-module! workspace root-lib ns-sym [] body))
  ([workspace root-lib ns-sym required-namespaces body]
   (let [relative-root "spools/module-root"
         root (io/file workspace relative-root)
         relative-source (-> (str ns-sym)
                             (str/replace "." "/")
                             (str/replace "-" "_"))
         source (io/file root "src" (str relative-source ".clj"))]
     (io/make-parents source)
     (spit (io/file workspace "spools.edn")
           (pr-str {:spools {root-lib {:local/root relative-root}}}))
     (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
     (spit source
           (str "(ns " ns-sym
                "\n  (:require [millstrand.core.weaver.runtime :as runtime]"
                "\n            [millstrand.api.millstrand.alpha :as millstrand]"
                (str/join "" (map #(str "\n            [" % "]") required-namespaces))
                "))\n" body "\n"))
     source)))

(defn- write-multi-root-spool-module!
  "Write one module beneath a local family with independently mapped roots."
  [workspace family roots root-lib ns-sym body]
  (let [family-relative (str "spools/" (name family))
        family-root (io/file workspace family-relative)
        root-path (get roots root-lib)
        root (io/file family-root root-path)
        relative-source (-> (str ns-sym)
                            (str/replace "." "/")
                            (str/replace "-" "_"))
        source (io/file root "src" (str relative-source ".clj"))]
    (doseq [relative-root (vals roots)]
      (let [root-dir (io/file family-root relative-root)]
        (.mkdirs root-dir)
        (.mkdirs (io/file root-dir "src"))
        (spit (io/file root-dir "deps.edn") "{:paths [\"src\"]}\n")))
    (spit (io/file workspace "spools.edn")
          (pr-str {:spools {family {:local/root family-relative
                                    :roots roots}}}))
    (io/make-parents source)
    (spit source
          (str "(ns " ns-sym
               "\n  (:require [millstrand.api.millstrand.alpha :as millstrand]))\n"
               body "\n"))
    source))

(defn- bin-form
  "Return one source-level `defbin` form for the acceptance fixtures."
  [name executable & {:keys [build override?]}]
  (str "(require '[millstrand.api.millstrand.alpha :as millstrand])\n"
       "(millstrand/defbin! " name " \"" name "\" "
       (pr-str (cond-> {:executable executable}
                 build (assoc :build build)
                 override? (assoc :override? true))) ")\n"))

(defn- write-executable!
  "Write a deterministic fixture file and set its executable bit."
  [file executable?]
  (io/make-parents file)
  (spit file "#!/bin/sh\n")
  (.setExecutable ^java.io.File file executable? false)
  file)

(deftest defbin-production-path-publishes-removes-and-restores
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/bin-root
            first-ns (symbol (str "test.module.bin-first-" suffix))
            _ (write-local-spool-module!
               workspace root-lib first-ns
               (str (bin-form "first-bin" "first-command" :build ["make" "first-bin"])
                    (bin-form "second-bin" "second-command")))]
        (let [result (runtime/module! rt :first
                                      {:ns first-ns :spools [root-lib]})]
          (is (= :applied (:status result)) (pr-str result)))
        (is (= #{{:name "first-bin" :executable "first-command"}
                 {:name "second-bin" :executable "second-command"}}
               (set (map #(select-keys % [:name :executable])
                         (:bins (weaver/op! rt 'bins ["list"]))))))
        (let [entry (assoc (get (core-registry/effective (:bin-store rt)) "first-bin")
                           :executable "replacement-command")]
          (core-registry/replace-owner!
           (:bin-store rt) :second
           {:layer :direct :entries {"first-bin" entry} :overrides #{"first-bin"}}))
        (is (= "replacement-command"
               (get-in (weaver/op! rt 'bins ["plan" "first-bin"])
                       [:exec :command])))
        (core-registry/remove-owner! (:bin-store rt) :second)
        (is (= "first-command"
               (get-in (weaver/op! rt 'bins ["plan" "first-bin"])
                       [:exec :command])))
        (is (= "first-bin"
               (->> (weaver/op! rt 'bins ["list"]) :bins
                    (some #(when (= "first-bin" (:name %)) %))
                    :name))
            "omission removes the overriding owner while restoring the base")))))

(deftest defbin-image-replay-preserves-partition-and-empty-records
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/bin-image-root
            forms-ns (symbol (str "test.module.bin-image-" suffix))
            empty-ns (symbol (str "test.module.bin-image-empty-" suffix))
            no-record-ns (symbol (str "test.module.bin-image-no-record-" suffix))]
        (write-local-spool-module!
         workspace root-lib forms-ns
         (bin-form "image-bin" "image-command"))
        (is (= :applied (:status (runtime/module! rt :image
                                                  {:ns forms-ns :spools [root-lib]}))))
        (let [source-view (core-registry/effective (:bin-store rt))]
          (is (= :unchanged (:status (runtime/module! rt :image
                                                      {:ns forms-ns :load :image}))))
          (is (= source-view (core-registry/effective (:bin-store rt)))
              "image activation replays the same bin partition"))

        (write-local-spool-module! workspace root-lib empty-ns "")
        (is (= :applied (:status (runtime/module! rt :empty
                                                  {:ns empty-ns :spools [root-lib]}))))
        (is (= :unchanged (:status (runtime/module! rt :empty
                                                    {:ns empty-ns :load :image}))))

        (create-ns no-record-ns)
        (let [image (runtime/module! rt :no-record
                                     {:ns no-record-ns :load :image})]
          (is (= :missing-declaration-record
                 (get-in image [:modules :no-record :error :data :reason]))))))))

(deftest bins-plan-covers-string-spellings-anchors-and-readiness
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            family 'test/bin-multi-family
            selected-lib 'test/bin-selected
            other-lib 'test/bin-other
            roots {selected-lib "selected" other-lib "other"}
            family-root (io/file workspace "spools/bin-multi-family")
            selected-root (io/file family-root "selected")
            source-dir (io/file selected-root "src" "test" "module")
            absolute (write-executable! (io/file workspace "absolute-bin") true)
            _ (write-executable! (io/file family-root "family-bin") true)
            _ (write-executable! (io/file selected-root "root-bin") true)
            _ (write-executable! (io/file source-dir "runnable") true)
            _ (write-executable! (io/file source-dir "non-executable") false)
            ns-sym (symbol (str "test.module.bin-matrix-" suffix))
            body (str
                  (bin-form "bare-bin" "bare-command" :build ["build-bare"])
                  (bin-form "dot-bin" "./dot-bin" :build ["build-dot"])
                  (bin-form "leaving-bin" "../../../../outside-bin"
                            :build ["build-leaving"])
                  (bin-form "home-bin" "~" :build ["build-home"])
                  (bin-form "absolute-bin" (.getPath absolute)
                            :build ["build-absolute"])
                  (bin-form "family-bin" [:family "family-bin"]
                            :build ["build-family"])
                  (bin-form "root-bin" [:root "root-bin"]
                            :build ["build-root"])
                  (bin-form "absent-bin" "./absent" :build ["build-absent"])
                  (bin-form "directory-bin" "./." :build ["build-directory"])
                  (bin-form "non-executable-bin" "./non-executable"
                            :build ["build-non-executable"])
                  (bin-form "runnable-bin" "./runnable" :build ["build-runnable"]))]
        (write-multi-root-spool-module! workspace family roots selected-lib ns-sym body)
        (is (= :applied (:status (runtime/module! rt :matrix
                                                  {:ns ns-sym :spools [selected-lib]}))))
        (let [plans (into {}
                          (map (fn [name]
                                 [name (weaver/op! rt 'bins ["plan" name])]))
                          ["bare-bin" "dot-bin" "leaving-bin" "home-bin"
                           "absolute-bin" "family-bin" "root-bin" "absent-bin"
                           "directory-bin" "non-executable-bin" "runnable-bin"])
              canonical #(.getCanonicalPath (io/file %))]
          (is (nil? (get-in plans ["bare-bin" :runnable])))
          (is (= "bare-command" (get-in plans ["bare-bin" :exec :command])))
          (is (= (canonical source-dir) (get-in plans ["bare-bin" :build :cwd])))
          (is (= (canonical source-dir) (get-in plans ["dot-bin" :build :cwd])))
          (is (= (canonical source-dir) (get-in plans ["leaving-bin" :build :cwd])))
          (is (= (canonical source-dir) (get-in plans ["home-bin" :build :cwd])))
          (is (= (canonical source-dir) (get-in plans ["absolute-bin" :build :cwd])))
          (is (= (canonical family-root) (get-in plans ["family-bin" :build :cwd])))
          (is (= (canonical selected-root) (get-in plans ["root-bin" :build :cwd])))
          (is (= (canonical (io/file family-root "family-bin"))
                 (get-in plans ["family-bin" :exec :path])))
          (is (= (canonical (io/file selected-root "root-bin"))
                 (get-in plans ["root-bin" :exec :path])))
          (is (false? (get-in plans ["absent-bin" :runnable])))
          (is (false? (get-in plans ["directory-bin" :runnable])))
          (is (false? (get-in plans ["non-executable-bin" :runnable])))
          (is (true? (get-in plans ["runnable-bin" :runnable])))
          (is (every? #(s/valid? :millstrand.core.weaver.bins/plan-result %)
                      (vals plans)))
          (is (s/valid? :millstrand.core.weaver.bins/list-result
                        (weaver/op! rt 'bins ["list"])))
          (is (= "[:family \"family-bin\"]"
                 (->> (weaver/op! rt 'bins ["list"]) :bins
                      (some #(when (= "family-bin" (:name %)) %))
                      :executable))))))))
