from __future__ import annotations

import base64
from collections.abc import Callable
from copy import deepcopy
import json
import math
from pathlib import Path
import re
from typing import Any

from omniflow.core.config import DEFAULT_PLANNER_SYSTEM_PROMPT
from omniflow.core.model import Function, Observation, ToolCall
from omniflow.core.schemas import canonicalize_action, vlm_action_tools
from omniflow.functions.assets import validate_arguments
from omniflow.vlm.context import analyze_page_context
from omniflow.vlm.model_config import resolve_openai_compatible_config
from omniflow.vlm.usage import LLMUsageTracker
from omniflow.vlm_coordinates import screen_pixel_args_to_canonical

_SCHEDULER_CONTRACT = (
    "\n\n【调度契约】宿主每轮仅准入首个工具调用进入真实执行环境。若收到 executed=false 的工具结果，"
    "表示该调用未被执行；必须依据当前最新屏幕观测重新决策，严禁假设已生效，严禁盲目复用旧坐标。"
    "\n\n【环境与启动说明】任务运行于独立后台显示屏，该屏幕无桌面Launcher，且不与前台主屏同步。"
    "若当前屏幕为空白、黑屏、无前台应用或非目标应用界面，必须使用 open_app 工具从已安装应用列表中选择合适的应用启动；"
    "严禁反复调用 get_state、home 等只读或桌面操作等待界面自动出现。"
)
SYSTEM_PROMPT = f"{DEFAULT_PLANNER_SYSTEM_PROMPT}{_SCHEDULER_CONTRACT}"

class ModelToolCallError(ValueError):
    def __init__(
        self,
        message: str,
        *,
        tool_name: str = "",
        arguments: Any = None,
    ):
        self.code = str(message)
        self.tool_name = str(tool_name).strip()
        self.arguments = arguments
        super().__init__(message)


def build_model_turn_request(
    *,
    goal: str,
    model: str,
    state: dict[str, Any],
    turn_index: int,
    installed_apps: dict[str, str] | None = None,
    functions: list[Function] | tuple[Function, ...] = (),
    committed_tool_messages: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    text = _turn_text(
        goal=goal,
        state=state,
    )
    content: list[dict[str, Any]] = []
    current_image = _state_image_data_uri(state)
    if current_image:
        content.append(
            {
                "type": "image_url",
                "image_url": {"url": current_image, "detail": "high"},
            }
        )
    raw_extra = state.get("extra") if isinstance(state.get("extra"), dict) else {}
    visual_regions = raw_extra.get("visual_regions")
    if isinstance(visual_regions, list):
        for region in visual_regions:
            if isinstance(region, dict):
                crop_img = region.get("image_base64")
                if crop_img:
                    crop_uri = (
                        crop_img
                        if str(crop_img).startswith("data:image/")
                        else f"data:image/jpeg;base64,{crop_img}"
                    )
                    content.append(
                        {
                            "type": "image_url",
                            "image_url": {"url": crop_uri, "detail": "high"},
                        }
                    )
    content.append({"type": "text", "text": text})
    tools = function_tools(functions)
    tools.extend(vlm_action_tools())
    tools = constrain_open_app_tool(
        tools,
        installed_apps or {},
    )
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": SYSTEM_PROMPT},
    ]
    if committed_tool_messages:
        for message in committed_tool_messages:
            if isinstance(message, dict):
                messages.append(dict(message))
    messages.append({"role": "user", "content": content})
    return {
        "model": str(model),
        "messages": messages,
        "stream": True,
        "stream_options": {"include_usage": True},
        "tools": tools,
        "tool_choice": "required",
        "parallel_tool_calls": False,
    }

def parse_model_turn_response(
    value: Any,
    *,
    requested_model: str,
    turn_index: int,
    display: dict[str, Any] | None = None,
    functions: list[Function] | tuple[Function, ...] = (),
) -> tuple[ToolCall, dict[str, Any]]:
    if not isinstance(value, dict):
        raise ValueError("model_turn_response_invalid")
    if str(value.get("requested_model") or "").strip() != requested_model:
        raise ValueError("model_turn_requested_model_mismatch")
    tool_calls = value.get("tool_calls")
    if not isinstance(tool_calls, list):
        raise ModelToolCallError("model_turn_tool_calls_invalid")
    if len(tool_calls) == 0:
        raise ModelToolCallError("model_turn_tool_calls_empty")

    seen_call_ids: set[str] = set()
    normalized_calls: list[dict[str, Any]] = []
    for call in tool_calls:
        if not isinstance(call, dict):
            raise ModelToolCallError("model_turn_tool_call_invalid")
        raw_id = call.get("id")
        if not isinstance(raw_id, str):
            raise ModelToolCallError("model_turn_tool_call_id_missing")
        call_id = raw_id.strip()
        if not call_id:
            raise ModelToolCallError("model_turn_tool_call_id_missing")
        if call_id in seen_call_ids:
            raise ModelToolCallError(f"model_turn_tool_call_id_duplicate:{call_id}")
        seen_call_ids.add(call_id)
        raw_type = call.get("type")
        if raw_type is not None and (
            not isinstance(raw_type, str) or raw_type.strip() != "function"
        ):
            raise ModelToolCallError("model_turn_tool_call_type_invalid")
        function = call.get("function")
        if not isinstance(function, dict):
            raise ModelToolCallError("model_turn_tool_call_function_invalid")
        raw_name = function.get("name")
        if not isinstance(raw_name, str) or not raw_name.strip():
            raise ModelToolCallError("model_turn_tool_call_function_invalid")
        call_name = raw_name.strip()
        raw_arguments = function.get("arguments")
        if not isinstance(raw_arguments, str):
            raise ModelToolCallError(
                "model_turn_tool_arguments_invalid",
                tool_name=call_name,
                arguments=raw_arguments,
            )
        normalized_calls.append(
            {
                "id": call_id,
                "type": "function",
                "function": {
                    "name": call_name,
                    "arguments": raw_arguments,
                },
            }
        )

    first_tool_call = normalized_calls[0]
    first_function = first_tool_call["function"]
    tool = first_function["name"]
    if len(tool_calls) > 1 and tool in {"finished", "abort"}:
        raise ModelToolCallError("model_turn_terminal_tool_must_be_alone")

    model_visible_tools = {
        str(item.get("function", {}).get("name") or "")
        for item in vlm_action_tools()
        if isinstance(item, dict) and isinstance(item.get("function"), dict)
    }
    function_catalog = {function.id: function for function in functions}
    model_visible_tools.update(function_catalog)
    if tool not in model_visible_tools:
        raise ModelToolCallError(f"model_turn_tool_not_visible:{tool}")
    raw_arguments = first_function["arguments"]
    try:
        arguments = json.loads(raw_arguments)
    except json.JSONDecodeError as error:
        raise ModelToolCallError(
            "model_turn_tool_arguments_must_be_json",
            tool_name=tool,
            arguments=raw_arguments,
        ) from error
    if not isinstance(arguments, dict):
        raise ModelToolCallError(
            "model_turn_tool_arguments_must_be_object",
            tool_name=tool,
            arguments=arguments,
        )
    rejected_arguments = dict(arguments)
    resolved_model = str(value.get("resolved_model") or requested_model).strip()
    adapter_metadata = None
    try:
        if tool in function_catalog:
            validate_arguments(function_catalog[tool].input_schema, arguments)
        else:
            try:
                arguments, adapter_metadata = adapt_tool_arguments(
                    tool=tool,
                    arguments=arguments,
                    requested_model=requested_model,
                    resolved_model=resolved_model,
                    display=display,
                )
                canonical = canonicalize_action(
                    {"tool": tool, "args": arguments},
                    persisted_only=False,
                    allow_non_action=True,
                )
                arguments = dict(canonical["args"])
            except ValueError:
                if tool == "collect_screen":
                    # 保留原 call 身份让 engine 统一进行 invalid_collect_screen_arguments 校验与零动作结果反馈
                    arguments = rejected_arguments
                else:
                    raise
    except ValueError as error:
        raise ModelToolCallError(
            str(error),
            tool_name=tool,
            arguments=rejected_arguments,
        ) from error
    metadata: dict[str, Any] = {
        "provider_tool_calls": normalized_calls,
    }
    if adapter_metadata is not None:
        metadata["model_adapter"] = adapter_metadata
    thinking = str(value.get("reasoning") or "").strip()
    if thinking:
        metadata["thinking"] = thinking
    usage = value.get("usage")
    if isinstance(usage, dict):
        metadata["token_usage"] = {
            **usage,
            "model": requested_model,
            "resolved_model": resolved_model,
            "turn_index": int(turn_index),
        }
    return ToolCall(tool, arguments), metadata


def function_tools(
    functions: list[Function] | tuple[Function, ...],
) -> list[dict[str, Any]]:
    tools: list[dict[str, Any]] = []
    for function in functions:
        parameters = deepcopy(function.input_schema)
        properties = parameters.setdefault("properties", {})
        parameters["properties"] = properties
        parameters["required"] = list(parameters.get("required") or ())
        tools.append(
            {
                "type": "function",
                "function": {
                    "name": function.id,
                    "description": function.description,
                    "strict": True,
                    "parameters": parameters,
                },
            }
        )
    return tools


def _turn_text(
    *,
    goal: str,
    state: dict[str, Any],
) -> str:
    display = state.get("display") if isinstance(state.get("display"), dict) else {}
    raw_extra = state.get("extra")
    obs_id = ""
    if isinstance(raw_extra, dict):
        obs_id = str(raw_extra.get("observation_id") or "").strip()
    screen_desc = (
        "Current screen: "
        f"package={state.get('package_name') or '-'}, "
        f"activity={state.get('activity_name') or '-'}, "
        f"display={display.get('width') or '?'}x{display.get('height') or '?'}"
    )
    if obs_id:
        screen_desc += f", observation_id={obs_id}"
    lines = [
        f"Task: {goal}",
        screen_desc,
    ]
    if not state.get("package_name") or str(state.get("package_name")).strip() in {"-", "None"}:
        lines.append(
            "Screen status: 当前后台虚拟屏暂无前台应用界面且无桌面Launcher，不与前台主屏同步。"
            "请根据任务目标，使用 open_app 工具从候选已安装应用列表中启动相应应用，切勿连续重复调用 get_state 或 home 等待屏幕变化。"
        )
    page_context = analyze_page_context(state, goal=goal)
    if page_context.useful:
        lines.append(page_context.evidence)
    lines.append(
        "Coordinate space: all action coordinates (x, y, x1, y1, x2, y2) must use full-screen normalized 0..1000 coordinates."
    )
    raw_extra = state.get("extra")
    if isinstance(raw_extra, dict):
        previous_error = str(raw_extra.get("previous_action_error") or "").strip()
        if previous_error:
            lines.append(f"Previous action error: {previous_error}")
        else:
            previous_action = raw_extra.get("previous_action")
            if isinstance(previous_action, dict) and previous_action.get("tool"):
                tool = str(previous_action["tool"]).strip()
                args = (
                    previous_action.get("args")
                    if isinstance(previous_action.get("args"), dict)
                    else {}
                )
                result_status = str(
                    raw_extra.get("previous_action_result") or "succeeded"
                ).strip()
                if tool == "get_state":
                    parts = []
                    if args.get("reason"):
                        parts.append(f"reason={args['reason']!r}")
                    if args.get("ocr") is not None:
                        parts.append(f"ocr={args['ocr']}")
                    if all(
                        k in args and args[k] is not None
                        for k in ("x1", "y1", "x2", "y2")
                    ):
                        parts.append(
                            f"region=({args['x1']},{args['y1']},{args['x2']},{args['y2']})"
                        )
                    args_str = ", ".join(parts)
                    if raw_extra.get("observation_stalled"):
                        lines.append(
                            f"Previous action: get_state({args_str}) succeeded，但当前屏幕无新变化或感知证据。请勿重复调用相同只读操作，可尝试有界 wait 等待异步加载或使用 open_app 启动应用/执行交互操作。"
                        )
                    else:
                        lines.append(
                            f"Previous action: get_state({args_str}) {result_status}. "
                            "Latest screen state, visual region and OCR evidence are refreshed. Continue with the next step."
                        )
                else:
                    lines.append(f"Previous action: {tool} {result_status}.")
        function_execution = raw_extra.get("function_execution")
        if isinstance(function_execution, dict):
            function_id = str(function_execution.get("function_id") or "").strip()
            status = str(function_execution.get("replay_status") or "").strip()
            if function_id and status:
                result = "succeeded" if status == "actions_succeeded" else status
                lines.append(f"Previous Function result: {function_id} {result}")
        user_input = str(raw_extra.get("user_input") or "").strip()
        if user_input:
            lines.append(f"User response: {user_input}")
        visual_regions = raw_extra.get("visual_regions")
        if isinstance(visual_regions, list) and visual_regions:
            for idx, region in enumerate(visual_regions):
                if isinstance(region, dict):
                    rbounds = region.get("bounds")
                    lines.append(
                        f"High-resolution crop region {idx + 1}: bounds={rbounds} in full-screen 0..1000 normalized coordinates. "
                        "NOTE: All action coordinates (x, y, x1, y1, x2, y2) MUST use full-screen normalized 0..1000 coordinates, NOT local crop coordinates."
                    )
        app_experiences = raw_extra.get("app_experiences")
        if isinstance(app_experiences, list) and app_experiences:
            for exp in app_experiences:
                if not isinstance(exp, dict):
                    continue
                rule = exp.get("rule") if isinstance(exp.get("rule"), dict) else exp
                raw_target_id = str(rule.get("target_resource_id") or "").strip()
                if not raw_target_id:
                    continue
                target_id = json.dumps(raw_target_id, ensure_ascii=False).strip('"')
                action = str(rule.get("action") or "").strip()
                action_label = {
                    "click": "点击",
                    "long_press": "长按",
                    "input_text": "输入文本",
                }.get(action, action)
                raw_after_act = str(rule.get("after_activity") or "").strip()
                after_act = json.dumps(raw_after_act, ensure_ascii=False).strip('"') if raw_after_act else ""
                if after_act:
                    lines.append(f"当前页面参考：控件{target_id}可用于{action_label}，曾到达{after_act}；以当前状态和目标为准")
                else:
                    lines.append(f"当前页面参考：控件{target_id}可用于{action_label}；以当前状态和目标为准")
    return "\n".join(lines)


def constrain_open_app_tool(
    tools: list[dict[str, Any]],
    installed_apps: dict[str, str],
) -> list[dict[str, Any]]:
    candidates = _installed_app_candidates(installed_apps)
    packages = list(dict.fromkeys(package for _label, package in candidates))
    constrained: list[dict[str, Any]] = []
    for tool in tools:
        function = tool.get("function") if isinstance(tool, dict) else None
        if not isinstance(function, dict):
            constrained.append(tool)
            continue
        parameters = function.get("parameters")
        properties = (
            parameters.get("properties") if isinstance(parameters, dict) else None
        )
        package_schema = (
            properties.get("package_name") if isinstance(properties, dict) else None
        )
        if not isinstance(package_schema, dict):
            if function.get("name") == "open_app":
                raise ValueError("open_app_package_schema_missing")
            constrained.append(tool)
            continue
        if packages:
            package_schema["enum"] = packages
            choices = (
                "Choose one installed app (app label -> package): "
                + "; ".join(f"{label} -> {package}" for label, package in candidates)
            )
            description = str(package_schema.get("description") or "").strip()
            package_schema["description"] = " ".join(
                part for part in (description, choices) if part
            )
        else:
            package_schema.pop("enum", None)
        constrained.append(tool)
    return constrained


def _installed_app_candidates(
    installed_apps: dict[str, str],
) -> list[tuple[str, str]]:
    candidates = {
        (str(label).strip(), str(package).strip())
        for label, package in installed_apps.items()
        if str(label).strip() and str(package).strip()
    }
    return sorted(candidates, key=lambda item: (item[0].casefold(), item[1]))


def _state_image_data_uri(state: dict[str, Any]) -> str:
    image = str(state.get("image_base64") or "").strip()
    if image:
        return (
            image
            if image.startswith("data:image/")
            else f"data:image/jpeg;base64,{image}"
        )
    return _image_data_uri(str(state.get("screenshot_path") or ""))


def _image_data_uri(path: str) -> str:
    candidate = Path(str(path or "").strip())
    if not candidate.is_file():
        return ""
    try:
        payload = candidate.read_bytes()
    except OSError:
        return ""
    mime_type = _image_mime_type(payload)
    if not mime_type:
        return ""
    encoded = base64.b64encode(payload).decode("ascii")
    return f"data:{mime_type};base64,{encoded}" if encoded else ""


def _image_mime_type(payload: bytes) -> str:
    if payload.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if payload.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if len(payload) >= 12 and payload[:4] == b"RIFF" and payload[8:12] == b"WEBP":
        return "image/webp"
    return ""



ModelTransport = Callable[[dict[str, Any]], Any]


class VLMPlanner:
    """Choose one native GUI or recalled Function tool per model turn."""

    def __init__(
        self,
        *,
        model: str,
        provider: str = "openai",
        api_key: str | None = None,
        base_url: str | None = None,
        timeout: float = 60.0,
        client: Any | None = None,
        transport: ModelTransport | None = None,
        metadata_sink: Callable[[dict[str, Any]], None] | None = None,
    ):
        if provider not in {"openai", "openai_compatible"}:
            raise ValueError("VLMPlanner supports OpenAI-compatible providers only")
        if client is not None and transport is not None:
            raise ValueError("planner_model_transport_ambiguous")
        self.model = str(model).strip()
        self.timeout = float(timeout)
        self._client = client
        self._transport = transport
        self._metadata_sink = metadata_sink
        self._api_key, self._base_url = resolve_openai_compatible_config(
            api_key=api_key,
            base_url=base_url,
        )
        self._turn_index = 0
        self._metadata: dict[str, Any] = {}
        self._usage = LLMUsageTracker(component="planner", model=self.model)
        self._pending_provider_tool_calls: list[dict[str, Any]] | None = None
        self._pending_selected_tool_call: ToolCall | None = None
        self._committed_tool_messages: list[dict[str, Any]] = []

    def commit_pending_result(self, observation: Observation) -> None:
        """纯提交上一轮未决工具调用的执行结果到历史消息，不请求模型。若无 pending 则为 no-op。"""
        if (
            self._pending_provider_tool_calls is None
            or self._pending_selected_tool_call is None
        ):
            return

        pending_calls = self._pending_provider_tool_calls
        selected_call = self._pending_selected_tool_call
        first_provider_call = pending_calls[0]
        call_tool_name = selected_call.name
        call_args = selected_call.arguments
        extra = (
            observation.extra if isinstance(observation.extra, dict) else {}
        )
        prev_act = extra.get("previous_action")
        prev_res = extra.get("previous_action_result")
        prev_err = extra.get("previous_action_error")

        first_res_dict: dict[str, Any] | None = None

        if (
            isinstance(prev_act, dict)
            and prev_act.get("tool") == call_tool_name
            and prev_act.get("args") == call_args
            and prev_res in {"succeeded", "failed"}
        ):
            success = (prev_res == "succeeded" and not prev_err)
            first_res_dict = {
                "success": success,
                "tool": call_tool_name,
                "package_name": observation.package_name,
                "activity_name": observation.activity_name,
            }
            if call_tool_name == "collect_screen":
                collection_data = extra.get("collection")
                if isinstance(collection_data, dict):
                    first_res_dict["collection"] = collection_data
            if call_tool_name == "get_state":
                ocr_data = extra.get("ocr")
                if isinstance(ocr_data, dict):
                    first_res_dict["ocr"] = {
                        "status": ocr_data.get("status", "unavailable"),
                        "count": (
                            len(ocr_data.get("items") or ())
                            if isinstance(ocr_data.get("items"), list)
                            else 0
                        ),
                    }
                else:
                    first_res_dict["ocr"] = {"status": "not_requested"}
                regions = extra.get("visual_regions")
                if isinstance(regions, list) and regions:
                    first_res_dict["visual_regions"] = [
                        {"bounds": r.get("bounds")}
                        for r in regions
                        if isinstance(r, dict) and "bounds" in r
                    ]
                if extra.get("observation_stalled"):
                    first_res_dict["progress"] = False
                    first_res_dict["screen_changed"] = False
            if call_tool_name == "wait":
                wait_diag = extra.get("wait_diagnostics")
                if isinstance(wait_diag, dict):
                    first_res_dict.update(wait_diag)
                if extra.get("observation_stalled"):
                    first_res_dict["progress"] = False
                    first_res_dict["screen_changed"] = False
                    first_res_dict["suggestion"] = "等待无新变化或条件未产生新进展，可尝试更换操作路径或执行交互操作"
            if prev_err:
                first_res_dict["error"] = prev_err

        if first_res_dict is None:
            self._pending_provider_tool_calls = None
            self._pending_selected_tool_call = None
            raise RuntimeError(
                f"action_execution_unconfirmed:{call_tool_name}"
            )

        # 确证成功，组装标准 assistant 消息（含本轮全部 tool_calls）以及每个 id 对应的 tool 结果消息
        assistant_message: dict[str, Any] = {
            "role": "assistant",
            "content": None,
            "tool_calls": [dict(c) for c in pending_calls],
        }
        self._committed_tool_messages.append(assistant_message)

        first_call_id = str(first_provider_call.get("id") or "").strip()
        self._committed_tool_messages.append(
            {
                "role": "tool",
                "tool_call_id": first_call_id,
                "content": json.dumps(first_res_dict, ensure_ascii=False),
            }
        )

        for tail_call in pending_calls[1:]:
            tail_id = str(tail_call.get("id") or "").strip()
            tail_fn = (
                tail_call.get("function")
                if isinstance(tail_call.get("function"), dict)
                else {}
            )
            tail_tool_name = str(tail_fn.get("name") or "").strip()
            tail_res = {
                "success": False,
                "executed": False,
                "error": "not_executed_observation_refresh_required",
                "tool": tail_tool_name,
            }
            self._committed_tool_messages.append(
                {
                    "role": "tool",
                    "tool_call_id": tail_id,
                    "content": json.dumps(tail_res, ensure_ascii=False),
                }
            )

        self._pending_provider_tool_calls = None
        self._pending_selected_tool_call = None

    async def one_step_tool_call(
        self,
        goal: str,
        observation: Observation,
        functions: tuple[Function, ...] = (),
        installed_apps: dict[str, str] | None = None,
    ) -> ToolCall:
        state = _planner_state(observation)
        self._turn_index += 1

        # 若存在上一轮模型的工具调用，进行纯提交
        self.commit_pending_result(observation)
        request = build_model_turn_request(
            goal=str(goal),
            model=self.model,
            state=state,
            installed_apps=installed_apps or {},
            functions=functions,
            turn_index=self._turn_index,
            committed_tool_messages=list(self._committed_tool_messages),
        )
        self._usage.start_call()
        try:
            response = self._call_model(
                {
                    "goal": str(goal),
                    "model": self.model,
                    "request": request,
                }
            )
            self._usage.record_response(response)
            tool_call, metadata = parse_model_turn_response(
                _normalize_response(response, requested_model=self.model),
                requested_model=self.model,
                turn_index=self._turn_index,
                functions=functions,
                display=state.get("display"),
            )
        except Exception:
            self._usage.record_failure()
            raise
        # 历史保留真实来源观测与工具配对；旧图片不进入后续请求。
        self._committed_tool_messages.append(
            {
                "role": "user",
                "content": [
                    dict(part)
                    for part in request["messages"][-1]["content"]
                    if part.get("type") == "text"
                ],
            }
        )
        self._metadata = metadata
        self._pending_provider_tool_calls = list(
            metadata.get("provider_tool_calls") or ()
        )
        self._pending_selected_tool_call = tool_call
        if self._metadata_sink is not None:
            self._metadata_sink(dict(metadata))
        return tool_call

    def take_metadata(self) -> dict[str, Any]:
        metadata = dict(self._metadata)
        self._metadata.clear()
        return metadata

    def take_usage(self) -> dict[str, Any]:
        return self._usage.take_usage()

    def _call_model(self, envelope: dict[str, Any]) -> Any:
        if self._transport is not None:
            return self._transport(envelope)
        client = self._client or self._build_client()
        options = dict(envelope["request"])
        options.pop("enable_thinking", None)
        options["stream"] = False
        options.pop("stream_options", None)
        options["timeout"] = self.timeout
        return client.chat.completions.create(**options)

    def _build_client(self):
        try:
            from openai import OpenAI
        except ImportError as exc:
            raise RuntimeError("Install omniflow[llm] to use VLMPlanner") from exc
        options: dict[str, Any] = {
            "api_key": self._api_key or "not-required",
            "max_retries": 0,
        }
        if self._base_url:
            options["base_url"] = self._base_url
        return OpenAI(**options)


def _planner_state(observation: Observation) -> dict[str, Any]:
    state = observation.to_dict()
    state["state_id"] = str(observation.extra.get("state_id") or "").strip()
    for key in ("display", "screenshot_path"):
        if observation.extra.get(key) is not None:
            state[key] = observation.extra[key]
    return state


def _normalize_response(value: Any, *, requested_model: str) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    choices = getattr(value, "choices", None) or ()
    message = getattr(choices[0], "message", None) if choices else None
    calls = getattr(message, "tool_calls", None) or ()
    usage = getattr(value, "usage", None)
    return {
        "requested_model": requested_model,
        "resolved_model": str(getattr(value, "model", None) or requested_model),
        "reasoning": str(getattr(message, "reasoning", None) or ""),
        "tool_calls": [
            {
                "id": (
                    str(getattr(call, "id", "") or "").strip()
                    if isinstance(getattr(call, "id", None), str)
                    else ""
                ),
                "type": str(getattr(call, "type", "function") or "function").strip(),
                "function": {
                    "name": str(getattr(call.function, "name", "") or "")
                    if getattr(call, "function", None) is not None
                    else "",
                    "arguments": str(getattr(call.function, "arguments", "") or "")
                    if getattr(call, "function", None) is not None
                    else "",
                },
            }
            for call in calls
        ],
        "usage": (
            {
                "prompt_tokens": int(getattr(usage, "prompt_tokens", 0) or 0),
                "completion_tokens": int(getattr(usage, "completion_tokens", 0) or 0),
                "total_tokens": int(getattr(usage, "total_tokens", 0) or 0),
            }
            if usage is not None
            else None
        ),
    }


_ADAPTER_NAME = "qwen_vl_coordinate_arrays.v1"
_QWEN_VL_MODEL = re.compile(
    r"(?:^|[^a-z0-9])qwen(?:\d+(?:\.\d+)?)?(?:[-_.]?vl|[-_.]?plus)(?:[^a-z0-9]|$)",
    re.IGNORECASE,
)
_COORDINATE_PAIRS = {
    "click": (("x", "y"),),
    "long_press": (("x", "y"),),
    "input_text": (("x", "y"),),
    "swipe": (("x1", "y1"), ("x2", "y2")),
    "get_state": (("x1", "y1"), ("x2", "y2")),
    "collect_screen": (("x1", "y1"), ("x2", "y2")),
}
_MISSING = object()


def adapt_tool_arguments(
    *,
    tool: str,
    arguments: dict[str, Any],
    requested_model: str,
    resolved_model: str,
    display: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    model = _adapter_model(requested_model, resolved_model)
    coordinate_pairs = _COORDINATE_PAIRS.get(tool)
    if coordinate_pairs is None:
        return dict(arguments), None

    adapted = dict(arguments)
    changes: list[dict[str, Any]] = []
    if model:
        for x_field, y_field in coordinate_pairs:
            _adapt_coordinate_pair(
                adapted,
                x_field,
                y_field,
                changes,
            )
    raw_pixel_changes = _adapt_raw_pixel_coordinates(
        tool=tool,
        arguments=adapted,
        display=display,
    )
    if raw_pixel_changes is not None:
        adapted, conversion = raw_pixel_changes
        changes.append(conversion)
    if not changes:
        return adapted, None
    return adapted, {
        "name": (
            _ADAPTER_NAME
            if all(change.get("source_shape") for change in changes)
            else "planner_coordinate_adapter.v1"
        ),
        "model": model or str(resolved_model or requested_model).strip(),
        "tool": tool,
        "changes": changes,
    }


def _adapt_raw_pixel_coordinates(
    *,
    tool: str,
    arguments: dict[str, Any],
    display: dict[str, Any] | None,
) -> tuple[dict[str, Any], dict[str, Any]] | None:
    pairs = _COORDINATE_PAIRS.get(tool) or ()
    present_pairs = [
        (x_field, y_field)
        for x_field, y_field in pairs
        if x_field in arguments and y_field in arguments
    ]
    if not present_pairs:
        return None
    fields = [field for pair in present_pairs for field in pair]
    values = [arguments[field] for field in fields]
    if not all(_is_number(value) for value in values):
        return None
    if not any(float(value) > 1000 for value in values):
        return None
    width = float((display or {}).get("width") or 0)
    height = float((display or {}).get("height") or 0)
    dimensions = {
        field: width if field.startswith("x") else height for field in fields
    }
    raw_fields = {
        field: arguments[field]
        for field in fields
        if float(arguments[field]) > 1000
    }
    if all(float(arguments[field]) <= dimensions[field] for field in fields):
        raw_fields = {field: arguments[field] for field in fields}
    converted_fields, metadata = screen_pixel_args_to_canonical(
        tool="swipe" if tool in {"swipe", "get_state", "collect_screen"} else tool,
        args=raw_fields,
        display=display,
    )
    converted = dict(arguments)
    converted.update(converted_fields)
    return converted, metadata or {}


def _adapter_model(requested_model: str, resolved_model: str) -> str:
    for candidate in (resolved_model, requested_model):
        normalized = str(candidate or "").strip()
        if normalized and _QWEN_VL_MODEL.search(normalized):
            return normalized
    return ""


def _adapt_coordinate_pair(
    arguments: dict[str, Any],
    x_field: str,
    y_field: str,
    changes: list[dict[str, Any]],
) -> None:
    x_value = arguments.get(x_field, _MISSING)
    y_value = arguments.get(y_field, _MISSING)
    if (
        isinstance(x_value, list)
        and len(x_value) == 2
        and all(_is_number(value) for value in x_value)
        and _matches_inferred_y(y_value, x_value[1])
    ):
        arguments[x_field] = x_value[0]
        arguments[y_field] = x_value[1]
        changes.append(
            {
                "source_field": x_field,
                "source_shape": "number_pair",
                "target_fields": [x_field, y_field],
            }
        )
        return

    for field in (x_field, y_field):
        value = arguments.get(field, _MISSING)
        if isinstance(value, list) and len(value) == 1 and _is_number(value[0]):
            arguments[field] = value[0]
            changes.append(
                {
                    "source_field": field,
                    "source_shape": "singleton_number_array",
                    "target_fields": [field],
                }
            )


def _matches_inferred_y(value: Any, inferred_y: int | float) -> bool:
    if value is _MISSING:
        return True
    if _is_number(value):
        return value == inferred_y
    return (
        isinstance(value, list)
        and (
            (
                len(value) == 1
                and _is_number(value[0])
                and value[0] == inferred_y
            )
            or (
                len(value) == 2
                and all(_is_number(item) for item in value)
                and value[1] == inferred_y
            )
        )
    )


def _is_number(value: Any) -> bool:
    return (
        not isinstance(value, bool)
        and isinstance(value, (int, float))
        and math.isfinite(float(value))
    )


__all__ = [
    "ModelToolCallError",
    "ModelTransport",
    "SYSTEM_PROMPT",
    "VLMPlanner",
    "build_model_turn_request",
    "function_tools",
    "parse_model_turn_response",
]
