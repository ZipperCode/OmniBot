#!/usr/bin/env python3
"""Report Dart libraries disconnected from the app's declared entry points.

This is a conservative import/export/part graph, not symbol-level dead-code
analysis. It includes conditional URI alternatives and VM entry points. Native
entry points and source/fixture references must also be reviewed before deleting
anything; a report is never permission to delete a file automatically.
"""

import argparse
import json
import re
from pathlib import Path


def audit(repo: Path, extra_entrypoints: list[str]) -> dict:
    library_root = (repo / "ui/lib").resolve()
    files = sorted(library_root.rglob("*.dart"))
    sources = {path: path.read_text(encoding="utf-8") for path in files}
    package = re.search(
        r"^name:\s*(\S+)",
        (repo / "ui/pubspec.yaml").read_text(encoding="utf-8"),
        re.MULTILINE,
    ).group(1)
    graph = {path: set() for path in files}
    for path, source in sources.items():
        # Examining every quoted URI also covers deferred/conditional imports.
        # Comments may add conservative extra edges, never remove a live edge.
        for directive in re.finditer(r"\b(?:import|export|part)\s+([\s\S]*?);", source):
            for uri in re.findall(r"['\"]([^'\"]+)['\"]", directive.group(1)):
                prefix = f"package:{package}/"
                if uri.startswith(prefix):
                    target = (library_root / uri[len(prefix):]).resolve()
                elif ":" not in uri:
                    target = (path.parent / uri).resolve()
                else:
                    continue
                if target in graph:
                    graph[path].add(target)

    entries = {path for path in files if path.name in {"main.dart", "main_standard.dart"}}
    entries.update(
        path for path, source in sources.items()
        if re.search(r"@pragma\(\s*['\"]vm:entry-point['\"]", source)
    )
    for entry in extra_entrypoints:
        path = (library_root / entry).resolve()
        if path not in graph:
            raise ValueError(f"Entry point is not a Dart library in ui/lib: {entry}")
        entries.add(path)
    visited = set()
    pending = list(entries)
    while pending:
        path = pending.pop()
        if path not in visited:
            visited.add(path)
            pending.extend(graph[path])

    def relative(path: Path) -> str:
        return path.relative_to(repo).as_posix()

    unreachable = [
        {
            "path": relative(path),
            "lines": len(sources[path].splitlines()),
            "bytes": path.stat().st_size,
            "importedBy": [relative(source) for source in files if path in graph[source]],
        }
        for path in files if path not in visited
    ]
    return {
        "entrypoints": [relative(path) for path in sorted(entries)],
        "libraries": len(files),
        "reachableLibraries": len(visited),
        "unreachableLibraries": len(unreachable),
        "unreachableLines": sum(row["lines"] for row in unreachable),
        "unreachableBytes": sum(row["bytes"] for row in unreachable),
        "unreachable": unreachable,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--entrypoint", action="append", default=[], help="Extra path below ui/lib")
    parser.add_argument("--json", action="store_true", help="Print the full machine-readable report")
    args = parser.parse_args()
    report = audit(Path(__file__).resolve().parents[1], args.entrypoint)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    print(
        f"{report['libraries']} libraries; {report['reachableLibraries']} reachable; "
        f"{report['unreachableLibraries']} disconnected "
        f"({report['unreachableLines']} lines, {report['unreachableBytes']} bytes)"
    )
    for row in report["unreachable"]:
        print(row["path"])


if __name__ == "__main__":
    main()
