package client

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"millstrand-strand-cli/internal/config"
)

func TestReadMillMetadataRejectsMissingMalformedAndMismatched(t *testing.T) {
	xdg := filepath.Join(t.TempDir(), "state")
	t.Setenv("XDG_STATE_HOME", xdg)
	if _, err := ReadMillMetadata(); err == nil || !strings.Contains(err.Error(), "no running mill") {
		t.Fatalf("expected missing metadata error, got %v", err)
	}
	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	metadataPath := filepath.Join(root, config.MillMetadataFileName)
	if err := os.WriteFile(metadataPath, []byte("{"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadMillMetadata(); err == nil || !strings.Contains(err.Error(), "malformed mill metadata") {
		t.Fatalf("expected malformed metadata error, got %v", err)
	}

	writeMillMetadata(t, metadataPath, MillMetadata{ProtocolVersion: MillProtocolVersion, PID: os.Getpid(), MillID: "mill-test", StateRoot: filepath.Join(root, "other"), SocketPath: filepath.Join(root, config.MillSocketFileName), StartedAt: time.Now().UTC().Format(time.RFC3339Nano)})
	if _, err := ReadMillMetadata(); err == nil || !strings.Contains(err.Error(), "state root mismatch") {
		t.Fatalf("expected state root mismatch, got %v", err)
	}

	writeMillMetadata(t, metadataPath, MillMetadata{ProtocolVersion: MillProtocolVersion, PID: os.Getpid(), MillID: "mill-test", StateRoot: root, SocketPath: filepath.Join(root, "other.sock"), StartedAt: time.Now().UTC().Format(time.RFC3339Nano)})
	if _, err := ReadMillMetadata(); err == nil || !strings.Contains(err.Error(), "socket mismatch") {
		t.Fatalf("expected socket mismatch, got %v", err)
	}

	writeMillMetadata(t, metadataPath, MillMetadata{ProtocolVersion: MillProtocolVersion, PID: 0, MillID: "mill-test", StateRoot: root, SocketPath: filepath.Join(root, config.MillSocketFileName), StartedAt: time.Now().UTC().Format(time.RFC3339Nano)})
	if _, err := ReadMillMetadata(); err == nil || !strings.Contains(err.Error(), "missing required fields [pid]") {
		t.Fatalf("expected missing required fields naming pid, got %v", err)
	}

	writeMillMetadata(t, metadataPath, MillMetadata{ProtocolVersion: MillProtocolVersion, PID: -1, MillID: "mill-test", StateRoot: root, SocketPath: filepath.Join(root, config.MillSocketFileName), StartedAt: time.Now().UTC().Format(time.RFC3339Nano)})
	if _, err := ReadMillMetadata(); err == nil || !strings.Contains(err.Error(), "stale mill metadata") {
		t.Fatalf("expected stale metadata, got %v", err)
	}
}

func TestReadMillMetadataProtocolMismatchNamesVersionsAndSkipsStartRemedy(t *testing.T) {
	xdg := filepath.Join(t.TempDir(), "state")
	t.Setenv("XDG_STATE_HOME", xdg)
	root, err := config.StateRoot()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	metadataPath := filepath.Join(root, config.MillMetadataFileName)

	// A live pid with an older protocol version is skew, not staleness: the
	// error must name both protocol versions, both build identities, and the
	// metadata path, and must not suggest `mill start` while a mill runs.
	writeMillMetadata(t, metadataPath, MillMetadata{ProtocolVersion: MillProtocolVersion - 1, PID: os.Getpid(), MillID: "mill-test", StateRoot: root, SocketPath: filepath.Join(root, config.MillSocketFileName), StartedAt: time.Now().UTC().Format(time.RFC3339Nano)})
	_, err = ReadMillMetadata()
	if err == nil || !errors.Is(err, ErrMillProtocolMismatch) {
		t.Fatalf("expected ErrMillProtocolMismatch, got %v", err)
	}
	for _, want := range []string{
		fmt.Sprintf("v%d", MillProtocolVersion-1),
		fmt.Sprintf("v%d", MillProtocolVersion),
		metadataPath,
		"build unstamped",
		"rebuild strand",
	} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("expected mismatch error to contain %q, got %v", want, err)
		}
	}
	if strings.Contains(err.Error(), "start one with: mill start") {
		t.Fatalf("mismatch error must not carry the mill start remedy, got %v", err)
	}

	// A stamped writing mill surfaces its build id in the skew message.
	writeMillMetadata(t, metadataPath, MillMetadata{ProtocolVersion: MillProtocolVersion - 1, PID: os.Getpid(), MillID: "mill-test", StateRoot: root, SocketPath: filepath.Join(root, config.MillSocketFileName), StartedAt: time.Now().UTC().Format(time.RFC3339Nano), MillBuild: "abc1234"})
	_, err = ReadMillMetadata()
	if err == nil || !strings.Contains(err.Error(), "build abc1234") {
		t.Fatalf("expected mismatch error naming mill build abc1234, got %v", err)
	}
}

func writeMillMetadata(t *testing.T, path string, metadata MillMetadata) {
	t.Helper()
	b, err := json.Marshal(metadata)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, b, 0o644); err != nil {
		t.Fatal(err)
	}
}
