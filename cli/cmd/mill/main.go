package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/spf13/cobra"
	"skein-strand-cli/internal/client"
	"skein-strand-cli/internal/config"
	"skein-strand-cli/internal/errfmt"
)

type server struct {
	meta     client.MillMetadata
	mu       sync.Mutex
	children map[string]*weaverChild
}

type weaverChild struct {
	cmd   *exec.Cmd
	world config.World
	name  string
	done  chan error
}

var millLogOut io.Writer = os.Stdout

func millLogf(format string, args ...any) {
	_, _ = fmt.Fprintf(millLogOut, format+"\n", args...)
}

var launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
	cmd := exec.Command("clojure", args...)
	cmd.Dir = source
	cmd.Stdout = out
	cmd.Stderr = errOut
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	return cmd, nil
}

func main() {
	root := newMillCommand()
	// ExecuteC hands back the command that actually ran, which is how the
	// renderer learns what the user typed.
	cmd, err := root.ExecuteC()
	if err != nil {
		writeMillCommandFailure(err, cmd)
		os.Exit(1)
	}
}

// millCommandPath is the command the user typed, binary name dropped.
func millCommandPath(cmd *cobra.Command) []string {
	if cmd == nil {
		return nil
	}
	path := strings.Fields(cmd.CommandPath())
	if len(path) == 0 {
		return nil
	}
	return path[1:]
}

func newMillCommand() *cobra.Command {
	root := &cobra.Command{
		Use:   "mill",
		Short: "Millstrand local router",
		Long: `mill is the Millstrand local router: it supervises weavers and forwards
strand invocations to the one selected for a workspace.

Environment:
  MILLSTRAND_ERROR_FORMAT error rendering: plain|pretty|json (default: pretty at a
                       terminal, plain everywhere else). json writes the error
                       envelope {type, code, message, details} as one line on
                       stderr, and nothing else, for a caller composing over
                       this CLI
  NO_COLOR             drop ANSI colour from the pretty rendering`,
		// Silencing hands the whole failure output to writeMillCommandFailure,
		// which renders once and decides for itself which of Cobra's help
		// pointer and usage block the failure has earned.
		SilenceErrors: true,
		SilenceUsage:  true,
		// Cobra reaches here only once --help, completion, and the other paths
		// that need no weaver have been served, which is exactly where the error
		// format may fail loudly.
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error { return errfmt.ValidateFormat() },
	}
	root.AddCommand(&cobra.Command{Use: "start", Short: "Start mill in the foreground", RunE: func(cmd *cobra.Command, args []string) error {
		return start()
	}})
	root.AddCommand(&cobra.Command{Use: "status", Short: "Check the active mill", RunE: func(cmd *cobra.Command, args []string) error {
		result, err := client.MillStatus()
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(result)
	}})
	initCmd := &cobra.Command{Use: "init", Short: "Bootstrap missing selected config workspace files through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, _ := cmd.Flags().GetString("workspace")
		stealth, _ := cmd.Flags().GetBool("stealth")
		return runInit(workspace, stealth)
	}}
	initCmd.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	initCmd.Flags().Bool("stealth", false, "keep repo-local .millstrand/.ms and Claude guidance untracked through .git/info/exclude")
	root.AddCommand(initCmd)

	weaver := &cobra.Command{Use: "weaver", Short: "Manage supervised weavers"}
	weaver.AddCommand(&cobra.Command{Use: "list", Short: "List known selected workspace weavers", RunE: func(cmd *cobra.Command, args []string) error {
		result, err := client.MillCall("weaver-list", client.MillWorldRequest{})
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(result)
	}})
	start := &cobra.Command{Use: "start", Short: "Start the selected workspace's weaver through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, _ := cmd.Flags().GetString("workspace")
		name, _ := cmd.Flags().GetString("name")
		readyTimeout, _ := cmd.Flags().GetString("ready-timeout")
		if cmd.Flags().Changed("name") && strings.TrimSpace(name) == "" {
			return errors.New("--name requires a non-empty value")
		}
		return runWeaverLifecycleWithReadyTimeout("weaver-start", workspace, name, readyTimeout)
	}}
	start.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	start.Flags().String("name", "", "friendly name for this weaver (defaults to workspace basename)")
	start.Flags().String("ready-timeout", "", "ready metadata wait budget (Go duration, default 5m)")
	weaver.AddCommand(start)
	status := &cobra.Command{Use: "status", Short: "Show selected workspace weaver status through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, _ := cmd.Flags().GetString("workspace")
		return runWeaverLifecycle("weaver-status", workspace, "")
	}}
	status.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	weaver.AddCommand(status)
	stop := &cobra.Command{Use: "stop", Short: "Stop the selected workspace's weaver through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, _ := cmd.Flags().GetString("workspace")
		return runWeaverLifecycle("weaver-stop", workspace, "")
	}}
	stop.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	weaver.AddCommand(stop)
	repl := &cobra.Command{Use: "repl", Short: "Attach directly to the selected workspace's live weaver nREPL", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, _ := cmd.Flags().GetString("workspace")
		stdin, _ := cmd.Flags().GetBool("stdin")
		return runWeaverRepl(workspace, stdin)
	}}
	repl.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	repl.Flags().Bool("stdin", false, "send stdin Clojure forms to the running weaver, print one result per top-level form, then exit")
	weaver.AddCommand(repl)
	root.AddCommand(weaver)

	millstrand := &cobra.Command{Use: "millstrand", Short: "Millstrand orientation for agents"}
	millstrand.AddCommand(&cobra.Command{Use: "prime", Short: "Print orientation for building on .millstrand: resolved source path and the docs to read", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return runPrime("millstrand")
	}})
	root.AddCommand(millstrand)

	strandCmd := &cobra.Command{Use: "strand", Short: "Strand workflow guidance for agents"}
	strandCmd.AddCommand(&cobra.Command{Use: "prime", Short: "Print the strand planning/tracking workflow", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return runPrime("strand")
	}})
	root.AddCommand(strandCmd)
	root.AddCommand(newBinCommand())
	stampUsageErrors(root)
	return root
}

func start() error {
	root, err := config.StateRoot()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		return err
	}
	socketPath := filepath.Join(root, config.MillSocketFileName)
	metadataPath := filepath.Join(root, config.MillMetadataFileName)
	if err := cleanupPreviousMillState(root, socketPath, metadataPath); err != nil {
		return err
	}
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		return err
	}
	defer func() { _ = listener.Close() }()
	defer func() { _ = os.Remove(socketPath) }()
	defer func() { _ = os.Remove(metadataPath) }()

	meta := client.MillMetadata{ProtocolVersion: client.MillProtocolVersion, PID: os.Getpid(), MillID: fmt.Sprintf("mill-%d-%d", os.Getpid(), time.Now().UnixNano()), StateRoot: root, SocketPath: socketPath, StartedAt: time.Now().UTC().Format(time.RFC3339Nano), MillBuild: config.BuildID}
	b, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(metadataPath, append(b, '\n'), 0o644); err != nil {
		return err
	}
	millLogf("mill listening state_root=%s socket=%s pid=%d", meta.StateRoot, meta.SocketPath, meta.PID)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() { <-sig; _ = listener.Close() }()
	s := server{meta: meta, children: map[string]*weaverChild{}}
	defer s.stopAll()
	for {
		conn, err := listener.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}
		go s.handle(conn)
	}
}

func (s *server) handle(conn net.Conn) {
	defer func() { _ = conn.Close() }()
	var req client.MillRequest
	if err := json.NewDecoder(conn).Decode(&req); err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse("", "protocol", "mill/protocol", "malformed mill request", err.Error()))
		return
	}
	if req.ProtocolVersion != client.MillProtocolVersion || req.RequestID == "" || req.MillID != s.meta.MillID {
		_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "protocol", "mill/identity", "mill request identity mismatch", ""))
		return
	}
	switch req.Operation {
	case "status", "ping":
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: map[string]any{"healthy": true, "protocol_version": client.MillProtocolVersion, "pid": s.meta.PID, "mill_id": s.meta.MillID, "state_root": s.meta.StateRoot, "socket_path": s.meta.SocketPath, "started_at": s.meta.StartedAt}})
	case "init":
		if err := validateInitRequest(req.World); err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-invalid-request", "invalid mill init request", err.Error()))
			return
		}
		var world config.World
		var stealth *config.StealthReport
		var err error
		if req.World.Stealth {
			var report config.StealthReport
			world, report, err = config.BootstrapStealthWorld(req.World.CWD)
			stealth = &report
		} else {
			world, err = config.BootstrapWorld(req.World.CWD, req.World.ConfigDir, req.World.Source)
		}
		if err != nil {
			var refusal *config.StealthRefusal
			if errors.As(err, &refusal) {
				details, detailsErr := refusal.Details()
				if detailsErr != nil {
					_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-failed", "mill init failed", detailsErr.Error()))
					return
				}
				_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: false, Error: &client.ResponseError{Type: "domain", Code: "mill/init-stealth-refused", Message: "mill stealth init refused", Details: details}})
			} else {
				_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-failed", "mill init failed", err.Error()))
			}
			return
		}
		if stealth != nil {
			result := config.StealthInitResult{ConfigDir: world.ConfigDir, ConfigFile: world.ConfigFile, Stealth: *stealth}
			if err := result.Validate(); err != nil {
				_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-failed", "mill init failed", err.Error()))
				return
			}
			_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
			return
		}
		result := map[string]any{"config_dir": world.ConfigDir, "config_file": world.ConfigFile}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-start":
		result, err := s.startWeaver(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-start-failed", "weaver start failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-status":
		result, err := s.weaverStatus(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-status-failed", "weaver status failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-list":
		result, err := s.weaverList()
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-list-failed", "weaver list failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-repl-context":
		result, err := s.weaverReplContext(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-repl-context-failed", "weaver repl context failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-stop":
		result, err := s.stopWeaver(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-stop-failed", "weaver stop failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "invoke":
		// invoke relays the weaver's own single/stream frames verbatim; it does
		// not wrap in a MillResponse, so it writes to conn itself and returns.
		s.handleInvoke(conn, req)
		return
	default:
		_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "protocol", "mill/unknown-operation", "unknown mill operation", req.Operation))
	}
}

func validateInitRequest(world client.MillWorldRequest) error {
	if world.Stealth && strings.TrimSpace(world.ConfigDir) != "" {
		return errors.New("stealth init cannot select an explicit workspace")
	}
	return nil
}

func cleanupPreviousMillState(root, socketPath, metadataPath string) error {
	if b, err := os.ReadFile(metadataPath); err == nil {
		var meta client.MillMetadata
		if err := json.Unmarshal(b, &meta); err == nil && meta.PID != 0 && processAlive(meta.PID) {
			return fmt.Errorf("mill is already running with pid %d", meta.PID)
		}
	} else if err != nil && !os.IsNotExist(err) {
		return err
	}
	for _, pattern := range []string{filepath.Join(root, "weavers", "*", "weaver.json")} {
		matches, err := filepath.Glob(pattern)
		if err != nil {
			return err
		}
		for _, path := range matches {
			if b, err := os.ReadFile(path); err == nil {
				var meta client.Metadata
				if err := json.Unmarshal(b, &meta); err == nil && meta.PID != 0 && processAlive(meta.PID) {
					terminatePID(meta.PID)
				}
			}
			stateDir := filepath.Dir(path)
			cleanupWorldArtifacts(config.World{StateDir: stateDir})
		}
	}
	_ = os.Remove(socketPath)
	_ = os.Remove(metadataPath)
	return nil
}

func errorResponse(requestID, typ, code, message, detail string) client.MillResponse {
	return client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: requestID, OK: false, Error: &client.ResponseError{Type: typ, Code: code, Message: message, Details: map[string]any{"detail": detail}}}
}
