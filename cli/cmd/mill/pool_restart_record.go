package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"millstrand-strand-cli/internal/jvmpool"
)

const (
	poolRestartRecordFormat = "millstrand.jvm-pool-restart/v1"
	poolRestartRecordFile   = "restart.json"
)

type poolRestartMember struct {
	ConfigDir    string `json:"config_dir"`
	WeaverID     string `json:"weaver_id"`
	GenerationID string `json:"generation_id"`
}

type poolRestartHost struct {
	HostID           string              `json:"host_id"`
	HostGeneration   string              `json:"host_generation_id"`
	PID              int                 `json:"pid"`
	BasisFingerprint string              `json:"basis_fingerprint"`
	Members          []poolRestartMember `json:"members"`
}

// poolRestartRecord is the single lifecycle record for one pooled host. It is
// intentionally separate from restartRecord: an isolated member's history
// must never be decoded as host transition state.
type poolRestartRecord struct {
	Format               string              `json:"format"`
	JVMPool              string              `json:"jvm_pool"`
	State                string              `json:"state"`
	TransitionID         string              `json:"transition_id"`
	UpdatedAt            string              `json:"updated_at"`
	MembershipRevision   string              `json:"membership_revision"`
	OldGenerationStopped bool                `json:"old_generation_stopped"`
	AdmittedHost         *poolRestartHost    `json:"admitted_host"`
	PreviousHost         *poolRestartHost    `json:"previous_host"`
	RegisteredMembers    []string            `json:"registered_members"`
	PendingMembers       []string            `json:"pending_members"`
	Probe                *restartProbeResult `json:"probe"`
	Failure              *restartFailure     `json:"failure"`
}

type poolRestartSummaryCacheEntry struct {
	info    os.FileInfo
	record  poolRestartRecord
	present bool
	err     error
}

func poolRestartRecordPath(stateRoot, pool string) (string, error) {
	hostDir, err := jvmpool.PoolHostDir(stateRoot, pool)
	if err != nil {
		return "", err
	}
	return filepath.Join(hostDir, poolRestartRecordFile), nil
}

func poolRestartRecordPathForHost(host *weaverHost) string {
	if host == nil {
		return ""
	}
	return host.PoolRestartPath
}

func validatePoolRestartRecordForWrite(record poolRestartRecord) error {
	if record.UpdatedAt != "" {
		return errors.New("pool restart record updated_at is writer-owned")
	}
	return validatePoolRestartRecord(record, false)
}

func validatePoolRestartRecordFromDisk(record poolRestartRecord) error {
	if strings.TrimSpace(record.UpdatedAt) == "" {
		return errors.New("pool restart record requires updated_at")
	}
	return validatePoolRestartRecord(record, true)
}

func validatePoolRestartRecord(record poolRestartRecord, fromDisk bool) error {
	if record.Format != poolRestartRecordFormat {
		return fmt.Errorf("pool restart record format must be %q", poolRestartRecordFormat)
	}
	for field, value := range map[string]string{
		"jvm_pool": record.JVMPool, "transition_id": record.TransitionID,
		"membership_revision": record.MembershipRevision,
	} {
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("pool restart record %s must be non-blank", field)
		}
	}
	if fromDisk && strings.TrimSpace(record.UpdatedAt) == "" {
		return errors.New("pool restart record updated_at must be non-blank")
	}
	switch record.State {
	case restartStateProbing, restartStateRestarting, restartStateRunning, restartStateFailed:
	default:
		return fmt.Errorf("pool restart record has unknown state %q", record.State)
	}
	if err := validateCanonicalPoolDirs("registered_members", record.RegisteredMembers); err != nil {
		return err
	}
	if err := validateCanonicalPoolDirs("pending_members", record.PendingMembers); err != nil {
		return err
	}
	if err := validatePoolRestartHost(record.AdmittedHost); err != nil {
		return fmt.Errorf("admitted_host: %w", err)
	}
	if err := validatePoolRestartHost(record.PreviousHost); err != nil {
		return fmt.Errorf("previous_host: %w", err)
	}
	if err := validatePoolRestartSetRelations(record); err != nil {
		return err
	}
	if record.Probe != nil {
		if err := record.Probe.validate(); err != nil {
			return fmt.Errorf("pool restart record probe: %w", err)
		}
	}
	if record.Failure != nil {
		if strings.TrimSpace(record.Failure.Stage) == "" || strings.TrimSpace(record.Failure.Message) == "" {
			return errors.New("pool restart failure requires non-blank stage and message")
		}
		if strings.TrimSpace(record.Failure.LogPath) == "" && record.Failure.LogPath != "" {
			return errors.New("pool restart failure log_path must be non-blank when present")
		}
		if strings.TrimSpace(record.Failure.ExitEvidence) == "" && record.Failure.ExitEvidence != "" {
			return errors.New("pool restart failure exit_evidence must be non-blank when present")
		}
	}
	switch record.State {
	case restartStateProbing:
		if record.AdmittedHost == nil || record.PreviousHost != nil || record.OldGenerationStopped || record.Failure != nil {
			return errors.New("probing pool restart record has contradictory host state")
		}
		if record.Probe != nil && !record.Probe.Success {
			return errors.New("probing pool restart record cannot contain a failed probe")
		}
	case restartStateRestarting:
		if record.AdmittedHost != nil || record.PreviousHost == nil || record.Failure != nil || record.Probe == nil || !record.Probe.Success {
			return errors.New("restarting pool restart record has contradictory host state")
		}
	case restartStateRunning:
		if record.AdmittedHost == nil {
			return errors.New("running pool restart record has contradictory host state")
		}
		if record.PreviousHost == nil {
			if record.OldGenerationStopped {
				return errors.New("initial running pool restart record has contradictory transition state")
			}
			if record.Failure == nil {
				if record.Probe != nil || len(record.PendingMembers) != 0 {
					return errors.New("initial running pool restart record has contradictory transition state")
				}
			} else if record.Failure.Stage != "probe" || record.Probe == nil || record.Probe.Success {
				return errors.New("running pool restart failure requires an unsuccessful probe")
			}
		} else if record.Probe == nil || !record.Probe.Success || !record.OldGenerationStopped || len(record.PendingMembers) != 0 {
			return errors.New("replaced running pool restart record requires completed cutover")
		} else if record.Failure != nil {
			return errors.New("replaced running pool restart record cannot contain failure")
		}
	case restartStateFailed:
		if record.AdmittedHost != nil || record.PreviousHost == nil || record.Probe == nil || !record.Probe.Success || record.Failure == nil {
			return errors.New("failed pool restart record has contradictory host state")
		}
	}
	return nil
}

func validateCanonicalPoolDirs(label string, values []string) error {
	seen := map[string]bool{}
	for i, value := range values {
		if value == "" || !filepath.IsAbs(value) || filepath.Clean(value) != value {
			return fmt.Errorf("pool restart %s[%d] must be a canonical absolute path", label, i)
		}
		if seen[value] {
			return fmt.Errorf("pool restart %s contains duplicate %q", label, value)
		}
		seen[value] = true
		if i > 0 && values[i-1] >= value {
			return fmt.Errorf("pool restart %s must be sorted by config_dir", label)
		}
	}
	return nil
}

func validatePoolRestartHost(host *poolRestartHost) error {
	if host == nil {
		return nil
	}
	for field, value := range map[string]string{"host_id": host.HostID, "host_generation_id": host.HostGeneration, "basis_fingerprint": host.BasisFingerprint} {
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("%s must be non-blank", field)
		}
	}
	if host.PID <= 0 {
		return errors.New("pid must be positive")
	}
	if !validBasisFingerprint(host.BasisFingerprint) {
		return errors.New("basis_fingerprint is invalid")
	}
	if len(host.Members) == 0 {
		return errors.New("members must not be empty")
	}
	seen := map[string]bool{}
	for i, member := range host.Members {
		if member.ConfigDir == "" || !filepath.IsAbs(member.ConfigDir) || filepath.Clean(member.ConfigDir) != member.ConfigDir || strings.TrimSpace(member.WeaverID) == "" || strings.TrimSpace(member.GenerationID) == "" {
			return fmt.Errorf("invalid member %d", i)
		}
		if seen[member.ConfigDir] {
			return fmt.Errorf("members contains duplicate %q", member.ConfigDir)
		}
		seen[member.ConfigDir] = true
		if i > 0 && host.Members[i-1].ConfigDir >= member.ConfigDir {
			return errors.New("members must be sorted by config_dir")
		}
	}
	return nil
}

func validatePoolRestartSetRelations(record poolRestartRecord) error {
	registered := setOf(record.RegisteredMembers)
	pending := setOf(record.PendingMembers)
	if record.AdmittedHost != nil {
		admitted := hostMemberSet(record.AdmittedHost)
		if !subset(admitted, registered) || !equalSets(pending, difference(registered, admitted)) {
			return errors.New("pool restart admitted and pending members contradict registered_members")
		}
		return nil
	}
	if record.PreviousHost != nil {
		previous := hostMemberSet(record.PreviousHost)
		if !subset(previous, registered) || !equalSets(pending, difference(registered, previous)) {
			return errors.New("pool restart previous and pending members contradict registered_members")
		}
		return nil
	}
	return errors.New("pool restart record requires admitted_host or previous_host")
}

func setOf(values []string) map[string]bool {
	result := make(map[string]bool, len(values))
	for _, value := range values {
		result[value] = true
	}
	return result
}

func hostMemberSet(host *poolRestartHost) map[string]bool {
	result := make(map[string]bool, len(host.Members))
	for _, member := range host.Members {
		result[member.ConfigDir] = true
	}
	return result
}

func subset(values, universe map[string]bool) bool {
	for value := range values {
		if !universe[value] {
			return false
		}
	}
	return true
}

func difference(left, right map[string]bool) map[string]bool {
	result := map[string]bool{}
	for value := range left {
		if !right[value] {
			result[value] = true
		}
	}
	return result
}

func equalSets(left, right map[string]bool) bool {
	return subset(left, right) && subset(right, left)
}

func writePoolRestartRecord(path string, record poolRestartRecord) error {
	if err := validatePoolRestartRecordForWrite(record); err != nil {
		return fmt.Errorf("invalid pool restart record: %w", err)
	}
	record.UpdatedAt = time.Now().UTC().Format(time.RFC3339Nano)
	return atomicPoolJSON(path, record)
}

func readPoolRestartRecord(path, pool string) (poolRestartRecord, bool, error) {
	if err := validatePoolRestartPath(path, pool); err != nil {
		return poolRestartRecord{}, false, err
	}
	b, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return poolRestartRecord{}, false, nil
	}
	if err != nil {
		return poolRestartRecord{}, false, fmt.Errorf("read pool restart record %s: %w", path, err)
	}
	raw, err := decodeObject(b, "pool restart record")
	if err != nil {
		return poolRestartRecord{}, false, fmt.Errorf("decode pool restart record %s: %w", path, err)
	}
	expected := map[string]bool{"format": true, "jvm_pool": true, "state": true, "transition_id": true, "updated_at": true, "membership_revision": true, "old_generation_stopped": true, "admitted_host": true, "previous_host": true, "registered_members": true, "pending_members": true, "probe": true, "failure": true}
	for key := range raw {
		if !expected[key] {
			return poolRestartRecord{}, false, fmt.Errorf("pool restart record contains unknown field %q", key)
		}
	}
	for key := range expected {
		if _, ok := raw[key]; !ok {
			return poolRestartRecord{}, false, fmt.Errorf("pool restart record is missing field %q", key)
		}
	}
	if err := decodePoolRestartRequiredFields(raw); err != nil {
		return poolRestartRecord{}, false, fmt.Errorf("invalid pool restart record %s: %w", path, err)
	}
	var record poolRestartRecord
	if err := json.Unmarshal(mustJSON(raw), &record); err != nil {
		return poolRestartRecord{}, false, fmt.Errorf("decode pool restart record %s: %w", path, err)
	}
	if record.JVMPool != pool {
		return poolRestartRecord{}, false, fmt.Errorf("pool restart record pool %q does not match %q", record.JVMPool, pool)
	}
	if record.AdmittedHost, err = decodePoolRestartHost(raw["admitted_host"]); err != nil {
		return poolRestartRecord{}, false, fmt.Errorf("decode admitted_host: %w", err)
	}
	if record.PreviousHost, err = decodePoolRestartHost(raw["previous_host"]); err != nil {
		return poolRestartRecord{}, false, fmt.Errorf("decode previous_host: %w", err)
	}
	if record.Probe, err = decodePoolRestartProbe(raw["probe"]); err != nil {
		return poolRestartRecord{}, false, fmt.Errorf("decode probe: %w", err)
	}
	if record.Failure, err = decodePoolRestartFailure(raw["failure"]); err != nil {
		return poolRestartRecord{}, false, fmt.Errorf("decode failure: %w", err)
	}
	if err := validatePoolRestartRecordFromDisk(record); err != nil {
		return poolRestartRecord{}, false, fmt.Errorf("invalid pool restart record %s: %w", path, err)
	}
	return record, true, nil
}

func validatePoolRestartPath(path, pool string) error {
	if strings.TrimSpace(path) == "" || !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return errors.New("pool restart record path must be canonical absolute path")
	}
	if filepath.Base(path) != poolRestartRecordFile {
		return fmt.Errorf("pool restart record path must end in %s", poolRestartRecordFile)
	}
	if err := jvmpool.ValidateArtifactKey(pool, filepath.Base(filepath.Dir(path))); err != nil {
		return fmt.Errorf("pool restart record path: %w", err)
	}
	if filepath.Base(filepath.Dir(filepath.Dir(path))) != "hosts" || filepath.Base(filepath.Dir(filepath.Dir(filepath.Dir(path)))) != "jvm-pools" {
		return errors.New("pool restart record path is outside the JVM pool hosts directory")
	}
	return nil
}

func decodePoolRestartRequiredFields(raw map[string]json.RawMessage) error {
	for _, key := range []string{"format", "jvm_pool", "state", "transition_id", "updated_at", "membership_revision", "registered_members", "pending_members"} {
		if bytes.Equal(bytes.TrimSpace(raw[key]), []byte("null")) {
			return fmt.Errorf("%s must not be null", key)
		}
	}
	if bytes.Equal(bytes.TrimSpace(raw["old_generation_stopped"]), []byte("null")) {
		return errors.New("old_generation_stopped must not be null")
	}
	return nil
}

func mustJSON(raw map[string]json.RawMessage) []byte {
	b, _ := json.Marshal(raw)
	return b
}

func decodePoolRestartHost(data json.RawMessage) (*poolRestartHost, error) {
	if bytes.Equal(bytes.TrimSpace(data), []byte("null")) {
		return nil, nil
	}
	value, err := decodeObject(data, "pool restart host")
	if err != nil {
		return nil, err
	}
	expected := map[string]bool{"host_id": true, "host_generation_id": true, "pid": true, "basis_fingerprint": true, "members": true}
	for key := range value {
		if !expected[key] {
			return nil, fmt.Errorf("pool restart host contains unknown field %q", key)
		}
	}
	for key := range expected {
		if _, ok := value[key]; !ok {
			return nil, fmt.Errorf("pool restart host is missing field %q", key)
		}
	}
	var host poolRestartHost
	if err := json.Unmarshal(data, &host); err != nil {
		return nil, err
	}
	var memberValues []json.RawMessage
	if err := json.Unmarshal(value["members"], &memberValues); err != nil {
		return nil, fmt.Errorf("pool restart host members must be an array: %w", err)
	}
	host.Members = make([]poolRestartMember, 0, len(memberValues))
	for index, memberValue := range memberValues {
		member, err := decodePoolRestartMember(memberValue)
		if err != nil {
			return nil, fmt.Errorf("pool restart host member %d: %w", index, err)
		}
		host.Members = append(host.Members, member)
	}
	if err := validatePoolRestartHost(&host); err != nil {
		return nil, err
	}
	return &host, nil
}

func decodePoolRestartMember(data json.RawMessage) (poolRestartMember, error) {
	value, err := decodeObject(data, "pool restart member")
	if err != nil {
		return poolRestartMember{}, err
	}
	expected := map[string]bool{"config_dir": true, "weaver_id": true, "generation_id": true}
	for key := range value {
		if !expected[key] {
			return poolRestartMember{}, fmt.Errorf("pool restart member contains unknown field %q", key)
		}
	}
	for key := range expected {
		field, ok := value[key]
		if !ok {
			return poolRestartMember{}, fmt.Errorf("pool restart member is missing field %q", key)
		}
		if bytes.Equal(bytes.TrimSpace(field), []byte("null")) {
			return poolRestartMember{}, fmt.Errorf("pool restart member %s must not be null", key)
		}
	}
	var member poolRestartMember
	if err := json.Unmarshal(data, &member); err != nil {
		return poolRestartMember{}, err
	}
	return member, nil
}

func decodePoolRestartProbe(data json.RawMessage) (*restartProbeResult, error) {
	if bytes.Equal(bytes.TrimSpace(data), []byte("null")) {
		return nil, nil
	}
	probe, err := decodeRestartProbe(data)
	if err != nil {
		return nil, err
	}
	return &probe, nil
}

func decodePoolRestartFailure(data json.RawMessage) (*restartFailure, error) {
	if bytes.Equal(bytes.TrimSpace(data), []byte("null")) {
		return nil, nil
	}
	raw, err := decodeObject(data, "pool restart failure")
	if err != nil {
		return nil, err
	}
	expected := map[string]bool{"stage": true, "message": true, "log_path": true, "exit_evidence": true}
	for key := range raw {
		if !expected[key] {
			return nil, fmt.Errorf("pool restart failure contains unknown field %q", key)
		}
	}
	for _, key := range []string{"stage", "message"} {
		if value, ok := raw[key]; !ok || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return nil, fmt.Errorf("pool restart failure requires %s", key)
		}
	}
	for _, key := range []string{"log_path", "exit_evidence"} {
		if value, ok := raw[key]; ok && bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return nil, fmt.Errorf("pool restart failure %s must not be null", key)
		}
	}
	var failure restartFailure
	if err := json.Unmarshal(data, &failure); err != nil {
		return nil, err
	}
	return &failure, nil
}

func compactPoolRestartRecord(record poolRestartRecord) poolRestartRecord {
	record.Probe = nil
	return record
}

func (s *server) readPoolRestartRecordSummaryCached(path, pool string) (poolRestartRecord, bool, error) {
	if s.poolRestartSummaryCache == nil {
		s.poolRestartSummaryCache = map[string]poolRestartSummaryCacheEntry{}
	}
	info, err := os.Stat(path)
	if err != nil {
		delete(s.poolRestartSummaryCache, path)
		if os.IsNotExist(err) {
			return poolRestartRecord{}, false, nil
		}
		return poolRestartRecord{}, false, fmt.Errorf("stat pool restart record %s: %w", path, err)
	}
	if cached, ok := s.poolRestartSummaryCache[path]; ok && os.SameFile(cached.info, info) && cached.info.Size() == info.Size() && cached.info.ModTime().Equal(info.ModTime()) {
		return cached.record, cached.present, cached.err
	}
	record, present, readErr := readPoolRestartRecord(path, pool)
	compact := compactPoolRestartRecord(record)
	s.poolRestartSummaryCache[path] = poolRestartSummaryCacheEntry{info: info, record: compact, present: present, err: readErr}
	return compact, present, readErr
}

// sortedPoolRestartMembers returns a defensive canonical member copy for
// callers constructing a record at a lifecycle boundary.
