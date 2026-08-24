package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"millstrand-strand-cli/internal/client"
	"millstrand-strand-cli/internal/config"
)

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

func TestInvokeRelaysStreamFramesVerbatim(t *testing.T) {
	world, cfg := forwardWorld(t)
	serveFakeWeaverStream(t, world, func(req map[string]any) [][]byte {
		id := req["request_id"]
		return [][]byte{
			mustFrame(t, map[string]any{"protocol_version": client.ProtocolVersion, "request_id": id, "stream": true}),
			mustFrame(t, map[string]any{"i": 0}),
			mustFrame(t, map[string]any{"i": 1}),
			mustFrame(t, map[string]any{"protocol_version": client.ProtocolVersion, "request_id": id, "done": true, "success": true, "result": map[string]any{"emitted": 2}}),
		}
	})
	writeWeaverMetadata(t, world, os.Getpid(), "weaver-stream")

	frames := runInvoke(t, cfg, map[string]any{"name": "test-stream", "argv": []any{}, "payloads": map[string]any{}})
	if len(frames) != 4 {
		t.Fatalf("expected header + 2 emitted + terminator, got %#v", frames)
	}
	if frames[0]["stream"] != true {
		t.Fatalf("first frame is not a stream header: %#v", frames[0])
	}
	if frames[1]["i"] != float64(0) || frames[2]["i"] != float64(1) {
		t.Fatalf("emitted lines not relayed verbatim: %#v", frames)
	}
	if frames[3]["done"] != true || frames[3]["success"] != true {
		t.Fatalf("terminator not relayed verbatim: %#v", frames[3])
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
	go func() {
		outcome := relayInvokeWithAdmission(ctx, socket, "non-reading", map[string]any{
			"name": "large-write",
			"data": strings.Repeat("x", 32<<20),
		}, 0, w, func() { close(admitted) })
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
	case <-time.After(100 * time.Millisecond):
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

func forwardWorld(t *testing.T) (config.World, string) {
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
