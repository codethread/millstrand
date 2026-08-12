(ns millstrand.test.warm
  "Per-worktree warm test REPL bootstrap for the `:test-repl` alias.

  `-main` starts a `clojure.core.server` socket REPL on an ephemeral port,
  records the chosen port and this process's PID in `.test-repl-port` and
  `.test-repl.pid` at the worktree root (both gitignored) so a shell helper can
  connect to the socket and later reap the process by recorded PID, and arms a
  daemon idle watchdog: each client connection refreshes the deadline, and
  after `idle-limit-minutes` with no connection the server deletes both runtime
  files and exits. This is dev/test tooling on the test classpath, off the
  blessed `millstrand.test.alpha` vocabulary; the agent-facing focused-run entry is
  `millstrand.test.alpha/run-focused!`."
  (:require [clojure.core.server :as server]
            [clojure.java.io :as io])
  (:import (java.net ServerSocket)))

(def ^:private port-file (io/file ".test-repl-port"))
(def ^:private pid-file (io/file ".test-repl.pid"))

(def ^:private idle-limit-minutes 60)
(def ^:private idle-check-ms 60000)

;; Monotonic nanoTime of the most recent client connection: refreshed by the
;; socket accept fn, read by the idle watchdog.
(def ^:private last-active (atom (System/nanoTime)))

(defn touch!
  "Refresh the idle deadline to now; called on each client REPL connection."
  []
  (reset! last-active (System/nanoTime)))

(defn repl
  "Socket-server accept fn: record the connection, then run a standard REPL."
  []
  (touch!)
  (server/repl))

(defn- delete-runtime-files! []
  (io/delete-file port-file true)
  (io/delete-file pid-file true))

(defn- idle-expired? []
  (>= (- (System/nanoTime) @last-active)
      (* idle-limit-minutes 60 1000000000)))

(defn- watchdog
  "Daemon loop: once the idle window elapses with no client connection, delete
  the runtime files and exit the process."
  []
  (loop []
    (if (idle-expired?)
      (do (delete-runtime-files!) (System/exit 0))
      (do (Thread/sleep idle-check-ms) (recur)))))

(defn -main
  "Boot the per-worktree warm test REPL. Blocks until the idle watchdog exits."
  [& _]
  (let [^ServerSocket socket (server/start-server {:name "test-repl"
                                                   :port 0
                                                   :accept `repl
                                                   :server-daemon true})]
    (spit port-file (str (.getLocalPort socket) "\n"))
    (spit pid-file (str (.pid (java.lang.ProcessHandle/current)) "\n"))
    (doto (Thread. ^Runnable watchdog "test-repl-idle-watchdog")
      (.setDaemon true)
      (.start))
    ;; Park this non-daemon main thread so the daemon socket server and watchdog
    ;; stay alive; the watchdog's System/exit is the only exit path.
    @(promise)))
