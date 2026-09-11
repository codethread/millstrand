(ns millstrand.core.weaver.pool-wire
  "Translate the closed JVM-pool manifests and result envelopes at the JSON edge.

  Registry projections deliberately remain string-keyed. Only protocol fields
  are normalized here; arbitrary projection keys are data, not field names."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [millstrand.core.specs]))

(defn- closed-raw
  [kind allowed value]
  (when-not (and (map? value) (= allowed (set (keys value))))
    (throw (ex-info "JVM-pool wire object has an invalid closed shape"
                    {:kind kind :expected-keys allowed :value value})))
  value)

(defn- required
  [value key]
  (if (contains? value key)
    (get value key)
    (throw (ex-info "JVM-pool wire field is missing" {:field key}))))

(defn- require-valid!
  [spec value kind]
  (when-not (s/valid? spec value)
    (throw (ex-info "JVM-pool wire object has invalid protocol fields"
                    {:kind kind :spec spec :value value
                     :explain (s/explain-data spec value)})))
  value)

(defn- keyword-status [value]
  (when-not (string? value)
    (throw (ex-info "JVM-pool wire enum must be a string" {:value value})))
  (keyword value))

(defn- decode-launch-member [value]
  (closed-raw :launch-member
              #{"config_dir" "source_cwd" "state_dir" "data_dir" "name"
                "weaver_id" "generation_id" "dependency_diagnostic"}
              value)
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
  (closed-raw :launch-manifest
              #{"format" "jvm_pool" "host_id" "host_generation_id"
                "membership_revision" "pool_restart_path" "millstrand_source"
                "millstrand_version" "members"}
              value)
  (let [members (mapv decode-launch-member (required value "members"))]
    (require-valid!
     :millstrand.jvm-pool/launch-manifest
     {:format (required value "format")
      :jvm-pool (required value "jvm_pool")
      :host-id (required value "host_id")
      :host-generation-id (required value "host_generation_id")
      :membership-revision (required value "membership_revision")
      :pool-restart-path (required value "pool_restart_path")
      :millstrand-source (required value "millstrand_source")
      :millstrand-version (required value "millstrand_version")
      :members members}
     :launch-manifest)))

(defn read-json
  "Read one JSON object from `file` and decode its string-keyed map."
  [file]
  (json/read-str (slurp (io/file file))))

(defn read-launch-manifest
  "Read and decode a serving manifest from `file`."
  [file]
  (decode-launch-manifest (read-json file)))

(defn- decode-probe-baseline [value]
  (when-not (nil? value)
    (closed-raw :probe-baseline #{"status" "projection"} value)
    (when-not (string? (required value "status"))
      (throw (ex-info "JVM-pool baseline status must be a string"
                      {:value value})))
    {:status (keyword-status (required value "status"))
     :projection (required value "projection")}))

(defn- decode-probe-member [value]
  (closed-raw :probe-member
              #{"original_config_dir" "original_source_cwd" "probe_config_dir"
                "probe_state_dir" "probe_data_dir" "member_diagnostic" "name"
                "candidate_weaver_id" "candidate_generation_id"
                "old_member_baseline"}
              value)
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
   (decode-probe-baseline (required value "old_member_baseline"))})

(defn decode-probe-manifest
  "Decode a JSON private probe manifest while preserving projection keys."
  [value]
  (closed-raw :probe-manifest
              #{"format" "jvm_pool" "probe_id" "candidate_host_id"
                "candidate_host_generation_id" "probe_root" "millstrand_source"
                "result" "collective_diagnostic" "members"}
              value)
  (require-valid!
   :millstrand.jvm-pool/probe-manifest
   {:format (required value "format")
    :jvm-pool (required value "jvm_pool")
    :probe-id (required value "probe_id")
    :candidate-host-id (required value "candidate_host_id")
    :candidate-host-generation-id (required value "candidate_host_generation_id")
    :probe-root (required value "probe_root")
    :millstrand-source (required value "millstrand_source")
    :result (required value "result")
    :collective-diagnostic (required value "collective_diagnostic")
    :members (mapv decode-probe-member (required value "members"))}
   :probe-manifest))

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

(defn- decode-ready-member [value]
  (closed-raw :ready-member
              #{"config_dir" "weaver_id" "generation_id" "socket_path"
                "nrepl_host" "nrepl_port"}
              value)
  {:config-dir (required value "config_dir")
   :weaver-id (required value "weaver_id")
   :generation-id (required value "generation_id")
   :socket-path (required value "socket_path")
   :nrepl-host (required value "nrepl_host")
   :nrepl-port (required value "nrepl_port")})

(defn decode-ready-marker
  "Decode a JSON ready marker with its complete member identity set."
  [value]
  (closed-raw :ready-marker
              #{"format" "jvm_pool" "host_id" "host_generation_id" "pid"
                "membership_revision" "basis_fingerprint" "members"}
              value)
  (require-valid!
   :millstrand.jvm-pool/ready-marker
   {:format (required value "format")
    :jvm-pool (required value "jvm_pool")
    :host-id (required value "host_id")
    :host-generation-id (required value "host_generation_id")
    :pid (required value "pid")
    :membership-revision (required value "membership_revision")
    :basis-fingerprint (required value "basis_fingerprint")
    :members (mapv decode-ready-member (required value "members"))}
   :ready-marker))

(defn- wire-probe-member
  [member]
  {"original_config_dir" (:original-config-dir member)
   "probe_config_dir" (:probe-config-dir member)
   "candidate_weaver_id" (:candidate-weaver-id member)
   "candidate_generation_id" (:candidate-generation-id member)
   "baseline_kind" (name (:baseline-kind member))
   "status" (name (:status member))
   "registry_projection" (:registry-projection member)
   "registry_diff" (:registry-diff member)
   "member_diagnostic" (:member-diagnostic member)})

(defn- decode-probe-result-member [value]
  (closed-raw :probe-result-member
              #{"original_config_dir" "probe_config_dir" "candidate_weaver_id"
                "candidate_generation_id" "baseline_kind" "status"
                "registry_projection" "registry_diff" "member_diagnostic"}
              value)
  (let [registry-diff (required value "registry_diff")]
    (when (some? registry-diff)
      (closed-raw :registry-diff #{"added" "removed" "changed"}
                  registry-diff))
    {:original-config-dir (required value "original_config_dir")
     :probe-config-dir (required value "probe_config_dir")
     :candidate-weaver-id (required value "candidate_weaver_id")
     :candidate-generation-id (required value "candidate_generation_id")
     :baseline-kind (keyword-status (required value "baseline_kind"))
     :status (keyword-status (required value "status"))
     :registry-projection (required value "registry_projection")
     :registry-diff (when (some? registry-diff)
                      {:added (get registry-diff "added")
                       :removed (get registry-diff "removed")
                       :changed (get registry-diff "changed")})
     :member-diagnostic (required value "member_diagnostic")}))

(defn decode-probe-result
  "Decode a JSON pooled probe result without dropping registry keys."
  [value]
  (closed-raw :probe-result
              #{"format" "probe_id" "success" "stage" "probe_root"
                "source_workspace" "completed" "members"
                "collective_diagnostic" "log"}
              value)
  (require-valid!
   :millstrand.jvm-pool/probe-result
   {:format (required value "format")
    :probe-id (required value "probe_id")
    :success (required value "success")
    :stage (required value "stage")
    :probe-root (required value "probe_root")
    :source-workspace (required value "source_workspace")
    :completed (required value "completed")
    :members (mapv decode-probe-result-member (required value "members"))
    :collective-diagnostic (required value "collective_diagnostic")
    :log (required value "log")}
   :probe-result))

(defn read-probe-result
  "Read and decode a pooled probe result from `file`."
  [file]
  (decode-probe-result (read-json file)))

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
