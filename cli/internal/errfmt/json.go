package errfmt

import (
	"fmt"
	"io"
	"sort"
)

// jsonError is the shape stderr carries in JSON mode: the envelope's own four
// fields, in a fixed order, with no prose folded in and nothing added.
type jsonError struct {
	Type    string         `json:"type"`
	Code    string         `json:"code"`
	Message string         `json:"message"`
	Details map[string]any `json:"details"`
}

// renderJSON writes e as one line a consumer can decode without scraping. The
// fields are always present, so a consumer never has to test for absence:
// `details` is an object even when the error carried none.
func renderJSON(w io.Writer, e Error) {
	details := e.Details
	if details == nil {
		details = map[string]any{}
	}
	encoded, err := encodeJSONNoEscape(jsonError{Type: e.Type, Code: e.Code, Message: e.Message, Details: details})
	if err == nil {
		_, _ = fmt.Fprintln(w, string(encoded))
		return
	}
	// Details the weaver stringified into something unencodable must not cost the
	// consumer its line: keep the rest of the envelope, and say both why the
	// details went and which keys they were, so the loss is diagnosable.
	salvaged, salvageErr := encodeJSONNoEscape(jsonError{
		Type:    e.Type,
		Code:    e.Code,
		Message: e.Message,
		Details: map[string]any{"errfmt/unrenderable-details": err.Error(), "errfmt/dropped-keys": detailKeys(details)},
	})
	if salvageErr == nil {
		_, _ = fmt.Fprintln(w, string(salvaged))
		return
	}
	// Nothing about this error will encode, which leaves saying so loudly in the
	// one rendering that cannot fail. A consumer's decode fails on this line, and
	// that is the honest outcome — writing nothing at all would look like success.
	_, _ = fmt.Fprintln(w, "error:", e.PlainMessage())
}

// detailKeys names the details a failed encode took with it, sorted so the line
// is stable.
func detailKeys(details map[string]any) []string {
	keys := make([]string, 0, len(details))
	for key := range details {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}
