package client

import (
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"testing"
)

func TestProtocolVersionMatchesJVM(t *testing.T) {
	_, testFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate client test source")
	}
	protocolFile := filepath.Join(filepath.Dir(testFile), "..", "..", "..", "src", "millstrand", "core", "weaver", "protocol.clj")
	source, err := os.ReadFile(protocolFile)
	if err != nil {
		t.Fatalf("read JVM protocol version: %v", err)
	}
	match := regexp.MustCompile(`(?ms)\(def version\s+"[^"]*"\s+([0-9]+)\)`).FindSubmatch(source)
	if len(match) != 2 {
		t.Fatalf("find JVM protocol version in %s", protocolFile)
	}
	jvmVersion, err := strconv.Atoi(string(match[1]))
	if err != nil {
		t.Fatalf("parse JVM protocol version: %v", err)
	}
	if ProtocolVersion != jvmVersion {
		t.Fatalf("Go protocol version %d differs from JVM protocol version %d; bump both atomically", ProtocolVersion, jvmVersion)
	}
}

func TestResponseErrorDetailsDoNotHTMLEscape(t *testing.T) {
	err := (&ResponseError{
		Type:    "domain",
		Code:    "op/usage",
		Message: "invalid invocation",
		Details: map[string]any{"usage": "strand kanban <usage>"},
	}).Error()
	if !strings.Contains(err, `details={"usage":"strand kanban <usage>"}`) {
		t.Fatalf("details JSON should preserve angle brackets, got %q", err)
	}
	if strings.Contains(err, `\\u003c`) || strings.Contains(err, `\\u003e`) {
		t.Fatalf("details JSON must not HTML-escape angle brackets, got %q", err)
	}
}

func TestValidateStorageIdentity(t *testing.T) {
	path := func(s string) *string { return &s }
	cases := []struct {
		name    string
		m       Metadata
		wantErr bool
	}{
		{"file consistent", Metadata{DatabaseKind: "sqlite-file", DatabaseLabel: "/a/b.sqlite", DatabasePath: path("/a/b.sqlite")}, false},
		{"file label mismatch", Metadata{DatabaseKind: "sqlite-file", DatabaseLabel: "/a", DatabasePath: path("/b")}, true},
		{"file null path", Metadata{DatabaseKind: "sqlite-file", DatabaseLabel: "/a"}, true},
		{"file empty path", Metadata{DatabaseKind: "sqlite-file", DatabaseLabel: "", DatabasePath: path("")}, true},
		{"memory consistent", Metadata{DatabaseKind: "sqlite-memory", DatabaseLabel: "sqlite-memory:x"}, false},
		{"memory with fake path", Metadata{DatabaseKind: "sqlite-memory", DatabaseLabel: "x", DatabasePath: path("/a")}, true},
		{"memory with empty-string path", Metadata{DatabaseKind: "sqlite-memory", DatabaseLabel: "x", DatabasePath: path("")}, true},
		{"memory missing label", Metadata{DatabaseKind: "sqlite-memory"}, true},
		{"memory blank label", Metadata{DatabaseKind: "sqlite-memory", DatabaseLabel: "   "}, true},
		{"unknown kind", Metadata{DatabaseKind: "postgres", DatabaseLabel: "x"}, true},
		{"missing kind", Metadata{DatabaseLabel: "x", DatabasePath: path("/a")}, true},
	}
	for _, c := range cases {
		if err := ValidateStorageIdentity(c.m); (err != nil) != c.wantErr {
			t.Fatalf("%s: got err=%v wantErr=%v", c.name, err, c.wantErr)
		}
	}
}
