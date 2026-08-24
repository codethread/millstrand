(ns millstrand.api.process.alpha-test
  "Focused contract tests for the explicit-runtime process custody API."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :as t]
            [millstrand.api.process.alpha :as process])
  (:import [java.io BufferedReader]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel]))

(def launch-spec
  {:argv ["sh" "-c" "printf output"]
   :cwd "/tmp"
   :env {"NO_COLOR" "1"}})

(defn throws-message? [pattern thunk]
  (try
    (thunk)
    false
    (catch clojure.lang.ExceptionInfo error
      (boolean (re-find pattern (ex-message error))))))

(defn wire-record [phase]
  (cond-> {:handle "process-test"
           :owner "agent-harness/run"
           :key "run-42"
           :phase phase
           :output {:stdout_ref "/tmp/stdout.log"
                    :stderr_ref "/tmp/stderr.log"}}
    (= phase "terminal") (assoc :exit {:code 0 :signal nil})))

(t/deftest all-unacknowledged-phases-project-through-five-ops
  (let [phase (atom "starting")
        acknowledged (atom false)
        runtime {:process-control
                 (fn [operation _arguments]
                   (case operation
                     "process.launch" (wire-record @phase)
                     "process.get" (wire-record @phase)
                     "process.list-owned" [(wire-record @phase)]
                     "process.cancel" (do (reset! phase "terminal")
                                          (-> (wire-record "terminal")
                                              (dissoc :exit)
                                              (assoc :cancellation {:reason "cancelled"})))
                     "process.acknowledge" (do (reset! acknowledged true)
                                               {:acknowledged true
                                                :handle "process-test"})))}]
    (t/testing "starting and running remain visible"
      (t/is (= :starting (:phase (process/launch! runtime :agent-harness/run
                                                  "run-42" launch-spec))))
      (reset! phase "running")
      (t/is (= :running (:phase (process/get runtime "process-test"))))
      (t/is (= [:running]
               (mapv :phase (process/list-owned runtime :agent-harness/run)))))
    (t/testing "terminal remains visible until acknowledgement"
      (reset! phase "terminal")
      (t/is (= :terminal (:phase (process/get runtime "process-test"))))
      (t/is (= :terminal (:phase (process/cancel! runtime :agent-harness/run
                                                  "process-test"))))
      (t/is (= {:acknowledged true :handle "process-test"}
               (process/acknowledge! runtime :agent-harness/run "process-test")))
      (t/is @acknowledged))))

(t/deftest explicit-runtime-and-boundary-validation
  (t/is (throws-message? #"explicit Millstrand runtime"
                         #(process/get nil "handle")))
  (let [runtime {:process-control (fn [_ _] (wire-record "running"))}]
    (t/is (throws-message? #"owner must be a keyword"
                           #(process/list-owned runtime "agent-harness/run")))
    (t/is (throws-message? #"launch specification is malformed"
                           #(process/launch! runtime :agent-harness/run
                                             "run-42"
                                             {:argv [] :cwd "/tmp" :env {}})))))

(t/deftest unavailable-control-channel-fails-visibly
  (t/is (throws-message? #"identity mismatch|control channel is unavailable|response is malformed"
                         #(process/get {:metadata {:nonce "missing-weaver"}}
                                       "process-test"))))

(t/deftest cancel-and-acknowledge-send-caller-owner
  (let [calls (atom [])
        runtime {:process-control
                 (fn [operation arguments]
                   (swap! calls conj [operation arguments])
                   (if (= operation "process.cancel")
                     (wire-record "terminal")
                     {:acknowledged true :handle "process-test"}))}]
    (process/cancel! runtime :agent-harness/run "process-test")
    (process/acknowledge! runtime :agent-harness/run "process-test")
    (t/is (= [["process.cancel" {"owner" "agent-harness/run"
                                 "handle" "process-test"}]
              ["process.acknowledge" {"owner" "agent-harness/run"
                                      "handle" "process-test"}]]
             @calls))))

(t/deftest acknowledgement-result-is-closed-and-correlated
  (let [runtime {:process-control
                 (fn [_ _]
                   {:acknowledged true
                    :handle "process-test"
                    :unexpected "field"})}]
    (t/is (throws-message? #"malformed process acknowledgement"
                           #(process/acknowledge! runtime :agent-harness/run
                                                  "process-test")))))

(t/deftest response-parsing-roundtrips-through-a-unix-socket
  (let [socket-file (java.io.File/createTempFile "mill-process-" ".sock")
        _ (.delete socket-file)
        socket-path (.getAbsolutePath socket-file)
        server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of socket-path)))
        response (future
                   (with-open [channel (.accept server)
                               reader (BufferedReader.
                                       (io/reader (Channels/newInputStream channel)))
                               writer (io/writer (Channels/newOutputStream channel))]
                     (let [request (json/read-str (.readLine reader))]
                       (.write writer
                               (json/write-str
                                {"protocol_version" 3
                                 "request_id" (get request "request_id")
                                 "ok" true
                                 "result" {"handle" "process-socket"
                                           "owner" "socket-owner/run"
                                           "key" "roundtrip"
                                           "phase" "terminal"
                                           "output" {"stdout_ref" "/tmp/stdout"
                                                     "stderr_ref" "/tmp/stderr"}
                                           "exit" {"code" 0 "signal" nil}}}))
                       (.write writer "\n")
                       (.flush writer))))]
    (try
      (let [row (process/get {:metadata {:nonce "socket-weaver"}
                              :process-control-socket socket-path}
                             "process-socket")]
        (t/is (= {:handle "process-socket"
                  :owner :socket-owner/run
                  :key "roundtrip"
                  :phase :terminal
                  :output {:stdout-ref "/tmp/stdout"
                           :stderr-ref "/tmp/stderr"}
                  :exit {:code 0 :signal nil}}
                 row)))
      (finally
        (.close server)
        @response
        (.delete socket-file)))))
