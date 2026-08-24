(ns millstrand.integration.restart-admission-test
  "Focused contract tests for planned peer interruption at the send boundary."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [millstrand.api.peers.alpha :as peers]
            [millstrand.spools.test-support :as test-support])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader
            OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel]))

(defn- peer-row [socket]
  {:name "planned-peer"
   :workspace "/tmp/planned-peer-workspace"
   :weaver-id "peer-weaver"
   :protocol-version 3
   :socket-path socket
   :state-dir "/tmp/planned-peer-state"})

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
        (f (peer-row socket-path)))
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
      (try
        (peers/call! peer "read")
        (is false "expected ordinary peer transport failure")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :peer/transport-failed (:code (ex-data ex)))))))))
