(ns millstrand.core.weaver.pool-wire
  "Translate the closed JVM-pool manifests and result envelopes at the JSON edge.

  Registry projections deliberately remain string-keyed. Only protocol fields
  are normalized here; arbitrary projection keys are data, not field names."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn- required
  [value key]
  (if (contains? value key)
    (get value key)
    (throw (ex-info "JVM-pool wire field is missing" {:field key}))))

(defn- keyword-status [value]
  (when (some? value)
    (keyword value)))

(defn- decode-launch-member [value]
  {:config-dir (required value "config_dir")
   :source-cwd (required value "source_cwd")
   :state-dir (required value "state_dir")
   :data-dir (required value "data_dir")
   :name (required value "name")
   :weaver-id (required value "weaver_id")
   :generation-id (required value "generation_id")
   :dependency-diagnostic (required value "dependency_diagnostic")})

(defn decode-launch-manifest
  "Decode a JSON serving manifest without rewriting registry-like data."
  [value]
  (let [members (mapv decode-launch-member (required value "members"))]
    {:format (required value "format")
     :jvm-pool (required value "jvm_pool")
     :host-id (required value "host_id")
     :host-generation-id (required value "host_generation_id")
     :membership-revision (required value "membership_revision")
     :millstrand-source (required value "millstrand_source")
     :millstrand-version (required value "millstrand_version")
     :members members}))

(defn read-json
  "Read one JSON object from `file` and decode its string-keyed map."
  [file]
  (json/read-str (slurp (io/file file))))

(defn read-launch-manifest
  "Read and decode a serving manifest from `file`."
  [file]
  (decode-launch-manifest (read-json file)))

(defn- decode-probe-baseline [value]
  (when value
    {:status (keyword-status (required value "status"))
     :projection (required value "projection")}))

(defn- decode-probe-member [value]
  {:original-config-dir (required value "original_config_dir")
   :original-source-cwd (required value "original_source_cwd")
   :probe-config-dir (required value "probe_config_dir")
   :probe-state-dir (required value "probe_state_dir")
   :probe-data-dir (required value "probe_data_dir")
   :member-diagnostic (required value "member_diagnostic")
   :name (required value "name")
   :candidate-weaver-id (required value "candidate_weaver_id")
   :candidate-generation-id (required value "candidate_generation_id")
   :old-member-baseline
   (decode-probe-baseline (get value "old_member_baseline"))})

(defn decode-probe-manifest
  "Decode a JSON private probe manifest while preserving projection keys."
  [value]
  {:format (required value "format")
   :jvm-pool (required value "jvm_pool")
   :probe-id (required value "probe_id")
   :candidate-host-id (required value "candidate_host_id")
   :candidate-host-generation-id (required value "candidate_host_generation_id")
   :probe-root (required value "probe_root")
   :millstrand-source (required value "millstrand_source")
   :result (required value "result")
   :collective-diagnostic (required value "collective_diagnostic")
   :members (mapv decode-probe-member (required value "members"))})

(defn read-probe-manifest
  "Read and decode a private probe manifest from `file`."
  [file]
  (decode-probe-manifest (read-json file)))

(defn- wire-member
  [member]
  {"config_dir" (:config-dir member)
   "weaver_id" (:weaver-id member)
   "generation_id" (:generation-id member)
   "socket_path" (:socket-path member)
   "nrepl_host" (:nrepl-host member)
   "nrepl_port" (:nrepl-port member)})

(defn ready-marker-wire
  "Return the exact JSON-shaped ready marker map for `marker`."
  [marker]
  {"format" (:format marker)
   "jvm_pool" (:jvm-pool marker)
   "host_id" (:host-id marker)
   "host_generation_id" (:host-generation-id marker)
   "pid" (:pid marker)
   "membership_revision" (:membership-revision marker)
   "basis_fingerprint" (:basis-fingerprint marker)
   "members" (mapv wire-member (:members marker))})

(defn- wire-probe-member
  [member]
  (cond-> {"original_config_dir" (:original-config-dir member)
           "probe_config_dir" (:probe-config-dir member)
           "candidate_weaver_id" (:candidate-weaver-id member)
           "candidate_generation_id" (:candidate-generation-id member)
           "baseline_kind" (name (:baseline-kind member))
           "status" (name (:status member))
           "registry_projection" (:registry-projection member)
           "registry_diff" (:registry-diff member)
           "member_diagnostic" (:member-diagnostic member)}
    (nil? (:registry-diff member)) (dissoc "registry_diff")))

(defn probe-result-wire
  "Return the exact JSON-shaped pooled probe result map for `result`."
  [result]
  {"format" (:format result)
   "probe_id" (:probe-id result)
   "success" (:success result)
   "stage" (name (:stage result))
   "probe_root" (:probe-root result)
   "source_workspace" (:source-workspace result)
   "completed" (mapv str (:completed result))
   "members" (mapv wire-probe-member (:members result))
   "collective_diagnostic" (:collective-diagnostic result)
   "log" (:log result)})

(defn write-json!
  "Write `value` as JSON to `file`, creating its parent directory."
  [file value]
  (let [file (io/file file)]
    (.mkdirs (.getParentFile file))
    (spit file (json/write-str value))
    file))
