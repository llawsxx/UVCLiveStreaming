import argparse
import json
import re
import socket
import subprocess
import threading
import time
from pathlib import Path

from PIL import Image


PACKAGE = "com.llawsxx.uvclivestreaming.rawpipeline"
ROOT = Path("build/perf-diagnostics/raw-pipeline")
ADB = r"D:\AndroidSDK\platform-tools\adb.exe"
FFMPEG = r"C:\WINDOWS\ffmpeg.exe"
FFPROBE = r"C:\WINDOWS\ffprobe.exe"
FORMATS = {1: "MJPEG", 2: "YUYV", 3: "UYVY", 4: "RGB", 5: "NV12", 6: "I420", 7: "P010", 9: "BGR"}


def adb(*args, check=True):
    return subprocess.run([ADB, "-t", "1", *args], capture_output=True, check=check)


def device_file(name):
    result = adb("exec-out", "run-as", PACKAGE, "cat", "files/" + name, check=False)
    if result.stdout.startswith((b"run-as:", b"cat:")):
        result.returncode = 1
    return result


def network_reader(label, output, errors):
    deadline = time.monotonic() + 45
    connection = None
    try:
        while time.monotonic() < deadline:
            ready = device_file("rawpipeline-ready")
            if ready.returncode == 0 and ready.stdout.decode().strip() == label:
                try:
                    connection = socket.create_connection(("127.0.0.1", 13419), timeout=10)
                    break
                except OSError:
                    pass
            time.sleep(0.15)
        if connection is None:
            raise RuntimeError("Diagnostic HTTP server did not become ready")
        connection.settimeout(25)
        connection.sendall(b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        header = b""
        while b"\r\n\r\n" not in header:
            chunk = connection.recv(4096)
            if not chunk or len(header) > 16384:
                raise RuntimeError("Invalid HTTP response")
            header += chunk
        response, body = header.split(b"\r\n\r\n", 1)
        if not response.startswith(b"HTTP/1.1 200"):
            raise RuntimeError(response.decode(errors="replace"))
        adb("shell", "run-as", PACKAGE, "touch", "files/rawpipeline-client-ready")
        with output.open("wb") as stream:
            stream.write(body)
            while True:
                chunk = connection.recv(65536)
                if not chunk:
                    break
                stream.write(chunk)
    except Exception as error:
        errors.append(str(error))
    finally:
        if connection is not None:
            connection.close()


def dechunk(source, destination):
    payload = source.read_bytes()
    offset = 0
    output = bytearray()
    while offset < len(payload):
        end = payload.find(b"\r\n", offset)
        if end < 0:
            break
        size = int(payload[offset:end].split(b";")[0], 16)
        offset = end + 2
        if size == 0 or offset + size + 2 > len(payload):
            break
        output.extend(payload[offset:offset + size])
        if payload[offset + size:offset + size + 2] != b"\r\n":
            raise RuntimeError("Broken chunk framing")
        offset += size + 2
    destination.write_bytes(output)
    return len(output)


def decoder_warnings(stderr):
    diagnostics = r"(?m)^\[hevc @ [^\]]+\] \[HEVCOutputFrameConstructionContext @ [^\]]+\]:\s*\n\s*DPB:\s+Counter=(\d+) POCOutOfOrder=0 Orphaned=0\s*\n\s*Output:\s+Counter=\1 POCOutOfOrder=0(?:\n|$)"
    return re.sub(diagnostics, "", stderr).strip()


def inspect_video(path, first_id, expected_last):
    probe = subprocess.run([FFPROBE, "-v", "error", "-count_frames", "-show_entries",
                            "stream=codec_name,width,height,avg_frame_rate,nb_read_frames,color_space,color_range,color_transfer,color_primaries:format=duration",
                            "-of", "json", str(path)], capture_output=True, text=True, check=True)
    metadata = json.loads(probe.stdout)
    path.with_suffix(path.suffix + ".probe.json").write_text(probe.stdout, encoding="utf-8")
    strip = path.with_suffix(path.suffix + ".markers.rgb")
    graph = "split=2[top][bottom];[top]crop=256:16:0:0[a];[bottom]crop=256:16:0:ih-16[b];[a][b]vstack,format=rgb24"
    decode = subprocess.run([FFMPEG, "-v", "error", "-i", str(path), "-vf", graph,
                             "-fps_mode", "passthrough", "-f", "rawvideo", "-y", str(strip)], capture_output=True, text=True)
    path.with_suffix(path.suffix + ".decode.log").write_text(decode.stderr, encoding="utf-8")
    if decode.returncode:
        raise RuntimeError(decode.stderr)
    raw = strip.read_bytes()
    frame_bytes = 256 * 32 * 3
    if len(raw) % frame_bytes:
        raise RuntimeError("Incomplete decoded marker frame")
    ids = []
    tearing = 0
    for frame in range(len(raw) // frame_bytes):
        offset = frame * frame_bytes
        values = []
        for row in (8, 24):
            value = 0
            for bit in range(16):
                pixel = offset + (row * 256 + bit * 16 + 8) * 3
                if sum(raw[pixel:pixel + 3]) > 128 * 3:
                    value |= 1 << bit
            values.append(value)
        tearing += values[0] != values[1]
        ids.append(values[0])
    samples = path.with_suffix(path.suffix + ".png")
    subprocess.run([FFMPEG, "-v", "error", "-i", str(path), "-frames:v", "1",
                    "-vf", "scale=960:540", "-y", str(samples)], capture_output=True, check=True)
    colors = [(255, 255, 255), (255, 255, 0), (0, 255, 255), (0, 255, 0),
              (255, 0, 255), (255, 0, 0), (0, 0, 255), (0, 0, 0)]
    image = Image.open(samples).convert("RGB")
    color_error = max(abs(actual - expected) for bar, color in enumerate(colors)
                      for actual, expected in zip(image.getpixel((bar * 120 + 60, 135)), color))
    gray_error = max(abs(actual - expected) for band, expected in enumerate((0, 32, 128, 235))
                     for actual in image.getpixel((band * 240 + 120, 405)))
    result = {"frames": len(ids), "first_id": ids[0] if ids else None, "last_id": ids[-1] if ids else None,
              "tearing_frames": tearing, "duplicate_or_reordered": sum(second <= first for first, second in zip(ids, ids[1:])),
              "missing_measured_ids": len(set(range(first_id, expected_last + 1)) - set(ids)),
              "color_max_error": color_error, "gray_max_error": gray_error, "probe": metadata,
              "decode_warnings": decoder_warnings(decode.stderr), "decode_diagnostics": decode.stderr.strip()}
    strip.unlink()
    return result, ids


def run_case(width, height, input_format, codec, fps, seconds, low_preview, cpu_repack=False, yuv_input=False):
    label = f"{width}x{height}-f{input_format}-{codec}-{fps}fps-{'p5' if low_preview else 'p60'}-{seconds}s{'-cpu' if cpu_repack else ''}{'-yuv' if yuv_input else ''}"
    folder = ROOT / label
    folder.mkdir(parents=True, exist_ok=True)
    errors = []
    adb("shell", "am", "force-stop", PACKAGE)
    if input_format == 1:
        from prepare_mjpeg import generate
        bundle = ROOT / f"synthetic-mjpeg-{width}x{height}-{fps}-{seconds}.bin"
        if not bundle.exists():
            print("Preparing real JPEG frames", width, height, flush=True)
            generate(width, height, fps * (seconds + 1) + 90, bundle)
        temporary = "/data/local/tmp/" + bundle.name
        adb("push", str(bundle), temporary)
        adb("shell", "run-as", PACKAGE, "cp", temporary, f"files/synthetic-mjpeg-{width}x{height}.bin")
    collector = threading.Thread(target=network_reader, args=(label, folder / "http-chunked.bin", errors), daemon=True)
    collector.start()
    command = [ADB, "-t", "1", "shell", "am", "instrument", "-w", "-r", "-e", "class",
               "com.llawsxx.uvclivestreaming.recording.SyntheticRawPipelineDeviceTest#rawFramesTraverseProductionPipeline"]
    for key, value in {"width": width, "height": height, "format": input_format, "codec": codec,
                       "fps": fps, "seconds": seconds, "lowPreview": str(low_preview).lower(),
                       "cpuRepack": str(cpu_repack).lower(), "yuvInput": str(yuv_input).lower()}.items():
        command.extend(["-e", key, str(value)])
    command.append(PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
    print("BEGIN", label, FORMATS[input_format], flush=True)
    with (folder / "instrumentation.log").open("wb") as output:
        try:
            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=seconds + 45)
        except subprocess.TimeoutExpired as error:
            phases = device_file("rawpipeline-phases.log")
            (folder / "phases.log").write_bytes(phases.stdout)
            adb("shell", "am", "force-stop", PACKAGE, check=False)
            raise RuntimeError(f"Pipeline timeout; last phases:\n{phases.stdout.decode(errors='replace')}") from error
    phases = device_file("rawpipeline-phases.log")
    (folder / "phases.log").write_bytes(phases.stdout)
    collector.join(30)
    if collector.is_alive():
        errors.append("HTTP collector did not finish")
    log = (folder / "instrumentation.log").read_text(encoding="utf-8", errors="replace")
    fetched_summary = False
    for extension in ("json", "mp4", "png"):
        name = label + ("-preview.png" if extension == "png" else "." + extension)
        fetched = device_file(name)
        if fetched.returncode == 0:
            (folder / name).write_bytes(fetched.stdout)
            if extension == "json":
                fetched_summary = True
    summary_file = folder / (label + ".json")
    if not fetched_summary:
        print(log, flush=True)
        raise RuntimeError(f"No summary for {label}; network={errors}")
    summary = json.loads(summary_file.read_text(encoding="utf-8"))
    if "OK (1 test)" not in log:
        errors.append("Instrumentation assertions failed; see instrumentation.log")
    ts_file = folder / (label + ".ts")
    summary["http_bytes"] = dechunk(folder / "http-chunked.bin", ts_file)
    last_id = fps + seconds * fps - 1
    mp4, mp4_ids = inspect_video(folder / (label + ".mp4"), fps, last_id)
    ts, ts_ids = inspect_video(ts_file, fps, last_id)
    summary["mp4_validation"] = mp4
    summary["http_validation"] = ts
    summary["http_missing_recorded_ids"] = len(set(mp4_ids) - set(ts_ids))
    measured_ids = [frame_id for frame_id in mp4_ids if fps <= frame_id <= last_id]
    summary["measured_encoded_frames"] = len(measured_ids)
    summary["measured_encoded_fps"] = len(measured_ids) / seconds
    summary["http_missing_measured_ids"] = len(set(measured_ids) - set(ts_ids))
    summary["host_errors"] = errors
    summary["image_pass"] = all(validation["tearing_frames"] == 0 and validation["duplicate_or_reordered"] == 0
                                and validation["color_max_error"] <= 12 and validation["gray_max_error"] <= 12
                                and not validation["decode_warnings"] for validation in (mp4, ts))
    summary["http_all_measured_frames_pass"] = summary["http_missing_measured_ids"] == 0
    (folder / "validated.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("RESULT", json.dumps(summary, separators=(",", ":")), flush=True)
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", action="append", help="width,height,format,H264|H265,fps,seconds,p5|p60")
    parser.add_argument("--cpu-repack", action="store_true", help="Require the isolated CPU-repacking build")
    parser.add_argument("--yuv-input", action="store_true", help="Require direct YUV encoder input without Surface fallback")
    parser.add_argument("--tag", default="", help="Store pipeline evidence in a separate directory")
    arguments = parser.parse_args()
    global ROOT
    if arguments.tag:
        ROOT = ROOT / arguments.tag
    ROOT.mkdir(parents=True, exist_ok=True)
    for package in (PACKAGE, PACKAGE + ".test"):
        installed = adb("shell", "pm", "path", package, check=False)
        if not installed.stdout.startswith(b"package:"):
            raise RuntimeError("Install the isolated diagnostic package first: " + package)
    adb("forward", "tcp:13419", "tcp:13419")
    cases = arguments.case or [f"{width},{height},{input_format},{codec},60,6,p60"
                              for width, height, codec in [(1920, 1080, "H264"), (3840, 2160, "H265")]
                              for input_format in (2, 3, 5, 6, 7, 4, 9)]
    results = []
    try:
        for case in cases:
            width, height, input_format, codec, fps, seconds, preview = case.split(",")
            results.append(run_case(int(width), int(height), int(input_format), codec, int(fps), int(seconds),
                                    preview == "p5", arguments.cpu_repack, arguments.yuv_input))
    finally:
        adb("forward", "--remove", "tcp:13419", check=False)
        (ROOT / ("summary-cpu.json" if arguments.cpu_repack else "summary.json")).write_text(json.dumps(results, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
