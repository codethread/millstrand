package errfmt

import (
	"bytes"
	"errors"
	"testing"
)

// Plain output is the SPEC-002.C4 contract surface and what every existing
// script and test scrapes, so these are golden byte-for-byte.
func TestRenderPlainIsGolden(t *testing.T) {
	cases := []struct {
		name string
		in   Error
		want string
	}{
		{
			name: "domain with code and details",
			in: Error{
				Type:    "domain",
				Code:    "op/not-found",
				Message: "Operation not found",
				Details: map[string]any{"available": []any{"add", "list"}},
			},
			want: `error: weaver domain error (op/not-found): Operation not found (available: add, list) details={"available":["add","list"]}` + "\n",
		},
		{
			name: "protocol without a code",
			in:   Error{Type: "protocol", Message: "identity mismatch", Details: map[string]any{"detail": "mill/identity"}},
			want: `error: weaver protocol error: identity mismatch details={"detail":"mill/identity"}` + "\n",
		},
		{
			name: "transport with a canonical query",
			in: Error{
				Type:    "transport",
				Code:    "query/not-found",
				Message: "no such query",
				Details: map[string]any{"canonical-query": "agent-failures"},
			},
			want: `error: weaver transport error (query/not-found): no such query: agent-failures details={"canonical-query":"agent-failures"}` + "\n",
		},
		{
			name: "local invocation error",
			in:   LocalError(TypeLocal, errors.New("unknown flag: --nope"), nil),
			want: "error: unknown flag: --nope\n",
		},
		{
			// Locally raised but transport by taxonomy: no envelope stands behind
			// it, so the `weaver ... error` prefix would be a lie.
			name: "unreachable mill",
			in:   LocalError(TypeTransport, errors.New("mill socket unreachable; start one with: mill start"), []string{"kanban", "board"}),
			want: "error: mill socket unreachable; start one with: mill start\n",
		},
		{
			// The single code this renderer switches on: its bare message is the
			// `mill init` remediation, and no other rendering may leak in.
			name: "database not initialized carve-out",
			in: Error{
				Type:    "domain",
				Code:    databaseNotInitialized,
				Message: "workspace has no database; run: mill init",
				Details: map[string]any{"workspace": "/tmp/ws"},
			},
			want: "error: workspace has no database; run: mill init\n",
		},
		{
			name: "empty message and no details",
			in:   Error{Type: "transport", Code: "transport/server-error"},
			want: "error: weaver transport error (transport/server-error): \n",
		},
	}
	for _, c := range cases {
		var out bytes.Buffer
		Render(&out, c.in, Plain)
		if out.String() != c.want {
			t.Fatalf("%s:\n got %q\nwant %q", c.name, out.String(), c.want)
		}
	}
}

func TestPlainDetailsAreByteFaithful(t *testing.T) {
	var out bytes.Buffer
	Render(&out, Error{
		Type:    "domain",
		Code:    "op/usage",
		Message: "invalid invocation",
		Details: map[string]any{"usage": "strand kanban <usage>"},
	}, Plain)
	want := `error: weaver domain error (op/usage): invalid invocation details={"usage":"strand kanban <usage>"}` + "\n"
	if out.String() != want {
		t.Fatalf("angle brackets must survive unescaped:\n got %q\nwant %q", out.String(), want)
	}
}
