// Package errfmt is the single place a strand or mill error becomes text. It is
// a leaf package: client, dispatch, and cmd/mill map their typed values into
// Error and call Render, and nothing here imports them back.
//
// Three renderings ship. Plain is the machine-facing single line pinned by
// SPEC-002.C4 and is the default whenever the error writer is not a terminal.
// Pretty is the expanded terminal layout. JSON is the error envelope as one
// decodable line, for a caller composing over the CLI. SKEIN_ERROR_FORMAT
// overrides the detection, and is the only way to reach JSON.
package errfmt

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
)

// Mode names a rendering.
type Mode string

const (
	// Plain is today's single `error: ...` line, byte-faithful details and all.
	Plain Mode = "plain"
	// Pretty is the expanded layout, only ever selected for a terminal.
	Pretty Mode = "pretty"
	// JSON is the four-field envelope as one line, never detected: a caller
	// composing over the CLI asks for it by name.
	JSON Mode = "json"
)

// FormatEnv overrides terminal detection, and is the only route to JSON.
const FormatEnv = "SKEIN_ERROR_FORMAT"

// TypeLocal marks an error the bin raised about its own invocation. It is the
// fourth value of the client-side taxonomy and never appears on the wire, where
// the envelope stays domain|protocol|transport (SPEC-004.C24).
const TypeLocal = "cli"

// TypeTransport is the wire type a locally raised failure to reach or speak to
// the mill also carries: unreachable, stale, or skewed is transport, and only a
// bad invocation is TypeLocal (SPEC-002.C4c).
const TypeTransport = "transport"

// Codes for the errors a bin raises itself. They exist so a JSON-mode consumer
// can log one local failure apart from another; like every code outside the
// three SPEC-005.C7 pins, they are informative and free to change, and nothing
// branches on them.
const (
	// CodeInvalidInvocation is the catch-all bad command line: an unknown flag,
	// a flag missing its value, an unparseable --timeout.
	CodeInvalidInvocation = "cli/invalid-invocation"
	// CodeInvalidErrorFormat is a SKEIN_ERROR_FORMAT this bin does not accept.
	CodeInvalidErrorFormat = "cli/invalid-error-format"
	// CodePayloadUnreadable is a --payload or --stdin slot that would not load.
	CodePayloadUnreadable = "cli/payload-unreadable"
	// CodeContextUnresolved is a cwd or git context that would not derive.
	CodeContextUnresolved = "cli/context-unresolved"
	// CodeOutputUnwritable is a failure to write the bin's own stdout.
	CodeOutputUnwritable = "cli/output-unwritable"
	// CodeMillUnreachable is no mill to talk to: no metadata, stale metadata, a
	// dead pid, a refused dial. Retryable once a mill is running.
	CodeMillUnreachable = "cli/mill-unreachable"
	// CodeMillTransportFailed is a mill that was reached and then failed: a
	// dropped connection, a write that never landed.
	CodeMillTransportFailed = "cli/mill-transport-failed"
	// CodeWeaverResponseMalformed is a response frame that would not decode —
	// skew between this bin, the mill, and the weaver.
	CodeWeaverResponseMalformed = "cli/weaver-response-malformed"
)

// databaseNotInitialized is the one code this package switches on: its bare
// message is the `mill init` remediation users see, and codes are otherwise
// explicitly non-contractual (SPEC-005.C7).
const databaseNotInitialized = "database/not-initialized"

// fallbackCode carries no information — every `shared/fail!` without an explicit
// code lands here — so the pretty headline drops it.
const fallbackCode = "domain/error"

var acceptedFormats = []Mode{JSON, Plain, Pretty}

// Error is the renderer's own input shape. Call sites fill what they have; no
// field is required.
type Error struct {
	// Type is domain, protocol, or transport from the wire, or TypeLocal.
	Type string
	// Code is opaque: rendered, never branched on (the one carve-out above).
	Code string
	// Message is the human half of the error, rendered verbatim.
	Message string
	// Details is the machine-readable half, as decoded from the envelope.
	Details map[string]any
	// Command is what the user typed, binary name excluded.
	Command []string
	// Local marks an error the bin raised itself, with no wire envelope behind
	// it. Type and origin are separate axes: a mill it could not reach is local
	// and TypeTransport at once.
	Local bool
}

// FromError lifts a bare error value — a Cobra failure, a usage complaint, any
// locally raised error with no envelope behind it — into a local Error under
// the catch-all invocation code. A caller that knows which local failure it is
// holding calls LocalError with the code that says so.
func FromError(err error, command []string) Error {
	return LocalError(TypeLocal, CodeInvalidInvocation, err, command)
}

// LocalError lifts a locally raised error under an explicit taxonomy type and
// code. No envelope stands behind it, so plain rendering stays the bare line
// and pretty prints no code, whatever the type says: the code is there for a
// JSON-mode consumer, not for a reader.
func LocalError(errType, code string, err error, command []string) Error {
	message := ""
	if err != nil {
		message = err.Error()
	}
	return Error{Type: errType, Code: code, Message: message, Command: command, Local: true}
}

// Render writes e to w in the given mode, terminated by a newline.
func Render(w io.Writer, e Error, mode Mode) {
	switch mode {
	case Pretty:
		renderPretty(w, e)
	case JSON:
		renderJSON(w, e)
	default:
		_, _ = fmt.Fprintln(w, "error:", e.PlainMessage())
	}
}

// RenderRemedy writes a standalone `try: <command>` pointer on its own line,
// for a caller that has a remedy but no envelope to hang it on — mill's pointer
// at `--help` after an unknown command. Pretty gives it the indented cyan line
// the layout's `try` section gets, separated from the headline it follows;
// plain keeps it bare, one line under SPEC-002.C4's untouched `error:` line.
// A consumer in JSON mode reads one envelope line per failure and no prose, so
// the pointer is not written at all there.
func RenderRemedy(w io.Writer, remedy string, mode Mode) {
	if remedy == "" || mode == JSON {
		return
	}
	if mode == Pretty {
		_, _ = fmt.Fprintln(w, "\n"+remedyLine(remedy, newPalette()))
		return
	}
	_, _ = fmt.Fprintln(w, "try:", remedy)
}

// Resolve picks the rendering for w. SKEIN_ERROR_FORMAT wins when set;
// otherwise pretty for a character device and plain for everything else —
// resolved against the writer in use, never process stderr, so a buffer-backed
// caller never sees a terminal. A malformed override comes back alongside the
// detected mode, so the caller can render the complaint and exit non-zero.
func Resolve(w io.Writer) (Mode, error) {
	if err := ValidateFormat(); err != nil {
		return detect(w), err
	}
	if override := os.Getenv(FormatEnv); override != "" {
		return Mode(override), nil
	}
	return detect(w), nil
}

// ValidateFormat reports a malformed SKEIN_ERROR_FORMAT. Binaries call it once
// at entry, after the local-only paths that must keep working with no weaver
// (SPEC-002.C34). An unset or empty variable is no override.
func ValidateFormat() error {
	value := os.Getenv(FormatEnv)
	if value == "" {
		return nil
	}
	for _, accepted := range acceptedFormats {
		if value == string(accepted) {
			return nil
		}
	}
	names := make([]string, 0, len(acceptedFormats))
	for _, accepted := range acceptedFormats {
		names = append(names, string(accepted))
	}
	return fmt.Errorf("invalid %s %q: accepted values are %s", FormatEnv, value, strings.Join(names, ", "))
}

// ModeFor resolves the rendering for w and ignores a malformed
// SKEIN_ERROR_FORMAT, because both binaries reject one at entry: by the time an
// error is on its way to stderr, the variable is either valid or is itself the
// complaint being rendered.
func ModeFor(w io.Writer) Mode {
	mode, _ := Resolve(w)
	return mode
}

func detect(w io.Writer) Mode {
	file, ok := w.(*os.File)
	if !ok {
		return Plain
	}
	info, err := file.Stat()
	if err != nil {
		return Plain
	}
	if info.Mode()&os.ModeCharDevice != 0 {
		return Pretty
	}
	return Plain
}

// QuotedDetails returns the details entries whose string value already appears
// verbatim in message, keyed by detail key. Pretty rendering drops those rows
// rather than saying the same word twice; card bmzvd picks the offending token
// for its suggestions out of the same map.
func QuotedDetails(message string, details map[string]any) map[string]string {
	quoted := map[string]string{}
	if message == "" {
		return quoted
	}
	for key, value := range details {
		text, ok := value.(string)
		if ok && text != "" && strings.Contains(message, text) {
			quoted[key] = text
		}
	}
	return quoted
}

// stringList reads a details value that carries a list of names, as `available`
// does. Anything else comes back empty.
func stringList(value any) []string {
	items, ok := value.([]any)
	if !ok {
		return nil
	}
	names := []string{}
	for _, item := range items {
		if name, ok := item.(string); ok {
			names = append(names, name)
		}
	}
	return names
}

// encodeJSONNoEscape keeps `<`, `>`, and `&` literal in the details= tail, which
// SPEC-002.C4 pins as byte-faithful.
func encodeJSONNoEscape(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}
