package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

func TestForwardOwnedBlockingHelperProcess(t *testing.T) {
	if os.Getenv("MILLSTRAND_FORWARD_HELPER_PROCESS") != "1" {
		return
	}
	select {}
}

func startForwardOwnedBlockingProcess(t *testing.T) *exec.Cmd {
	t.Helper()
	cmd := exec.Command(os.Args[0], "-test.run=TestForwardOwnedBlockingHelperProcess")
	cmd.Env = append(os.Environ(), "MILLSTRAND_FORWARD_HELPER_PROCESS=1")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	pid := cmd.Process.Pid
	t.Cleanup(func() {
		if processAlive(pid) {
			terminatePID(pid)
		}
		waitForPIDExit(pid, time.Second)
	})
	return cmd
}

func TestInvokeRelaysSingleWeaverResponse(t *testing.T) {
	world, cfg := forwardWorld(t)
	var gotReq map[string]any
	serveFakeWeaverStream(t, world, func(req map[string]any) [][]byte {
		gotReq = req
		return [][]byte{mustFrame(t, map[string]any{"protocol_version": client.ProtocolVersion, "request_id": req["request_id"], "ok": true, "result": map[string]any{"title": "hello"}, "error": nil})}
	})
	writeWeaverMetadata(t, world, os.Getpid(), "weaver-invoke")

	frames := runInvoke(t, cfg, map[string]any{"name": "add", "argv": []any{"hello"}, "payloads": map[string]any{}})
	if gotReq["operation"] != "invoke" || gotReq["weaver_id"] != "weaver-invoke" {
		t.Fatalf("weaver did not receive an invoke frame: %#v", gotReq)
	}
	args, ok := gotReq["arguments"].(map[string]any)
	if !ok || args["name"] != "add" {
		t.Fatalf("invoke envelope not forwarded verbatim as arguments: %#v", gotReq["arguments"])
	}
	if len(frames) != 1 || frames[0]["ok"] != true || frames[0]["result"].(map[string]any)["title"] != "hello" {
		t.Fatalf("single response not relayed verbatim: %#v", frames)
	}
}

func TestInvokeReportsTruncatedResponseWithoutReplay(t *testing.T) {
	world, cfg := forwardWorld(t)
	var decodedRequests atomic.Int32
	serveFakeWeaverPartialResponse(t, world, []byte("not-json"), &decodedRequests)
	writeWeaverMetadata(t, world, os.Getpid(), "weaver-truncated")

	frames := runInvoke(t, cfg, map[string]any{"name": "read", "argv": []any{}, "payloads": map[string]any{}})
	if len(frames) != 1 || frames[0]["ok"] != false {
		t.Fatalf("expected one transport error frame, got %#v", frames)
	}
	errFrame := frames[0]["error"].(map[string]any)
	if errFrame["type"] != "transport" || errFrame["code"] != "mill/weaver-forward-failed" {
		t.Fatalf("truncated response was not surfaced as transport error: %#v", errFrame)
	}
	if errFrame["details"].(map[string]any)["request_delivery"] != true {
		t.Fatalf("truncated response must never be replayed: %#v", errFrame)
	}
	if got := decodedRequests.Load(); got != 1 {
		t.Fatalf("ambiguous delivery replayed the request: decoded server requests=%d", got)
	}
}

func TestInvokeReportsPlannedRestartForAcceptedOldGeneration(t *testing.T) {
	world, cfg := forwardWorld(t)
	serveFakeWeaverPartialResponse(t, world, []byte("not-json"), nil)
	writeWeaverMetadata(t, world, os.Getpid(), "weaver-restart")
	transition := &weaverTransition{
		world:          world,
		transitionID:   "transition-restart",
		stateValue:     restartStateProbing,
		cutoverStarted: true,
		done:           make(chan struct{}),
		old: &weaverChild{
			generationID: "generation-weaver-restart",
			identity:     weaverIdentity{WeaverID: "weaver-restart"},
		},
	}
	s := server{children: map[string]*weaverChild{}, transitions: map[string]*weaverTransition{world.ConfigDir: transition}}
	req := client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion,
		RequestID:       "planned-restart",
		Operation:       "invoke",
		World:           client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg},
		Payload:         map[string]any{"name": "mutate", "argv": []any{}, "payloads": map[string]any{}},
	}
	clientConn, serverConn := net.Pipe()
	go func() {
		s.handleInvoke(serverConn, req)
		_ = serverConn.Close()
	}()
	defer func() { _ = clientConn.Close() }()
	_ = clientConn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var frame map[string]any
	if err := json.NewDecoder(clientConn).Decode(&frame); err != nil {
		t.Fatal(err)
	}
	errFrame, ok := frame["error"].(map[string]any)
	if !ok || errFrame["code"] != "weaver/restarted" {
		t.Fatalf("accepted planned interruption was not structured as weaver/restarted: %#v", frame)
	}
	details, ok := errFrame["details"].(map[string]any)
	if !ok || details["request_delivery"] != true || details["sent_once"] != true || details["response"] != "ambiguous" {
		t.Fatalf("planned interruption lost sent-once ambiguity: %#v", errFrame)
	}
}

func TestInvokeDoesNotRelabelFailureWhenRestartStartsAfterAdmission(t *testing.T) {
	world, cfg := forwardWorld(t)
	socket := filepath.Join(world.StateDir, "weaver.sock")
	listener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close(); _ = os.Remove(socket) })

	received := make(chan struct{})
	release := make(chan struct{})
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer func() { _ = conn.Close() }()
		var request map[string]any
		if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&request); err != nil {
			return
		}
		close(received)
		<-release
		_, _ = conn.Write([]byte("not-json"))
	}()
	writeWeaverMetadata(t, world, os.Getpid(), "weaver-late-restart")

	s := &server{children: map[string]*weaverChild{}}
	req := client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion,
		RequestID:       "late-restart",
		Operation:       "invoke",
		World:           client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg},
		Payload:         map[string]any{"name": "mutate", "argv": []any{}, "payloads": map[string]any{}},
	}
	clientConn, serverConn := net.Pipe()
	done := make(chan struct{})
	go func() {
		s.handleInvoke(serverConn, req)
		close(done)
	}()
	defer func() { _ = clientConn.Close(); _ = serverConn.Close() }()

	select {
	case <-received:
	case <-time.After(time.Second):
		t.Fatal("weaver did not receive the admitted invocation")
	}
	// There was no transition during target selection or request delivery.
	// Create the planned cutover only after the old generation has received the
	// frame, then let it interrupt the response.
	transition := &weaverTransition{
		world:          world,
		transitionID:   "transition-after-send",
		stateValue:     restartStateRunning,
		cutoverStarted: true,
		done:           make(chan struct{}),
		old: &weaverChild{
			generationID: "generation-weaver-late-restart",
			identity:     weaverIdentity{WeaverID: "weaver-late-restart"},
		},
	}
	s.mu.Lock()
	s.transitions = map[string]*weaverTransition{world.ConfigDir: transition}
	s.mu.Unlock()
	s.completeTransition(transition, map[string]any{"state": restartStateRunning}, nil, false)
	close(release)

	_ = clientConn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var frame map[string]any
	if err := json.NewDecoder(clientConn).Decode(&frame); err != nil {
		t.Fatal(err)
	}
	errFrame, ok := frame["error"].(map[string]any)
	if !ok || errFrame["code"] != "mill/weaver-forward-failed" {
		t.Fatalf("restart created after admission relabelled an unrelated failure: %#v", frame)
	}
	details, ok := errFrame["details"].(map[string]any)
	if !ok || details["request_delivery"] != true || details["sent_once"] != nil {
		t.Fatalf("ordinary transport failure lost its non-replay delivery evidence: %#v", errFrame)
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("invoke did not finish")
	}
}

func TestInvokeClassifiesActualRestartAndDurableCleanup(t *testing.T) {
	world, cfg := forwardWorld(t)
	t.Setenv("MILLSTRAND_SOURCE", tempSource(t))
	oldSocket := filepath.Join(world.StateDir, "weaver.sock")
	listener, err := net.Listen("unix", oldSocket)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close(); _ = os.Remove(oldSocket) })

	accepted := make(chan struct{})
	releaseResponse := make(chan struct{})
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer func() { _ = conn.Close() }()
		var request map[string]any
		if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&request); err != nil {
			return
		}
		close(accepted)
		<-releaseResponse
		_, _ = conn.Write([]byte("not-json"))
	}()

	var launches atomic.Int32
	var oldPID atomic.Int32
	var replacementCleanupObserved atomic.Bool
	originalLaunch, originalProbe, originalReady := launchWeaver, probeRuntime, waitForReplacementReadyStatus
	t.Cleanup(func() {
		launchWeaver, probeRuntime, waitForReplacementReadyStatus = originalLaunch, originalProbe, originalReady
	})
	launchWeaver = func(source string, args []string, _ []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := startForwardOwnedBlockingProcess(t)
		if launches.Add(1) == 1 {
			oldPID.Store(int32(cmd.Process.Pid))
			writeWeaverMetadata(t, world, cmd.Process.Pid, "old-weaver")
		} else {
			if _, statErr := os.Stat(filepath.Join(world.StateDir, "weaver.json")); os.IsNotExist(statErr) {
				replacementCleanupObserved.Store(true)
			}
			writeWeaverMetadata(t, world, cmd.Process.Pid, "new-weaver")
		}
		return cmd, nil
	}
	probeStarted := make(chan struct{})
	releaseProbe := make(chan struct{})
	probeRuntime = func(string, config.World) (restartProbeResult, error) {
		close(probeStarted)
		<-releaseProbe
		return restartProbeResult{Success: true, Stage: "probe/complete", ProbeWorkspace: "probe", SourceWorkspace: cfg, Completed: []string{}, Diagnostics: []map[string]any{}, Log: "probe.log"}, nil
	}
	waitForReplacementReadyStatus = func(world config.World, pid int, done <-chan error, timeout time.Duration) (map[string]any, error) {
		return map[string]any{
			"state": "running", "pid": pid, "weaver_id": "new-weaver", "generation_id": "new-generation",
			"started_at": time.Now().UTC().Format(time.RFC3339Nano), "socket_path": filepath.Join(world.StateDir, "weaver.sock"),
			"config_dir": world.ConfigDir, "state_dir": world.StateDir, "data_dir": world.DataDir,
		}, nil
	}

	s := &server{children: map[string]*weaverChild{}, transitions: map[string]*weaverTransition{}}
	t.Cleanup(func() { _ = s.stopAll() })
	req := client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg, ReadyTimeoutMs: 2_000}
	if _, err := s.startWeaver(req); err != nil {
		t.Fatal(err)
	}
	restartDone := make(chan struct {
		result map[string]any
		err    error
	})
	go func() {
		result, restartErr := s.restartWeaver(req)
		restartDone <- struct {
			result map[string]any
			err    error
		}{result, restartErr}
	}()
	select {
	case <-probeStarted:
	case <-time.After(time.Second):
		t.Fatal("restart did not enter its durable probe")
	}

	invokeReq := client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion,
		RequestID:       "actual-restart",
		Operation:       "invoke",
		World:           client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg},
		Payload:         map[string]any{"name": "mutate", "argv": []any{}, "payloads": map[string]any{}},
	}
	clientConn, serverConn := net.Pipe()
	invokeDone := make(chan struct{})
	go func() {
		s.handleInvoke(serverConn, invokeReq)
		close(invokeDone)
	}()
	t.Cleanup(func() { _ = clientConn.Close(); _ = serverConn.Close() })
	select {
	case <-accepted:
	case <-time.After(time.Second):
		t.Fatal("old generation did not receive the admitted invocation")
	}
	close(releaseProbe)
	var restartResult struct {
		result map[string]any
		err    error
	}
	select {
	case restartResult = <-restartDone:
	case <-time.After(5 * time.Second):
		t.Fatal("actual restart did not complete")
	}
	if restartResult.err != nil || restartResult.result["state"] != restartStateRunning {
		t.Fatalf("actual restart failed: result=%#v err=%v", restartResult.result, restartResult.err)
	}
	close(releaseResponse)
	_ = clientConn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var frame map[string]any
	if err := json.NewDecoder(clientConn).Decode(&frame); err != nil {
		t.Fatal(err)
	}
	if got := frame["error"].(map[string]any)["code"]; got != "weaver/restarted" {
		t.Fatalf("durable restart did not classify the accepted old call: %#v", frame)
	}
	select {
	case <-invokeDone:
	case <-time.After(time.Second):
		t.Fatal("invoke did not finish after replacement interruption")
	}
	record, ok, err := readRestartRecordDetailed(world)
	if err != nil || !ok || record.State != restartStateRunning || record.PreviousWeaver != "old-weaver" || record.PreviousGeneration != "generation-old-weaver" || !record.OldGenerationStopped {
		t.Fatalf("durable transition lost old identity: record=%#v ok=%v err=%v", record, ok, err)
	}
	if !replacementCleanupObserved.Load() {
		t.Fatal("replacement launch did not observe cleanup of old generation artifacts")
	}
	if processAlive(int(oldPID.Load())) {
		t.Fatal("actual restart left the old weaver process alive")
	}
}

func TestInvokeRelaysStreamFramesVerbatim(t *testing.T) {
	world, cfg := forwardWorld(t)
	serveFakeWeaverStream(t, world, func(req map[string]any) [][]byte {
		id := req["request_id"]
		return [][]byte{
			mustFrame(t, map[string]any{"protocol_version": client.ProtocolVersion, "request_id": id, "stream": true}),
			mustFrame(t, map[string]any{"i": 0}),
			mustFrame(t, map[string]any{"done": true}),
			mustFrame(t, map[string]any{"i": 1}),
			mustFrame(t, map[string]any{"protocol_version": client.ProtocolVersion, "request_id": id, "done": true, "success": true, "result": map[string]any{"emitted": 2}}),
		}
	})
	writeWeaverMetadata(t, world, os.Getpid(), "weaver-stream")

	frames := runInvoke(t, cfg, map[string]any{"name": "test-stream", "argv": []any{}, "payloads": map[string]any{}})
	if len(frames) != 5 {
		t.Fatalf("expected header + 3 emitted + terminator, got %#v", frames)
	}
	if frames[0]["stream"] != true {
		t.Fatalf("first frame is not a stream header: %#v", frames[0])
	}
	if frames[1]["i"] != float64(0) || frames[2]["done"] != true || frames[3]["i"] != float64(1) {
		t.Fatalf("emitted lines not relayed verbatim: %#v", frames)
	}
	if frames[4]["done"] != true || frames[4]["success"] != true {
		t.Fatalf("terminator not relayed verbatim: %#v", frames[4])
	}
}

func TestInvokeReportsNoSelectedWorldWeaver(t *testing.T) {
	_, cfg := forwardWorld(t)
	frames := runInvoke(t, cfg, map[string]any{"name": "list", "argv": []any{}, "payloads": map[string]any{}})
	if len(frames) != 1 || frames[0]["ok"] != false {
		t.Fatalf("expected a single error frame, got %#v", frames)
	}
	errFrame := frames[0]["error"].(map[string]any)
	if errFrame["code"] != "mill/no-selected-weaver" {
		t.Fatalf("expected no-selected-weaver error, got %#v", errFrame)
	}
}

func TestInvokeReportsStaleSelectedWorldWeaver(t *testing.T) {
	world, cfg := forwardWorld(t)
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{bad`), 0o644); err != nil {
		t.Fatal(err)
	}
	frames := runInvoke(t, cfg, map[string]any{"name": "list", "argv": []any{}, "payloads": map[string]any{}})
	if len(frames) != 1 || frames[0]["ok"] != false {
		t.Fatalf("expected a single error frame, got %#v", frames)
	}
	errFrame := frames[0]["error"].(map[string]any)
	if errFrame["code"] != "mill/stale-selected-weaver" || errFrame["details"].(map[string]any)["stale_reason"] == nil {
		t.Fatalf("expected stale-selected-weaver error, got %#v", errFrame)
	}
}

func TestInvokeCancellationBeforeReplacementAdmissionSendsNoWeaverFrame(t *testing.T) {
	world, cfg := forwardWorld(t)
	transition := &weaverTransition{world: world, transitionID: "transition", stateValue: restartStateRestarting, done: make(chan struct{})}
	s := server{children: map[string]*weaverChild{}, transitions: map[string]*weaverTransition{world.ConfigDir: transition}}
	clientConn, serverConn := net.Pipe()
	req := client.MillRequest{ProtocolVersion: client.MillProtocolVersion, RequestID: "req-cancel", Operation: "invoke", World: client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}, Payload: map[string]any{"name": "mutate"}}
	done := make(chan struct{})
	go func() {
		s.handleInvoke(serverConn, req)
		close(done)
	}()
	_ = clientConn.Close()
	close(transition.done)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("cancelled invocation did not return")
	}
	_ = serverConn.Close()
	// There is deliberately no Weaver socket: reaching relayInvoke after the
	// caller closed would be a test failure in the admission path above.
}

func TestInvokeDeadlineBeforeReplacementAdmissionSendsNoWeaverFrame(t *testing.T) {
	world, cfg := forwardWorld(t)
	transition := &weaverTransition{world: world, transitionID: "transition-deadline", stateValue: restartStateRestarting, done: make(chan struct{})}
	s := server{children: map[string]*weaverChild{}, transitions: map[string]*weaverTransition{world.ConfigDir: transition}}
	clientConn, serverConn := net.Pipe()
	req := client.MillRequest{ProtocolVersion: client.MillProtocolVersion, RequestID: "req-deadline", Operation: "invoke", World: client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}, Payload: map[string]any{"name": "mutate", "timeout": float64(1)}}
	done := make(chan struct{})
	go func() {
		s.handleInvoke(serverConn, req)
		close(done)
	}()
	defer func() { _ = clientConn.Close(); _ = serverConn.Close() }()
	_ = clientConn.SetReadDeadline(time.Now().Add(time.Second))
	var frame map[string]any
	if err := json.NewDecoder(clientConn).Decode(&frame); err != nil {
		t.Fatal(err)
	}
	errFrame, ok := frame["error"].(map[string]any)
	if !ok || errFrame["code"] != "mill/weaver-restart-failed" {
		t.Fatalf("deadline before admission returned the wrong envelope: %#v", frame)
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("deadline admission wait did not return")
	}
}

func TestInvokeThroughHandleAcceptsSplitTrailingWhitespace(t *testing.T) {
	world, cfg := forwardWorld(t)
	serveFakeWeaverStream(t, world, func(req map[string]any) [][]byte {
		return [][]byte{mustFrame(t, map[string]any{
			"protocol_version": client.ProtocolVersion,
			"request_id":       req["request_id"],
			"ok":               true,
			"result":           map[string]any{"ok": true},
			"error":            nil,
		})}
	})
	writeWeaverMetadata(t, world, os.Getpid(), "split-whitespace")
	s := server{meta: client.MillMetadata{MillID: "mill-split"}, children: map[string]*weaverChild{}}
	req := client.MillRequest{
		ProtocolVersion: client.MillProtocolVersion,
		RequestID:       "split-request",
		MillID:          "mill-split",
		Operation:       "invoke",
		World:           client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg},
		Payload:         map[string]any{"name": "status"},
	}
	clientConn, serverConn := net.Pipe()
	done := make(chan struct{})
	go func() {
		s.handle(serverConn)
		close(done)
	}()
	defer func() {
		_ = clientConn.Close()
		_ = serverConn.Close()
	}()
	frame, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := clientConn.Write(frame); err != nil {
		t.Fatal(err)
	}
	// Send the legal framing whitespace separately, after Decode has returned.
	if _, err := clientConn.Write([]byte("\n \t\r")); err != nil {
		t.Fatal(err)
	}
	_ = clientConn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var response map[string]any
	if err := json.NewDecoder(clientConn).Decode(&response); err != nil {
		t.Fatalf("split trailing whitespace cancelled invoke: %v", err)
	}
	if response["ok"] != true {
		t.Fatalf("unexpected response: %#v", response)
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("mill connection handler did not finish")
	}
}

func TestInvokeCancellationInterruptsNonReadingWeaverWrite(t *testing.T) {
	world, _ := forwardWorld(t)
	socket := filepath.Join(world.StateDir, "non-reading.sock")
	listener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = listener.Close(); _ = os.Remove(socket) }()
	accepted := make(chan struct{})
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr == nil {
			close(accepted)
			defer func() { _ = conn.Close() }()
			select {}
		}
	}()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	w := bufio.NewWriter(io.Discard)
	writeDone := make(chan error, 1)
	admitted := make(chan struct{})
	writeStarted := make(chan struct{})
	go func() {
		outcome := relayInvokeWithAdmission(ctx, socket, "non-reading", map[string]any{
			"name": "large-write",
			"data": strings.Repeat("x", 32<<20),
		}, 0, w, func() { close(admitted) }, func() { close(writeStarted) })
		writeDone <- outcome.err
	}()
	select {
	case <-accepted:
	case <-time.After(time.Second):
		t.Fatal("non-reading Weaver did not accept the socket")
	}
	select {
	case <-admitted:
		t.Fatal("non-reading Weaver accepted the complete request unexpectedly")
	case <-writeStarted:
	}
	cancel()
	select {
	case err := <-writeDone:
		if err == nil || !strings.Contains(err.Error(), "canceled") {
			t.Fatalf("cancelling caller did not interrupt blocked write: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelling caller left blocked Weaver write running")
	}
}

func TestInvokeAdmissionDoesNotBlockAnotherWorkspace(t *testing.T) {
	worldA, cfgA := forwardWorld(t)
	cfgB := t.TempDir()
	if err := os.WriteFile(filepath.Join(cfgB, "config.json"), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	worldB, err := config.RuntimeWorld(cfgB)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(worldB.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("unix", filepath.Join(worldA.StateDir, "weaver.sock"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = listener.Close() }()
	accepted := make(chan struct{})
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr == nil {
			close(accepted)
			defer func() { _ = conn.Close() }()
			select {}
		}
	}()
	if err := os.MkdirAll(worldA.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	writeWeaverMetadata(t, worldA, os.Getpid(), "non-reading")
	serveFakeWeaverStream(t, worldB, func(req map[string]any) [][]byte {
		return [][]byte{mustFrame(t, map[string]any{
			"protocol_version": client.ProtocolVersion,
			"request_id":       req["request_id"],
			"ok":               true,
			"result":           map[string]any{"workspace": "other"},
		})}
	})
	writeWeaverMetadata(t, worldB, os.Getpid(), "responsive")
	s := &server{children: map[string]*weaverChild{}}
	largeReq := client.MillRequest{ProtocolVersion: client.MillProtocolVersion, RequestID: "blocked", Operation: "invoke", World: client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfgA}, Payload: map[string]any{"name": "large-write", "data": strings.Repeat("x", 32<<20)}}
	aClient, aServer := net.Pipe()
	aDone := make(chan struct{})
	go func() {
		s.handleInvoke(aServer, largeReq)
		close(aDone)
	}()
	defer func() { _ = aClient.Close(); _ = aServer.Close() }()
	select {
	case <-accepted:
	case <-time.After(time.Second):
		t.Fatal("non-reading workspace did not reach its Weaver")
	}

	bReq := client.MillRequest{ProtocolVersion: client.MillProtocolVersion, RequestID: "other", Operation: "invoke", World: client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfgB}, Payload: map[string]any{"name": "status"}}
	bClient, bServer := net.Pipe()
	bDone := make(chan struct{})
	go func() {
		s.handleInvoke(bServer, bReq)
		close(bDone)
	}()
	defer func() { _ = bClient.Close(); _ = bServer.Close() }()
	_ = bClient.SetReadDeadline(time.Now().Add(time.Second))
	var response map[string]any
	if err := json.NewDecoder(bClient).Decode(&response); err != nil {
		t.Fatalf("another workspace was blocked by non-reading Weaver: %v", err)
	}
	if response["ok"] != true {
		t.Fatalf("responsive workspace returned an error: %#v", response)
	}
	_ = aClient.Close()
	select {
	case <-aDone:
	case <-time.After(time.Second):
		t.Fatal("cancelled non-reading workspace did not return")
	}
	select {
	case <-bDone:
	case <-time.After(time.Second):
		t.Fatal("responsive workspace handler did not finish")
	}
}

// runInvoke drives handleInvoke over an in-memory pipe and returns the relayed
// NDJSON frames.
func runInvoke(t *testing.T, cfg string, envelope map[string]any) []map[string]any {
	t.Helper()
	s := server{children: map[string]*weaverChild{}}
	req := client.MillRequest{ProtocolVersion: client.MillProtocolVersion, RequestID: "req-1", Operation: "invoke", World: client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}, Payload: envelope}
	clientConn, srvConn := net.Pipe()
	go func() {
		s.handleInvoke(srvConn, req)
		_ = srvConn.Close()
	}()
	defer func() { _ = clientConn.Close() }()
	_ = clientConn.SetDeadline(time.Now().Add(5 * time.Second))
	var frames []map[string]any
	r := bufio.NewReader(clientConn)
	for {
		line, err := r.ReadBytes('\n')
		if len(line) > 0 {
			var frame map[string]any
			if uerr := json.Unmarshal(line, &frame); uerr != nil {
				t.Fatalf("relayed a non-JSON frame %q: %v", line, uerr)
			}
			frames = append(frames, frame)
		}
		if err != nil {
			break
		}
	}
	return frames
}

func mustFrame(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func forwardWorld(t testing.TB) (config.World, string) {
	t.Helper()
	xdg, err := os.MkdirTemp("/tmp", "mill-forward-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(xdg) })
	t.Setenv("XDG_STATE_HOME", xdg)
	cfg := t.TempDir()
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	return world, cfg
}

// serveFakeWeaverStream stands up a weaver socket that decodes the invoke
// request and writes the handler's frames as NDJSON, one flushed line each,
// then closes — mirroring the real weaver's single/stream response shape.
func serveFakeWeaverStream(t *testing.T, world config.World, handler func(map[string]any) [][]byte) {
	t.Helper()
	socket := filepath.Join(world.StateDir, "weaver.sock")
	_ = os.Remove(socket)
	ln, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close(); _ = os.Remove(socket) })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer func() { _ = c.Close() }()
				_ = c.SetDeadline(time.Now().Add(time.Second))
				var req map[string]any
				if err := json.NewDecoder(bufio.NewReader(c)).Decode(&req); err != nil {
					return
				}
				w := bufio.NewWriter(c)
				for _, frame := range handler(req) {
					_, _ = w.Write(frame)
					_, _ = w.Write([]byte("\n"))
					_ = w.Flush()
				}
			}(conn)
		}
	}()
}

func serveFakeWeaverPartialResponse(t *testing.T, world config.World, response []byte, decodedRequests *atomic.Int32) {
	t.Helper()
	socket := filepath.Join(world.StateDir, "weaver.sock")
	_ = os.Remove(socket)
	ln, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close(); _ = os.Remove(socket) })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer func() { _ = c.Close() }()
				_ = c.SetDeadline(time.Now().Add(time.Second))
				var req map[string]any
				if err := json.NewDecoder(bufio.NewReader(c)).Decode(&req); err != nil {
					return
				}
				if decodedRequests != nil {
					decodedRequests.Add(1)
				}
				_, _ = c.Write(response)
			}(conn)
		}
	}()
}
