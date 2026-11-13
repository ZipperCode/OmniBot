from __future__ import annotations

import json
import math
from pathlib import Path
import sys
import sysconfig
from typing import Any

PLANNER_SUMMARY_DESCRIPTION = (
    "persistent working memory plus the brief plan for this action. Preserve exact facts "
    "read from the current screen that later steps need, including every pending record "
    "and field value. Track what remains. Never replace missing facts with guesses."
)

CANONICAL_ACTION_SCHEMA_FILENAME = "oob_canonical_actions.v1.json"
OMNIFLOW_RUN_LOG_SCHEMA_FILENAME = "omniflow_run_log.v1.json"
CHECKER_RULE_SCHEMA_FILENAME = "omniflow_checker_rule.v2.json"
VLM_ACTION_TOOL_NAMES = (
    "click",
    "input_text",
    "long_press",
    "swipe",
    "open_app",
    "press_key",
    "wait",
    "collect_screen",
    "get_state",
    "finished",
)
_VLM_ACTION_ARGUMENT_NAMES = {
    "click": ("target_description", "x", "y", "node_id", "node_resource_id"),
    "input_text": ("target_description", "text", "x", "y", "node_id", "node_resource_id"),
    "long_press": ("target_description", "x", "y", "duration_ms", "node_id", "node_resource_id"),
    "swipe": ("direction", "x1", "y1", "x2", "y2"),
    "open_app": ("package_name",),
    "press_key": ("key",),
    "wait": ("duration_ms", "condition", "target_description"),
    "collect_screen": ("direction", "x1", "y1", "x2", "y2", "max_pages", "max_items", "ocr"),
    "get_state": ("reason", "ocr", "x1", "y1", "x2", "y2"),
    "finished": ("content", "observation_id", "expected_text"),
}


def canonical_action_schema_path() -> Path:
    return _schema_path(CANONICAL_ACTION_SCHEMA_FILENAME)


def omniflow_run_log_schema_path() -> Path:
    return _schema_path(OMNIFLOW_RUN_LOG_SCHEMA_FILENAME)


def checker_rule_schema_path() -> Path:
    return _schema_path(CHECKER_RULE_SCHEMA_FILENAME)


def _schema_path(filename: str) -> Path:
    source_path = Path(__file__).resolve()
    data_root = Path(sysconfig.get_path("data") or sys.prefix)
    candidates = tuple(
        parent / "schemas" / "oob" / filename for parent in source_path.parents
    ) + (data_root / "schemas" / "oob" / filename,)
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(filename)


_FALLBACK_COLLECT_SCREEN_SPEC = {
    "name": "collect_screen",
    "kind": "action",
    "replayable": False,
    "recordable": False,
    "editor_visible": False,
    "ui_label": {"zh_cn": "多屏采集", "en_us": "Collect screen"},
    "description": {"zh_cn": "在指定区域内按方向连续滚动并采集页面文本条目。", "en_us": "Continuously scroll and collect text items in the specified region."},
    "args": [
        {"name": "direction", "type": "string", "required": True, "enum_values": ["down", "up"]},
        {"name": "x1", "type": "number", "required": True, "minimum": 0, "maximum": 1000},
        {"name": "y1", "type": "number", "required": True, "minimum": 0, "maximum": 1000},
        {"name": "x2", "type": "number", "required": True, "minimum": 0, "maximum": 1000},
        {"name": "y2", "type": "number", "required": True, "minimum": 0, "maximum": 1000},
        {"name": "max_pages", "type": "integer", "minimum": 1, "maximum": 20},
        {"name": "max_items", "type": "integer", "minimum": 1, "maximum": 200},
        {"name": "ocr", "type": "boolean", "persisted": False},
    ],
}


def load_canonical_action_schema() -> dict[str, Any]:
    payload = _load_schema(canonical_action_schema_path())
    tools = payload.get("tools")
    if isinstance(tools, list):
        if not any(isinstance(t, dict) and t.get("name") == "collect_screen" for t in tools):
            payload = dict(payload)
            payload["tools"] = list(tools) + [_FALLBACK_COLLECT_SCREEN_SPEC]
    return payload

def load_omniflow_run_log_schema() -> dict[str, Any]:
    return _load_schema(omniflow_run_log_schema_path())


def load_checker_rule_schema() -> dict[str, Any]:
    return _load_schema(checker_rule_schema_path())


def _load_schema(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("canonical_action_schema_must_be_object")
    return payload


def openai_action_tools(
    *,
    include_summary: bool = False,
    include_hidden_names: frozenset[str] = frozenset(),
) -> list[dict[str, Any]]:
    tools: list[dict[str, Any]] = []
    for action in load_canonical_action_schema().get("tools") or ():
        if not isinstance(action, dict):
            continue
        action_name = str(action.get("name") or "")
        if (
            action.get("model_visible") is False
            and action_name not in include_hidden_names
        ):
            continue
        properties: dict[str, Any] = {}
        required: list[str] = []
        if include_summary:
            properties["summary"] = {
                "type": "string",
                "description": PLANNER_SUMMARY_DESCRIPTION,
            }
            required.append("summary")
        for argument in action.get("args") or ():
            if not isinstance(argument, dict) or not argument.get("name"):
                continue
            name = str(argument["name"])
            argument_type = str(argument.get("type") or "string")
            schema = {
                "type": "array" if argument_type == "string_array" else argument_type
            }
            if argument.get("enum_values"):
                schema["enum"] = list(argument["enum_values"])
            if argument.get("minimum") is not None:
                schema["minimum"] = argument["minimum"]
            if argument.get("maximum") is not None:
                schema["maximum"] = argument["maximum"]
            if schema["type"] == "array":
                schema["items"] = {"type": "string"}
            if schema["type"] == "object":
                schema["additionalProperties"] = bool(
                    argument.get("additional_properties")
                )
            description = argument.get("description") or {}
            if isinstance(description, dict) and description.get("en_us"):
                schema["description"] = description["en_us"]
            properties[name] = schema
            if argument.get("required"):
                required.append(name)
        description = action.get("description") or {}
        tools.append(
            {
                "type": "function",
                "function": {
                    "name": action_name,
                    "description": description.get("en_us", "")
                    if isinstance(description, dict)
                    else str(description),
                    "strict": True,
                    "parameters": {
                        "type": "object",
                        "properties": properties,
                        "required": required,
                        "additionalProperties": False,
                    },
                },
            }
        )
    return tools


def vlm_action_tools(*, include_summary: bool = False) -> list[dict[str, Any]]:
    tools: list[dict[str, Any]] = []
    for tool in openai_action_tools(include_summary=include_summary):
        function = tool.get("function", {})
        name = str(function.get("name") or "")
        allowed_arguments = _VLM_ACTION_ARGUMENT_NAMES.get(name)
        if allowed_arguments is None:
            continue
        parameters = function.get("parameters", {})
        properties = parameters.get("properties", {})
        allowed = ({"summary"} if include_summary else set()).union(
            allowed_arguments
        )
        parameters["properties"] = {
            key: dict(value) for key, value in properties.items() if key in allowed
        }
        if name == "wait" and "duration_ms" in parameters["properties"]:
            parameters["properties"]["duration_ms"]["maximum"] = 10000
        parameters["required"] = [
            key for key in parameters.get("required", ()) if key in allowed
        ]
        tools.append(tool)
    return tools


def canonicalize_action(
    value: Any,
    *,
    replayable_only: bool = False,
    persisted_only: bool = True,
    allow_non_action: bool = False,
) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != {"tool", "args"}:
        raise ValueError("canonical_action_contract_invalid")
    tool = str(value.get("tool") or "").strip().lower()
    specs = {
        str(item.get("name") or "").strip().lower(): item
        for item in load_canonical_action_schema().get("tools") or ()
        if isinstance(item, dict) and str(item.get("name") or "").strip()
    }
    tool_spec = specs.get(tool)
    if tool_spec is None:
        raise ValueError(f"canonical_action_tool_unsupported:{tool}")
    kind = str(tool_spec.get("kind") or "")
    if kind != "action" and not allow_non_action:
        raise ValueError(f"canonical_action_kind_invalid:{kind}:{tool}")
    if replayable_only and tool_spec.get("replayable") is False:
        raise ValueError(f"canonical_action_tool_not_replayable:{tool}")
    raw_args = value.get("args")
    if not isinstance(raw_args, dict):
        raise ValueError("canonical_action_args_must_be_object")
    all_arg_specs = [
        item for item in tool_spec.get("args") or () if isinstance(item, dict)
    ]
    known_arg_names = {str(item.get("name") or "") for item in all_arg_specs}
    unknown = sorted(set(raw_args) - known_arg_names)
    if unknown:
        if tool == "collect_screen":
            raise ValueError("invalid_collect_screen_arguments")
        raise ValueError(f"canonical_action_args_unknown:{tool}:{','.join(unknown)}")
    if tool == "wait":
        raw_condition = raw_args.get("condition")
        raw_target = raw_args.get("target_description")
        raw_duration = raw_args.get("duration_ms")
        has_condition = "condition" in raw_args and raw_condition is not None
        has_target = "target_description" in raw_args and raw_target is not None
        if has_condition:
            if not isinstance(raw_condition, str) or raw_condition not in {"text_present", "text_absent", "screen_changed"}:
                raise ValueError("invalid_wait_condition")
            if raw_condition in {"text_present", "text_absent"}:
                if not has_target or not isinstance(raw_target, str) or not raw_target.strip():
                    raise ValueError("invalid_wait_condition")
            else:
                if has_target:
                    raise ValueError("invalid_wait_condition")
            if isinstance(raw_duration, bool) or not isinstance(raw_duration, (int, float)) or not (1 <= raw_duration <= 10000) or int(raw_duration) != raw_duration:
                raise ValueError("invalid_wait_duration")
        else:
            if has_target:
                raise ValueError("invalid_wait_condition")
            if raw_duration is not None:
                if isinstance(raw_duration, bool) or not isinstance(raw_duration, (int, float)) or raw_duration < 0 or int(raw_duration) != raw_duration:
                    raise ValueError("invalid_wait_duration")
                if isinstance(raw_duration, (int, float)) and raw_duration > 60000:
                    raw_args = dict(raw_args)
                    raw_args["duration_ms"] = 60000
    if tool == "collect_screen":
        raw_direction = raw_args.get("direction")
        raw_x1 = raw_args.get("x1")
        raw_y1 = raw_args.get("y1")
        raw_x2 = raw_args.get("x2")
        raw_y2 = raw_args.get("y2")
        raw_max_pages = raw_args.get("max_pages")
        raw_max_items = raw_args.get("max_items")
        raw_ocr = raw_args.get("ocr")

        if not isinstance(raw_direction, str) or raw_direction not in {"down", "up"}:
            raise ValueError("invalid_collect_screen_arguments")

        for coord in (raw_x1, raw_y1, raw_x2, raw_y2):
            if isinstance(coord, bool) or not isinstance(coord, (int, float)) or not math.isfinite(coord):
                raise ValueError("invalid_collect_screen_arguments")
        if not (0 <= raw_x1 < raw_x2 <= 1000) or not (0 <= raw_y1 < raw_y2 <= 1000):
            raise ValueError("invalid_collect_screen_arguments")

        if raw_max_pages is not None:
            if (
                isinstance(raw_max_pages, bool)
                or not isinstance(raw_max_pages, (int, float))
                or not math.isfinite(raw_max_pages)
                or int(raw_max_pages) != raw_max_pages
                or not (1 <= raw_max_pages <= 20)
            ):
                raise ValueError("invalid_collect_screen_arguments")

        if raw_max_items is not None:
            if (
                isinstance(raw_max_items, bool)
                or not isinstance(raw_max_items, (int, float))
                or not math.isfinite(raw_max_items)
                or int(raw_max_items) != raw_max_items
                or not (1 <= raw_max_items <= 200)
            ):
                raise ValueError("invalid_collect_screen_arguments")

        if raw_ocr is not None and not isinstance(raw_ocr, bool):
            raise ValueError("invalid_collect_screen_arguments")
    arg_specs = [
        item
        for item in all_arg_specs
        if not persisted_only or item.get("persisted") is not False
    ]
    args: dict[str, Any] = {}
    for spec in arg_specs:
        name = str(spec.get("name") or "")
        if name not in raw_args or raw_args[name] is None:
            continue
        args[name] = _canonical_arg(raw_args[name], spec)
    missing = [
        str(spec.get("name") or "")
        for spec in arg_specs
        if spec.get("required") and str(spec.get("name") or "") not in args
    ]
    if missing:
        if tool == "collect_screen":
            raise ValueError("invalid_collect_screen_arguments")
        raise ValueError(
            f"canonical_action_required_args_missing:{tool}:{','.join(missing)}"
        )
    return {"tool": tool, "args": args}


def _canonical_arg(value: Any, spec: dict[str, Any]) -> Any:
    name = str(spec.get("name") or "")
    kind = str(spec.get("type") or "string")
    if kind == "string":
        if not isinstance(value, str):
            raise ValueError(f"canonical_action_arg_type_invalid:{name}")
        converted: Any = value
    elif kind in {"number", "integer"}:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"canonical_action_arg_type_invalid:{name}")
        number = float(value)
        if not math.isfinite(number) or (kind == "integer" and not number.is_integer()):
            raise ValueError(f"canonical_action_arg_type_invalid:{name}")
        converted = int(number) if number.is_integer() else number
    elif kind == "boolean":
        if not isinstance(value, bool):
            raise ValueError(f"canonical_action_arg_type_invalid:{name}")
        converted = value
    elif kind == "object":
        if not isinstance(value, dict):
            raise ValueError(f"canonical_action_arg_type_invalid:{name}")
        converted = json.loads(json.dumps(value, ensure_ascii=False))
    elif kind == "string_array":
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            raise ValueError(f"canonical_action_arg_type_invalid:{name}")
        converted = list(value)
    else:
        raise ValueError(f"canonical_action_arg_type_invalid:{name}")
    if spec.get("enum_values") and converted not in spec["enum_values"]:
        raise ValueError(f"canonical_action_arg_enum_invalid:{name}")
    if isinstance(converted, (int, float)) and not isinstance(converted, bool):
        if spec.get("minimum") is not None and converted < spec["minimum"]:
            raise ValueError(f"canonical_action_arg_range_invalid:{name}")
        if spec.get("maximum") is not None and converted > spec["maximum"]:
            raise ValueError(f"canonical_action_arg_range_invalid:{name}")
    return converted


__all__ = [
    "CANONICAL_ACTION_SCHEMA_FILENAME",
    "OMNIFLOW_RUN_LOG_SCHEMA_FILENAME",
    "CHECKER_RULE_SCHEMA_FILENAME",
    "VLM_ACTION_TOOL_NAMES",
    "canonical_action_schema_path",
    "omniflow_run_log_schema_path",
    "canonicalize_action",
    "checker_rule_schema_path",
    "load_canonical_action_schema",
    "load_omniflow_run_log_schema",
    "load_checker_rule_schema",
    "openai_action_tools",
    "vlm_action_tools",
]
