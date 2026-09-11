// Package jvmpool owns the durable, process-independent membership record for
// named JVM pools. It deliberately does not discover workspaces or inspect
// automatic-start records.
package jvmpool

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

const (
	MembershipFormat = "millstrand.jvm-pool-membership/v1"
	MembershipFile   = "membership.json"
)

// Member is one resolved, canonical workspace registered in a pool. Pool
// names are opaque: a valid name is preserved byte-for-byte.
type Member struct {
	ConfigDir string `json:"config_dir"`
	SourceCWD string `json:"source_cwd"`
	JVMPool   string `json:"jvm_pool"`
}

// Document is the closed on-disk membership envelope.
type Document struct {
	Format   string   `json:"format"`
	Revision string   `json:"revision"`
	Members  []Member `json:"members"`
}

// PoolSnapshot is the registered set for one exact pool name. Members are
// sorted bytewise by ConfigDir and Revision identifies the document read.
type PoolSnapshot struct {
	Pool     string
	Revision string
	Members  []Member
}

// EffectiveConfig is the minimum resolved configuration needed to validate a
// registered member. A resolver may leave ConfigDir and SourceCWD empty when
// it only supplies the effective pool value.
type EffectiveConfig struct {
	ConfigDir string
	SourceCWD string
	JVMPool   *string
}

// Resolver reloads one effective workspace configuration. It is supplied by
// the lifecycle owner so this package neither knows config file locations nor
// scans projects.
type Resolver func(configDir string) (EffectiveConfig, error)

// LiveOwner describes the currently admitted host owning one workspace.
// HostID is diagnostic; Pool is the ownership decision used by reconciliation.
type LiveOwner struct {
	Pool   string
	HostID string
}

// LiveOwnership is caller-supplied live state, keyed by canonical ConfigDir.
type LiveOwnership map[string]LiveOwner

// ErrStopRequired marks a requested membership move or removal that would
// conflict with a live host. Its text contains the public lifecycle error code.
var ErrStopRequired = errors.New("mill/jvm-pool-stop-required")

// StopRequiredError reports the workspace and live host that must be stopped
// before membership can change.
type StopRequiredError struct {
	ConfigDir string
	LivePool  string
	HostID    string
}

func (e *StopRequiredError) Error() string {
	if e.HostID == "" {
		return fmt.Sprintf("%s: %s is owned by live pool %q", ErrStopRequired, e.ConfigDir, e.LivePool)
	}
	return fmt.Sprintf("%s: %s is owned by live pool %q (host %s)", ErrStopRequired, e.ConfigDir, e.LivePool, e.HostID)
}

func (e *StopRequiredError) Unwrap() error { return ErrStopRequired }

// Mutation describes a reconciliation result. An unchanged result means the
// registry bytes were not rewritten and Revision is the existing revision.
type Mutation struct {
	Changed  bool
	Revision string
	Members  []Member
}

// Registry is a membership file with an explicit path and an in-process
// mutation mutex. Separate processes should serialize access at their Mill
// lifecycle boundary before sharing one registry path.
type Registry struct {
	path string
	mu   sync.Mutex
}

// Open opens the registry beneath an explicit state root. The state root is
// not created until the first successful mutation.
func Open(stateRoot string) (*Registry, error) {
	root, err := canonicalPath("state root", stateRoot)
	if err != nil {
		return nil, err
	}
	return &Registry{path: filepath.Join(root, "jvm-pools", MembershipFile)}, nil
}

// New is an alias for Open for lifecycle callers that construct dependencies.
func New(stateRoot string) (*Registry, error) { return Open(stateRoot) }

// OpenPath opens an explicitly selected membership file. This is useful for
// tests and callers that already own state-root path resolution.
func OpenPath(path string) (*Registry, error) {
	path, err := canonicalPath("membership path", path)
	if err != nil {
		return nil, err
	}
	return &Registry{path: path}, nil
}

// Path returns the explicit membership file path.
func (r *Registry) Path() string { return r.path }

// Read loads and validates the current document. An absent file is an
// uninitialized empty registry; malformed existing data fails loudly.
func (r *Registry) Read() (Document, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.readLocked()
}

func (r *Registry) readLocked() (Document, error) {
	b, err := os.ReadFile(r.path)
	if os.IsNotExist(err) {
		return Document{Format: MembershipFormat, Members: []Member{}}, nil
	}
	if err != nil {
		return Document{}, fmt.Errorf("read JVM pool membership %s: %w", r.path, err)
	}
	var doc Document
	decoder := json.NewDecoder(strings.NewReader(string(b)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&doc); err != nil {
		return Document{}, fmt.Errorf("malformed JVM pool membership %s: %w", r.path, err)
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err == nil {
			return Document{}, fmt.Errorf("malformed JVM pool membership %s: trailing JSON", r.path)
		}
		return Document{}, fmt.Errorf("malformed JVM pool membership %s: %w", r.path, err)
	}
	if err := validateDocument(doc, true); err != nil {
		return Document{}, fmt.Errorf("invalid JVM pool membership %s: %w", r.path, err)
	}
	return doc, nil
}

// Snapshot returns the exact registered rows for pool, preserving the
// document revision and canonical ordering.
func (r *Registry) Snapshot(pool string) (PoolSnapshot, error) {
	if err := validatePoolName(pool); err != nil {
		return PoolSnapshot{}, err
	}
	doc, err := r.Read()
	if err != nil {
		return PoolSnapshot{}, err
	}
	rows := make([]Member, 0)
	for _, member := range doc.Members {
		if member.JVMPool == pool {
			rows = append(rows, member)
		}
	}
	return PoolSnapshot{Pool: pool, Revision: doc.Revision, Members: rows}, nil
}

// ValidatePool reloads every registered member selected by pool and checks the
// effective configuration against its row. It returns no partial snapshot:
// any missing, invalid, or mismatched member causes a loud error.
func (r *Registry) ValidatePool(pool string, resolve Resolver) (PoolSnapshot, error) {
	if err := validatePoolName(pool); err != nil {
		return PoolSnapshot{}, err
	}
	if resolve == nil {
		return PoolSnapshot{}, errors.New("JVM pool effective-config resolver is required")
	}
	doc, err := r.Read()
	if err != nil {
		return PoolSnapshot{}, err
	}
	rows := make([]Member, 0)
	var failures []error
	for _, member := range doc.Members {
		if member.JVMPool != pool {
			continue
		}
		rows = append(rows, member)
		effective, resolveErr := resolve(member.ConfigDir)
		if resolveErr != nil {
			failures = append(failures, fmt.Errorf("validate JVM pool member %s: %w", member.ConfigDir, resolveErr))
			continue
		}
		if effective.ConfigDir != "" && effective.ConfigDir != member.ConfigDir {
			failures = append(failures, fmt.Errorf("validate JVM pool member %s: effective config directory is %q", member.ConfigDir, effective.ConfigDir))
		}
		if effective.SourceCWD != "" && effective.SourceCWD != member.SourceCWD {
			failures = append(failures, fmt.Errorf("validate JVM pool member %s: effective source cwd is %q", member.ConfigDir, effective.SourceCWD))
		}
		if effective.JVMPool == nil {
			failures = append(failures, fmt.Errorf("validate JVM pool member %s: effective JVMPool is null or omitted", member.ConfigDir))
			continue
		}
		if strings.TrimSpace(*effective.JVMPool) == "" {
			failures = append(failures, fmt.Errorf("validate JVM pool member %s: effective JVMPool is blank", member.ConfigDir))
			continue
		}
		if *effective.JVMPool != member.JVMPool {
			failures = append(failures, fmt.Errorf("validate JVM pool member %s: effective JVMPool %q does not match registered pool %q", member.ConfigDir, *effective.JVMPool, member.JVMPool))
		}
	}
	if len(failures) != 0 {
		return PoolSnapshot{}, errors.Join(failures...)
	}
	return PoolSnapshot{Pool: pool, Revision: doc.Revision, Members: rows}, nil
}

// Reconcile adds or updates one row in one atomic registry replacement. A
// newcomer may join a live host with the same pool (and becomes pending at the
// lifecycle layer); changing pool ownership requires the host to be stopped.
func (r *Registry) Reconcile(member Member, live LiveOwnership) (Mutation, error) {
	if err := validateMember(member); err != nil {
		return Mutation{}, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	doc, err := r.readLocked()
	if err != nil {
		return Mutation{}, err
	}
	owner, owned := live[member.ConfigDir]
	for i, current := range doc.Members {
		if current.ConfigDir != member.ConfigDir {
			continue
		}
		if owned && (owner.Pool != member.JVMPool || current.JVMPool != owner.Pool) {
			return Mutation{}, stopRequired(member.ConfigDir, owner)
		}
		if current == member {
			return mutationFrom(doc, false), nil
		}
		doc.Members[i] = member
		return r.replaceLocked(doc)
	}
	if owned && owner.Pool != member.JVMPool {
		return Mutation{}, stopRequired(member.ConfigDir, owner)
	}
	doc.Members = append(doc.Members, member)
	sortMembers(doc.Members)
	return r.replaceLocked(doc)
}

// Remove deletes one row in one atomic registry replacement. A live owner
// always refuses removal and leaves the existing bytes untouched.
func (r *Registry) Remove(configDir string, live LiveOwnership) (Mutation, error) {
	if err := validateCanonicalConfigDir(configDir); err != nil {
		return Mutation{}, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	doc, err := r.readLocked()
	if err != nil {
		return Mutation{}, err
	}
	if owner, owned := live[configDir]; owned {
		return Mutation{}, stopRequired(configDir, owner)
	}
	for i, member := range doc.Members {
		if member.ConfigDir != configDir {
			continue
		}
		doc.Members = append(doc.Members[:i], doc.Members[i+1:]...)
		return r.replaceLocked(doc)
	}
	return mutationFrom(doc, false), nil
}

func (r *Registry) replaceLocked(doc Document) (Mutation, error) {
	sortMembers(doc.Members)
	revision, err := newRevision()
	if err != nil {
		return Mutation{}, fmt.Errorf("create JVM pool membership revision: %w", err)
	}
	doc.Format = MembershipFormat
	doc.Revision = revision
	if err := atomicWrite(r.path, doc); err != nil {
		return Mutation{}, err
	}
	return mutationFrom(doc, true), nil
}

func mutationFrom(doc Document, changed bool) Mutation {
	return Mutation{Changed: changed, Revision: doc.Revision, Members: append([]Member(nil), doc.Members...)}
}

func stopRequired(configDir string, owner LiveOwner) error {
	return &StopRequiredError{ConfigDir: configDir, LivePool: owner.Pool, HostID: owner.HostID}
}

func validateDocument(doc Document, persisted bool) error {
	if doc.Format != MembershipFormat {
		return fmt.Errorf("format must be %q", MembershipFormat)
	}
	if persisted && (doc.Revision == "" || !strings.HasPrefix(doc.Revision, "membership-")) {
		return errors.New("revision must be an opaque membership-* value")
	}
	if doc.Members == nil {
		return errors.New("members must be an array")
	}
	seen := make(map[string]struct{}, len(doc.Members))
	for _, member := range doc.Members {
		if err := validateMember(member); err != nil {
			return err
		}
		if _, ok := seen[member.ConfigDir]; ok {
			return fmt.Errorf("duplicate member config_dir %q", member.ConfigDir)
		}
		seen[member.ConfigDir] = struct{}{}
	}
	for i := 1; i < len(doc.Members); i++ {
		if doc.Members[i-1].ConfigDir >= doc.Members[i].ConfigDir {
			return errors.New("members must be sorted bytewise by config_dir")
		}
	}
	return nil
}

func validateMember(member Member) error {
	if err := validateCanonicalConfigDir(member.ConfigDir); err != nil {
		return err
	}
	if err := validateCanonicalPath("source_cwd", member.SourceCWD); err != nil {
		return err
	}
	return validatePoolName(member.JVMPool)
}

func validateCanonicalConfigDir(path string) error {
	return validateCanonicalPath("config_dir", path)
}

func validateCanonicalPath(label, path string) error {
	if path == "" || !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return fmt.Errorf("%s must be an absolute canonical path: %q", label, path)
	}
	return nil
}

func validatePoolName(pool string) error {
	if strings.TrimSpace(pool) == "" {
		return errors.New("jvm pool name must be non-blank")
	}
	return nil
}

func canonicalPath(label, path string) (string, error) {
	if err := validateCanonicalPath(label, path); err != nil {
		return "", err
	}
	return path, nil
}

func sortMembers(members []Member) {
	sort.Slice(members, func(i, j int) bool { return members[i].ConfigDir < members[j].ConfigDir })
}

func newRevision() (string, error) {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	// UUIDv4 bits make the opaque value recognizable to operators without
	// introducing a dependency solely for revision generation.
	raw[6] = (raw[6] & 0x0f) | 0x40
	raw[8] = (raw[8] & 0x3f) | 0x80
	encoded := fmt.Sprintf("%s-%s-%s-%s-%s", hex.EncodeToString(raw[0:4]), hex.EncodeToString(raw[4:6]), hex.EncodeToString(raw[6:8]), hex.EncodeToString(raw[8:10]), hex.EncodeToString(raw[10:16]))
	return "membership-" + encoded, nil
}

func atomicWrite(path string, doc Document) error {
	parent := filepath.Dir(path)
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return fmt.Errorf("create JVM pool membership directory: %w", err)
	}
	b, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return fmt.Errorf("encode JVM pool membership: %w", err)
	}
	b = append(b, '\n')
	tmp, err := os.CreateTemp(parent, ".membership.json-*")
	if err != nil {
		return fmt.Errorf("create temporary JVM pool membership: %w", err)
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if err := tmp.Chmod(0o644); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(b); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("write temporary JVM pool membership: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("sync temporary JVM pool membership: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close temporary JVM pool membership: %w", err)
	}
	if err := os.Rename(tmpName, path); err != nil {
		return fmt.Errorf("replace JVM pool membership: %w", err)
	}
	dir, err := os.Open(parent)
	if err != nil {
		return fmt.Errorf("open JVM pool membership directory: %w", err)
	}
	defer func() { _ = dir.Close() }()
	if err := dir.Sync(); err != nil {
		return fmt.Errorf("sync JVM pool membership directory: %w", err)
	}
	return nil
}

// ArtifactKey returns the first 32 lowercase hexadecimal SHA-256 characters
// over the exact UTF-8 bytes "millstrand-jvm-pool", a zero byte, and pool.
func ArtifactKey(pool string) string {
	h := sha256.New()
	_, _ = h.Write([]byte("millstrand-jvm-pool"))
	_, _ = h.Write([]byte{0})
	_, _ = h.Write([]byte(pool))
	return hex.EncodeToString(h.Sum(nil))[:32]
}

// ValidateArtifactKey rejects a host directory whose key does not correspond
// to its clear, exact pool name.
func ValidateArtifactKey(pool, key string) error {
	if err := validatePoolName(pool); err != nil {
		return err
	}
	if key != ArtifactKey(pool) {
		return fmt.Errorf("JVM pool artifact key %q does not match pool %q", key, pool)
	}
	return nil
}

// PoolHostDir resolves a host artifact directory beneath an explicit state
// root without touching the filesystem.
func PoolHostDir(stateRoot, pool string) (string, error) {
	root, err := canonicalPath("state root", stateRoot)
	if err != nil {
		return "", err
	}
	if err := validatePoolName(pool); err != nil {
		return "", err
	}
	return filepath.Join(root, "jvm-pools", "hosts", ArtifactKey(pool)), nil
}
