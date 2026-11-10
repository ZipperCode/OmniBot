from __future__ import annotations

import ast
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import re
import unittest
from zipfile import ZipFile


COMPONENT_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = COMPONENT_ROOT.parents[1]
CATALOG_PATH = REPOSITORY_ROOT / "plugins/catalog.v1.json"


def resolve_archive_path() -> Path:
    override = os.environ.get("OMNIFLOW_COMPONENT_TEST_ARCHIVE")
    if override:
        return Path(override)
    catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    first_plugin = catalog["plugins"][0]
    packaged_path = first_plugin.get("runtimeSkill", {}).get("packagedArchivePath", "")
    filename = Path(packaged_path).name
    return REPOSITORY_ROOT / "artifacts" / filename


def read_properties(contents: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in contents.splitlines():
        line = raw_line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


class RuntimeBundleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.archive_path = resolve_archive_path()
        if not cls.archive_path.is_file():
            raise FileNotFoundError(f"Runtime bundle archive not found: {cls.archive_path}")
        with ZipFile(cls.archive_path) as archive:
            cls.names = set(archive.namelist())
            cls.files = {
                name: archive.read(name)
                for name in cls.names
                if not name.endswith("/")
            }
        cls.properties = read_properties(
            cls.files["scripts/runtime/runtime.properties"].decode("utf-8")
        )

    def test_release_asset_matches_catalog(self) -> None:
        if os.environ.get("OMNIFLOW_COMPONENT_TEST_ARCHIVE"):
            self.skipTest("working-tree component is not a catalog release asset")
        catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))["plugins"][0]
        self.assertEqual(self.archive_path.stat().st_size, catalog["downloadSizeBytes"])
        archive_sha = hashlib.sha256(self.archive_path.read_bytes()).hexdigest()
        self.assertEqual(
            archive_sha,
            catalog["runtimeSkill"]["packagedArchiveSha256"],
        )
        # Offline bundle does not require a remote URL; if present, verify version consistency
        remote_url = catalog["runtimeSkill"].get("componentArchiveUrl")
        if remote_url:
            self.assertIn(f"-{catalog['version']}.zip", remote_url)

    def test_release_is_self_contained_mobile_component(self) -> None:
        required = {
            "component.json",
            "README.md",
            "INSTALL_DIR.json",
            "SKILL.md",
            "scripts/runtime/runtime.properties",
            "scripts/runtime/python/omniflow/bridge.py",
            "scripts/runtime/python/config/paper_androidworld.json",
            "vendor/site-packages/json_repair/__init__.py",
            "scripts/runtime/python/schemas/oob/omniflow_android_bridge.v2.json",
            "scripts/runtime/python/schemas/oob/oob_canonical_actions.v1.json",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/runtime.py",
        }
        self.assertTrue(required <= self.names)
        self.assertIn(
            "scripts/runtime/python/omniflow/catalog/releases/2026.08.06.2/function_store.json",
            self.names,
        )
        self.assertIn(
            "scripts/runtime/python/omniflow/catalog/releases/2026.08.06.2/states.json.xz.b64",
            self.names,
        )
        self.assertFalse(any(name.endswith((".zip", ".whl")) for name in self.names))
        self.assertNotIn("pyproject.toml", self.names)
        self.assertNotIn("uv.lock", self.names)
        self.assertFalse(
            any(name.startswith("scripts/runtime/python/omniflow_mcp/") for name in self.names)
        )
        self.assertFalse(any("/.venv/" in f"/{name}" for name in self.names))
        self.assertFalse(any(name.startswith(".venv/") for name in self.names))

    def test_release_manifest_and_schema_contract(self) -> None:
        version = self.properties.get("runtime.version", "")
        self.assertTrue(bool(re.match(r"^[A-Za-z0-9._-]+$", version)), f"Invalid runtime.version: {version}")
        self.assertIn("initialize", self.properties.get("runtime.capabilities", ""))
        self.assertIn("tools/call", self.properties.get("runtime.capabilities", ""))
        self.assertIn("tools/list", self.properties.get("runtime.capabilities", ""))
        self.assertEqual(self.properties.get("runtime.python"), "3.12")
        self.assertTrue(bool(self.properties.get("omniflow.commit")))

        source_sha = self.properties.get("omniflow.source.sha256", "")
        self.assertTrue(bool(re.match(r"^[a-f0-9]{64}$", source_sha)), f"Invalid source sha256: {source_sha}")

        self.assertIn("omniflow.patch.provenance", self.properties)
        patch_sha = self.properties.get("omniflow.patch.sha256", "")
        self.assertTrue(bool(re.match(r"^[a-f0-9]{64}$", patch_sha)), f"Invalid patch sha256: {patch_sha}")

        # Check internal component.json
        internal_comp = json.loads(self.files["component.json"].decode("utf-8"))
        self.assertEqual(internal_comp.get("id"), "com.omnimind.omni-vlm-lite")
        self.assertEqual(internal_comp.get("androidAdapter"), "omniflow_android_gui")

        catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))["plugins"][0]
        self.assertEqual(internal_comp.get("version"), catalog["version"])

        # Check schemas declared in runtime.properties
        schema_prefix = "scripts/runtime/python/schemas/oob/"
        checked_schemas = 0
        for key, expected_hash in self.properties.items():
            if key.startswith("schema.") and key.endswith(".sha256"):
                file_name = key[len("schema.") : -len(".sha256")]
                full_path = schema_prefix + file_name
                self.assertIn(full_path, self.names)
                actual_hash = hashlib.sha256(self.files[full_path]).hexdigest()
                self.assertEqual(
                    actual_hash,
                    expected_hash.lower(),
                    f"Hash mismatch for {file_name}",
                )
                checked_schemas += 1
        self.assertGreaterEqual(checked_schemas, 5)

        bridge_contract = self.properties.get("bridge.contract.sha256", "").lower()
        bridge_schema_sha = self.properties.get(
            "schema.omniflow_android_bridge.v2.json.sha256", ""
        ).lower()
        self.assertEqual(bridge_contract, bridge_schema_sha)

        # Validate python syntax of core runtime modules
        py_modules = [
            "scripts/runtime/python/omniflow/bridge.py",
            "scripts/runtime/python/omniflow/vlm/planner.py",
            "scripts/runtime/python/omniflow/vlm/context.py",
            "scripts/runtime/python/omniflow/runtime/engine.py",
            "scripts/runtime/python/omniflow/runtime/execution.py",
            "scripts/runtime/python/omniflow/runtime/semantic_grounding.py",
            "scripts/runtime/python/omniflow/core/schemas.py",
        ]
        for mod in py_modules:
            self.assertIn(mod, self.names)
            code = self.files[mod].decode("utf-8")
            ast.parse(code, filename=mod)

    def test_release_checkpoint_contract(self) -> None:
        checkpoint_rel = self.properties.get("omnitransfer.checkpoint", "")
        self.assertTrue(checkpoint_rel, "omnitransfer.checkpoint missing from properties")
        prefix = "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/"
        checkpoint = prefix + checkpoint_rel
        self.assertIn(checkpoint, self.names)

        expected_checkpoint_sha = self.properties.get("omnitransfer.checkpoint.sha256", "").lower()
        actual_checkpoint_sha = hashlib.sha256(self.files[checkpoint]).hexdigest()
        self.assertEqual(actual_checkpoint_sha, expected_checkpoint_sha)

        with ZipFile(BytesIO(self.files[checkpoint])) as weights:
            weight_names = set(weights.namelist())
            checkpoint_config = weights.read("__config_json__.npy")
        self.assertIn(b'"visual_encoder":"deterministic_icon_v1"', checkpoint_config)
        self.assertFalse(any(name.startswith("visual_encoder.") for name in weight_names))
        self.assertIn("missing_visual.npy", weight_names)


if __name__ == "__main__":
    unittest.main()
