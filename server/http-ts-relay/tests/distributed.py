"""Distributed protocol and production Kotlin uploader tests, including capped/slow HTTP download paths."""
import argparse
import contextlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import socket
import tempfile
import threading
import time
from integration import relay, upload, Playback, ts


def get(port, path, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
    connection.request("GET", path, headers=headers or {})
    response = connection.getresponse()
    result = response.status, dict(response.getheaders()), response.read()
    connection.close()
    return result


def block(port, sequence, body, epoch=1000, session="ordered", duration=100_000, final=False):
    return upload(port, session, sequence, body, duration, final,
                  {"X-Session-Started-Ms": str(epoch), "X-Start-Us": str(sequence * 100_000)})


def merge_args(a, b=None, gap=2):
    result = ["--mode", "merge", "--upstream", f"http://127.0.0.1:{a}", "--gap-timeout", str(gap)]
    if b is not None:
        result += ["--upstream", f"http://127.0.0.1:{b}"]
    return result


def store_status(binary):
    logs = []

    def wait_for(predicate, timeout=6):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            matches = [line for line in list(logs) if line.startswith("[status]") and predicate(line)]
            if matches:
                return matches[-1]
            time.sleep(.02)
        raise AssertionError(logs)

    with relay(binary, retention=3, logs=logs, extra_args=["--mode", "store"]) as source:
        idle = wait_for(lambda line: "mode=store" in line)
        assert "session=- seq=-" in idle and "feedback=unknown" in idle
        assert "upload=0.00 KiB/s download=0.00 KiB/s" in idle
        body = ts(1)
        assert block(source, 100, body)[0] == 200
        assert block(source, 98, ts(2))[0] == 200
        assert block(source, 100, body)[0] == 200
        assert block(source, 100, ts(3))[0] == 409
        assert get(source, "/chunks/live/ordered/98")[2] == ts(2)
        assert get(source, "/chunks/live/ordered/98")[2] == ts(2)
        feedback = {"X-Download-Rate-Bps": "125000", "X-Rescue-Session": "ordered", "X-Rescue-Sequence": "99"}
        for _ in range(2):
            assert get(source, "/feedback/live", feedback)[0] == 200
        assert get(source, "/status/live")[1]["X-Rescue-Sequence"] == "99"
        active = wait_for(lambda line: "accepted=2 duplicates=1" in line)
        assert "session=ordered seq=100" in active, active
        assert "cache=47.00 KiB cached=0.20 s blocks=2" in active, active
        assert "uploaded=70.50 KiB downloaded=47.00 KiB" in active, active
        assert "upload=0.00 KiB/s" not in active and "download=0.00 KiB/s" not in active, active
        assert "feedback=122.07 KiB/s redirect_requests=1 redirect_received=0 rescue=ordered:99" in active, active
        redirected = {"X-Session-Started-Ms": "2000", "X-Start-Us": "0",
                      "X-Redirect-From": "http://other-store:8080/upload/live", "X-Redirect-Reason": "download-slow"}
        assert upload(source, "restart", 5, body, extra=redirected)[0] == 200
        assert upload(source, "restart", 5, body, extra=redirected)[0] == 200
        assert block(source, 200, body)[0] == 200
        latest = wait_for(lambda line: "accepted=4 duplicates=2" in line)
        assert "session=restart seq=5" in latest and "redirect_received=1" in latest, latest
        expired = wait_for(lambda line: "blocks=0" in line and "expired=4" in line)
        assert "cache=0.00 KiB cached=0.00 s" in expired, expired
        assert "upload=0.00 KiB/s download=0.00 KiB/s" in expired, expired
        assert get(source, "/chunks/live/restart/5")[0] == 404
        assert all("mode=store" in line and "pending=" not in line and "state=" not in line
                   for line in logs if line.startswith("[status]")), logs
    print("PASS: store status reports real cache/traffic, deduplicates counters, tracks latest session and expires idle cache", flush=True)

    logs.clear()
    with relay(binary, buffer_mb=16, logs=logs, extra_args=["--mode", "store"]) as source:
        large = ts(1, packets=50_000)
        assert block(source, 0, large)[0] == 200
        assert block(source, 1, large)[0] == 200
        limited = wait_for(lambda line: "accepted=2" in line and "evicted=1" in line)
        assert "blocks=1" in limited and f"cache={len(large) / 1024:.2f} KiB" in limited, limited
        assert get(source, "/chunks/live/ordered/0")[0] == 404
    print("PASS: store status distinguishes memory-budget eviction from retention expiry", flush=True)


def protocol(binary):
    empty = subprocess.run([str(binary), "--mode", "merge"], capture_output=True, text=True, timeout=5)
    assert empty.returncode != 0 and "Invalid relay options" in empty.stdout + empty.stderr
    with relay(binary, logs=(store_logs := []), extra_args=["--mode", "store"]) as source:
        headers = {"X-Session-Started-Ms": "1000", "X-Start-Us": "0",
                   "X-Redirect-From": "http://other-store:8080/upload/live", "X-Redirect-Reason": "upload-failed"}
        logs = []
        with relay(binary, delay=.1, logs=logs, extra_args=merge_args(source)) as output:
            viewer = Playback(output, len(ts(1)))
            assert upload(source, "redirected", 0, ts(1), extra=headers)[0] == 200
            assert upload(source, "redirected", 0, ts(1), extra=headers)[0] == 200
            data = get(source, "/chunks/live/redirected/0")
            assert data[2] == ts(1) and data[1]["X-Redirect-From"] == headers["X-Redirect-From"]
            assert data[1]["X-Redirect-Reason"] == "upload-failed"
            assert viewer.finish() == ts(1)
            deadline = time.monotonic() + 3
            while not any("redirect_received=1" in line for line in logs) and time.monotonic() < deadline:
                time.sleep(.02)
            assert any("[redirect] phase=received session=redirected seq=0" in line and "reason=upload-failed count=1" in line for line in logs), logs
            assert any("redirect_received=1" in line for line in logs), logs
        events = [line for line in store_logs if "[redirect] phase=stored" in line]
        assert len(events) == 2 and all("count=1" in line for line in events), store_logs
        assert "result=accepted" in events[0] and "result=duplicate" in events[1], events
    print("PASS: redirected uploads carry source/reason to store and merge logs, duplicate ACKs do not inflate store count", flush=True)
    with relay(binary, extra_args=["--mode", "store"]) as source:
        with relay(binary, delay=.3, extra_args=merge_args(source)) as output:
            viewer = Playback(output, len(ts(1)) * 4, timeout=10)
            for seq in (2, 0, 3, 1):
                assert block(source, seq, ts(seq + 1))[0] == 200
            assert block(source, 4, b"", duration=0, final=True)[0] == 200
            assert viewer.finish() == b"".join(ts(tag) for tag in range(1, 5))
    print("PASS: single-upstream merge preserves ordered bytes; zero upstreams rejected", flush=True)

    with relay(binary, extra_args=["--mode", "store"]) as a, relay(binary, extra_args=["--mode", "store"]) as b:
        assert "X-Download-Rate-Bps" not in get(a, "/status/live")[1], "Unmeasured stores must not advertise a guessed cap"
        get(b, "/feedback/live", {"X-Download-Rate-Bps": "2500000"})
        assert get(b, "/status/live")[1]["X-Download-Rate-Bps"] == "2500000", "Measured feedback must not be capped at 3 Mbps"
        body = ts(1)
        assert block(a, 0, body)[0] == 200
        assert block(a, 0, body)[0] == 200
        assert block(a, 0, ts(2))[0] == 409
        assert get(a, "/chunks/live/ordered/0")[2] == body
        assert get(a, "/live/live.ts")[0] == 404
        assert get(a, "/index/live")[2].count(b"ordered ") == 1
        assert get(a, "/index/live", {"X-Index-Epoch": "1000", "X-Index-Session": "ordered", "X-Index-From": "1"})[2] == b"TS-CHUNKS 1\n"
        get(a, "/feedback/live", {"X-Download-Rate-Bps": "125000", "X-Rescue-Session": "ordered", "X-Rescue-Sequence": "0"})
        headers = get(a, "/status/live")[1]
        assert headers["X-Download-Rate-Bps"] == "125000"
        assert headers["X-Rescue-Sequence"] == "0"
        # A healthy store must relay a request even when the missing block lives elsewhere.
        assert get(a, "/chunks/live/ordered/99")[0] == 404
        get(a, "/feedback/live", {"X-Rescue-Session": "ordered", "X-Rescue-Sequence": "99"})
        assert get(a, "/status/live")[1]["X-Rescue-Sequence"] == "99"
        with relay(binary, delay=.3, extra_args=merge_args(a, b)) as output:
            viewer = Playback(output, len(body) * 9, timeout=10)
            for seq in (3, 1, 5, 2, 4):
                assert block(a if seq % 2 == 0 else b, seq, ts(seq + 1))[0] == 200
            assert block(b, 6, b"", duration=0, final=True)[0] == 200
            deadline = time.monotonic() + 5
            while len(viewer.output) < len(body) * 6 and time.monotonic() < deadline:
                time.sleep(.01)
            assert bytes(viewer.output[:len(body) * 6]) == b"".join(ts(i) for i in range(1, 7))
            # A restarted sender has a new epoch/session; old stored chunks must not be replayed.
            for seq in range(3):
                assert block(a if seq % 2 == 0 else b, seq, ts(seq + 8), epoch=2000, session="restart")[0] == 200
            assert viewer.finish() == b"".join(ts(i) for i in (*range(1, 7), *range(8, 11)))
    print("PASS: stores deduplicate/conflict-check, feedback/rescue, shuffled merge and session restart", flush=True)

    with relay(binary, extra_args=["--mode", "store"]) as a, relay(binary, extra_args=["--mode", "store"]) as b:
        with relay(binary, delay=.3, extra_args=merge_args(a, b, .5)) as output:
            viewer = Playback(output, len(ts(1)) * 3, timeout=10)
            for seq in (0, 2, 3):
                assert block(a if seq % 2 == 0 else b, seq, ts(seq + 1))[0] == 200
            assert viewer.finish() == ts(1) + ts(3) + ts(4)
    print("PASS: permanently missing block expires without an infinite playback wait", flush=True)

    with relay(binary, retention=1, extra_args=["--mode", "store"]) as source:
        assert block(source, 0, ts(1))[0] == 200
        time.sleep(1.1)
        assert get(source, "/chunks/live/ordered/0")[0] == 404
    print("PASS: store retention expires payloads", flush=True)

    with relay(binary, extra_args=["--mode", "store"]) as a, relay(binary, extra_args=["--mode", "store"]) as b:
        with capped_proxy(a) as (proxy, state):
            state["fail_downloads"] = True
            assert block(a, 0, ts(1))[0] == 200
            with relay(binary, extra_args=merge_args(proxy, b)):
                deadline = time.monotonic() + 5
                while state["failed_downloads"] < 4 and time.monotonic() < deadline:
                    time.sleep(.05)
                assert state["failed_downloads"] >= 4, "Merger did not attempt the unavailable chunk"
                assert "X-Download-Rate-Bps" not in get(a, "/status/live")[1], "Failed initial downloads must not advertise guessed bandwidth"
                state["fail_downloads"] = False
                deadline = time.monotonic() + 5
                headers = {}
                while "X-Download-Rate-Bps" not in headers and time.monotonic() < deadline:
                    time.sleep(.1)
                    headers = get(a, "/status/live")[1]
                assert int(headers.get("X-Download-Rate-Bps", 0)) > 0, "Successful download did not publish a measured rate"
    print("PASS: initial download failures keep bandwidth unknown; successful download publishes measurement", flush=True)


@contextlib.contextmanager
def capped_proxy(upstream, rate=375_000, slow_first=False):
    state = {"slow": slow_first, "downloaded": 0, "rescued": False, "fail_downloads": False, "failed_downloads": 0,
             "index_delay": 0, "keep_alive": False, "clients": set()}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def handle(self):
            try:
                super().handle()
            except OSError:
                pass

        def forward(self):
            try:
                state["clients"].add(self.client_address)
                if self.path.startswith("/index/") and state["index_delay"]:
                    delay = state["index_delay"]
                    state["index_delay"] = 0
                    time.sleep(delay)
                if self.path.startswith("/chunks/") and state["fail_downloads"]:
                    state["failed_downloads"] += 1
                    self.send_error(503)
                    self.close_connection = True
                    return
                data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                connection = http.client.HTTPConnection("127.0.0.1", upstream, timeout=20)
                headers = dict(self.headers)
                headers["Connection"] = "close"
                connection.request(self.command, self.path, body=data if self.command == "POST" else None, headers=headers)
                result = connection.getresponse()
                body = result.read()
                connection.close()
                self.send_response(result.status)
                for key, value in result.getheaders():
                    if key.lower() not in ("connection", "server", "date"):
                        self.send_header(key, value)
                self.send_header("Connection", "keep-alive" if state["keep_alive"] else "close")
                self.end_headers()
                self.close_connection = not state["keep_alive"]
                if self.command == "POST" and self.headers.get("X-Sequence") == "0":
                    state["rescued"] = True
                if self.path.startswith("/chunks/") and result.status == 200:
                    if state["slow"] and self.path.endswith("/0"):
                        state["slow"] = False
                        time.sleep(8)  # Head block has been accepted; only its download path suddenly stalls.
                    begin = time.monotonic()
                    for offset in range(0, len(body), 8192):
                        part = body[offset:offset + 8192]
                        due = begin + (offset + len(part)) / rate
                        time.sleep(max(0, due - time.monotonic()))
                        self.wfile.write(part)
                        self.wfile.flush()
                    state["downloaded"] += len(body)
                else:
                    self.wfile.write(body)
            except (OSError, http.client.HTTPException):
                pass  # The merger cancels slow sockets when it receives a replica elsewhere.

        do_GET = forward
        do_POST = forward

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server.server_port, state
    finally:
        server.shutdown()
        server.server_close()
        thread.join(3)


def upstream_network(binary):
    with relay(binary, extra_args=["--mode", "store"]) as source:
        assert block(source, 0, ts(1))[0] == 200
        connection = http.client.HTTPConnection("127.0.0.1", source, timeout=3)
        reused = None
        for path in ("/index/live", "/feedback/live", "/chunks/live/ordered/0", "/status/live"):
            connection.request("GET", path, headers={"Connection": "keep-alive"})
            response = connection.getresponse()
            assert response.status == 200 and response.getheader("Connection") == "keep-alive"
            body = response.read()
            if "/chunks/" in path:
                assert body == ts(1)
            if reused is None:
                reused = connection.sock
            assert connection.sock is reused and reused is not None, "Store did not retain the TCP connection"
        connection.close()
    print("PASS: store serves index/feedback/chunk/status on one persistent TCP connection", flush=True)

    with relay(binary, extra_args=["--mode", "store"]) as source:
        with capped_proxy(source) as (proxy, state):
            state["keep_alive"] = True
            with relay(binary, delay=.3, extra_args=merge_args(proxy)) as output:
                viewer = Playback(output, 4 * len(ts(1)), timeout=10)
                for seq in range(4):
                    assert block(source, seq, ts(seq + 1))[0] == 200
                assert viewer.finish() == b"".join(ts(tag) for tag in range(1, 5))
                time.sleep(.5)
                assert len(state["clients"]) == 2, state["clients"]  # One data connection, one feedback connection.
    print("PASS: merger reuses independent data/feedback connections instead of reconnecting for every request", flush=True)

    with relay(binary, extra_args=["--mode", "store"]) as source:
        with capped_proxy(source) as (proxy, state):
            state["index_delay"] = 2.5
            logs = []
            with relay(binary, delay=.3, logs=logs, extra_args=merge_args(proxy)) as output:
                viewer = Playback(output, 4 * len(ts(1)), timeout=10)
                for seq in range(4):
                    assert block(source, seq, ts(seq + 1))[0] == 200
                assert viewer.finish(timeout=10) == b"".join(ts(tag) for tag in range(1, 5))
                assert not any("retry:" in line for line in logs), logs
    print("PASS: 2.5-second index response is tolerated without retrying or losing blocks", flush=True)

    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        unavailable = reserved.getsockname()[1]
    with relay(binary, extra_args=["--mode", "store"]) as source:
        logs = []
        with relay(binary, delay=.3, logs=logs, extra_args=merge_args(source, unavailable)) as output:
            viewer = Playback(output, 4 * len(ts(1)), timeout=10)
            for seq in range(4):
                assert block(source, seq, ts(seq + 1))[0] == 200
            assert viewer.finish() == b"".join(ts(tag) for tag in range(1, 5))
            deadline = time.monotonic() + 6
            while not any("phase=connect" in line for line in logs) and time.monotonic() < deadline:
                time.sleep(.05)
            failures = [line for line in logs if "phase=connect" in line]
            assert failures and f"http://127.0.0.1:{unavailable}/index/live" in failures[0], logs
            assert "socket error=" in failures[0], failures
    print("PASS: failed upstream identifies URL/connect phase/socket error; healthy upstream keeps delivering", flush=True)


def live_join(binary):
    # A nonzero retained prefix must not trigger a gap-timeout wait for sequence zero.
    with relay(binary, extra_args=["--mode", "store"]) as source:
        for seq in range(100, 104):
            assert block(source, seq, ts(seq - 99))[0] == 200
        with capped_proxy(source) as (proxy, state):
            state["index_delay"] = .25
            logs = []
            began = time.monotonic()
            with relay(binary, delay=.3, logs=logs, extra_args=merge_args(proxy, gap=10)) as output:
                viewer = Playback(output, 4 * len(ts(1)), timeout=10)
                assert viewer.finish() == b"".join(ts(tag) for tag in range(1, 5))
                assert time.monotonic() - began < 5, logs
                assert any("start seq=100 " in line for line in logs), logs
                assert not any("missing seq=0" in line for line in logs), logs
    print("PASS: late join starts at retained nonzero sequence without waiting for expired sequence zero", flush=True)

    with contextlib.ExitStack() as stack:
        a = stack.enter_context(relay(binary, extra_args=["--mode", "store"]))
        b = stack.enter_context(relay(binary, extra_args=["--mode", "store"]))
        for seq in range(650, 770):
            result = upload(a if seq % 2 == 0 else b, "ordered", seq, ts(seq % 200 + 1), 1_000_000, False,
                            {"X-Session-Started-Ms": "1000", "X-Start-Us": str(seq * 1_000_000)})
            assert result[0] == 200
        pa, sa = stack.enter_context(capped_proxy(a))
        pb, sb = stack.enter_context(capped_proxy(b))
        sa["index_delay"] = sb["index_delay"] = .25
        logs = []
        began = time.monotonic()
        output = stack.enter_context(relay(binary, delay=2, maximum=4, logs=logs, extra_args=merge_args(pa, pb, 10)))
        viewer = Playback(output, 2 * len(ts(1)), timeout=10)
        assert viewer.finish() == ts(169) + ts(170)
        assert time.monotonic() - began < 5, logs
        assert any("start seq=768 " in line for line in logs), logs
    print("PASS: late join selects the delay window near live edge instead of downloading 120 seconds of expiring history", flush=True)


def production_single(binary, classes, stdlib, java, javac):
    build = Path(__file__).resolve().parents[1] / "build"
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=build) as temporary:
        cp = os.pathsep.join([str(classes.resolve()), str(stdlib.resolve()), temporary])
        subprocess.run([str(javac), "-cp", cp, "-d", temporary,
                        str(Path(__file__).with_name("UploadHarness.java"))], check=True)
        # Exercise both the new single-store route and the existing direct relay route.
        for mode in ("store", "relay"):
            with contextlib.ExitStack() as stack:
                source = stack.enter_context(relay(binary, delay=.5, extra_args=["--mode", mode]))
                output = stack.enter_context(relay(binary, delay=.5, extra_args=merge_args(source))) if mode == "store" else source
                viewer = Playback(output, 4 * 188 * 128, timeout=15)
                uploader = subprocess.run([str(java), "-cp", cp, "UploadHarness", temporary,
                    f"http://127.0.0.1:{source}/upload/live"], capture_output=True, text=True, timeout=20)
                assert uploader.returncode == 0, (uploader.stdout, uploader.stderr)
                expected = b"".join(bytes(0x47 if i % 188 == 0 else tag for i in range(188 * 128)) for tag in (1, 2, 4, 5))
                assert viewer.finish() == expected
                print(f"PASS: production single-address Kotlin uploader -> {mode}" +
                    (" -> single-upstream merge" if mode == "store" else "") + ", two sessions, byte-exact", flush=True)


def production_unreachable(binary, classes, stdlib, java, javac):
    build = Path(__file__).resolve().parents[1] / "build"
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=build) as temporary:
        cp = os.pathsep.join([str(classes.resolve()), str(stdlib.resolve()), temporary])
        subprocess.run([str(javac), "-cp", cp, "-d", temporary,
                        str(Path(__file__).with_name("CrossStoreRescueHarness.java"))], check=True)
        for offline_upstream in (False, True):
            with contextlib.ExitStack() as stack:
                a = stack.enter_context(relay(binary, extra_args=["--mode", "store"]))
                b = stack.enter_context(relay(binary, extra_args=["--mode", "store"]))
                sender = subprocess.Popen([str(java), "-cp", cp, "CrossStoreRescueHarness",
                    f"http://127.0.0.1:{a}/upload/live", f"http://127.0.0.1:{b}/upload/live"],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
                try:
                    ready = sender.stdout.readline().strip()
                    assert ready == "READY: 0,2 on A; 1 on B", ready
                    assert get(a, "/chunks/live/cross-store-test/1")[0] == 404
                    assert get(b, "/chunks/live/cross-store-test/1")[0] == 200
                    unavailable = None
                    if offline_upstream:
                        # Bound without listening: unreachable from merge while the phone still uploads to real B.
                        reserved = stack.enter_context(socket.socket())
                        reserved.bind(("127.0.0.1", 0))
                        unavailable = reserved.getsockname()[1]
                    logs = []
                    output = stack.enter_context(relay(binary, delay=.2, logs=logs,
                        extra_args=merge_args(a, unavailable, gap=8)))
                    viewer = Playback(output, 3 * len(ts(1)), timeout=15)
                    try:
                        assert viewer.finish(timeout=15) == b"".join(ts(tag) for tag in (1, 2, 3))
                    except AssertionError:
                        print("Cross-store test failure: received bytes=", len(viewer.output), "merge logs=", logs, flush=True)
                        raise
                    remaining = sender.communicate(timeout=15)[0]
                    assert sender.returncode == 0, remaining
                    assert "PASS: cross-store rescue" in remaining, remaining
                    copied = get(a, "/chunks/live/cross-store-test/1")
                    assert copied[2] == ts(2)
                    assert copied[1]["X-Redirect-From"] == f"http://127.0.0.1:{b}/upload/live"
                    assert copied[1]["X-Redirect-Reason"] == "download-slow"
                    assert any("phase=received" in line and "seq=1 " in line for line in logs), logs
                    assert not any("skipped after timeout" in line for line in logs), logs
                finally:
                    if sender.poll() is None:
                        sender.kill()
                        sender.communicate()
            print("PASS: production phone recovers missing B block through A, byte-exact; " +
                  ("second merge upstream unreachable" if offline_upstream else "merge configured with only A"), flush=True)


def production(binary, classes, stdlib, slow, java, javac):
    with contextlib.ExitStack() as stack:
        a = stack.enter_context(relay(binary, extra_args=["--mode", "store"]))
        b = stack.enter_context(relay(binary, extra_args=["--mode", "store"]))
        pa, sa = stack.enter_context(capped_proxy(a, slow_first=slow))
        pb, sb = stack.enter_context(capped_proxy(b))
        sa["keep_alive"] = sb["keep_alive"] = True
        merge_logs = []
        # Keep merge logs to verify that requesting a rescue is distinct from receiving it.
        output = stack.enter_context(relay(binary, delay=3, logs=merge_logs, extra_args=merge_args(pa, pb, 12)))
        build = Path(__file__).resolve().parents[1] / "build"
        build.mkdir(exist_ok=True)
        temporary = stack.enter_context(tempfile.TemporaryDirectory(dir=build))
        cp = os.pathsep.join([str(classes.resolve()), str(stdlib.resolve()), temporary])
        subprocess.run([str(javac), "-cp", cp, "-d", temporary, str(Path(__file__).with_name("DistributedUploadHarness.java"))], check=True)
        packets, count = 3657, 8  # 5.500128 Mbps total, over two 3 Mbps download paths.
        started = time.monotonic()
        viewer = Playback(output, 188 * packets * count, timeout=25)
        sender = subprocess.Popen([str(java), "-cp", cp, "DistributedUploadHarness",
            f"http://127.0.0.1:{pa}/upload/live", f"http://127.0.0.1:{pb}/upload/live", str(count), str(packets)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            result = viewer.finish(timeout=25)
            logs = sender.communicate(timeout=25)[0]
            assert sender.returncode == 0, logs
            expected = b"".join(bytes(0x47 if i % 188 == 0 else seq + 1 for i in range(188 * packets)) for seq in range(count))
            assert result == expected, (len(result), len(expected), logs)
            assert sa["downloaded"] and sb["downloaded"], (sa, sb)
            if not slow:
                assert len(sa["clients"]) == len(sb["clients"]) == 4, (sa, sb)  # Upload/status/index+data/feedback.
            if slow:
                assert sb["rescued"], "Slow downloaded block was never copied to the other store"
                assert any("[redirect] phase=requested" in line and "seq=0 " in line for line in merge_logs), merge_logs
                assert any("[redirect] phase=received" in line and "reason=download-slow" in line for line in merge_logs), merge_logs
                first_playback = viewer.times[0] - started
                assert first_playback < 9, f"First playback took {first_playback:.2f}s; slow original needs at least 9.83s"
        finally:
            if sender.poll() is None:
                sender.kill()
                sender.communicate()
    print("PASS: production Kotlin uploader, 5.5 Mbps over two capped 3 Mbps paths" + (", stalled download rescued byte-exact" if slow else ", byte-exact merge"), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--android-classes", type=Path)
    parser.add_argument("--kotlin-stdlib", type=Path)
    parser.add_argument("--java", type=Path, default=Path("java"))
    parser.add_argument("--javac", type=Path, default=Path("javac"))
    parser.add_argument("--case", choices=["all", "status", "protocol", "network", "join", "single", "normal", "slow", "unreachable"], default="all")
    args = parser.parse_args()
    if args.case in ("all", "status"):
        store_status(args.binary.resolve())
    if args.case in ("all", "protocol"):
        protocol(args.binary.resolve())
    if args.case in ("all", "network"):
        upstream_network(args.binary.resolve())
    if args.case in ("all", "join"):
        live_join(args.binary.resolve())
    if args.android_classes and args.case not in ("status", "protocol", "network", "join"):
        assert args.kotlin_stdlib
        if args.case in ("all", "single"):
            production_single(args.binary.resolve(), args.android_classes, args.kotlin_stdlib, args.java, args.javac)
        if args.case in ("all", "normal"):
            production(args.binary.resolve(), args.android_classes, args.kotlin_stdlib, False, args.java, args.javac)
        if args.case in ("all", "slow"):
            production(args.binary.resolve(), args.android_classes, args.kotlin_stdlib, True, args.java, args.javac)
        if args.case in ("all", "unreachable"):
            production_unreachable(args.binary.resolve(), args.android_classes, args.kotlin_stdlib, args.java, args.javac)
