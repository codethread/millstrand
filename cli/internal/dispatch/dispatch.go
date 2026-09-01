// Package dispatch implements the strand invoke-envelope dispatcher: it parses
// dispatcher flags, resolves selection/context, assembles the invoke envelope,
// and relays the weaver's NDJSON response. strand has no builtin subcommands;
// the first non-flag token is the op name and everything after it ships verbatim
// as argv (SPEC-002-D004.C1).
package dispatch

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"golang.org/x/term"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/errfmt"
)

// Version and BuildID are the product release and exact source revision
// reported by --version and carried in the invoke envelope's client identity.
var (
	Version = config.Version
	BuildID = config.BuildID
)

// Seams overridden in tests.
var (
	getwd            = os.Getwd
	deriveGitContext = config.DeriveGitContext
	sendInvoke       = client.InvokeThroughMill
)

type terminalCapabilities struct {
	isTTY  bool
	ttyCol *int
}

type parsed struct {
	workspace    string
	cwd          string
	worktreeRoot string
	gitCommonDir string
	stdin        bool
	payloads     []string
	timeout      string
	timeoutSet   bool
	dryRun       bool
	version      bool
	help         bool
	haveOp       bool
	opName       string
	argv         []string
}

// Run parses args, assembles the envelope, and either prints (help/version/
// dry-run) or dispatches through the mill. It returns the process exit code and
// writes any usage/assembly error to stderr itself.
func Run(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	p, err := parse(args)
	if err != nil {
		return fail(stderr, errfmt.CodeInvalidInvocation, err)
	}
	if p.version {
		return printJSON(stdout, stderr, map[string]any{
			"version":          Version,
			"build_id":         BuildID,
			"protocol_version": client.ProtocolVersion,
		})
	}
	// A pre-op --help/-h can only be seen before the op token (flag parsing stops
	// at the first non-flag token). With an op named, --help must trail the op
	// (DELTA-Dtf-001.CC6): redirect rather than print static usage.
	if p.help && p.haveOp {
		return fail(stderr, errfmt.CodeInvalidInvocation,
			fmt.Errorf("--help must follow the op: run `strand help %s` or `strand %s --help`", p.opName, p.opName))
	}
	if p.help || !p.haveOp {
		if _, err := fmt.Fprint(stdout, helpText); err != nil {
			return fail(stderr, errfmt.CodeOutputUnwritable, err)
		}
		return 0
	}
	// Selection is validated once the local-only flags above are out of the way,
	// so a typo'd MILLSTRAND_ERROR_FORMAT never breaks the paths SPEC-002.C34 promises
	// work with no weaver.
	if err := errfmt.ValidateFormat(); err != nil {
		return fail(stderr, errfmt.CodeInvalidErrorFormat, err)
	}

	payloads, err := buildPayloads(p, stdin)
	if err != nil {
		return fail(stderr, errfmt.CodePayloadUnreadable, err)
	}
	effectiveCwd, worktreeRoot, gitCommonDir, err := resolveContext(p)
	if err != nil {
		return fail(stderr, errfmt.CodeContextUnresolved, err)
	}
	envelope, err := assembleEnvelope(p, payloads, effectiveCwd, worktreeRoot, gitCommonDir,
		detectTerminalCapabilities(stdout))
	if err != nil {
		return fail(stderr, errfmt.CodeInvalidInvocation, err)
	}

	if p.dryRun {
		return printJSON(stdout, stderr, map[string]any{
			"protocol_version": client.ProtocolVersion,
			"request_id":       "<dry-run>",
			"weaver_id":        "<dry-run>",
			"operation":        "invoke",
			"arguments":        envelope,
			"options":          map[string]any{},
		})
	}

	world := client.MillWorldRequest{CWD: effectiveCwd, ConfigDir: p.workspace}
	code, err := sendInvoke(world, envelope, stdout, stderr)
	if err != nil {
		// A *client.ResponseError has already been rendered by the relay; anything
		// else (transport, malformed frames) has not. Exactly one rendering
		// reaches stderr either way.
		var responseErr *client.ResponseError
		if !errors.As(err, &responseErr) {
			render(stderr, client.ForRendering(err, append([]string{p.opName}, p.argv...)))
		}
		if code == 0 {
			code = 1
		}
	}
	return code
}

func parse(args []string) (parsed, error) {
	var p parsed
	i := 0
	for i < len(args) {
		a := args[i]
		switch {
		case a == "--help" || a == "-h":
			p.help = true
			i++
		case a == "--version":
			p.version = true
			i++
		case a == "--dry-run":
			p.dryRun = true
			i++
		case a == "--stdin":
			p.stdin = true
			i++
		case flagMatches(a, "--workspace"):
			v, ni, err := flagValue(args, i, "--workspace")
			if err != nil {
				return p, err
			}
			p.workspace, i = v, ni
		case flagMatches(a, "--cwd"):
			v, ni, err := flagValue(args, i, "--cwd")
			if err != nil {
				return p, err
			}
			p.cwd, i = v, ni
		case flagMatches(a, "--worktree-root"):
			v, ni, err := flagValue(args, i, "--worktree-root")
			if err != nil {
				return p, err
			}
			p.worktreeRoot, i = v, ni
		case flagMatches(a, "--git-common-dir"):
			v, ni, err := flagValue(args, i, "--git-common-dir")
			if err != nil {
				return p, err
			}
			p.gitCommonDir, i = v, ni
		case flagMatches(a, "--timeout"):
			v, ni, err := flagValue(args, i, "--timeout")
			if err != nil {
				return p, err
			}
			p.timeout, p.timeoutSet, i = v, true, ni
		case flagMatches(a, "--payload"):
			v, ni, err := flagValue(args, i, "--payload")
			if err != nil {
				return p, err
			}
			p.payloads, i = append(p.payloads, v), ni
		case strings.HasPrefix(a, "-"):
			return p, fmt.Errorf("unknown flag: %s", a)
		default:
			// First non-flag token is the op name; the rest is opaque argv.
			p.haveOp = true
			p.opName = a
			p.argv = append([]string{}, args[i+1:]...)
			i = len(args)
		}
	}
	return p, nil
}

func flagMatches(arg, name string) bool {
	return arg == name || strings.HasPrefix(arg, name+"=")
}

func flagValue(args []string, i int, name string) (string, int, error) {
	a := args[i]
	if strings.HasPrefix(a, name+"=") {
		return strings.TrimPrefix(a, name+"="), i + 1, nil
	}
	if i+1 >= len(args) {
		return "", 0, fmt.Errorf("flag needs an argument: %s", name)
	}
	return args[i+1], i + 2, nil
}

// buildPayloads reads --stdin and --payload slots client-side. Duplicate slot
// names (including a --payload named "stdin" colliding with --stdin) fail loudly
// before transport (SPEC-002-D004.C3).
func buildPayloads(p parsed, stdin io.Reader) (map[string]string, error) {
	payloads := map[string]string{}
	if p.stdin {
		b, err := io.ReadAll(stdin)
		if err != nil {
			return nil, fmt.Errorf("failed to read --stdin: %w", err)
		}
		payloads["stdin"] = string(b)
	}
	for _, spec := range p.payloads {
		name, path, ok := strings.Cut(spec, "=")
		if !ok || name == "" || path == "" {
			return nil, fmt.Errorf("malformed --payload (want name=path): %s", spec)
		}
		if _, dup := payloads[name]; dup {
			return nil, fmt.Errorf("duplicate payload slot: %s", name)
		}
		b, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("failed to read --payload %s: %w", name, err)
		}
		payloads[name] = string(b)
	}
	return payloads, nil
}

// resolveContext applies SPEC-002-D004.C2 precedence: --workspace wins outright;
// explicit git flags win over derivation; derivation runs from --cwd when given,
// else process cwd; failed derivation with nothing pinned fails loudly.
func resolveContext(p parsed) (effectiveCwd, worktreeRoot, gitCommonDir string, err error) {
	effectiveCwd = p.cwd
	if effectiveCwd == "" {
		effectiveCwd, err = getwd()
		if err != nil {
			return "", "", "", err
		}
	}
	worktreeRoot = p.worktreeRoot
	gitCommonDir = p.gitCommonDir
	if worktreeRoot == "" || gitCommonDir == "" {
		root, common, derr := deriveGitContext(effectiveCwd)
		if derr != nil {
			workspacePinned := p.workspace != ""
			gitPinned := p.worktreeRoot != "" && p.gitCommonDir != ""
			if !workspacePinned && !gitPinned {
				return "", "", "", derr
			}
			// Workspace (or both git flags) pins the selection; leave any
			// underivable git field empty.
		} else {
			if worktreeRoot == "" {
				worktreeRoot = root
			}
			if gitCommonDir == "" {
				gitCommonDir = common
			}
		}
	}
	return effectiveCwd, worktreeRoot, gitCommonDir, nil
}

func detectTerminalCapabilities(stdout io.Writer) terminalCapabilities {
	file, ok := stdout.(*os.File)
	if !ok || !term.IsTerminal(int(file.Fd())) {
		return terminalCapabilities{}
	}
	width, _, err := term.GetSize(int(file.Fd()))
	if err != nil || width <= 0 {
		width = 80
	}
	return terminalCapabilities{isTTY: true, ttyCol: &width}
}

func assembleEnvelope(p parsed, payloads map[string]string, effectiveCwd, worktreeRoot, gitCommonDir string, terminal terminalCapabilities) (map[string]any, error) {
	env := map[string]any{
		"name":     p.opName,
		"argv":     p.argv,
		"payloads": payloads,
		"cwd":      effectiveCwd,
		"is_tty":   terminal.isTTY,
		"tty_col":  terminal.ttyCol,
		"client":   map[string]any{"pid": os.Getpid(), "version": Version, "build_id": BuildID},
	}
	if worktreeRoot != "" {
		env["worktree_root"] = worktreeRoot
	}
	if gitCommonDir != "" {
		env["git_common_dir"] = gitCommonDir
	}
	if p.workspace != "" {
		env["workspace"] = p.workspace
	}
	if p.timeoutSet {
		d, err := time.ParseDuration(p.timeout)
		if err != nil {
			return nil, fmt.Errorf("invalid --timeout %q: %w", p.timeout, err)
		}
		ms := d.Milliseconds()
		if ms <= 0 {
			return nil, fmt.Errorf("invalid --timeout %q: must be a positive duration", p.timeout)
		}
		env["timeout"] = ms
	}
	return env, nil
}

func printJSON(stdout, stderr io.Writer, v any) int {
	b, err := json.Marshal(v)
	if err != nil {
		return fail(stderr, errfmt.CodeOutputUnwritable, err)
	}
	if _, err := fmt.Fprintln(stdout, string(b)); err != nil {
		return fail(stderr, errfmt.CodeOutputUnwritable, err)
	}
	return 0
}

// fail renders a bad invocation — unknown flags, unreadable payloads,
// underivable context — under the code that names which one it was, and returns
// the exit code.
func fail(stderr io.Writer, code string, err error) int {
	return render(stderr, errfmt.LocalError(errfmt.TypeLocal, code, err, nil))
}

func render(stderr io.Writer, e errfmt.Error) int {
	errfmt.Render(stderr, e, errfmt.ModeFor(stderr))
	return 1
}

const helpText = `Usage:
  strand [flags] <op> [args...]

Flags:
  --workspace <dir>        workspace directory
  --cwd <dir>              working directory (default: process cwd)
  --stdin                  read stdin into the stdin payload
  --payload name=path      read a file into a named payload (repeatable)
  --timeout <dur>          request deadline (e.g. 30s)
  --version                print bin and protocol version
  -h, --help               print this help

Operations:
  Commands are registered by the workspace. ` + "`add`" + ` below is only an example.

  Load help, then prime, then about:
    help     what you can type
    prime    runbook for that command
    about    detailed explanation

  strand help                      list commands
  strand help add                  flags and args
  strand prime add                 runbook
  strand about add                 detailed explanation
  strand help --json add           same as help, as JSON
  strand add --help                same as strand help add

Examples:
  printf 'notes' | strand --stdin add "Title" --attr body=:stdin
  strand --payload body=notes.md add "Title" --attr body=:payload/body
  strand --stdin add "Title" --attributes :stdin <<'EOF'
{"priority":3}
EOF
`
