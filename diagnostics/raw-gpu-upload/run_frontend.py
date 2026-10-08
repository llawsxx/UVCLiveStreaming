import argparse
import json
import subprocess

from run_pipeline import ADB, PACKAGE, ROOT, FORMATS, adb, device_file


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--formats", default="2", help="Native raw format IDs, comma-separated")
    parser.add_argument("--seconds", type=int, default=3)
    parser.add_argument("--burst-frames", type=int, default=160)
    parser.add_argument("--tag", default="", help="Suffix for a separate evidence directory")
    arguments = parser.parse_args()
    folder = ROOT / ("frontend-f" + arguments.formats.replace(",", "-") + ("-" + arguments.tag if arguments.tag else ""))
    folder.mkdir(parents=True, exist_ok=True)
    adb("shell", "am", "force-stop", PACKAGE)
    command = [ADB, "-t", "1", "shell", "am", "instrument", "-w", "-r", "-e", "class",
               "com.llawsxx.uvclivestreaming.recording.SyntheticRawFrontendDeviceTest#benchmarkBeforeGpu"]
    for key, value in {"formats": arguments.formats, "seconds": arguments.seconds,
                       "burstFrames": arguments.burst_frames}.items():
        command.extend(["-e", key, str(value)])
    command.append(PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
    print("BEGIN frontend", arguments.formats, flush=True)
    with (folder / "instrumentation.log").open("wb") as output:
        try:
            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT,
                           timeout=35 + len(arguments.formats.split(",")) * 4 * (arguments.seconds + 6))
        except subprocess.TimeoutExpired:
            adb("shell", "am", "force-stop", PACKAGE, check=False)
            raise
    log = (folder / "instrumentation.log").read_text(encoding="utf-8", errors="replace")
    fetched = device_file("rawfrontend.json")
    if fetched.returncode:
        raise RuntimeError(fetched.stdout.decode(errors="replace"))
    results = json.loads(fetched.stdout)
    (folder / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    idle = device_file("rawfrontend-idle.json")
    if idle.returncode == 0:
        (folder / "idle.json").write_bytes(idle.stdout)
        print("IDLE", idle.stdout.decode().strip(), flush=True)
    if "OK (1 test)" not in log:
        print(log, flush=True)
        raise RuntimeError("Frontend instrumentation failed; partial results saved")
    for result in results:
        print(f'{result["width"]}x{result["height"]} {FORMATS[result["format"]]} '
              f'{"CPU" if result["cpu_repack"] else "direct"} {result["mode"]}: '
              f'copy={result["copy"]["mean_ms"]:.4f} callback={result["callback"]["mean_ms"]:.4f} '
              f'queue={result["queue"]["mean_ms"]:.4f} conversion={result["conversion"]["mean_ms"]:.4f} '
              f'total={result["frontend_total"]["mean_ms"]:.4f}/{result["frontend_total"]["p95_ms"]:.4f} ms '
              f'fps={result["throughput_fps"]:.2f} source_skips={result["source_skipped"]} '
              f'queue_drops={result["measured_queue_drops"]}', flush=True)


if __name__ == "__main__":
    main()
