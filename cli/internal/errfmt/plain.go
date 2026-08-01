package errfmt

import (
	"fmt"
	"strings"
)

// PlainMessage flattens the error into the one line SPEC-002.C4 pins: the
// message, the `available` and `canonical-query` details folded into prose, and
// the whole details map appended as byte-faithful JSON. It carries no `error: `
// prefix — Render adds that — so *client.ResponseError can return it as its Go
// error string.
//
// A local error is its message and nothing else, which is what a bad invocation
// has always printed.
func (e Error) PlainMessage() string {
	if e.Type == TypeLocal {
		return e.Message
	}
	message := e.Message
	if query, ok := e.Details["canonical-query"].(string); ok && query != "" {
		message = fmt.Sprintf("%s: %s", message, query)
	}
	if names := stringList(e.Details["available"]); len(names) > 0 {
		message = fmt.Sprintf("%s (available: %s)", message, strings.Join(names, ", "))
	}
	if e.Code == databaseNotInitialized {
		return message
	}
	// ex-data details are the machine-readable half of a fail-loudly error;
	// agents scripting the CLI need them, so append them as compact JSON
	if len(e.Details) > 0 {
		if encoded, err := encodeJSONNoEscape(e.Details); err == nil {
			message = fmt.Sprintf("%s details=%s", message, encoded)
		}
	}
	if e.Code != "" {
		return fmt.Sprintf("weaver %s error (%s): %s", e.Type, e.Code, message)
	}
	return fmt.Sprintf("weaver %s error: %s", e.Type, message)
}
