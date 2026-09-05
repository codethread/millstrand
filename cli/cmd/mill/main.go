package main

import (
	"bufio"
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
	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
	"millstrand-strand-cli/internal/errfmt"
	"millstrand-strand-cli/internal/process"
)

type server struct {
	meta      client.MillMetadata
	mu        sync.Mutex
	children  map[string]*weaverChild
	custodies map[string]*process.Custody
	// startClaims close the check-to-registration window for a new child. A
	// restart waits for the claim to resolve instead of racing a start that has
	// not yet published its admitted generation.
	startClaims map[string]chan struct{}
	// transitions is keyed by the selected config directory.  A transition is
	// deliberately shared by all lifecycle callers for that workspace: the
	// caller's timeout only bounds its wait on transition.done.
	transitions map[string]*weaverTransition
	// admissionEpochs advances once per lifecycle cutover. An invocation keeps
	// the transition pointer it observed at admission; the epoch prevents a
	// later transition from being mistaken for that boundary.
	admissionEpochs map[string]uint64
	// admissionLocks serialize target selection and request-frame admission per
	// workspace. They keep one workspace's external socket write from blocking
	// lifecycle access or invocations for another workspace.
	admissionLocks map[string]*sync.Mutex
	// shutdown is closed exactly once when mill receives its termination signal.
	// Autostart workers observe it before taking a slot and before launching.
	shutdown     chan struct{}
	shutdownOnce sync.Once
	autostartWG  sync.WaitGroup
}

type weaverChild struct {
	cmd      *exec.Cmd
	world    config.World
	name     string
	done     chan error
	waitDone chan struct{}
	identity weaverIdentity
	// unsupervised means this child was discovered from runtime metadata rather
	// than launched and owned by this mill.  Such a child needs endpoint-backed
	// identity proof before mill may signal its recorded PID.
	unsupervised bool
	// generationID is Mill's opaque identity for this process generation.  It
	// is not derived from a PID or from user configuration.
	generationID string
}

var (
	millLogOut  io.Writer = os.Stdout
	millLogJSON bool
	millLogMu   sync.Mutex
)

// startClaimInstalledFn is a deterministic seam for overlap tests.  The
// default hook is inert; production callers still hold s.mu while the claim is
// installed and the hook is called.
var startClaimInstalledFn = func(string) {}

// startClaimLookupFn is a deterministic seam for proving that overlapping
// lifecycle callers reached the claim lookup before the owner releases it.
var startClaimLookupFn = func(string) {}

func millLogf(format string, args ...any) {
	millLogMu.Lock()
	defer millLogMu.Unlock()
	message := fmt.Sprintf(format, args...)
	if millLogJSON {
		_ = json.NewEncoder(millLogOut).Encode(map[string]any{"time": time.Now().Format(time.RFC3339), "message": message})
		return
	}
	newStatusOutput(millLogOut).event(time.Now(), message)
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
		Use:     "mill",
		Short:   "Millstrand local router",
		Version: config.Version,
		Long: `mill is the Millstrand local router: it supervises weavers and forwards
strand invocations to the one selected for a workspace.

Environment:
  MILLSTRAND_ERROR_FORMAT error rendering: plain|pretty|json (default: pretty at a
                       terminal, plain everywhere else). json writes the error
                       envelope {type, code, message, details} as one line on
                       stderr, and nothing else, for a caller composing over
                       this CLI
  NO_COLOR             disable ANSI colour in status and error output`,
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
	root.SetVersionTemplate(fmt.Sprintf("{\"build_id\":%q,\"protocol_version\":%d,\"version\":%q}\n", config.BuildID, client.ProtocolVersion, config.Version))
	startCmd := &cobra.Command{Use: "start", Short: "Start mill in the foreground", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		millLogJSON, _ = cmd.Flags().GetBool("json")
		return start()
	}}
	startCmd.Flags().Bool("json", false, "write timestamped JSON log events")
	root.AddCommand(startCmd)
	statusCmd := &cobra.Command{Use: "status", Short: "Check the active mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		result, err := client.MillStatus()
		if err != nil {
			return err
		}
		jsonOutput, _ := cmd.Flags().GetBool("json")
		return writeStatusResult(cmd.OutOrStdout(), jsonOutput, "status", result, 0)
	}}
	statusCmd.Flags().Bool("json", false, "print the full status as JSON")
	root.AddCommand(statusCmd)
	initCmd := &cobra.Command{Use: "init", Short: "Bootstrap missing selected config workspace files through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, _ := cmd.Flags().GetString("workspace")
		stealth, _ := cmd.Flags().GetBool("stealth")
		autoStart, _ := cmd.Flags().GetBool("auto-start")
		return runInit(workspace, stealth, autoStart)
	}}
	initCmd.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	initCmd.Flags().Bool("stealth", false, "keep repo-local .millstrand/.ms and Claude guidance untracked through .git/info/exclude")
	initCmd.Flags().Bool("auto-start", false, "enable and register this workspace for automatic weaver startup")
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
		jsonOutput, _ := cmd.Flags().GetBool("json")
		return runWeaverLifecycle(cmd.OutOrStdout(), jsonOutput, "weaver-start", workspace, name, readyTimeout)
	}}
	start.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	start.Flags().String("name", "", "friendly name for this weaver (defaults to workspace basename)")
	start.Flags().String("ready-timeout", "", "ready metadata wait budget (Go duration, default 5m)")
	start.Flags().Bool("json", false, "print the full result as JSON without progress messages")
	weaver.AddCommand(start)
	restart := &cobra.Command{Use: "restart", Short: "Probe and replace the selected workspace's weaver through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, _ := cmd.Flags().GetString("workspace")
		readyTimeout, _ := cmd.Flags().GetString("ready-timeout")
		jsonOutput, _ := cmd.Flags().GetBool("json")
		return runWeaverLifecycle(cmd.OutOrStdout(), jsonOutput, "weaver-restart", workspace, "", readyTimeout)
	}}
	restart.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	restart.Flags().String("ready-timeout", "", "ready metadata wait budget (Go duration, default 5m)")
	restart.Flags().Bool("json", false, "print the full result as JSON without progress messages")
	weaver.AddCommand(restart)
	status := &cobra.Command{Use: "status", Short: "Show selected workspace weaver status through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, _ := cmd.Flags().GetString("workspace")
		jsonOutput, _ := cmd.Flags().GetBool("json")
		return runWeaverLifecycle(cmd.OutOrStdout(), jsonOutput, "weaver-status", workspace, "", "")
	}}
	status.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	status.Flags().Bool("json", false, "print the full result as JSON without progress messages")
	weaver.AddCommand(status)
	stop := &cobra.Command{Use: "stop", Short: "Stop the selected workspace's weaver through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		workspace, _ := cmd.Flags().GetString("workspace")
		jsonOutput, _ := cmd.Flags().GetBool("json")
		return runWeaverLifecycle(cmd.OutOrStdout(), jsonOutput, "weaver-stop", workspace, "", "")
	}}
	stop.Flags().String("workspace", "", "explicit workspace selection (defaults to repo-local .millstrand)")
	stop.Flags().Bool("json", false, "print the full result as JSON without progress messages")
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

	prime := &cobra.Command{Use: "prime", Short: "Print agent orientation", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return cmd.Help()
	}}
	prime.AddCommand(&cobra.Command{Use: "millstrand", Short: "Print orientation for building on .millstrand: resolved source path and the docs to read", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return runMillstrandPrime()
	}})
	root.AddCommand(prime)
	root.AddCommand(&cobra.Command{Use: "changelog", Short: "Print the resolved Millstrand changelog", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return runChangelog()
	}})
	root.AddCommand(newBinCommand())
	stampUsageErrors(root)
	return root
}

func start() (err error) {
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

	meta := client.MillMetadata{ProtocolVersion: client.MillProtocolVersion, MillVersion: config.Version, PID: os.Getpid(), MillID: fmt.Sprintf("mill-%d-%d", os.Getpid(), time.Now().UnixNano()), StateRoot: root, SocketPath: socketPath, StartedAt: time.Now().UTC().Format(time.RFC3339Nano), MillBuild: config.BuildID}
	b, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(metadataPath, append(b, '\n'), 0o644); err != nil {
		return err
	}
	millLogf("Mill ready (PID %d, v%s). Listening on %s", meta.PID, config.Version, meta.SocketPath)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	s := server{meta: meta, children: map[string]*weaverChild{}, custodies: map[string]*process.Custody{}, transitions: map[string]*weaverTransition{}, startClaims: map[string]chan struct{}{}, shutdown: make(chan struct{})}
	go func() {
		<-sig
		s.signalShutdown()
		_ = listener.Close()
	}()
	s.startAutostart()
	defer func() {
		millLogf("Stopping mill and its weavers…")
		s.stopAutostart()
		if shutdownErr := s.stopAll(); shutdownErr != nil {
			if err == nil {
				err = shutdownErr
			} else {
				err = fmt.Errorf("mill stopped with request error: %v; custody shutdown failed: %w",
					err, shutdownErr)
			}
		}
		if err == nil {
			millLogf("Mill stopped")
		}
	}()
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
	reader := bufio.NewReader(conn)
	var raw map[string]json.RawMessage
	if err := json.NewDecoder(reader).Decode(&raw); err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse("", "protocol", "mill/protocol", "malformed mill request", err.Error()))
		return
	}
	var operation string
	_ = json.Unmarshal(raw["operation"], &operation)
	if strings.HasPrefix(operation, "process.") && raw["mill_id"] == nil {
		s.handleProcessControl(conn, raw)
		return
	}
	encoded, err := json.Marshal(raw)
	if err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse("", "protocol", "mill/protocol", "malformed mill request", err.Error()))
		return
	}
	var req client.MillRequest
	if err := json.Unmarshal(encoded, &req); err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse("", "protocol", "mill/protocol", "malformed mill request", err.Error()))
		return
	}
	// The request decoder is allowed to read past the JSON value. Drain the
	// buffered framing whitespace before invoke starts its disconnect watcher;
	// otherwise the watcher could mistake the request's trailing newline for a
	// caller message and cancel a healthy invocation.
	if err := drainRequestWhitespace(reader); err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "protocol", "mill/protocol", "malformed mill request", err.Error()))
		return
	}
	if req.ProtocolVersion != client.MillProtocolVersion || req.RequestID == "" || req.MillID != s.meta.MillID {
		_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "protocol", "mill/identity", "mill request identity mismatch", ""))
		return
	}
	switch req.Operation {
	case "status", "ping":
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: map[string]any{"healthy": true, "version": config.Version, "build_id": config.BuildID, "protocol_version": client.MillProtocolVersion, "pid": s.meta.PID, "mill_id": s.meta.MillID, "state_root": s.meta.StateRoot, "socket_path": s.meta.SocketPath, "started_at": s.meta.StartedAt}})
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
			if req.World.AutoStart {
				if err := config.SetAutoStart(world.ConfigDir, true); err != nil {
					_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-failed", "mill init failed", err.Error()))
					return
				}
				if err := registerAutoStart(world, req.World.CWD, ""); err != nil {
					_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-failed", "mill init failed", err.Error()))
					return
				}
				started, err := s.startWeaver(req.World)
				if err != nil {
					_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-weaver-start-failed", "mill init weaver start failed", err.Error()))
					return
				}
				if isRetainedFailedStart(started) {
					_ = json.NewEncoder(conn).Encode(retainedFailedInitResponse(req.RequestID, started))
					return
				}
			}
			result := config.StealthInitResult{ConfigDir: world.ConfigDir, ConfigFile: world.ConfigFile, Stealth: *stealth}
			if err := result.Validate(); err != nil {
				_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-failed", "mill init failed", err.Error()))
				return
			}
			_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
			return
		}
		result := map[string]any{"config_dir": world.ConfigDir, "config_file": world.ConfigFile}
		if req.World.AutoStart {
			if err := config.SetAutoStart(world.ConfigDir, true); err != nil {
				_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-failed", "mill init failed", err.Error()))
				return
			}
			if err := registerAutoStart(world, req.World.CWD, ""); err != nil {
				_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-failed", "mill init failed", err.Error()))
				return
			}
			started, startErr := s.startWeaver(req.World)
			if startErr != nil {
				_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-weaver-start-failed", "mill init weaver start failed", startErr.Error()))
				return
			}
			if isRetainedFailedStart(started) {
				_ = json.NewEncoder(conn).Encode(retainedFailedInitResponse(req.RequestID, started))
				return
			}
			result["auto_start"] = true
			result["weaver"] = started
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-start":
		result, err := s.startWeaver(req.World)
		if err != nil {
			var dependencyFailure *dependencyLaunchError
			if errors.As(err, &dependencyFailure) {
				_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: false, Error: &client.ResponseError{Type: "domain", Code: "mill/weaver-start-failed", Message: "weaver start failed", Details: map[string]any{"dependency": dependencyFailure.diagnostic}}})
				return
			}
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-start-failed", "weaver start failed", err.Error()))
			return
		}
		world, worldErr := resolveLifecycleWorld(req.World)
		if worldErr != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-start-failed", "weaver start failed", worldErr.Error()))
			return
		}
		cfg, _, configErr := config.Load(world.ConfigDir)
		if configErr != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-start-failed", "weaver start config read failed", configErr.Error()))
			return
		}
		if cfg.AutoStart {
			if registerErr := registerAutoStart(world, req.World.CWD, req.World.Name); registerErr != nil {
				_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-start-failed", "weaver start registration failed", registerErr.Error()))
				return
			}
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-restart":
		result, err := s.restartWeaver(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-restart-failed", "weaver restart failed", err.Error()))
			return
		}
		result = restartResultProjection(result)
		if err := validateRestartResult(result); err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "protocol", "mill/weaver-restart-invalid-result", "weaver restart returned an invalid result", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-status":
		result, err := s.weaverStatus(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-status-failed", "weaver status failed", err.Error()))
			return
		}
		if projection := millStatusProjection(result); projection != nil {
			if err := validateMillStatusProjection(projection); err != nil {
				_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "protocol", "mill/weaver-status-invalid-result", "weaver status returned an invalid projection", err.Error()))
				return
			}
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

func drainRequestWhitespace(reader *bufio.Reader) error {
	for reader.Buffered() > 0 {
		b, err := reader.ReadByte()
		if err != nil {
			return err
		}
		switch b {
		case ' ', '\t', '\r', '\n':
		default:
			return fmt.Errorf("unexpected data after mill request")
		}
	}
	return nil
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
			_ = os.Remove(filepath.Join(stateDir, "weaver.json"))
			_ = os.Remove(filepath.Join(stateDir, "weaver.edn"))
			_ = os.Remove(filepath.Join(stateDir, "weaver.sock"))
		}
	}
	_ = os.Remove(socketPath)
	_ = os.Remove(metadataPath)
	return nil
}

func errorResponse(requestID, typ, code, message, detail string) client.MillResponse {
	return client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: requestID, OK: false, Error: &client.ResponseError{Type: typ, Code: code, Message: message, Details: map[string]any{"detail": detail}}}
}

func isRetainedFailedStart(result map[string]any) bool {
	state, _ := result["state"].(string)
	return state == restartStateFailed
}

func retainedFailedInitResponse(requestID string, status map[string]any) client.MillResponse {
	return client.MillResponse{
		ProtocolVersion: client.MillProtocolVersion,
		RequestID:       requestID,
		OK:              false,
		Error: &client.ResponseError{
			Type:    "domain",
			Code:    "mill/init-weaver-start-failed",
			Message: "mill init weaver start failed",
			Details: map[string]any{"status": status},
		},
	}
}
