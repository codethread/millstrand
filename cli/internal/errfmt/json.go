package errfmt

import (
	"fmt"
	"io"
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
	if err != nil {
		// Details the weaver stringified into something unencodable must not cost
		// the consumer its line: keep the envelope and say what was dropped.
		encoded, err = encodeJSONNoEscape(jsonError{
			Type:    e.Type,
			Code:    e.Code,
			Message: e.Message,
			Details: map[string]any{"errfmt/unrenderable-details": err.Error()},
		})
		if err != nil {
			return
		}
	}
	_, _ = fmt.Fprintln(w, string(encoded))
}
