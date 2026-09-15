package main

import (
	"encoding/json"
	"net"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
)

func TestMillRejectsUnsupportedProtocolAndOwnedOperation(t *testing.T) {
	for _, tc := range []struct {
		name            string
		protocol        int
		operation, code string
	}{
		{"unsupported protocol", client.MillProtocolVersion + 1, "ping", "mill/protocol"},
		{"unsupported Mill operation", client.MillProtocolVersion, "future-mill-operation", "mill/unknown-operation"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := server{meta: client.MillMetadata{MillID: "mill-test"}}
			caller, peer := net.Pipe()
			defer func() { _ = caller.Close() }()
			go s.handle(peer)
			_ = caller.SetDeadline(time.Now().Add(5 * time.Second))
			req := client.MillRequest{ProtocolVersion: tc.protocol, RequestID: "compatibility", MillID: "mill-test", Operation: tc.operation}
			if err := json.NewEncoder(caller).Encode(req); err != nil {
				t.Fatal(err)
			}
			var response client.MillResponse
			if err := json.NewDecoder(caller).Decode(&response); err != nil {
				t.Fatal(err)
			}
			if response.OK || response.Error == nil || response.Error.Code != tc.code {
				t.Fatalf("unexpected boundary response: %#v", response)
			}
		})
	}
}
