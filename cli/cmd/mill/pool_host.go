package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"millstrand-strand-cli/internal/config"
)

const (
	poolLaunchFormat = "millstrand.jvm-pool-launch/v1"
	poolReadyFormat  = "millstrand.jvm-pool-ready/v1"
)

// poolMember is Mill's frozen view of one member. The host, rather than a
// member, owns the command and its lifecycle state.
type poolMember struct {
	World        config.World
	SourceCWD    string
	Name         string
	WeaverID     string
	GenerationID string
	MemberBasis  string
	Identity     weaverIdentity
}

type weaverHost struct {
	Pool             string
	HostID           string
	HostGenerationID string
	MembershipRev    string
	cmd              *exec.Cmd
	PID              int
	LaunchToken      string
	Members          []poolMember
	Allowances       map[string]config.World
	Admission        *sync.RWMutex
	ReadyPath        string
	ManifestPath     string
	LogPath          string
	Live             bool
}

type poolLaunchManifest struct {
	Format             string             `json:"format"`
	JVMPool            string             `json:"jvm_pool"`
	HostID             string             `json:"host_id"`
	HostGenerationID   string             `json:"host_generation_id"`
	MembershipRevision string             `json:"membership_revision"`
	MillstrandSource   string             `json:"millstrand_source"`
	MillstrandVersion  string             `json:"millstrand_version"`
	Members            []poolLaunchMember `json:"members"`
}

type poolLaunchMember struct {
	ConfigDir            string `json:"config_dir"`
	SourceCWD            string `json:"source_cwd"`
	StateDir             string `json:"state_dir"`
	DataDir              string `json:"data_dir"`
	Name                 string `json:"name"`
	WeaverID             string `json:"weaver_id"`
	GenerationID         string `json:"generation_id"`
	DependencyDiagnostic string `json:"dependency_diagnostic"`
}

type poolReadyMarker struct {
	Format             string            `json:"format"`
	JVMPool            string            `json:"jvm_pool"`
	HostID             string            `json:"host_id"`
	HostGenerationID   string            `json:"host_generation_id"`
	MembershipRevision string            `json:"membership_revision"`
	PID                int               `json:"pid"`
	BasisFingerprint   string            `json:"basis_fingerprint"`
	Members            []poolReadyMember `json:"members"`
}

type poolReadyMember struct {
	ConfigDir    string `json:"config_dir"`
	WeaverID     string `json:"weaver_id"`
	GenerationID string `json:"generation_id"`
	SocketPath   string `json:"socket_path"`
	NREPLHost    string `json:"nrepl_host"`
	NREPLPort    int    `json:"nrepl_port"`
}

func poolHostForConfigLocked(s *server, configDir string) *weaverHost {
	if s.poolMembers == nil {
		return nil
	}
	return s.poolMembers[configDir]
}

func (s *server) ensurePoolMapsLocked() {
	if s.poolHosts == nil {
		s.poolHosts = map[string]*weaverHost{}
	}
	if s.poolMembers == nil {
		s.poolMembers = map[string]*weaverHost{}
	}
	if s.poolAdmissionLocks == nil {
		s.poolAdmissionLocks = map[string]*sync.Mutex{}
	}
	if s.poolStartClaims == nil {
		s.poolStartClaims = map[string]chan struct{}{}
	}
}

func poolHostKey(pool string) string { return "pool:" + pool }

func (s *server) poolAdmissionLock(pool string) *sync.Mutex {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ensurePoolMapsLocked()
	key := poolHostKey(pool)
	if lock := s.poolAdmissionLocks[key]; lock != nil {
		return lock
	}
	lock := &sync.Mutex{}
	s.poolAdmissionLocks[key] = lock
	return lock
}

func writePoolLaunchManifest(path string, manifest poolLaunchManifest) error {
	if err := validatePoolLaunchManifest(manifest); err != nil {
		return err
	}
	return atomicPoolJSON(path, manifest)
}

func readPoolLaunchManifest(path string) (poolLaunchManifest, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return poolLaunchManifest{}, err
	}
	decoder := json.NewDecoder(strings.NewReader(string(b)))
	decoder.DisallowUnknownFields()
	var manifest poolLaunchManifest
	if err := decoder.Decode(&manifest); err != nil {
		return poolLaunchManifest{}, fmt.Errorf("decode JVM pool launch manifest: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return poolLaunchManifest{}, errors.New("JVM pool launch manifest contains trailing JSON")
	}
	if err := validatePoolLaunchManifest(manifest); err != nil {
		return poolLaunchManifest{}, err
	}
	return manifest, nil
}

func atomicPoolJSON(path string, value any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".pool-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if _, err = tmp.Write(append(b, '\n')); err != nil {
		_ = tmp.Close()
		return err
	}
	if err = tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

func validatePoolLaunchManifest(m poolLaunchManifest) error {
	if m.Format != poolLaunchFormat || strings.TrimSpace(m.JVMPool) == "" || strings.TrimSpace(m.HostID) == "" || strings.TrimSpace(m.HostGenerationID) == "" || strings.TrimSpace(m.MembershipRevision) == "" || strings.TrimSpace(m.MillstrandSource) == "" || strings.TrimSpace(m.MillstrandVersion) == "" {
		return errors.New("invalid JVM pool launch manifest identity")
	}
	if !filepath.IsAbs(m.MillstrandSource) || filepath.Clean(m.MillstrandSource) != m.MillstrandSource {
		return errors.New("JVM pool launch millstrand_source must be canonical absolute path")
	}
	if len(m.Members) == 0 {
		return errors.New("JVM pool launch manifest must contain members")
	}
	seen := map[string]bool{}
	for i, member := range m.Members {
		if member.ConfigDir == "" || !filepath.IsAbs(member.ConfigDir) || filepath.Clean(member.ConfigDir) != member.ConfigDir || member.SourceCWD == "" || !filepath.IsAbs(member.SourceCWD) || member.StateDir == "" || member.DataDir == "" || member.Name == "" || member.WeaverID == "" || member.GenerationID == "" || member.DependencyDiagnostic == "" {
			return fmt.Errorf("invalid JVM pool launch member %d", i)
		}
		for label, path := range map[string]string{"config_dir": member.ConfigDir, "source_cwd": member.SourceCWD, "state_dir": member.StateDir, "data_dir": member.DataDir, "dependency_diagnostic": member.DependencyDiagnostic} {
			if !filepath.IsAbs(path) || filepath.Clean(path) != path {
				return fmt.Errorf("invalid JVM pool launch member %d %s path", i, label)
			}
		}
		if seen[member.ConfigDir] {
			return fmt.Errorf("duplicate JVM pool launch member %q", member.ConfigDir)
		}
		seen[member.ConfigDir] = true
		if i > 0 && m.Members[i-1].ConfigDir >= member.ConfigDir {
			return errors.New("JVM pool launch members must be sorted by config_dir")
		}
	}
	return nil
}

func readPoolReady(path string) (poolReadyMarker, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return poolReadyMarker{}, err
	}
	var marker poolReadyMarker
	decoder := json.NewDecoder(strings.NewReader(string(b)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&marker); err != nil {
		return poolReadyMarker{}, fmt.Errorf("decode JVM pool ready marker: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return poolReadyMarker{}, errors.New("JVM pool ready marker contains trailing JSON")
	}
	if err := validatePoolReady(marker); err != nil {
		return poolReadyMarker{}, err
	}
	return marker, nil
}

func validatePoolReady(m poolReadyMarker) error {
	if m.Format != poolReadyFormat || strings.TrimSpace(m.JVMPool) == "" || strings.TrimSpace(m.HostID) == "" || strings.TrimSpace(m.HostGenerationID) == "" || strings.TrimSpace(m.MembershipRevision) == "" || m.PID <= 0 || !validBasisFingerprint(m.BasisFingerprint) || len(m.Members) == 0 {
		return errors.New("invalid JVM pool ready marker")
	}
	seen := map[string]bool{}
	for i, member := range m.Members {
		if member.ConfigDir == "" || member.WeaverID == "" || member.GenerationID == "" || member.SocketPath == "" || member.NREPLHost == "" || member.NREPLPort <= 0 || seen[member.ConfigDir] {
			return fmt.Errorf("invalid JVM pool ready member %d", i)
		}
		seen[member.ConfigDir] = true
		if i > 0 && m.Members[i-1].ConfigDir >= member.ConfigDir {
			return errors.New("JVM pool ready members must be sorted by config_dir")
		}
	}
	return nil
}

func validatePoolAdmission(host *weaverHost, marker poolReadyMarker, statuses map[string]map[string]any) error {
	if host == nil {
		return errors.New("JVM pool host is missing")
	}
	if marker.JVMPool != host.Pool || marker.HostID != host.HostID || marker.HostGenerationID != host.HostGenerationID || marker.MembershipRevision != host.MembershipRev || marker.PID != host.PID || len(marker.Members) != len(host.Members) {
		return errors.New("JVM pool ready marker does not match host manifest")
	}
	for i, expected := range host.Members {
		actual := marker.Members[i]
		if actual.ConfigDir != expected.World.ConfigDir || actual.WeaverID != expected.WeaverID || actual.GenerationID != expected.GenerationID {
			return fmt.Errorf("JVM pool ready identity mismatch for %s", expected.World.ConfigDir)
		}
		status := statuses[expected.World.ConfigDir]
		if status == nil {
			return fmt.Errorf("JVM pool member %s has no status", expected.World.ConfigDir)
		}
		identity, err := identityFromStatus(status)
		if err != nil {
			return fmt.Errorf("JVM pool member %s identity: %w", expected.World.ConfigDir, err)
		}
		if identity.PID != host.PID || identity.WeaverID != expected.WeaverID || identity.GenerationID != expected.GenerationID || identity.ConfigDir != expected.World.ConfigDir || !processAlive(identity.PID) {
			return fmt.Errorf("JVM pool member %s identity does not prove host pid", expected.World.ConfigDir)
		}
		if status["jvm_pool"] != host.Pool || status["host_id"] != host.HostID || status["host_generation_id"] != host.HostGenerationID || status["basis_fingerprint"] != marker.BasisFingerprint || status["member_basis_fingerprint"] == "" {
			return fmt.Errorf("JVM pool member %s host or basis metadata mismatch", expected.World.ConfigDir)
		}
		if actual.SocketPath != identity.Socket {
			return fmt.Errorf("JVM pool member %s socket identity mismatch", expected.World.ConfigDir)
		}
	}
	return nil
}

func poolMembersSorted(members []poolMember) []poolMember {
	result := append([]poolMember(nil), members...)
	sort.Slice(result, func(i, j int) bool { return result[i].World.ConfigDir < result[j].World.ConfigDir })
	return result
}
