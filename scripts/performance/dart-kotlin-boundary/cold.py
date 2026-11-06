#!/usr/bin/env python3
"""Measure Activity start TotalTime and settled PSS of the isolated test app."""
import argparse
import re
import statistics
import subprocess
import time
from common import ACTIVITY, PACKAGE, adb_for, output_directory, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--samples", type=int, default=7)
    parser.add_argument("--settle-seconds", type=float, default=6)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label):
        raise SystemExit("--label may contain only letters, digits, dash and underscore")
    if not 1 <= args.samples <= 100 or args.settle_seconds < 0:
        raise SystemExit("--samples must be 1..100 and --settle-seconds nonnegative")
    output = output_directory(args.output)
    target = output / f"{args.label}-cold.json"
    if target.exists():
        raise SystemExit("This label already has results; choose another label")
    adb = adb_for(args.device)
    rows = []
    for index in range(args.samples):
        subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], check=True, capture_output=True)
        result = subprocess.check_output(adb + ["shell", "am", "start", "-W", "-n", ACTIVITY], text=True)
        timing = re.search(r"TotalTime:\s+(\d+)", result)
        if "Status: ok" not in result or timing is None:
            raise SystemExit("Isolated Activity failed to start; no zero/failed sample is recorded")
        time.sleep(args.settle_seconds)
        subprocess.run(adb + ["shell", "pidof", PACKAGE], check=True, capture_output=True)
        memory = subprocess.check_output(adb + ["shell", "dumpsys", "meminfo", PACKAGE], text=True)
        battery = subprocess.check_output(adb + ["shell", "dumpsys", "battery"], text=True)
        thermal = subprocess.check_output(adb + ["shell", "dumpsys", "thermalservice"], text=True)
        (output / f"{args.label}-cold-{index}-memory.txt").write_text(memory)
        (output / f"{args.label}-cold-{index}-activity.txt").write_text(result)
        pss = re.search(r"TOTAL PSS:\s+(\d+)", memory)
        temperature = re.search(r"temperature:\s+(\d+)", battery)
        status = re.search(r"Thermal Status:\s+(\d+)", thermal)
        if pss is None:
            raise SystemExit("Device meminfo did not report TOTAL PSS; inspect the saved raw output")
        rows.append({"activityTotalTimeMs": int(timing[1]), "settledPssKiB": int(pss[1]),
                     "batteryC": int(temperature[1]) / 10 if temperature else None,
                     "thermalStatus": int(status[1]) if status else None})
        write_json(target, {"package": PACKAGE, "settleSeconds": args.settle_seconds, "samples": rows})
        print(f"{args.label} sample {index + 1}/{args.samples}: {rows[-1]}", flush=True)
    print("Medians:", {"activityTotalTimeMs": statistics.median(row["activityTotalTimeMs"] for row in rows),
                       "settledPssKiB": statistics.median(row["settledPssKiB"] for row in rows)})


if __name__ == "__main__":
    main()
