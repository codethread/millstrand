(ns millstrand.core.weaver.prime-advice-test
  "Tests for ordered module-owned prime advice."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.weaver.module-publication :as publication]
            [millstrand.test.alpha :as t])
  (:import [java.util.concurrent.locks ReentrantLock]))

(defn bare-op
  "Return a stable value for direct-registration failure fixtures."
  [_ctx]
  :bare)

(def ^:private flat-read-spec
  {:arg-spec {:op "bare"
              :doc "Bare op."
              :hook-class :read
              :deadline-class :standard}})

(defn- module-source [namespace body]
  (str "(ns " namespace "\n"
       "  (:require [millstrand.api.millstrand.alpha :as millstrand]))\n"
       body "\n"))

(defn- await-queued-reader [^ReentrantLock lock ^Thread reader]
  (loop [attempts 100000]
    (cond
      (.hasQueuedThread lock reader) true
      (not (.isAlive reader))
      (throw (ex-info "Prime reader terminated before queueing on the refresh lock"
                      {:thread/state (.getState reader)}))
      (zero? attempts)
      (throw (ex-info "Prime reader did not queue on the refresh lock"
                      {:thread/state (.getState reader)}))
      :else
      (do
        (Thread/yield)
        (recur (dec attempts))))))

(def ^:private base-source
  (module-source
   'millstrand.prime-advice-fixture.base
   (str "(millstrand/defop! guided \"Guided op.\"\n"
        "  {:arg-spec {:op \"guided\" :doc \"Guided op.\"\n"
        "              :hook-class :read :deadline-class :standard}\n"
        "   :prime \"Base discipline.\"}\n"
        "  [_] :guided)")))

(def ^:private wrapper-b-source
  (module-source
   'millstrand.prime-advice-fixture.wrapper-b
   (str "(millstrand/defprime-advice guided \"Wrapper B first.\")\n"
        "(millstrand/defprime-advice guided \"Wrapper B second.\")")))

(def ^:private wrapper-a-source
  (module-source
   'millstrand.prime-advice-fixture.wrapper-a
   "(millstrand/defprime-advice guided \"Wrapper A.\")"))

(defn- init-source [include-wrapper-b?]
  (str "(require '[millstrand.api.current.alpha :as current]\n"
       "         '[millstrand.api.runtime.alpha :as runtime])\n"
       "(runtime/module! (current/runtime) :base {:file \"modules/base.clj\"})\n"
       (when include-wrapper-b?
         (str "(runtime/module! (current/runtime) :wrapper-b\n"
              "  {:file \"modules/wrapper-b.clj\" :after [:base]})\n"))
       "(runtime/module! (current/runtime) :wrapper-a\n"
       "  {:file \"modules/wrapper-a.clj\" :after ["
       (if include-wrapper-b? ":wrapper-b" ":base")
       "]})\n"))

(deftest prime-composes-active-advice-in-module-and-source-order
  (t/with-weaver-world
    [ctx {:storage :sqlite-memory
          :files {"modules/base.clj" base-source
                  "modules/wrapper-b.clj" wrapper-b-source
                  "modules/wrapper-a.clj" wrapper-a-source}}]
    (t/declare-module! ctx :base {:file "modules/base.clj"})
    (t/declare-module! ctx :wrapper-b
                       {:file "modules/wrapper-b.clj" :after [:base]})
    (t/declare-module! ctx :wrapper-a
                       {:file "modules/wrapper-a.clj" :after [:wrapper-b]})
    (let [prime (weaver/op! (:runtime ctx) 'prime ["guided"])]
      (is (= (str "Base discipline.\n\n"
                  "Wrapper B first.\n\n"
                  "Wrapper B second.\n\n"
                  "Wrapper A.")
             (:prime prime)))
      (is (= [{:text "Wrapper B first."
               :provenance {:module "wrapper-b"
                            :namespace "millstrand.prime-advice-fixture.wrapper-b"}
               :order {:module 1 :declaration 0}}
              {:text "Wrapper B second."
               :provenance {:module "wrapper-b"
                            :namespace "millstrand.prime-advice-fixture.wrapper-b"}
               :order {:module 1 :declaration 1}}
              {:text "Wrapper A."
               :provenance {:module "wrapper-a"
                            :namespace "millstrand.prime-advice-fixture.wrapper-a"}
               :order {:module 2 :declaration 0}}]
             (mapv #(dissoc % :source) (:appendices prime))))
      (is (every? #(and (str/ends-with? (get-in % [:source :file]) ".clj")
                        (pos-int? (get-in % [:source :line])))
                  (:appendices prime)))
      (t/check-op-return! (:runtime ctx) 'prime prime))))

(deftest owner-complete-refresh-removes-only-that-modules-advice
  (t/with-weaver-world
    [ctx {:storage :sqlite-memory
          :init-clj (init-source true)
          :files {"modules/base.clj" base-source
                  "modules/wrapper-b.clj" wrapper-b-source
                  "modules/wrapper-a.clj" wrapper-a-source}}]
    (spit (io/file (:config-dir ctx) "modules/wrapper-b.clj")
          (module-source 'millstrand.prime-advice-fixture.wrapper-b
                         "(millstrand/defprime-advice guided \"Wrapper B second.\")"))
    (is (= :applied
           (:status (t/refresh-modules! ctx {:only [:wrapper-b]}))))
    (let [prime (weaver/op! (:runtime ctx) 'prime ["guided"])]
      (is (= "Base discipline.\n\nWrapper B second.\n\nWrapper A."
             (:prime prime))))
    (spit (io/file (:config-dir ctx) "init.clj") (init-source false))
    (is (= :applied (:status (t/refresh-modules! ctx))))
    (let [prime (weaver/op! (:runtime ctx) 'prime ["guided"])]
      (is (= "Base discipline.\n\nWrapper A." (:prime prime)))
      (is (= [["wrapper-a" "Wrapper A."]]
             (mapv (juxt #(get-in % [:provenance :module]) :text)
                   (:appendices prime)))))))

(deftest prime-read-waits-for-module-publication-to-complete
  (t/with-weaver-world
    [ctx {:storage :sqlite-memory
          :files {"modules/base.clj" base-source
                  "modules/wrapper-a.clj" wrapper-a-source}}]
    (t/declare-module! ctx :base {:file "modules/base.clj"})
    (let [original-publish! publication/publish!
          published (promise)
          release-publication (promise)
          read-started (promise)
          read-result (promise)
          ^ReentrantLock refresh-lock (:module-refresh-lock (:runtime ctx))
          refresh-result
          (future
            (with-redefs [publication/publish!
                          (fn [& args]
                            (let [result (apply original-publish! args)]
                              (deliver published true)
                              @release-publication
                              result))]
              (t/declare-module! ctx :wrapper-a
                                 {:file "modules/wrapper-a.clj"
                                  :after [:base]})))
          reader (Thread.
                  (fn []
                    (deliver read-started (Thread/currentThread))
                    (deliver read-result
                             (try
                               (weaver/op! (:runtime ctx) 'prime ["guided"])
                               (catch Throwable throwable
                                 throwable)))))]
      (try
        (is (true? (deref published 5000 false)))
        (.start reader)
        (let [read-thread (deref read-started 5000 nil)]
          (is (some? read-thread))
          (is (true? (await-queued-reader refresh-lock read-thread))))
        (finally
          (deliver release-publication true)
          (.join reader 5000)))
      (is (= :applied (:status @refresh-result)))
      (let [prime (deref read-result 5000 ::timeout)]
        (is (map? prime))
        (is (= "Base discipline.\n\nWrapper A." (:prime prime)))))))

(deftest blocked-prime-read-is-interruptible
  (t/with-weaver-world
    [ctx {:storage :sqlite-memory
          :files {"modules/base.clj" base-source
                  "modules/wrapper-a.clj" wrapper-a-source}}]
    (t/declare-module! ctx :base {:file "modules/base.clj"})
    (let [original-publish! publication/publish!
          published (promise)
          release-publication (promise)
          begin-read (promise)
          read-started (promise)
          read-finished (promise)
          ^ReentrantLock refresh-lock (:module-refresh-lock (:runtime ctx))
          refresh-result
          (future
            (with-redefs [publication/publish!
                          (fn [& args]
                            (let [result (apply original-publish! args)]
                              (deliver published true)
                              @release-publication
                              result))]
              (t/declare-module! ctx :wrapper-a
                                 {:file "modules/wrapper-a.clj"
                                  :after [:base]})))
          read-future
          (future
            @begin-read
            (deliver read-started (Thread/currentThread))
            (try
              (weaver/op! (:runtime ctx) 'prime ["guided"])
              (deliver read-finished :returned)
              (catch InterruptedException interrupted
                (deliver read-finished interrupted))
              (catch Throwable throwable
                (deliver read-finished throwable))))]
      (try
        (is (true? (deref published 5000 false)))
        (deliver begin-read true)
        (let [read-thread (deref read-started 5000 nil)]
          (is (some? read-thread))
          (is (true? (await-queued-reader refresh-lock read-thread))))
        (future-cancel read-future)
        (is (instance? InterruptedException
                       (deref read-finished 5000 ::timeout)))
        (is (not (realized? refresh-result)))
        (finally
          (deliver begin-read true)
          (future-cancel read-future)
          (deliver release-publication true)))
      (is (= :applied (:status @refresh-result))))))

(deftest prime-advice-declarations-fail-loudly
  (t/with-weaver-world
    [ctx {:storage :sqlite-memory
          :files {"modules/missing.clj"
                  (module-source
                   'millstrand.prime-advice-fixture.missing
                   "(millstrand/defprime-advice absent \"Missing target.\")")
                  "modules/no-base.clj"
                  (module-source
                   'millstrand.prime-advice-fixture.no-base
                   "(millstrand/defprime-advice bare \"Needs a base.\")")
                  "modules/blank.clj"
                  (module-source
                   'millstrand.prime-advice-fixture.blank
                   "(millstrand/defprime-advice bare \"  \")")
                  "modules/malformed.clj"
                  (module-source
                   'millstrand.prime-advice-fixture.malformed
                   "(millstrand/defprime-advice \"bare\" \"Wrong target.\")")}}]
    (testing "an unknown target rejects the complete refresh"
      (let [error (is (thrown? clojure.lang.ExceptionInfo
                               (t/declare-module!
                                ctx :missing {:file "modules/missing.clj"})))]
        (is (= :prime-advice/target-not-found (-> error ex-data :reason)))))
    (testing "an op without authoritative prime prose cannot receive advice"
      (weaver/register-op!
       (:runtime ctx) 'bare flat-read-spec
       'millstrand.core.weaver.prime-advice-test/bare-op)
      (let [error (is (thrown? clojure.lang.ExceptionInfo
                               (t/declare-module!
                                ctx :no-base {:file "modules/no-base.clj"})))]
        (is (= :prime-advice/base-prime-missing (-> error ex-data :reason)))))
    (testing "blank prose fails during source evaluation"
      (let [result (t/declare-module! ctx :blank {:file "modules/blank.clj"})]
        (is (= :partial (:status result)))
        (is (= "defprime-advice declaration is invalid"
               (get-in result [:modules :blank :error :message])))))
    (testing "a malformed target fails during macro expansion"
      (let [result (t/declare-module! ctx :malformed
                                      {:file "modules/malformed.clj"})]
        (is (= :partial (:status result)))
        (is (= :prime-advice/invalid-target
               (get-in result [:modules :malformed :error :data :reason])))))))
