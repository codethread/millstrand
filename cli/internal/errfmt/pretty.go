package errfmt

import (
	"fmt"
	"io"
	"os"
	"sort"
	"strconv"
	"strings"
)

// Layout is fixed: a two-space headline, four-space section labels, six-space
// entries. NO_COLOR removes the escapes and changes none of it.
const (
	headlineIndent = "  "
	labelIndent    = "    "
	entryIndent    = "      "
	defaultWidth   = 80
	minimumWidth   = 40
)

// The two details keys with a rendering of their own. Everything else is a row.
const (
	availableKey = "available"
	tryKey       = "try"
)

const (
	ansiReset = "\x1b[0m"
	ansiRed   = "\x1b[31m"
	ansiBold  = "\x1b[1m"
	ansiDim   = "\x1b[2m"
	ansiCyan  = "\x1b[36m"
)

func renderPretty(w io.Writer, e Error) {
	paint := palette{colour: os.Getenv("NO_COLOR") == ""}
	sections := []string{headline(e, paint)}
	if names := stringList(e.Details[availableKey]); len(names) > 0 {
		sections = append(sections, section(paint.dim(availableKey+":"), columns(names, terminalWidth())))
	}
	if rows := detailRows(e); len(rows) > 0 {
		sections = append(sections, section(paint.dim("details:"), rows))
	}
	if remedy, ok := e.Details[tryKey].(string); ok && remedy != "" {
		sections = append(sections, labelIndent+paint.cyan("try: "+remedy))
	}
	_, _ = fmt.Fprintln(w, strings.Join(sections, "\n\n"))
}

// headline names what the user typed and what went wrong. The code trails it as
// opaque text, dropped when it is the fallback that says nothing; the type noun
// never appears, because "weaver domain error" tells a user nothing either.
func headline(e Error, paint palette) string {
	message := e.Message
	if message == "" {
		message = "unknown error"
	}
	line := headlineIndent + paint.red("x") + " "
	if command := strings.Join(spokenCommand(e), " "); command != "" {
		line += command + ": "
	}
	line += paint.bold(message)
	if e.Code != "" && e.Code != fallbackCode {
		line += " " + paint.dim("("+e.Code+")")
	}
	return line
}

// spokenCommand drops trailing tokens the message already quotes, so
// `strand kanban list` failing on "list" heads its error with `kanban` rather
// than saying the token twice. The first token always survives.
func spokenCommand(e Error) []string {
	command := e.Command
	for len(command) > 1 && e.Message != "" && strings.Contains(e.Message, command[len(command)-1]) {
		command = command[:len(command)-1]
	}
	return command
}

// detailRows renders every details key that has no section of its own, sorted,
// with the values the message already quotes left out.
func detailRows(e Error) []string {
	quoted := QuotedDetails(e.Message, e.Details)
	keys := []string{}
	for key := range e.Details {
		if key == availableKey || key == tryKey {
			continue
		}
		if _, redundant := quoted[key]; redundant {
			continue
		}
		keys = append(keys, key)
	}
	if len(keys) == 0 {
		return nil
	}
	sort.Strings(keys)
	width := 0
	for _, key := range keys {
		if len(key) > width {
			width = len(key)
		}
	}
	rows := make([]string, 0, len(keys))
	for _, key := range keys {
		rows = append(rows, key+strings.Repeat(" ", width-len(key)+2)+indentContinuation(detailValue(e.Details[key]), width+2))
	}
	return rows
}

// detailValue prints strings as themselves and everything else — numbers,
// booleans, nested maps, the pr-str blobs the weaver stringifies — as compact
// JSON.
func detailValue(value any) string {
	if text, ok := value.(string); ok {
		return text
	}
	encoded, err := encodeJSONNoEscape(value)
	if err != nil {
		return fmt.Sprintf("%v", value)
	}
	return string(encoded)
}

// indentContinuation keeps a multi-line value aligned under its own column.
func indentContinuation(value string, width int) string {
	return strings.ReplaceAll(value, "\n", "\n"+entryIndent+strings.Repeat(" ", width))
}

// columns lays names out left to right in a fixed grid, wrapped to the
// terminal.
func columns(names []string, width int) []string {
	cell := 0
	for _, name := range names {
		if len(name) > cell {
			cell = len(name)
		}
	}
	cell += 2
	perRow := (width - len(entryIndent)) / cell
	if perRow < 1 {
		perRow = 1
	}
	rows := []string{}
	for start := 0; start < len(names); start += perRow {
		end := min(start+perRow, len(names))
		row := ""
		for _, name := range names[start:end] {
			row += name + strings.Repeat(" ", cell-len(name))
		}
		rows = append(rows, strings.TrimRight(row, " "))
	}
	return rows
}

func section(label string, entries []string) string {
	lines := []string{labelIndent + label}
	for _, entry := range entries {
		lines = append(lines, entryIndent+entry)
	}
	return strings.Join(lines, "\n")
}

// terminalWidth trusts COLUMNS and otherwise assumes the classic 80, which
// keeps this package dependency-free.
func terminalWidth() int {
	if value, err := strconv.Atoi(os.Getenv("COLUMNS")); err == nil && value >= minimumWidth {
		return value
	}
	return defaultWidth
}

type palette struct {
	colour bool
}

func (p palette) wrap(code, text string) string {
	if !p.colour {
		return text
	}
	return code + text + ansiReset
}

func (p palette) red(text string) string  { return p.wrap(ansiRed, text) }
func (p palette) bold(text string) string { return p.wrap(ansiBold, text) }
func (p palette) dim(text string) string  { return p.wrap(ansiDim, text) }
func (p palette) cyan(text string) string { return p.wrap(ansiCyan, text) }
