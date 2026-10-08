import argparse
import json
import time

import run_pipeline as pipeline


def passed(result):
    return (result["image_pass"] and result["http_all_measured_frames_pass"]
            and not result["host_errors"] and not result["errors"] and result["preview_errors"] == 0
            and result["mp4_validation"]["missing_measured_ids"] == 0
            and all(result[key] == 0 for key in ("source_skipped", "queue_drops", "timestamp_skips",
                                                "mjpeg_decode_failures", "mjpeg_input_drops", "mjpeg_output_skips")))


def compare(surface, yuv):
    result = {"width": surface["width"], "height": surface["height"],
              "format": pipeline.FORMATS[surface["format"]], "codec": surface["codec"],
              "surface_pass": passed(surface), "yuv_pass": passed(yuv),
              "surface_codec": surface["encoder_name"], "yuv_codec": yuv["encoder_name"],
              "surface_fps": surface["measured_encoded_fps"], "yuv_fps": yuv["measured_encoded_fps"],
              "yuv_conversion_ms": yuv["yuv_conversion_mean_ms"],
              "yuv_input_wait_ms": yuv["yuv_input_wait_mean_ms"]}
    for metric in ("release_mean_ms", "release_p95_ms", "encoder_delivery_mean_ms"):
        result["surface_" + metric] = surface[metric]
        result["yuv_" + metric] = yuv[metric]
        result[metric + "_reduction_percent"] = 100 * (1 - yuv[metric] / surface[metric]) if surface[metric] else None
    return result


def main():
    parser = argparse.ArgumentParser(description="Paired YUV-to-codec versus YUV-to-RGB Surface pipeline measurements")
    parser.add_argument("--formats", default="1,2,5,6")
    parser.add_argument("--seconds", type=int, default=6)
    parser.add_argument("--fps", type=int, default=60)
    parser.add_argument("--rounds", type=int, default=2)
    parser.add_argument("--start-round", type=int, default=1)
    parser.add_argument("--cooldown", type=float, default=3)
    parser.add_argument("--low-preview", action="store_true")
    parser.add_argument("--tag", default="yuv-input-compare")
    arguments = parser.parse_args()
    if arguments.rounds < 1 or arguments.start_round < 1 or arguments.seconds < 1 or arguments.fps < 1 or arguments.cooldown < 0:
        parser.error("rounds, seconds and fps must be positive; cooldown must be nonnegative")
    formats = [int(value) for value in arguments.formats.split(",")]
    if any(value not in pipeline.FORMATS for value in formats):
        parser.error("Unsupported input format")
    root = pipeline.ROOT / arguments.tag
    root.mkdir(parents=True, exist_ok=True)
    pipeline.adb("forward", "tcp:13419", "tcp:13419")
    summary_file = root / "comparison.json"
    results = json.loads(summary_file.read_text(encoding="utf-8")) if arguments.start_round > 1 and summary_file.exists() else []
    for name in ("thermalservice", "battery"):
        (root / f"{name}-before-round-{arguments.start_round}.txt").write_bytes(
            pipeline.adb("shell", "dumpsys", name).stdout)
    try:
        for iteration in range(arguments.start_round - 1, arguments.start_round - 1 + arguments.rounds):
            pipeline.ROOT = root / f"round-{iteration + 1}"
            pipeline.ROOT.mkdir(parents=True, exist_ok=True)
            for width, height, codec in ((1920, 1080, "H264"), (3840, 2160, "H265")):
                for input_format in formats:
                    pair = {}
                    for yuv_input in ((False, True) if iteration % 2 == 0 else (True, False)):
                        time.sleep(arguments.cooldown)
                        pair[yuv_input] = pipeline.run_case(width, height, input_format, codec,
                            arguments.fps, arguments.seconds, arguments.low_preview, yuv_input=yuv_input)
                    comparison = compare(pair[False], pair[True])
                    comparison["round"] = iteration + 1
                    results.append(comparison)
                    (root / "comparison.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
                    print("COMPARE", json.dumps(comparison, separators=(",", ":")), flush=True)
    finally:
        pipeline.adb("forward", "--remove", "tcp:13419", check=False)
        (root / "comparison.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        for name in ("thermalservice", "battery"):
            (root / f"{name}-after-round-{arguments.start_round + arguments.rounds - 1}.txt").write_bytes(
                pipeline.adb("shell", "dumpsys", name, check=False).stdout)
    if not all(result["surface_pass"] and result["yuv_pass"] for result in results):
        raise SystemExit("Pipeline validation failed; see comparison.json and per-case evidence")


if __name__ == "__main__":
    main()
