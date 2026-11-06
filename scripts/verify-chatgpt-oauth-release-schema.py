#!/usr/bin/env python3
"""Check a release APK retains every OAuth credential JSON field name.

Current releases run R8 with -dontobfuscate. --mapping is optional for current
artifacts and required for older renamed artifacts. Uses Android SDK dexdump,
not credentials; prints no APK string dump.
"""
import argparse
from contextlib import contextmanager
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


RECORD_CLASS = "cn.com.omnimind.baselib.llm.chatgpt.ChatGptRegistration"
ANNOTATION_CLASS = "com.google.gson.annotations.SerializedName"
FIELDS = {
    "clientId", "subject", "email", "accessToken", "refreshToken", "idToken",
    "loginNonce", "expiresAtSeconds", "earliestRefreshAtSeconds", "scopes", "accountRevision",
}


def mapped_descriptor(mapping, class_name):
    match = re.search(r"^" + re.escape(class_name) + r" -> ([^:]+):$", mapping, re.MULTILINE)
    if not match:
        raise ValueError("Required OAuth schema class is absent from the R8 mapping")
    return "L" + match.group(1).replace(".", "/") + ";"


@contextmanager
def dex_files(apk, dex_directory):
    if dex_directory is not None:
        yield [p for p in sorted(dex_directory.iterdir()) if re.fullmatch(r"classes\d*\.dex", p.name)]
        return
    with tempfile.TemporaryDirectory(prefix="omnibot-oauth-schema-") as directory, zipfile.ZipFile(apk) as archive:
        files = []
        for name in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                dex = Path(directory) / name
                dex.write_bytes(archive.read(name))
                files.append(dex)
        yield files


def verify(apk, dex_directory, mapping_file, dexdump):
    if mapping_file is None:
        record = "L" + RECORD_CLASS.replace(".", "/") + ";"
        annotation = "L" + ANNOTATION_CLASS.replace(".", "/") + ";"
        map_id = None
    else:
        mapping = mapping_file.read_text()
        record = mapped_descriptor(mapping, RECORD_CLASS)
        annotation = mapped_descriptor(mapping, ANNOTATION_CLASS)
        map_id = re.search(r"^# pg_map_id: (\S+)$", mapping, re.MULTILINE)
    with dex_files(apk, dex_directory) as files:
        for dex in files:
            output = subprocess.run(
                [str(dexdump), "-a", str(dex)], capture_output=True,
                text=True, errors="replace", timeout=60,
            )
            if output.returncode:
                raise ValueError("dexdump could not inspect the release DEX")
            embedded_ids = set(re.findall(r"r8-map-id-([a-f0-9]+)", output.stdout))
            if map_id and embedded_ids and map_id.group(1) not in embedded_ids:
                raise ValueError("R8 mapping belongs to a different build than the DEX/APK")
            match = re.search(
                r"^Class #(\d+)\s+-\n\s+Class descriptor\s+: '" + re.escape(record) + "'",
                output.stdout, re.MULTILINE,
            )
            if not match:
                continue
            start = output.stdout.rfind("Class #" + match.group(1) + " annotations:", 0, match.start())
            annotations = output.stdout[start:match.start()] if start >= 0 else ""
            found = re.findall(
                r"VISIBILITY_RUNTIME " + re.escape(annotation) + r'[^\n]*\bvalue="([^"]+)"',
                annotations,
            )
            if set(found) != FIELDS or len(found) != len(FIELDS):
                missing = ", ".join(sorted(FIELDS - set(found))) or "duplicate/unexpected fields"
                raise ValueError("Release OAuth JSON schema is not stable: " + missing)
            print("PASS: all 11 OAuth credential JSON field names are stable in " + dex.name)
            return
    hint = "check the matching R8 mapping" if mapping_file is not None else "check the release APK or DEX directory"
    raise ValueError("OAuth credential class was not found; " + hint)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path)
    source.add_argument("--dex-dir", type=Path, help="Directory containing compiled classes*.dex files")
    parser.add_argument("--mapping", type=Path, help="Matching R8 mapping; optional when class names are unchanged")
    parser.add_argument("--dexdump", required=True, type=Path)
    args = parser.parse_args()
    try:
        verify(args.apk, args.dex_dir, args.mapping, args.dexdump)
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        parser.exit(1, "FAIL: " + str(error) + "\n")
