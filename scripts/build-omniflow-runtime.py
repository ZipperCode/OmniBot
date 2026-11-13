#!/usr/bin/env python3
"""从固定底包叠加维护源码，生成可复现的离线运行时及目录校验信息。"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASE_ZIP = REPO_ROOT / "artifacts" / "omniflow-gui-runtime-2.1.8.zip"
DEFAULT_OUTPUT_ZIP = REPO_ROOT / "artifacts" / "omniflow-gui-runtime-2.1.9.zip"
DEFAULT_CATALOG_PATH = REPO_ROOT / "plugins" / "catalog.v1.json"
DEFAULT_COMPONENT_JSON_PATH = REPO_ROOT / "plugins" / "omni-vlm-lite" / "component.json"
DEFAULT_RUNTIME_SOURCE_DIR = REPO_ROOT / "plugins" / "omni-vlm-lite" / "runtime-source"
DEFAULT_CANONICAL_ACTIONS_PATH = (
    REPO_ROOT
    / "plugins"
    / "omni-vlm-lite"
    / "schemas"
    / "oob"
    / "oob_canonical_actions.v1.json"
)
DEFAULT_RUNTIME_SKILL_PROPERTIES_PATH = (
    REPO_ROOT
    / "plugins"
    / "omni-vlm-lite"
    / "runtime-skill"
    / "omniflow-gui-runtime"
    / "scripts"
    / "runtime"
    / "runtime.properties"
)
ZIP_TIMESTAMP = (1981, 1, 1, 0, 0, 0)
ZIP_FILE_ATTR = 0o644 << 16


def parse_properties(text: str) -> tuple[list[str], dict[str, str]]:
    raw_lines = text.splitlines()
    props: dict[str, str] = {}
    for line in raw_lines:
        s = line.strip()
        if s and not s.startswith(("#", "!")) and "=" in s:
            k, v = s.split("=", 1)
            props[k.strip()] = v.strip()
    return raw_lines, props


def render_properties(
    raw_lines: list[str],
    props: dict[str, str],
    new_keys_order: list[str],
) -> str:
    seen: set[str] = set()
    out_lines: list[str] = []
    for line in raw_lines:
        s = line.strip()
        if s and not s.startswith(("#", "!")) and "=" in s:
            k, _ = s.split("=", 1)
            k = k.strip()
            if k in props:
                out_lines.append(f"{k}={props[k]}")
                seen.add(k)
            else:
                out_lines.append(line)
        else:
            out_lines.append(line)

    for k in new_keys_order:
        if k in props and k not in seen:
            out_lines.append(f"{k}={props[k]}")
            seen.add(k)

    return "\n".join(out_lines) + "\n"


def compute_deterministic_zip(files: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name in sorted(files.keys()):
            info = zipfile.ZipInfo(filename=name, date_time=ZIP_TIMESTAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = ZIP_FILE_ATTR
            z.writestr(info, files[name])
    return buf.getvalue()


def assemble_bundle(
    base_zip_path: Path,
    runtime_source_dir: Path,
    canonical_actions_path: Path,
    version: str,
) -> tuple[bytes, str, int, dict[str, bytes], str]:
    if not base_zip_path.is_file():
        raise FileNotFoundError(f"Base runtime zip not found: {base_zip_path}")

    files: dict[str, bytes] = {}
    with zipfile.ZipFile(base_zip_path, "r") as z:
        for name in z.namelist():
            if not name.endswith("/"):
                files[name] = z.read(name)

    overlay_files: dict[str, bytes] = {}

    # 叠加本仓库维护的运行时源码。
    if runtime_source_dir.is_dir():
        for p in sorted(runtime_source_dir.rglob("*.py")):
            rel = p.relative_to(runtime_source_dir).as_posix()
            target_in_zip = f"scripts/runtime/python/{rel}"
            data = p.read_bytes()
            files[target_in_zip] = data
            overlay_files[rel] = data

    # 特征版本加载器与冻结权重分开维护，不修改底包或 checkpoint。
    transfer_source_dir = runtime_source_dir.parent / "omnitransfer-source"
    if transfer_source_dir.is_dir():
        for p in sorted(transfer_source_dir.rglob("*.py")):
            rel = p.relative_to(transfer_source_dir).as_posix()
            target_in_zip = f"scripts/runtime/.runtime/omnitransfer/src/{rel}"
            data = p.read_bytes()
            files[target_in_zip] = data
            overlay_files[f"omnitransfer/{rel}"] = data

    # 同步规范动作契约与公共 RunLog 契约。
    if canonical_actions_path.is_file():
        data = canonical_actions_path.read_bytes()
        files["scripts/runtime/python/schemas/oob/oob_canonical_actions.v1.json"] = data
        overlay_files["schemas/oob/oob_canonical_actions.v1.json"] = data

    public_run_log_path = canonical_actions_path.parent / "omniflow_run_log.v1.json"
    if public_run_log_path.is_file():
        data = public_run_log_path.read_bytes()
        files["scripts/runtime/python/schemas/oob/omniflow_run_log.v1.json"] = data
        overlay_files["schemas/oob/omniflow_run_log.v1.json"] = data
    # 更新包内组件版本。
    if "component.json" in files:
        comp = json.loads(files["component.json"].decode("utf-8"))
        comp["version"] = version
        files["component.json"] = (json.dumps(comp, indent=2) + "\n").encode("utf-8")

    # 更新运行时清单。
    manifest_name = "scripts/runtime/runtime.properties"
    if manifest_name in files:
        raw_lines, props = parse_properties(files[manifest_name].decode("utf-8"))

        # 计算叠加源码的校验摘要。
        h_patch = hashlib.sha256()
        for name in sorted(overlay_files.keys()):
            h_patch.update(name.encode("utf-8"))
            h_patch.update(b"\0")
            h_patch.update(overlay_files[name])
        patch_sha256 = h_patch.hexdigest()

        # 计算包内所有运行时源码的校验摘要。
        h_src = hashlib.sha256()
        for name in sorted(files.keys()):
            if name.startswith("scripts/runtime/python/omniflow/"):
                rel = name[len("scripts/runtime/python/") :]
                h_src.update(rel.encode("utf-8"))
                h_src.update(b"\0")
                h_src.update(files[name])
            elif name.startswith("scripts/runtime/.runtime/omnitransfer/src/") and name.endswith(".py"):
                rel = "omnitransfer/" + name[len("scripts/runtime/.runtime/omnitransfer/src/") :]
                h_src.update(rel.encode("utf-8"))
                h_src.update(b"\0")
                h_src.update(files[name])
        source_sha256 = h_src.hexdigest()

        props["omniflow.patch.provenance"] = "plugins/omni-vlm-lite/runtime-source"
        props["omniflow.patch.sha256"] = patch_sha256
        props["omniflow.source.sha256"] = source_sha256

        # 同步各项契约的校验摘要。
        for name in sorted(files.keys()):
            if name.startswith("scripts/runtime/python/schemas/oob/"):
                basename = name.split("/")[-1]
                props[f"schema.{basename}.sha256"] = hashlib.sha256(files[name]).hexdigest()

        bridge_schema_key = "schema.omniflow_android_bridge.v2.json.sha256"
        if bridge_schema_key in props:
            props["bridge.contract.sha256"] = props[bridge_schema_key]

        short_source = source_sha256[:8]
        omni_commit = props.get("omniflow.commit", "d453a47e")
        short_commit = omni_commit[:8]
        props["runtime.version"] = f"2026.11.10.patch.{short_source}.{short_commit}"

        new_props_text = render_properties(
            raw_lines,
            props,
            ["omniflow.patch.provenance", "omniflow.patch.sha256"],
        )
        files[manifest_name] = new_props_text.encode("utf-8")
    else:
        new_props_text = ""

    zip_bytes = compute_deterministic_zip(files)
    zip_sha256 = hashlib.sha256(zip_bytes).hexdigest()
    zip_size = len(zip_bytes)

    return zip_bytes, zip_sha256, zip_size, files, new_props_text


def update_catalog(
    catalog_path: Path,
    version: str,
    zip_filename: str,
    zip_sha256: str,
    zip_size: int,
) -> tuple[dict, bool]:
    catalog_data = json.loads(catalog_path.read_text(encoding="utf-8"))
    plugins = catalog_data.get("plugins", [])
    if not plugins:
        raise ValueError(f"No plugins found in catalog {catalog_path}")

    target = plugins[0]
    changed = False

    if target.get("version") != version:
        target["version"] = version
        changed = True

    if target.get("downloadSizeBytes") != zip_size:
        target["downloadSizeBytes"] = zip_size
        changed = True

    skill = target.setdefault("runtimeSkill", {})
    expected_path = f"runtime-components/{zip_filename}"
    if skill.get("packagedArchivePath") != expected_path:
        skill["packagedArchivePath"] = expected_path
        changed = True

    if skill.get("packagedArchiveSha256") != zip_sha256:
        skill["packagedArchiveSha256"] = zip_sha256
        changed = True

    if skill.get("prebuiltRuntimeSha256") != zip_sha256:
        skill["prebuiltRuntimeSha256"] = zip_sha256
        changed = True

    # 离线且尚未发布的组件不得登记虚构的远端地址及摘要。
    if "componentArchiveUrl" in skill:
        del skill["componentArchiveUrl"]
        changed = True
    if "componentArchiveSha256" in skill:
        del skill["componentArchiveSha256"]
        changed = True

    return catalog_data, changed


def update_component_json(
    component_json_path: Path,
    version: str,
) -> tuple[dict, bool]:
    data = json.loads(component_json_path.read_text(encoding="utf-8"))
    changed = False
    if data.get("version") != version:
        data["version"] = version
        changed = True
    return data, changed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="生成可复现的离线 OmniFlow GUI 运行时"
    )
    parser.add_argument(
        "--base-zip",
        type=Path,
        default=DEFAULT_BASE_ZIP,
        help="不可变底包路径（默认：%(default)s）",
    )
    parser.add_argument(
        "--output-zip",
        type=Path,
        default=DEFAULT_OUTPUT_ZIP,
        help="新运行时包输出路径（默认：%(default)s）",
    )
    parser.add_argument(
        "--version",
        default="2.1.9",
        help="目标组件版本（默认：%(default)s）",
    )
    parser.add_argument(
        "--catalog",
        type=Path,
        default=DEFAULT_CATALOG_PATH,
        help="插件目录文件路径（默认：%(default)s）",
    )
    parser.add_argument(
        "--component-json",
        type=Path,
        default=DEFAULT_COMPONENT_JSON_PATH,
        help="组件清单路径（默认：%(default)s）",
    )
    parser.add_argument(
        "--runtime-source",
        type=Path,
        default=DEFAULT_RUNTIME_SOURCE_DIR,
        help="叠加的维护源码目录（默认：%(default)s）",
    )
    parser.add_argument(
        "--canonical-actions",
        type=Path,
        default=DEFAULT_CANONICAL_ACTIONS_PATH,
        help="规范动作契约路径（默认：%(default)s）",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="只读核对运行时包、插件目录和组件版本的一致性",
    )

    args = parser.parse_args(argv)

    zip_bytes, zip_sha256, zip_size, files, new_props_text = assemble_bundle(
        base_zip_path=args.base_zip,
        runtime_source_dir=args.runtime_source,
        canonical_actions_path=args.canonical_actions,
        version=args.version,
    )

    output_zip_name = args.output_zip.name

    catalog_data, catalog_changed = update_catalog(
        catalog_path=args.catalog,
        version=args.version,
        zip_filename=output_zip_name,
        zip_sha256=zip_sha256,
        zip_size=zip_size,
    )

    comp_data, comp_changed = update_component_json(
        component_json_path=args.component_json,
        version=args.version,
    )

    if args.check:
        errors: list[str] = []
        if not args.output_zip.is_file():
            errors.append(f"Output zip does not exist: {args.output_zip}")
        else:
            existing_data = args.output_zip.read_bytes()
            existing_sha = hashlib.sha256(existing_data).hexdigest()
            if existing_sha != zip_sha256:
                errors.append(
                    f"Output zip SHA-256 mismatch: expected {zip_sha256}, got {existing_sha}"
                )
            if len(existing_data) != zip_size:
                errors.append(
                    f"Output zip size mismatch: expected {zip_size}, got {len(existing_data)}"
                )

        if catalog_changed:
            errors.append(f"Catalog {args.catalog} requires update to match bundle version/sha")

        if comp_changed:
            errors.append(f"Component json {args.component_json} requires update to version {args.version}")

        if errors:
            print("CHECK FAILED:", file=sys.stderr)
            for err in errors:
                print(f"  - {err}", file=sys.stderr)
            return 1

        print(
            f"CHECK OK: {output_zip_name} sha256={zip_sha256} size={zip_size} bytes (matches catalog and component.json)"
        )
        return 0

    # 写入新运行时包。
    args.output_zip.parent.mkdir(parents=True, exist_ok=True)
    args.output_zip.write_bytes(zip_bytes)

    # 同步插件目录。
    args.catalog.write_text(
        json.dumps(catalog_data, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    # 同步组件版本。
    args.component_json.write_text(
        json.dumps(comp_data, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    # 已存在运行时维护目录时，同步其清单。
    if DEFAULT_RUNTIME_SKILL_PROPERTIES_PATH.parent.is_dir() and new_props_text:
        DEFAULT_RUNTIME_SKILL_PROPERTIES_PATH.write_text(new_props_text, encoding="utf-8")

    print(f"Built bundle: {args.output_zip}")
    print(f"SHA-256:      {zip_sha256}")
    print(f"Size:         {zip_size} bytes")
    print(f"Version:      {args.version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
