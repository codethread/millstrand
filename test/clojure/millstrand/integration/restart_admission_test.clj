(ns millstrand.integration.restart-admission-test
  "Focused contract tests for planned peer interruption at the send boundary."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.api.peers.alpha :as peers]
            [millstrand.spools.test-support :as test-support])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader
            OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel]))

(defn- peer-row [socket state-dir]
  {:name "planned-peer"
   :workspace "/tmp/planned-peer-workspace"
   :weaver-id "peer-weaver"
   :generation-id "peer-generation"
   :protocol-version 3
   :socket-path socket
   :state-dir state-dir
   :running? true})

(defn- restart-record [fields]
  (merge {"transition_id" "transition-1"
          "updated_at" "2026-08-24T00:00:00Z"}
         fields))

(defn- pool-restart-record [workspace]
  {"format" "millstrand.jvm-pool-restart/v1"
   "jvm_pool" "backend"
   "state" "restarting"
   "transition_id" "pool-transition-1"
   "updated_at" "2026-08-24T00:00:00Z"
   "membership_revision" "membership-1"
   "old_generation_stopped" false
   "admitted_host" nil
   "previous_host" {"host_id" "host-1"
                    "host_generation_id" "host-generation-1"
                    "pid" 12345
                    "basis_fingerprint" (str "sha256:" (str/join (repeat 64 "a")))
                    "members" [{"config_dir" workspace
                                "weaver_id" "peer-weaver"
                                "generation_id" "peer-generation"}]}
   "registered_members" [workspace]
   "pending_members" []
   "probe" {"success" true
            "stage" "probe/complete"
            "probe/workspace" "/tmp/probe"
            "source/workspace" "/tmp/source"
            "completed" []
            "diagnostics" []
            "log" "/tmp/probe.log"}
   "failure" nil})

(defn- with-peer-server [response f]
  (let [root (test-support/temp-dir "millstrand-restart-admission")
        socket-path (.getPath (io/file root "peer.sock"))
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (.bind server (UnixDomainSocketAddress/of socket-path))
      (let [thread (Thread.
                    (fn []
                      (try
                        (with-open [channel (.accept server)
                                    reader (BufferedReader.
                                            (InputStreamReader.
                                             (Channels/newInputStream channel)))
                                    writer (BufferedWriter.
                                            (OutputStreamWriter.
                                             (Channels/newOutputStream channel)))]
                          (let [request (json/read-str (.readLine reader))]
                            (when response
                              (.write writer (json/write-str
                                              (response request)))
                              (.newLine writer)
                              (.flush writer))))
                        (catch java.nio.channels.ClosedChannelException _)))
                    "restart-admission-peer")]
        (.setDaemon thread true)
        (.start thread)
        (f (peer-row socket-path (.getPath root))))
      (finally
        (.close server)
        (test-support/delete-tree! root)))))

(deftest planned-peer-restart-is-distinct-from-domain-and-transport-loss
  (with-peer-server
    (fn [request]
      {"protocol_version" 3
       "request_id" (get request "request_id")
       "ok" false
       "result" nil
       "error" {"type" "transport"
                "code" "weaver/restarted"
                "message" "replacement interrupted the request"
                "details" {"request_delivery" true
                           "sent_once" true}}})
    (fn [peer]
      (try
        (peers/call! peer "read")
        (is false "expected a structured restart outcome")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :weaver/restarted (:code (ex-data ex))))
          (is (true? (get-in (ex-data ex) [:error "details" "sent_once"])))))))

  (with-peer-server nil
    (fn [peer]
      (spit (io/file (:state-dir peer) "restart.json")
            (str (json/write-str (restart-record
                                  {"state" "restarting"
                                   "previous_weaver_id" "peer-weaver"
                                   "previous_generation_id" "peer-generation"}))
                 " \n\t"))
      (try
        (peers/call! peer "read")
        (is false "expected a structured restart outcome after a sent request")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :weaver/restarted (:code (ex-data ex))))))))

  (with-peer-server nil
    (fn [peer]
      (spit (io/file (:state-dir peer) "restart.json")
            (json/write-str (restart-record
                             {"state" "running"
                              "generation_id" "replacement-generation"
                              "old_generation_stopped" true
                              "previous_weaver_id" "peer-weaver"
                              "previous_generation_id" "peer-generation"
                              "probe" {"success" true
                                       "stage" "probe/complete"
                                       "probe/workspace" "/tmp/probe"
                                       "source/workspace" "/tmp/source"
                                       "completed" []
                                       "diagnostics" []
                                       "log" "/tmp/probe.log"}})))
      (try
        (peers/call! peer "read")
        (is false "expected completed restart state to classify the old call")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :weaver/restarted (:code (ex-data ex))))))))

  (doseq [[label record]
          [["unrelated later restart"
            (restart-record
             {"state" "running"
              "generation_id" "replacement-generation"
              "old_generation_stopped" true
              "previous_weaver_id" "other-weaver"
              "previous_generation_id" "other-generation"
              "probe" {"success" true
                       "stage" "probe/complete"
                       "probe/workspace" "/tmp/probe"
                       "source/workspace" "/tmp/source"
                       "completed" []
                       "diagnostics" []
                       "log" "/tmp/probe.log"}})]
           ["generation mismatch"
            (restart-record
             {"state" "running"
              "generation_id" "replacement-generation"
              "old_generation_stopped" true
              "previous_weaver_id" "peer-weaver"
              "previous_generation_id" "other-generation"
              "probe" {"success" true
                       "stage" "probe/complete"
                       "probe/workspace" "/tmp/probe"
                       "source/workspace" "/tmp/source"
                       "completed" []
                       "diagnostics" []
                       "log" "/tmp/probe.log"}})]]]
    (with-peer-server nil
      (fn [peer]
        (spit (io/file (:state-dir peer) "restart.json")
              (json/write-str record))
        (try
          (peers/call! peer "read")
          (is false (str label " must remain an ordinary transport failure"))
          (catch clojure.lang.ExceptionInfo ex
            (is (= :peer/transport-failed (:code (ex-data ex)))))))))

  (with-peer-server nil
    (fn [peer]
      (try
        (peers/call! peer "read")
        (is false "expected ordinary peer transport failure")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :peer/transport-failed (:code (ex-data ex)))))))))

(deftest pooled-peer-restart-uses-host-record-and-member-identity
  (with-peer-server nil
    (fn [peer]
      (let [record-path (io/file (:state-dir peer) "pool-restart.json")
            pooled-peer (assoc peer
                               :jvm-pool "backend"
                               :host-id "host-1"
                               :host-generation-id "host-generation-1"
                               :pool-restart-path (.getCanonicalPath record-path))]
        (spit record-path
              (json/write-str
               (pool-restart-record
                (.getCanonicalPath (io/file (:workspace peer))))))
        (try
          (peers/call! pooled-peer "read")
          (is false "expected pooled host restart state to classify the old call")
          (catch clojure.lang.ExceptionInfo ex
            (is (= :weaver/restarted (:code (ex-data ex))))))))))

(deftest partial-pooled-peer-identity-fails-before-send
  (with-peer-server nil
    (fn [peer]
      (let [partial-peer (assoc peer :jvm-pool "backend")]
        (try
          (peers/call! partial-peer "read")
          (is false "expected partial pooled identity to be rejected")
          (catch clojure.lang.ExceptionInfo ex
            (is (= :peer/invalid-peer (:code (ex-data ex))))))))))

(deftest mismatched-pooled-restart-identity-fails-loudly
  (doseq [[label peer-fields record-fields]
          [["pool"
            {:jvm-pool "backend"
             :host-id "host-1"
             :host-generation-id "host-generation-1"}
            {"jvm_pool" "other"}]
           ["host generation"
            {:jvm-pool "backend"
             :host-id "other-host"
             :host-generation-id "other-generation"}
            {}]]]
    (with-peer-server nil
      (fn [peer]
        (let [record-path (io/file (:state-dir peer)
                                   (str "pool-restart-" label ".json"))
              pooled-peer (assoc (merge peer peer-fields)
                                 :pool-restart-path
                                 (.getCanonicalPath record-path))]
          (spit record-path
                (json/write-str
                 (merge (pool-restart-record
                         (.getCanonicalPath (io/file (:workspace peer))))
                        record-fields)))
          (try
            (peers/call! pooled-peer "read")
            (is false (str "expected mismatched " label " to be rejected"))
            (catch clojure.lang.ExceptionInfo ex
              (is (= :peer/restart-state-malformed
                     (:code (ex-data ex)))))))))))

(deftest mismatched-pooled-previous-member-fails-loudly
  (with-peer-server nil
    (fn [peer]
      (let [record-path (io/file (:state-dir peer) "pool-restart-member.json")
            pooled-peer (assoc peer
                               :jvm-pool "backend"
                               :host-id "host-1"
                               :host-generation-id "host-generation-1"
                               :pool-restart-path (.getCanonicalPath record-path))
            record (assoc-in
                    (pool-restart-record
                     (.getCanonicalPath (io/file (:workspace peer))))
                    ["previous_host" "members" 0 "generation_id"]
                    "other-generation")]
        (spit record-path (json/write-str record))
        (try
          (peers/call! pooled-peer "read")
          (is false "expected mismatched previous member to be rejected")
          (catch clojure.lang.ExceptionInfo ex
            (is (= :peer/restart-state-malformed
                   (:code (ex-data ex))))))))))

(deftest legacy-pooled-member-record-is-not-isolated-history
  (with-peer-server nil
    (fn [peer]
      (let [file (io/file (:state-dir peer) "restart.json")]
        (spit file
              (json/write-str
               (restart-record
                {"state" "running"
                 "generation_id" "replacement-generation"
                 "old_generation_stopped" true
                 "previous_weaver_id" "peer-weaver"
                 "previous_generation_id" "peer-generation"
                 "probe" {"success" true
                          "stage" "probe/complete"
                          "probe/workspace" "/tmp/jvm-pools/pool-probes/legacy"
                          "source/workspace" "/tmp/source"
                          "completed" []
                          "diagnostics" []
                          "log" "/tmp/probe.log"}})))
        (try
          (peers/call! peer "read")
          (is false "expected legacy pooled state to be rejected")
          (catch clojure.lang.ExceptionInfo ex
            (is (= :peer/restart-state-malformed (:code (ex-data ex))))))))))

(deftest malformed-peer-restart-records-fail-at-the-boundary
  (doseq [[label record expected-field]
          [["unknown state" (restart-record {"state" "surprise"}) "state"]
           ["missing transition" {"state" "restarting"
                                  "updated_at" "2026-08-24T00:00:00Z"}
            "transition_id"]
           ["wrong identity type" (restart-record
                                   {"state" "restarting"
                                    "previous_weaver_id" 42})
            "previous_weaver_id"]
           ["extra field" (assoc (restart-record {"state" "restarting"})
                                 "unexpected" true)
            "unexpected"]]]
    (with-peer-server nil
      (fn [peer]
        (spit (io/file (:state-dir peer) "restart.json")
              (json/write-str record))
        (try
          (peers/call! peer "read")
          (is false (str label " must fail loudly"))
          (catch clojure.lang.ExceptionInfo ex
            (let [data (ex-data ex)]
              (is (= :peer/restart-state-malformed (:code data)))
              (is (= expected-field (:field data)))
              (is (= (.getPath (io/file (:state-dir peer) "restart.json"))
                     (:file data)))
              (is (if (= expected-field "state")
                    (contains? (:allowed data) "running")
                    (if (= expected-field "unexpected")
                      (not (contains? (:allowed data) expected-field))
                      (contains? (:allowed data) expected-field)))))))))))

(deftest trailing-peer-restart-data-fails-at-the-boundary
  (let [record (json/write-str (restart-record {"state" "restarting"}))]
    (doseq [[label trailing] [["concatenated JSON" (json/write-str {})]
                              ["trailing text" "trailing text"]]]
      (with-peer-server nil
        (fn [peer]
          (let [file (io/file (:state-dir peer) "restart.json")
                _ (spit file (str record trailing))
                ex (try
                     (peers/call! peer "read")
                     (catch clojure.lang.ExceptionInfo ex
                       ex))
                data (ex-data ex)]
            (is ex (str label " must fail loudly"))
            (is (= :peer/restart-state-malformed (:code data)))
            (is (= (.getPath file) (:file data)))
            (is (nil? (:field data)))))))))
