"""Run real HTTP relay checks; no external Python dependencies.

python tests/integration.py --binary build/http-ts-relay.exe [--outage]
--outage additionally exercises a real 20-second upload blackout with 30-second playback delay.
"""
import argparse
import contextlib
from collections import deque
import http.client
import os
from pathlib import Path
import socket
import subprocess
import threading
import tempfile
import time


def ts(tag, packets=128):
    # Deliberately no PAT or keyframe. The server must preserve these opaque bytes.
    return bytes([0x47, 0x1F, 0xFE, 0x10]) + bytes([tag]) * 184 if packets == 1 else (
        bytes([0x47, 0x1F, 0xFE, 0x10]) + bytes([tag]) * 184
    ) * packets


@contextlib.contextmanager
def relay(binary, delay=0.4, retention=120, logs=None, maximum=60, buffer_mb=256):
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        port = reserved.getsockname()[1]
    arguments = [str(binary), "--bind", "127.0.0.1", "--port", str(port),
                 "--retention", str(retention), "--buffer-mb", str(buffer_mb)]
    if delay is not None:
        arguments += ["--delay", str(delay)]
    if maximum is not None:
        arguments += ["--max-pending-seconds", str(maximum)]
    process = subprocess.Popen(
        arguments,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    logs = logs if logs is not None else deque(maxlen=256)

    def drain():
        for line in process.stdout:
            logs.append(line.strip())

    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    try:
        for _ in range(100):
            if process.poll() is not None:
                raise AssertionError(list(logs))
            try:
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=0.2)
                connection.request("GET", "/health")
                assert connection.getresponse().read() == b"OK\n"
                connection.close()
                break
            except OSError:
                time.sleep(0.02)
        else:
            raise AssertionError("Relay startup timed out")
        yield port
    finally:
        process.terminate()
        process.wait(timeout=5)
        reader.join(5)
        process.stdout.close()


def status_logging(binary):
    logs = deque(maxlen=256)
    with relay(binary, delay=0.2, retention=1, logs=logs) as port:
        body = ts(1)
        assert upload(port, "stats-session", 0, body, duration=200_000)[0] == 200
        assert upload(port, "stats-session", 0, body, duration=200_000)[0] == 200

        def wait_for(predicate):
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                matches = [line for line in list(logs) if predicate(line)]
                if matches:
                    return matches[0]
                time.sleep(0.02)
            raise AssertionError(list(logs))

        accepted = wait_for(lambda line: "result=accepted" in line)
        assert "session=stats-session seq=0 status=200" in accepted
        assert "received=23.50 KiB" in accepted and "speed=" in accepted and "KiB/s" in accepted
        assert "cache=23.50 KiB cached=0.20 s pending=0.20 s blocks=1" in accepted
        duplicate = wait_for(lambda line: "result=duplicate" in line)
        assert "cache=23.50 KiB cached=0.20 s" in duplicate  # Retries do not inflate cache.
        status = wait_for(lambda line: line.startswith("[status]") and "session=stats-session seq=0" in line)
        assert "speed=" in status and "cache=" in status and "cached=" in status and "pending=" in status
        wait_for(lambda line: line.startswith("[status]") and "speed=0.00 KiB/s cache=0.00 KiB cached=0.00 s pending=0.00 s blocks=0" in line)
    print("PASS: session/sequence/speed/cache logs, deduplicated counters, periodic idle status and retention expiry", flush=True)


def upload(port, session, sequence, body, duration=100_000, final=False, extra=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    headers = {"Content-Type": "video/mp2t", "X-Session-ID": session,
               "X-Sequence": str(sequence), "X-Duration-Us": str(duration),
               "X-Final": "1" if final else "0"}
    headers.update(extra or {})
    connection.request("POST", "/upload/live", body=body, headers=headers)
    response = connection.getresponse()
    result = response.status, dict(response.getheaders()), response.read()
    connection.close()
    return result


class Playback:
    def __init__(self, port, size, timeout=5):
        self.output = bytearray()
        self.times = []
        self.errors = []
        self.connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
        self.connection.request("GET", "/live/live.ts")
        self.response = self.connection.getresponse()
        assert self.response.status == 200

        def read():
            try:
                while len(self.output) < size:
                    data = self.response.read(min(188 * 32, size - len(self.output)))
                    if not data:
                        raise AssertionError("Playback ended early")
                    self.output.extend(data)
                    self.times.append(time.monotonic())
            except BaseException as error:
                self.errors.append(error)
            finally:
                self.response.close()
                self.connection.close()

        self.thread = threading.Thread(target=read, daemon=True)
        self.thread.start()

    def finish(self, timeout=5):
        self.thread.join(timeout)
        assert not self.thread.is_alive(), "Playback timed out"
        assert not self.errors, self.errors
        return bytes(self.output)


def protocol_and_rollover(binary):
    with relay(binary) as port:
        bodies = [ts(tag) for tag in range(1, 11)]
        before = time.monotonic()
        assert upload(port, "old", 0, bodies[0])[0] == 200
        assert upload(port, "old", 0, bodies[0])[0] == 200  # Lost ACK retry.
        assert upload(port, "old", 0, ts(99))[0] == 409
        assert upload(port, "old", 1, b"not TS")[0] == 400
        viewers = [Playback(port, sum(map(len, bodies))) for _ in range(2)]
        for index in range(1, 8):
            assert upload(port, "old", index, bodies[index], final=index == 7)[0] == 200
        # Introduce a new session while old delayed data is already playing.
        time.sleep(0.6)
        rollover = time.monotonic()
        assert upload(port, "new", 0, bodies[8])[0] == 200
        assert upload(port, "new", 1, bodies[9], final=True)[0] == 200
        assert upload(port, "old", 7, bodies[7], final=True)[0] == 200
        for viewer in viewers:
            assert viewer.finish() == b"".join(bodies)
            assert viewer.times[0] - before < 0.3, "Full media window unnecessarily waited for wall time"
            assert max(b - a for a, b in zip(viewer.times, viewer.times[1:])) < 0.25, "Session change stalled playback"
            assert viewer.times[8 * 4] - rollover < 0.9, "New session restarted warm-up"
        assert upload(port, "third", 0, b"", duration=0, final=True)[0] == 200
    print("PASS: acknowledgements, deduplication, sequence validation, two viewers, raw-byte session rollover", flush=True)


def unsealed_session_rollover(binary):
    with relay(binary, delay=0.2) as port:
        first, second = ts(1), ts(2)
        assert upload(port, "old", 0, first)[0] == 200
        viewer = Playback(port, len(first) + len(second))
        # A sender process restart loses its old memory queue and final marker.
        assert upload(port, "new", 0, second, final=True)[0] == 200
        assert upload(port, "old", 1, ts(3), final=True)[0] == 409
        assert upload(port, "old", 0, first)[0] == 200
        assert viewer.finish() == first + second
    print("PASS: new session supersedes an unsealed old session without resetting delay or inserting late blocks", flush=True)


def skipped_uploads(binary):
    logs = deque(maxlen=256)
    with relay(binary, delay=0.1, logs=logs) as port:
        first, next_body = ts(1), ts(4)
        assert upload(port, "session", 1, first)[0] == 200  # The first block may already have expired.
        viewer = Playback(port, len(first) + len(next_body))
        assert upload(port, "session", 4, next_body, final=True)[0] == 200
        assert upload(port, "session", 2, ts(2))[0] == 200  # A late retired block is ACKed but never inserted.
        assert upload(port, "session", 4, next_body, final=True)[0] == 200
        assert viewer.finish() == first + next_body
        assert any("skipped=3" in line for line in logs), list(logs)
    print("PASS: skipped sequences and nonzero session start accepted, late/duplicate blocks never replayed", flush=True)


def maximum_cache(binary):
    for maximum in (0.6, 0.65):
        logs = deque(maxlen=256)
        with relay(binary, delay=0.3, maximum=maximum, logs=logs) as port:
            bodies = [ts(tag) for tag in range(1, 9)]
            viewers = [Playback(port, 3 * len(bodies[0])) for _ in range(2)]
            assert upload(port, "session", 0, b"".join(bodies[:2]), duration=200_000)[0] == 200
            assert upload(port, "session", 1, b"".join(bodies[2:]), duration=600_000)[0] == 200
            for viewer in viewers:
                assert viewer.finish() == b"".join(bodies[5:])
            assert any("seq=0 status=200 result=accepted" in line and
                       "cached=0.20 s pending=0.20 s blocks=1 state=buffering dropped=0" in line
                       for line in logs), list(logs)
            caught = [line for line in logs if "seq=1 status=200 result=accepted" in line]
            assert caught and "cached=0.80 s" in caught[-1] and "blocks=2 state=playing dropped=1" in caught[-1], list(logs)
            pending = float(caught[-1].split(" pending=")[1].split(" ")[0])
            assert 0.2 <= pending <= 0.3, caught[-1]  # Playback can advance while the ACK/log is written.
    print("PASS: pending at/above its seconds cap jumps to delay for both viewers without evicting cached history", flush=True)


def starts_when_ready(binary):
    with relay(binary, delay=30, maximum=60) as port:
        body = ts(1)
        viewer = Playback(port, 188 * 32, timeout=3)
        assert upload(port, "ready", 0, body, duration=29_000_000)[0] == 200
        time.sleep(0.15)
        assert not viewer.output, "Playback started before enough media accumulated"
        ready = time.monotonic()
        assert upload(port, "ready", 1, ts(2), duration=1_000_000)[0] == 200
        assert viewer.finish(timeout=2) == body[:188 * 32]
        assert viewer.times[0] - ready < 0.5, "Full buffer still waited for the first arrival plus delay"
    print("PASS: buffering 30 seconds of media starts immediately, without an extra 30-second wall-time wait", flush=True)


def skipped_sequences_after_refill(binary):
    logs = deque(maxlen=256)
    with relay(binary, delay=0.3, maximum=0.8, logs=logs) as port:
        bodies = [ts(tag) for tag in range(1, 13)]
        viewer = Playback(port, sum(map(len, bodies)))
        assert upload(port, "live", 0, bodies[0], duration=300_000)[0] == 200
        deadline = time.monotonic() + 2
        while len(viewer.output) < len(bodies[0]):
            assert time.monotonic() < deadline
            time.sleep(0.01)
        time.sleep(0.15)
        # Sender evicted sequences 1..99. These retained blocks refill a drained relay.
        for index in range(1, 4):
            assert upload(port, "live", 99 + index, bodies[index])[0] == 200
        for index in range(4, len(bodies)):
            time.sleep(0.1)
            assert upload(port, "live", 99 + index, bodies[index])[0] == 200
        assert viewer.finish() == b"".join(bodies)
        realtime = [line for line in logs if line.startswith("[upload]") and any(
            f"seq={99 + index} " in line for index in range(4, len(bodies)))]
        assert len(realtime) >= len(bodies) - 5, list(logs)
        for line in realtime:
            pending = float(line.split(" pending=")[1].split(" ")[0])
            assert "state=playing" in line and 0.2 <= pending <= 0.35, line
    print("PASS: sender sequence eviction followed by relay refill resumes playback; realtime pending stays near delay", flush=True)


def sixty_second_burst(binary):
    for seconds in (60, 120):
        logs = deque(maxlen=256)
        with relay(binary, delay=30, maximum=60, logs=logs) as port:
            assert upload(port, "burst", 0, ts(1), duration=seconds * 1_000_000)[0] == 200
            deadline = time.monotonic() + 2
            accepted = []
            while not accepted and time.monotonic() < deadline:
                accepted = [line for line in list(logs) if "seq=0 status=200 result=accepted" in line]
                time.sleep(0.01)
            assert accepted and f"cached={seconds:.2f} s" in accepted[-1] and "blocks=1 state=playing dropped=0" in accepted[-1], list(logs)
            pending = float(accepted[-1].split(" pending=")[1].split(" ")[0])
            assert 29.9 <= pending <= 30, accepted[-1]
    print("PASS: 60/120-second media bursts retain full cache while pending resets to 30s at its 60s threshold", flush=True)


def partial_block_catchup(binary):
    logs = deque(maxlen=256)
    with relay(binary, delay=0.25, maximum=0.5, logs=logs) as port:
        body = b"".join(ts(tag, 32) for tag in range(1, 5))
        viewers = [Playback(port, len(body) // 2) for _ in range(2)]
        assert upload(port, "partial", 0, body, duration=500_000)[0] == 200
        for viewer in viewers:
            assert viewer.finish() == body[len(body) // 2:]
        accepted = [line for line in logs if "result=accepted" in line]
        assert accepted and "cached=0.50 s" in accepted[-1] and "dropped_bytes=11.75 KiB" in accepted[-1], list(logs)
        assert "state=playing" in accepted[-1], accepted[-1]
    print("PASS: a target inside one block skips only complete TS packets, keeps full cache, and resumes both viewers", flush=True)


def burst_then_realtime(binary):
    logs = deque(maxlen=256)
    with relay(binary, delay=30, maximum=60, logs=logs) as port:
        # Reproduce the reported log: sender evicted 286 blocks, then quickly posts
        # sixty-plus seconds of 1.01-second chunks before returning to realtime.
        for sequence in range(286, 347):
            assert upload(port, "reported", sequence, ts(sequence % 255), duration=1_010_000)[0] == 200

        def accepted(sequence):
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                matches = [line for line in list(logs) if f"seq={sequence} status=200 result=accepted" in line]
                if matches:
                    return matches[-1]
                time.sleep(0.01)
            raise AssertionError(list(logs))

        baseline = accepted(346)
        assert "state=playing" in baseline and "skipped=286" in baseline, baseline
        assert any("state=playing" in line and "dropped=0" not in line for line in logs), list(logs)
        initial_pending = float(baseline.split(" pending=")[1].split(" ")[0])
        assert 29.5 <= initial_pending <= 32, baseline
        for sequence in range(347, 350):
            time.sleep(1)
            assert upload(port, "reported", sequence, ts(sequence % 255), duration=1_010_000)[0] == 200
            line = accepted(sequence)
            pending = float(line.split(" pending=")[1].split(" ")[0])
            assert "state=playing" in line and pending <= initial_pending + 0.2, line
    print("PASS: reported 60-second burst with skipped=286 switches to playing and realtime uploads do not add one pending second each", flush=True)


def refill_after_empty(binary):
    logs = deque(maxlen=256)
    with relay(binary, delay=0.3, logs=logs) as port:
        bodies = [ts(tag) for tag in range(1, 7)]
        viewers = [Playback(port, sum(map(len, bodies))) for _ in range(2)]
        for index in range(3):
            assert upload(port, "session", index, bodies[index])[0] == 200
        deadline = time.monotonic() + 3
        while any(len(viewer.output) < 3 * len(bodies[0]) for viewer in viewers):
            assert time.monotonic() < deadline, "Initial playback timed out"
            time.sleep(0.02)
        time.sleep(0.15)  # Shared media clock now reaches zero remaining data.
        for index in (3, 4):
            assert upload(port, "session", index, bodies[index])[0] == 200
            time.sleep(0.2)
            assert all(len(viewer.output) == 3 * len(bodies[0]) for viewer in viewers), "Playback resumed before delay was buffered"
        assert upload(port, "session", 5, bodies[5])[0] == 200
        for viewer in viewers:
            assert viewer.finish() == b"".join(bodies)
        assert any("pending=0.20 s" in line and "state=buffering" in line for line in logs), list(logs)
    print("PASS: zero buffer pauses both viewers until a complete delay window accumulates", flush=True)


def memory_pressure(binary):
    logs = deque(maxlen=256)
    with relay(binary, delay=0.2, buffer_mb=16, logs=logs) as port:
        old, fresh, last = ts(1, 50000), ts(2, 50000), ts(3)
        viewer = Playback(port, len(fresh) + len(last), timeout=10)
        assert upload(port, "session", 0, old)[0] == 200
        assert upload(port, "session", 1, fresh)[0] == 200
        assert upload(port, "session", 2, last)[0] == 200
        assert viewer.finish(timeout=10) == fresh + last
        assert any("dropped=1" in line for line in logs), list(logs)
    print("PASS: server byte limit retires old buffered data and ACKs fresh uploads instead of returning 503", flush=True)


def history_independent_of_pending_maximum(binary):
    logs = deque(maxlen=256)
    with relay(binary, delay=0.1, maximum=0.3, logs=logs) as port:
        for sequence in range(4):
            body = ts(sequence + 1)
            viewer = Playback(port, len(body))
            assert upload(port, "session", sequence, body)[0] == 200
            assert viewer.finish() == body
            time.sleep(0.15)
        at_limit = [line for line in logs if "seq=2 status=200 result=accepted" in line]
        assert at_limit and "cache=70.50 KiB cached=0.30 s" in at_limit[-1], list(logs)
        accepted = [line for line in logs if "seq=3 status=200 result=accepted" in line]
        assert accepted and "cache=94.00 KiB cached=0.40 s" in accepted[-1], list(logs)
        assert "dropped=0" in accepted[-1]  # History exceeding the pending threshold is not evicted.
    print("PASS: cached history may exceed maximum pending seconds without evicting data or skipping playback", flush=True)


def invalid_cache_options(binary):
    for maximum in ("0", "-1", "20", "3601", "nan", "inf"):
        process = subprocess.run([str(binary), "--delay", "30", "--max-pending-seconds", maximum],
                                 capture_output=True, text=True, timeout=5,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        assert process.returncode != 0, (maximum, process.stdout, process.stderr)
    print("PASS: maximum pending rejects nonfinite/out-of-range values and values below delay", flush=True)


def default_options(binary):
    logs = deque(maxlen=256)
    with relay(binary, delay=None, maximum=None, logs=logs) as port:
        assert any("delay=10s, max-pending=20s" in line for line in logs), list(logs)
        assert upload(port, "defaults", 0, ts(1), duration=20_000_000)[0] == 200
        deadline = time.monotonic() + 2
        accepted = []
        while not accepted and time.monotonic() < deadline:
            accepted = [line for line in list(logs) if "result=accepted" in line]
            time.sleep(0.01)
        assert accepted and "cached=20.00 s" in accepted[-1] and "state=playing" in accepted[-1], list(logs)
        pending = float(accepted[-1].split(" pending=")[1].split(" ")[0])
        assert 9.9 <= pending <= 10, accepted[-1]
    help_result = subprocess.run([str(binary), "--help"], capture_output=True, text=True, timeout=5,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    assert help_result.returncode == 0 and "--delay 10" in help_result.stdout and "--max-pending-seconds 20" in help_result.stdout
    print("PASS: defaults/help use delay=10s and max-pending=20s; a 20s burst resumes with 10s pending", flush=True)


def maximum_while_playing(binary):
    logs, packets, errors = deque(maxlen=256), [], []
    with relay(binary, delay=0.2, maximum=0.6, logs=logs) as port:
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        connection.request("GET", "/live/live.ts")
        response = connection.getresponse()
        started = threading.Event()

        def read():
            try:
                while True:
                    packet = response.read(188)
                    assert len(packet) == 188 and packet[0] == 0x47
                    packets.append(packet[4])
                    started.set()
                    if packet[4] == 12:
                        break
            except BaseException as error:
                errors.append(error)
            finally:
                response.close()
                connection.close()

        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        for sequence in range(2):
            assert upload(port, "session", sequence, ts(sequence + 1))[0] == 200
        assert started.wait(3)
        for sequence in range(2, 12):
            assert upload(port, "session", sequence, ts(sequence + 1))[0] == 200
        reader.join(5)
        assert not reader.is_alive() and not errors, errors
        assert packets[-1] == 12 and packets == sorted(packets), packets
        assert any("dropped=" in line and "dropped=0" not in line for line in logs), list(logs)
    print("PASS: eviction during playback cancels retired blocks and continues at newer TS bytes", flush=True)


def late_viewer(binary):
    with relay(binary, delay=0.3) as port:
        bodies = [ts(tag) for tag in range(1, 16)]
        for index, body in enumerate(bodies):
            assert upload(port, "session", index, body, final=index == len(bodies) - 1)[0] == 200
        time.sleep(0.75)
        start = time.monotonic()
        viewer = Playback(port, len(bodies[0]))
        first = viewer.finish()
        assert first in bodies[7:10], "New viewer did not join the current delayed position"
        assert viewer.times[0] - start < 0.2, "Late viewer unnecessarily warmed up"
    print("PASS: new viewer joins existing delayed timeline without a fresh delay", flush=True)


def underrun(binary):
    with relay(binary, delay=0.1) as port:
        bodies = [ts(tag) for tag in range(1, 4)]
        assert upload(port, "session", 0, bodies[0])[0] == 200
        viewer = Playback(port, sum(map(len, bodies)))
        time.sleep(0.5)  # Deliberately exhaust the configured delay.
        assert upload(port, "session", 1, bodies[1])[0] == 200
        assert upload(port, "session", 2, bodies[2], final=True)[0] == 200
        assert viewer.finish() == b"".join(bodies)
        assert viewer.times[-1] - viewer.times[4] >= 0.15, "Overdue blocks were dumped in a burst"
    print("PASS: exhausted delay pauses and resumes at normal speed without dropping bytes", flush=True)


def outage(binary):
    with relay(binary, delay=30) as port:
        bodies = [ts(tag) for tag in range(1, 37)]
        start = time.monotonic()
        for index in range(30):
            assert upload(port, "session", index, bodies[index], duration=1_000_000)[0] == 200
        viewer = Playback(port, sum(map(len, bodies)), timeout=40)
        time.sleep(1)
        assert viewer.times, "Delayed playback has not started"
        print("Testing a 20-second upload blackout with 30-second playback delay...", flush=True)
        time.sleep(20)
        for index in range(30, len(bodies)):
            assert upload(port, "session", index, bodies[index], duration=1_000_000,
                          final=index == len(bodies) - 1)[0] == 200
        assert viewer.finish(timeout=20) == b"".join(bodies)
        assert viewer.times[0] - start < 1, "Prebuffered media unnecessarily waited for wall time"
        assert max(b - a for a, b in zip(viewer.times, viewer.times[1:])) < 0.6
    print("PASS: real 20-second upload blackout, 30-second delay, continuous byte-exact playback", flush=True)


def production_uploader(binary, classes, stdlib, java, javac):
    build = Path(__file__).resolve().parents[1] / "build"
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=build) as temporary, relay(binary, delay=0.5) as port:
        classpath = os.pathsep.join([str(classes.resolve()), str(stdlib.resolve()), temporary])
        subprocess.run([str(javac), "-cp", classpath, "-d", temporary,
                        str(Path(__file__).with_name("UploadHarness.java"))], check=True)
        viewer = Playback(port, 4 * 188 * 128, timeout=15)
        uploader = subprocess.run([str(java), "-cp", classpath, "UploadHarness", temporary,
                                   f"http://127.0.0.1:{port}/upload/live"],
                                  capture_output=True, text=True, timeout=20)
        assert uploader.returncode == 0, (uploader.stdout, uploader.stderr)
        assert not list(Path(temporary).rglob("*.block")), "Uploader created disk cache files"
        expected = b"".join(bytes(0x47 if i % 188 == 0 else tag for i in range(188 * 128))
                            for tag in (1, 2, 4, 5))
        assert viewer.finish() == expected
        print("PASS: actual Kotlin uploader -> C++ server -> HTTP playback, two sessions, byte-exact", flush=True)


def stalled_upload(classes, stdlib, java, javac):
    build = Path(__file__).resolve().parents[1] / "build"
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=build) as temporary, socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(2)
        listener.settimeout(25)
        port = listener.getsockname()[1]
        classpath = os.pathsep.join([str(classes.resolve()), str(stdlib.resolve()), temporary])
        subprocess.run([str(javac), "-cp", classpath, "-d", temporary,
                        str(Path(__file__).with_name("UploadHarness.java"))], check=True)
        errors, retries = [], []

        def read_headers(connection):
            reader = connection.makefile("rb")
            assert reader.readline() == b"POST /upload/live HTTP/1.1\r\n"
            headers = {}
            while True:
                line = reader.readline()
                if line == b"\r\n":
                    break
                assert line, "Incomplete headers"
                key, value = line.decode("ascii").split(":", 1)
                headers[key.lower()] = value.strip()
            return reader, headers

        def server():
            try:
                with listener.accept()[0] as blocked:
                    blocked.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
                    blocked.settimeout(5)
                    reader, headers = read_headers(blocked)
                    start = time.monotonic()
                    # Leave the 13 MiB body unread, causing the real producer write to block.
                    with listener.accept()[0] as retry:
                        retries.append(time.monotonic() - start)
                        retry.settimeout(10)
                        incoming, repeated = read_headers(retry)
                        assert repeated == headers
                        body = incoming.read(int(repeated["content-length"]))
                        assert body == bytes(0x47 if i % 188 == 0 else 7 for i in range(188 * 70000))
                        retry.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\nX-Ack-Sequence: 0\r\n\r\n")
                        incoming.close()
                    reader.close()
            except BaseException as error:
                errors.append(error)

        thread = threading.Thread(target=server, daemon=True)
        thread.start()
        uploader = subprocess.run([str(java), "-cp", classpath, "UploadHarness", temporary,
                                   f"http://127.0.0.1:{port}/upload/live", "stall"],
                                  capture_output=True, text=True, timeout=32)
        thread.join(5)
        assert not thread.is_alive()
        assert not errors, errors
        assert uploader.returncode == 0, (uploader.stdout, uploader.stderr)
        assert not list(Path(temporary).rglob("*.block")), "Uploader created disk cache files"
        assert len(retries) == 1 and 14 <= retries[0] < 22, retries
        print("PASS: blocked 13 MiB POST aborted by 15-second deadline and retried byte-exact", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--outage", action="store_true")
    parser.add_argument("--android-classes", type=Path)
    parser.add_argument("--kotlin-stdlib", type=Path)
    parser.add_argument("--java", type=Path, default=Path("java"))
    parser.add_argument("--javac", type=Path, default=Path("javac"))
    parser.add_argument("--stall", action="store_true")
    args = parser.parse_args()
    executable = args.binary.resolve()
    default_options(executable)
    invalid_cache_options(executable)
    status_logging(executable)
    protocol_and_rollover(executable)
    unsealed_session_rollover(executable)
    skipped_uploads(executable)
    maximum_cache(executable)
    starts_when_ready(executable)
    skipped_sequences_after_refill(executable)
    sixty_second_burst(executable)
    partial_block_catchup(executable)
    burst_then_realtime(executable)
    maximum_while_playing(executable)
    memory_pressure(executable)
    history_independent_of_pending_maximum(executable)
    refill_after_empty(executable)
    late_viewer(executable)
    underrun(executable)
    if args.outage:
        outage(executable)
    if args.android_classes:
        assert args.kotlin_stdlib, "--kotlin-stdlib is required"
        production_uploader(executable, args.android_classes, args.kotlin_stdlib, args.java, args.javac)
        if args.stall:
            stalled_upload(args.android_classes, args.kotlin_stdlib, args.java, args.javac)
