#!/usr/bin/env python3
"""Export historical references and templates into a fresh ignored directory."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urljoin
from common import LEGACY_REVISION, PACKAGE, ROOT, output_directory, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = output_directory(args.output, prepared=False)
    if output.exists() and any(output.iterdir()):
        raise SystemExit("Choose a new empty output directory; existing measurements are never overwritten")
    sources = {
        "legacy_office_preview.dart": "ui/lib/services/office_preview_service.dart",
        "legacy_token_usage.dart": "ui/lib/services/token_usage_service.dart",
    }
    exported = {
        name: subprocess.check_output(["git", "show", f"{LEGACY_REVISION}:{source}"], cwd=ROOT)
        for name, source in sources.items()
    }
    output.mkdir(parents=True, exist_ok=True)
    for name, data in exported.items():
        (output / name).write_bytes(data)
    templates = ("profile_main.dart", "profile.init.gradle", "driver.dart")
    for name in templates:
        shutil.copyfile(Path(__file__).with_name(name), output / name)
    package_config = ROOT / "ui/.dart_tool/package_config.json"
    if package_config.exists():
        config = json.loads(package_config.read_text())
        for package in config["packages"]:
            package["rootUri"] = urljoin(package_config.as_uri(), package["rootUri"])
        (output / ".dart_tool").mkdir(exist_ok=True)
        write_json(output / ".dart_tool/package_config.json", config)
    write_json(output / "boundary-perf.json", {
        "package": PACKAGE,
        "legacyRevision": LEGACY_REVISION,
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "workingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
        "entrypoint": "profile_main.dart",
        "legacySources": {name: hashlib.sha256(data).hexdigest() for name, data in exported.items()},
        "templates": {name: hashlib.sha256((output / name).read_bytes()).hexdigest()
                      for name in templates},
        "purpose": "isolated synthetic data only; no account login",
    })
    print(f"Prepared ignored benchmark workspace: {output.relative_to(ROOT)}")
    print("No device or installed app was accessed.")


if __name__ == "__main__":
    main()
