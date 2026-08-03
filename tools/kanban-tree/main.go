// Command kanban-tree prints one kanban card as a terminal tree.
//
// It reads a card's parent-of subtree through `strand kanban-export` and draws
// the epic, its features, and their tasks with their titles and derived
// statuses. Dependencies between siblings become branches of the same tree, so
// the order the work has to happen in is the shape on screen. Work that
// several siblings block belongs to no single branch: it appears as an id stub
// under each blocker and is expanded once below, under the blocker set.
//
// This binary is repo-local development tooling. It is a separate Go module on
// purpose: it ships with no Skein release and belongs to no spool.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// Exit codes: 1 for a failed read, 2 for invalid invocation.
const (
	exitError = 1
	exitUsage = 2
)

func main() {
	code, err := run()
	if err != nil {
		fmt.Fprintln(os.Stderr, "kanban-tree: "+err.Error())
	}
	os.Exit(code)
}

type options struct {
	workspace string
	strandBin string
	from      string
	all       bool
	tasks     bool
	open      bool
	width     int
	colour    bool
	theme     theme
}

func run() (int, error) {
	var opts options
	flags := flag.NewFlagSet("kanban-tree", flag.ContinueOnError)
	flags.StringVar(&opts.workspace, "workspace", os.Getenv("SKEIN_WORKSPACE"), "strand workspace directory")
	flags.StringVar(&opts.strandBin, "strand", "", "strand binary (default: the one beside this one, else PATH)")
	flags.StringVar(&opts.from, "json", "", "read a kanban-export payload from this file instead of calling strand (- for stdin)")
	flags.BoolVar(&opts.tasks, "tasks", false, "under an epic, show each feature's tasks too (a feature already shows its tasks)")
	flags.BoolVar(&opts.all, "all", false, "keep execution strands (agent runs, workflow steps) as well as cards and tasks")
	flags.BoolVar(&opts.open, "open", false, "hide closed cards and tasks whose whole subtree is closed")
	noColour := flags.Bool("no-color", false, "never colourise")
	ascii := flags.Bool("ascii", false, "draw with plain ASCII instead of box-drawing characters")
	width := flags.Int("width", -1, "clip lines to this many columns (0 disables clipping)")
	flags.Usage = func() {
		_, _ = fmt.Fprint(flags.Output(), "usage: kanban-tree [flags] <card-id>\n\n"+
			"Print a kanban epic or feature card as a terminal tree of titles,\n"+
			"statuses, and the dependencies between them.\n\nflags:\n")
		flags.PrintDefaults()
	}
	// `kanban-tree <card> --tasks` is the natural way to type this, and the
	// flag package stops at the first positional, so keep parsing past it.
	args, err := parseInterspersed(flags, os.Args[1:])
	if err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0, nil
		}
		return exitUsage, err
	}

	cardID := ""
	switch len(args) {
	case 0:
		if opts.from == "" {
			flags.Usage()
			return exitUsage, errors.New("no card id given")
		}
	case 1:
		cardID = args[0]
	default:
		return exitUsage, fmt.Errorf("expected one card id, got %d", len(args))
	}

	opts.width, err = resolveWidth(*width)
	if err != nil {
		return exitError, err
	}
	opts.colour = !*noColour && os.Getenv("NO_COLOR") == "" && isTerminal(os.Stdout)
	opts.theme = unicodeTheme()
	if *ascii || !utf8Locale() {
		opts.theme = asciiTheme()
	}

	ex, err := load(opts, cardID)
	if err != nil {
		return exitError, err
	}
	f, err := resolveFilters(ex, opts)
	if err != nil {
		return exitUsage, err
	}
	m, err := build(ex, f)
	if err != nil {
		return exitError, err
	}
	fmt.Print(render(m, opts.theme, opts.colour, opts.width))
	return 0, nil
}

// parseInterspersed parses flags that appear before, after, or between
// positional arguments, and returns the positionals.
func parseInterspersed(flags *flag.FlagSet, args []string) ([]string, error) {
	var positionals []string
	for {
		if err := flags.Parse(args); err != nil {
			return nil, err
		}
		rest := flags.Args()
		if len(rest) == 0 {
			return positionals, nil
		}
		positionals = append(positionals, rest[0])
		args = rest[1:]
	}
}

// resolveFilters settles what the tree shows. An epic draws its features and
// takes --tasks to go a level deeper; anything else is already at task level,
// where --tasks would mean nothing and is refused rather than ignored.
func resolveFilters(ex export, opts options) (filters, error) {
	f := filters{all: opts.all, tasks: opts.tasks || opts.all, open: opts.open}
	for _, s := range ex.Strands {
		if s.ID != ex.RootID {
			continue
		}
		if s.attr(attrType) != "epic" {
			if opts.tasks {
				return f, fmt.Errorf("--tasks only applies to an epic; %s already shows its tasks", ex.RootID)
			}
			f.tasks = true
		}
		return f, nil
	}
	return f, fmt.Errorf("export omits its own root %s", ex.RootID)
}

// load reads the export payload, from a file when one is named and from the
// strand CLI otherwise.
func load(opts options, cardID string) (export, error) {
	if opts.from == "" {
		bin, err := resolveStrand(opts.strandBin)
		if err != nil {
			return export{}, err
		}
		return fetch(context.Background(), bin, opts.workspace, cardID)
	}
	if opts.from == "-" {
		return decode(os.Stdin)
	}
	file, err := os.Open(opts.from)
	if err != nil {
		return export{}, err
	}
	defer func() { _ = file.Close() }()
	return decode(file)
}

// resolveStrand prefers the binary sitting beside this one, so a repo-local
// build reads through its own CLI rather than the user's global install.
func resolveStrand(override string) (string, error) {
	if override != "" {
		return override, nil
	}
	if self, err := os.Executable(); err == nil {
		sibling := filepath.Join(filepath.Dir(self), "strand")
		if info, err := os.Stat(sibling); err == nil && !info.IsDir() {
			return sibling, nil
		}
	}
	found, err := exec.LookPath("strand")
	if err != nil {
		return "", errors.New("no strand binary beside kanban-tree or on PATH (run make build)")
	}
	return found, nil
}

// resolveWidth turns the flag into a column budget: an explicit value wins,
// -1 asks for the terminal's own width, and 0 deliberately disables clipping.
func resolveWidth(flagged int) (int, error) {
	if flagged >= 0 {
		return flagged, nil
	}
	if value, set := os.LookupEnv("COLUMNS"); set {
		columns, err := strconv.Atoi(value)
		if err != nil || columns <= 0 {
			return 0, fmt.Errorf("invalid COLUMNS value %q", value)
		}
		return columns, nil
	}
	if !isTerminal(os.Stdout) {
		return 0, nil
	}
	out, err := exec.Command("tput", "cols").Output()
	if err != nil {
		return 0, fmt.Errorf("determine terminal width with tput cols: %w", err)
	}
	value := strings.TrimSpace(string(out))
	columns, err := strconv.Atoi(value)
	if err != nil || columns <= 0 {
		return 0, fmt.Errorf("invalid tput cols output %q", value)
	}
	return columns, nil
}

// utf8Locale reports whether the terminal has told us it can render more than
// ASCII. A locale that says nothing is taken at its word rather than guessed
// past: mojibake is worse than plain elbows.
func utf8Locale() bool {
	for _, key := range []string{"LC_ALL", "LC_CTYPE", "LANG"} {
		if value := os.Getenv(key); value != "" {
			lower := strings.ToLower(value)
			return strings.Contains(lower, "utf-8") || strings.Contains(lower, "utf8")
		}
	}
	return false
}

func isTerminal(file *os.File) bool {
	info, err := file.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}
