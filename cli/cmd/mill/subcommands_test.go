package main

import (
	"testing"

	"millstrand-strand-cli/internal/client"
)

func TestValidateInitRequestRejectsStealthExplicitWorkspace(t *testing.T) {
	err := validateInitRequest(client.MillWorldRequest{ConfigDir: t.TempDir(), Stealth: true})
	if err == nil {
		t.Fatal("stealth request with explicit workspace was accepted")
	}
	if err := validateInitRequest(client.MillWorldRequest{Stealth: true}); err != nil {
		t.Fatalf("repo-local stealth request rejected: %v", err)
	}
}
