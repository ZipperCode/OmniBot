#!/usr/bin/env python3
"""Seed only an empty independent test package with synthetic benchmark data."""
import argparse
import datetime
import hashlib
import io
import sqlite3
import subprocess
import tarfile
from zoneinfo import ZoneInfo
from common import DATABASE, PACKAGE, adb_for, output_directory, write_json
from fixtures import create

DAY_MS = 86_400_000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--conversations", type=int, default=10_000)
    parser.add_argument("--tokens", type=int, default=100_000)
    args = parser.parse_args()
    if args.conversations <= 0 or args.tokens < 0:
        raise SystemExit("--conversations must be positive and --tokens nonnegative")
    output = output_directory(args.output)
    if (output / "dataset.json").exists():
        raise SystemExit("This output already contains a dataset; choose a fresh workspace")
    adb = adb_for(args.device)
    subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], check=True, capture_output=True)
    raw = subprocess.check_output(adb + ["exec-out", "run-as", PACKAGE, "tar", "cf", "-", "databases"])
    work = output / "synthetic-db" / "databases"
    if work.exists() and any(work.iterdir()):
        raise SystemExit("A previous staging database exists; use a fresh prepared output to avoid stale WAL files")
    work.mkdir(parents=True, exist_ok=True)
    # Extract only known SQLite files; no archive paths or symlinks are followed.
    with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
        for suffix in ("", "-wal", "-shm"):
            name = DATABASE + suffix
            try:
                item = archive.getmember("databases/" + name)
            except KeyError:
                if not suffix:
                    raise SystemExit("Launch the isolated test app once to create its empty database")
                continue
            if not item.isfile():
                raise SystemExit("Unexpected SQLite archive entry")
            (work / name).write_bytes(archive.extractfile(item).read())
    path = work / DATABASE
    connection = sqlite3.connect(path)
    try:
        for table in ("conversations", "token_usage_records"):
            if connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] != 0:
                raise SystemExit("Fresh isolated test database required; no existing records will be replaced")
        zone_name = subprocess.check_output(adb + ["shell", "getprop", "persist.sys.timezone"], text=True).strip()
        zone = ZoneInfo(zone_name)
        epoch = int(subprocess.check_output(adb + ["shell", "date", "+%s"], text=True).strip())
        now = datetime.datetime.fromtimestamp(epoch, zone)
        today = now.replace(hour=0, minute=0, second=0, microsecond=0)
        today_ms = int(today.timestamp() * 1000)
        start = today_ms - 111 * DAY_MS
        connection.executemany(
            "INSERT INTO conversations(title,mode,isArchived,isPinned,summary,contextSummaryUpdatedAt,status,lastMessage,"
            "messageCount,latestPromptTokens,promptTokenThreshold,latestPromptTokensUpdatedAt,createdAt,updatedAt) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ((f"[BOUNDARY PERF] Conversation {i}", "agent", int(i % 5 == 0), 0,
              f"Synthetic metadata {i}", 0, 0, "Synthetic previous response " + "sample " * 40,
              0, 0, 128000, 0, start + (i % 112) * DAY_MS + 3_600_000,
              start + (i % 112) * DAY_MS + 3_600_000) for i in range(args.conversations)),
        )
        models = ["openai/gpt-synthetic", "Provider :: qwen-synthetic", "deepseek:synthetic", "gpt-synthetic", "unknown"]
        conversation_ids = [row[0] for row in connection.execute("SELECT id FROM conversations ORDER BY id")]
        connection.executemany(
            "INSERT INTO token_usage_records(conversationId,model,promptTokens,completionTokens,reasoningTokens,textTokens,"
            "cachedTokens,cacheCreationTokens,createdAt) VALUES (?,?,?,?,?,?,?,?,?)",
            ((conversation_ids[i % len(conversation_ids)], models[i % 5], 100, 50, i % 13,
              0 if i % 4 == 0 else i % 17, i % 23, 0, start + (i % 112) * DAY_MS + 7_200_000)
             for i in range(args.tokens)),
        )
        connection.commit()
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise SystemExit("Synthetic database integrity check failed")
    finally:
        connection.close()
    subprocess.run(adb + ["shell", "run-as", PACKAGE, "rm", "-f", f"databases/{DATABASE}-wal", f"databases/{DATABASE}-shm"], check=True, capture_output=True)
    subprocess.run(adb + ["shell", "-T", "run-as", PACKAGE, "dd", f"of=databases/{DATABASE}"], input=path.read_bytes(), check=True, capture_output=True)
    create(output / "fixtures")
    documents = {}
    for source in sorted((output / "fixtures").iterdir()):
        content = source.read_bytes()
        subprocess.run(adb + ["shell", "-T", "run-as", PACKAGE, "dd", f"of=files/{source.name}"], input=content, check=True, capture_output=True)
        documents[source.name] = {"bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
    write_json(output / "dataset.json", {
        "package": PACKAGE, "conversations": args.conversations, "tokenRecords": args.tokens,
        "todayMs": today_ms, "timeZone": zone_name, "startMs": start, "endMs": start + 112 * DAY_MS,
        "documents": documents,
    })
    print(f"Seeded boundaryperf only: {args.conversations} synthetic conversations, {args.tokens} synthetic token records, 3 generated documents.")


if __name__ == "__main__":
    main()
