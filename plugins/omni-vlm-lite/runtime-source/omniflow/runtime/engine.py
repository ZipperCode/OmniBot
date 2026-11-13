from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import inspect
import json
import math
from pathlib import Path
from typing import Any

from omniflow.catalog import CatalogSnapshot
from omniflow.core.config import Experiment, OmniFlowConfig
from omniflow.core.model import (
    Action,
    Function,
    Host,
    Observation,
    Planner,
    RunResult,
    ToolCall,
)
from omniflow.core.schemas import canonicalize_action
from omniflow.functions.assets import FunctionStore, bind_function
from omniflow.functions.recall import RecallResult, recall_functions
from omniflow.runtime.execution import (
    execute_function,
    execute_robust_action,
    record_execution,
)
import xml.etree.ElementTree as ET
from omniflow.runtime.app_experience import AppExperienceStore
from omniflow.runtime.semantic_grounding import (
    iter_visible_nodes,
    match_expected_text_in_observation,
)
from omniflow.vlm.usage import merge_usage, token_usage_status
from omniflow.runtime.screen_collection import (
    extract_page_lines,
    merge_page_lines,
)

class _CollectSwipeHost:
    def __init__(self, inner: Host):
        self._inner = inner
        self.observe_failed = False

    async def act(self, action: Action) -> Any:
        return await _await(self._inner.act(action))

    async def observe(self, **kwargs: Any) -> Any:
        try:
            return await _await(self._inner.observe(**kwargs))
        except asyncio.CancelledError:
            raise
        except Exception:
            self.observe_failed = True
            raise

    async def installed_apps(self) -> Any:
        return await _await(self._inner.installed_apps())

    def __getattr__(self, name: str) -> Any:
        return getattr(self._inner, name)



def _extract_display_id(observation: Observation) -> Any:
    if isinstance(observation.extra, dict):
        disp_id = observation.extra.get("display_id")
        if disp_id is not None and not isinstance(disp_id, bool) and isinstance(disp_id, int):
            return disp_id
        display = observation.extra.get("display")
        if isinstance(display, dict):
            inner_id = display.get("id") or display.get("display_id")
            if inner_id is not None and not isinstance(inner_id, bool) and isinstance(inner_id, int):
                return inner_id
    return None


class InputRequired(RuntimeError):
    def __init__(self, question: str):
        self.question = str(question).strip()
        super().__init__(self.question or "input_required")


@dataclass
class _FunctionSession:
    selected_id: str | None = None
    bound: Function | None = None
    failed: bool = False
    failed_step_index: int | None = None
    completed: Function | None = None
    resume_events: list[dict[str, Any]] = field(default_factory=list)

    @property
    def failed_id(self) -> str | None:
        return self.selected_id if self.failed else None

    def mark_completed(self) -> None:
        self.completed = self.bound
        self.failed = False
        self.failed_step_index = None

    def mark_failed(self, replay: RunResult) -> None:
        self.failed = True
        self.completed = None
        self.failed_step_index = _optional_step_index(
            replay.detail.get("failed_step_index")
        )


class OmniFlow:
    def __init__(
        self,
        store_path: str | Path,
        *,
        host: Host | None = None,
        planner: Planner | None = None,
        installed_apps: dict[str, str] | None = None,
        config: OmniFlowConfig | None = None,
        catalog: CatalogSnapshot | None = None,
    ):
        self.config = config or OmniFlowConfig()
        self.catalog = catalog
        self.store = FunctionStore(
            store_path,
            seed_functions=(catalog.functions.values() if catalog is not None else ()),
            replace_seeded=catalog is not None,
        )
        self.host = host
        self.planner = planner
        self.installed_apps = (
            {
                str(label).strip(): str(package).strip()
                for label, package in installed_apps.items()
                if str(label).strip() and str(package).strip()
            }
            if installed_apps is not None
            else {}
        )
        self.installed_packages = (
            frozenset(self.installed_apps.values())
            if installed_apps is not None
            else None
        )
        self.plugins = self.config.resolved_plugins()
        exp_path = Path(self.store.path).parent / "app-experiences.json"
        try:
            self.experience_store: AppExperienceStore | None = AppExperienceStore(exp_path)
            self._experience_store_error: str | None = None
        except ValueError as err:
            self.experience_store = None
            self._experience_store_error = str(err)
        except Exception as err:
            self.experience_store = None
            self._experience_store_error = str(err)

    async def _execute(
        self,
        goal: str,
        direct_tool_call: ToolCall | None,
        *,
        experiment: Experiment | str | None = None,
    ) -> RunResult:
        goal = str(goal).strip()
        if self.host is None:
            return RunResult(
                False,
                function_id=direct_tool_call.name if direct_tool_call else None,
                error="host_not_set",
            )
        profile = _experiment(experiment)
        actions_executed = 0
        planner_steps = 0
        model_calls = 0
        fallback_steps = 0
        trace: list[dict[str, Any]] = []
        checker_decisions: list[dict[str, Any]] = []
        last_error = "tool_not_selected"
        llm_usage: dict[str, Any] = {}
        accumulated_collection_items: list[dict[str, Any]] = []
        total_collected_text_length: int = 0
        MAX_RUN_COLLECTION_TEXT_LENGTH = 32768
        total_pages_scanned = 0
        any_collection_truncated = False
        any_collection_partial = False
        last_collection_stop_reason: str | None = None
        any_overlap_uncertain = False
        last_ocr_status = "not_requested"
        had_any_collection = False
        function_session = _FunctionSession()
        observation = await self._observe(screenshot=True)
        planner_functions: tuple[Function, ...] = ()
        planner_function_catalog: dict[str, Function] = {}
        recall_events: list[dict[str, Any]] = []
        recall_source_states: dict[str, Observation | None] = {}
        function_resolution: dict[str, Any] = {
            "candidate_count": 0,
            "candidate_function_ids": [],
            "status": "direct" if direct_tool_call is not None else "planner_tool_space",
        }
        current_episode_used_experience_step_indices: list[int] = []

        def finish(success: bool, **kwargs: Any) -> RunResult:
            kwargs.setdefault("planner_steps", planner_steps)
            if kwargs.get("error") == "no_progress_detected" or (kwargs.get("terminal_detail") or {}).get("done_reason") == "no_progress_detected":
                diag = kwargs.get("planner_diagnostics")
                if not isinstance(diag, dict):
                    diag = planner_diagnostics
                diag["app_experience_failure_step_indexes"] = list(
                    dict.fromkeys(current_episode_used_experience_step_indices)
                )
            if had_any_collection:
                cumulative_coll = {
                    "items": list(accumulated_collection_items),
                    "pages_scanned": total_pages_scanned,
                    "truncated": any_collection_truncated,
                    "partial": any_collection_partial,
                    "stop_reason": last_collection_stop_reason,
                    "overlap_uncertain": any_overlap_uncertain,
                    "ocr_status": last_ocr_status,
                }
                terminal_detail = dict(kwargs.get("terminal_detail") or {})
                terminal_detail["collection"] = cumulative_coll
                kwargs["terminal_detail"] = terminal_detail
            if recall_events:
                function_resolution["recall"] = {
                    "schema_version": "omniflow.function-recall-events.v1",
                    "events": [dict(event) for event in recall_events],
                }
            if function_session.resume_events:
                terminal_detail = dict(kwargs.get("terminal_detail") or {})
                terminal_detail["function_resume"] = {
                    "schema_version": "omniflow.function-resume-audit.v1",
                    "events": [dict(event) for event in function_session.resume_events],
                    "attempt_count": len(function_session.resume_events),
                    "success_count": sum(
                        event.get("status") == "succeeded"
                        for event in function_session.resume_events
                    ),
                }
                kwargs["terminal_detail"] = terminal_detail
            if checker_decisions:
                terminal_detail = dict(kwargs.get("terminal_detail") or {})
                terminal_detail["checker_decisions"] = [
                    dict(decision) for decision in checker_decisions
                ]
                kwargs["terminal_detail"] = terminal_detail
            return self._result(
                success,
                function_resolution=function_resolution,
                **kwargs,
            )

        selected_function: Function | None = None
        resolved_arguments: dict[str, Any] = {}
        selected_tool_call = direct_tool_call
        if selected_tool_call is not None:
            selected_function = self.store.get_function(selected_tool_call.name)
            resolved_arguments = dict(selected_tool_call.arguments)
            function_resolution["selected_function_id"] = selected_tool_call.name
            function_resolution["arguments"] = dict(resolved_arguments)
            if selected_function is None:
                function_resolution["status"] = "unknown_selection"
            elif direct_tool_call is not None and not goal:
                goal = _direct_function_fallback_goal(
                    selected_function,
                    resolved_arguments,
                )

        if selected_function is not None:
            function_session.selected_id = selected_function.id
            try:
                function_session.bound = bind_function(
                    selected_function, resolved_arguments
                )
            except ValueError as error:
                function_resolution["binding_status"] = "failed"
                function_resolution["binding_error"] = str(error)
                function_resolution["replay_status"] = "not_started"
                replay = RunResult(
                    False,
                    function_id=selected_function.id,
                    error=str(error),
                    final_state=observation,
                )
            else:
                function_resolution["binding_status"] = "succeeded"
                replay = await execute_function(
                    function_session.bound,
                    host=self.host,
                    plugins=self.plugins,
                    observation=observation,
                    installed_packages=self.installed_packages,
                    state_loader=(
                        self.catalog.get_state if self.catalog is not None else None
                    ),
                    checker_page_similarity=(
                        self.config.runtime.checker_page_similarity
                    ),
                    checker_action_confidence=(
                        self.config.runtime.checker_action_confidence
                    ),
                )
            actions_executed += replay.actions_executed
            trace.extend(replay.detail.get("trace") or ())
            checker_decisions.extend(replay.detail.get("checker_decisions") or ())
            if replay.success:
                function_resolution["replay_status"] = "succeeded"
                observation = replay.final_state or observation
                last_error = "function_replay_completed_e2e_unverified"
                function_session.mark_completed()
            else:
                function_resolution["replay_status"] = "failed"
                function_resolution["replay_error"] = (
                    replay.error or "function_replay_failed"
                )
            observation = replay.final_state or observation
            if not replay.success:
                last_error = replay.error or "function_replay_failed"
                function_session.mark_failed(replay)
                function_resolution["failed_step_index"] = (
                    function_session.failed_step_index
                )

            if direct_tool_call is not None and replay.success:
                return finish(
                    True,
                    profile=profile,
                    trace=trace,
                    function_id=direct_tool_call.name,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    error=None,
                    final_state=observation,
                    terminal_detail={
                        "done_reason": "function_completed"
                    },
                )

        if direct_tool_call is not None and selected_function is None:
            try:
                direct_action = _action_from_tool_call(direct_tool_call)
            except ValueError as error:
                return finish(
                    False,
                    profile=profile,
                    trace=trace,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    error=f"tool_not_found:{error}",
                    final_state=observation,
                )
            if direct_action.tool == "finished":
                return finish(
                    True,
                    profile=profile,
                    trace=trace,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    final_state=observation,
                    terminal_detail={
                        "done_reason": "finished",
                        "finished_content": str(
                            direct_action.args.get("content") or ""
                        ),
                    },
                )
            if direct_action.tool in {"abort", "info", "get_state"}:
                return finish(
                    False,
                    profile=profile,
                    trace=trace,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    error=f"tool_not_directly_invokable:{direct_action.tool}",
                    final_state=observation,
                )
            step = await execute_robust_action(
                direct_action,
                observation=observation,
                host=self.host,
                plugins=self.plugins,
                installed_packages=self.installed_packages,
            )
            trace.extend(
                await record_execution(
                    self.host,
                    step,
                    trace_start_index=0,
                )
            )
            return finish(
                step.success,
                profile=profile,
                trace=trace,
                actions_executed=step.actions_executed,
                model_calls=model_calls,
                llm_usage=llm_usage,
                error=None if step.success else step.error,
                final_state=step.after or observation,
                terminal_detail={
                    "done_reason": "tool_completed" if step.success else "error"
                },
            )

        if self.planner is None:
            return finish(
                False,
                profile=profile,
                trace=trace,
                function_id=function_session.selected_id or function_session.failed_id,
                actions_executed=actions_executed,
                model_calls=model_calls,
                llm_usage=llm_usage,
                error=last_error,
                final_state=observation,
            )

        previous_action_error: str | None = (
            last_error if function_session.failed else None
        )
        previous_action: Action | None = None
        previous_action_result: str | None = None
        pending_user_input: str | None = None
        planner_diagnostics: dict[str, Any] = {}
        experience_store_invalid = False
        if self.experience_store is not None:
            try:
                self.experience_store.reload()
            except ValueError as err:
                if "app_experience_store_invalid" in str(err):
                    experience_store_invalid = True
            except Exception:
                experience_store_invalid = True
        elif getattr(self, "_experience_store_error", None) == "app_experience_store_invalid":
            experience_store_invalid = True

        if experience_store_invalid:
            planner_diagnostics["experience_store_error"] = "app_experience_store_invalid"
        consecutive_stalled_observations: int = 0
        current_screen_read_evidence: set[tuple[bool, tuple[float, float, float, float] | None]] = set()
        current_screen_satisfied_conditions: set[tuple[str, str]] = set()
        last_confirmed_rejected_action: tuple[tuple[str, str], tuple[str, str, str, str]] | None = None
        while planner_steps < self.config.runtime.max_steps:
            max_fallback_steps = self.config.runtime.max_fallback_steps
            fallback_this_turn = function_session.failed
            if (
                fallback_this_turn
                and max_fallback_steps is not None
                and fallback_steps >= max(
                    0, int(max_fallback_steps)
                )
            ):
                return finish(
                    False,
                    profile=profile,
                    trace=trace,
                    function_id=function_session.selected_id or function_session.failed_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    fallback_steps=fallback_steps,
                    error="fallback_budget_exhausted",
                    final_state=observation,
                    planner_diagnostics=planner_diagnostics,
                )
            evidence_function = function_session.completed or (
                function_session.bound if function_session.failed else None
            )
            function_execution = (
                _function_execution_evidence(
                    trace,
                    function=evidence_function,
                    final_observation=observation,
                    succeeded=function_session.completed is not None,
                )
                if evidence_function is not None
                else None
            )
            if (
                previous_action_error
                or previous_action is not None
                or pending_user_input
                or function_execution
            ):
                observation = Observation(
                    xml=observation.xml,
                    package_name=observation.package_name,
                    activity_name=observation.activity_name,
                    image_base64=observation.image_base64,
                    extra={
                        **dict(observation.extra),
                        "previous_action_error": previous_action_error,
                        "previous_action": previous_action.to_dict()
                        if previous_action is not None
                        else None,
                        **(
                            {"previous_action_result": previous_action_result}
                            if previous_action_result
                            else {}
                        ),
                        **(
                            {"function_execution": function_execution}
                            if function_execution
                            else {}
                        ),
                        **(
                            {"user_input": pending_user_input}
                            if pending_user_input
                            else {}
                        ),
                    },
                )
            pending_user_input = None
            selected_experiences: list[dict[str, Any]] = []
            if self.experience_store is not None and not experience_store_invalid:
                visible_ids = _extract_visible_resource_ids(observation.xml)
                selected_experiences = self.experience_store.select(
                    observation.package_name or "",
                    observation.activity_name or "",
                    visible_ids,
                )

            current_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
            if selected_experiences:
                current_extra["app_experiences"] = selected_experiences
            else:
                current_extra.pop("app_experiences", None)

            observation = Observation(
                xml=observation.xml,
                package_name=observation.package_name,
                activity_name=observation.activity_name,
                image_base64=observation.image_base64,
                extra=current_extra,
            )
            recall_result = await self._recall(
                goal,
                observation=observation,
                source_states=recall_source_states,
            )
            planner_functions = recall_result.functions
            planner_function_catalog = {
                function.id: function for function in planner_functions
            }
            recall_event = {
                "planner_turn": planner_steps,
                **recall_result.audit,
            }
            recall_events.append(recall_event)
            function_resolution["candidate_count"] = len(planner_functions)
            function_resolution["candidate_function_ids"] = [
                function.id for function in planner_functions
            ]
            planner_steps += 1
            try:
                planned_call = ToolCall.from_value(
                    await _await(
                        self.planner.one_step_tool_call(
                            goal,
                            observation,
                            planner_functions,
                            dict(self.installed_apps),
                        )
                    )
                )
            except Exception as error:  # noqa: BLE001
                planner_metadata = _take_planner_metadata(self.planner)
                _merge_planner_diagnostics(planner_diagnostics, planner_metadata)
                planner_usage = _take_llm_usage(self.planner)
                merge_usage(llm_usage, planner_usage, component="planner")
                model_calls += _usage_model_calls(planner_usage, fallback=1)
                planner_diagnostics["failure_kind"] = "planner_failed"
                return finish(
                    False,
                    profile=profile,
                    trace=trace,
                    function_id=function_session.selected_id or function_session.failed_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    fallback_steps=fallback_steps,
                    error=f"vlm_planner_failed:{error}",
                    final_state=observation,
                    planner_diagnostics=planner_diagnostics,
                )
            planner_usage = _take_llm_usage(self.planner)
            merge_usage(llm_usage, planner_usage, component="planner")
            model_calls += _usage_model_calls(planner_usage, fallback=1)
            if fallback_this_turn:
                fallback_steps += 1
            planner_metadata = _take_planner_metadata(self.planner)
            _merge_planner_diagnostics(planner_diagnostics, planner_metadata)
            selected_function = planner_function_catalog.get(planned_call.name)
            if selected_function is not None:
                retry_step_index = (
                    function_session.failed_step_index
                    if function_session.failed
                    and function_session.selected_id == selected_function.id
                    else None
                )
                previous_bound = function_session.bound
                try:
                    bound_function = bind_function(
                        selected_function,
                        planned_call.arguments,
                    )
                except ValueError as error:
                    previous_action_error = str(error)
                    previous_action = Action(planned_call.name, dict(planned_call.arguments))
                    previous_action_result = "failed"
                    if planner_steps >= self.config.runtime.max_steps:
                        return finish(
                            False,
                            profile=profile,
                            trace=trace,
                            function_id=selected_function.id,
                            actions_executed=actions_executed,
                            model_calls=model_calls,
                            llm_usage=llm_usage,
                            fallback_steps=fallback_steps,
                            error=previous_action_error or "max_steps_exceeded",
                            final_state=observation,
                            planner_diagnostics=planner_diagnostics,
                            terminal_detail={"done_reason": "max_steps_exceeded"},
                        )
                    continue
                if previous_bound != bound_function:
                    retry_step_index = None
                function_session.selected_id = selected_function.id
                function_session.bound = bound_function
                retry_metadata = None
                resume_event = None
                if retry_step_index is not None:
                    retry_step = next(
                        (
                            step
                            for step in bound_function.steps
                            if step.step_index == retry_step_index
                        ),
                        None,
                    )
                    if retry_step is not None:
                        retry_metadata = {
                            "protocol": "explicit_function_retry_v1",
                            "start_step_index": int(retry_step_index),
                            "resume_step_index": int(retry_step_index),
                            "source_state_id": retry_step.source_state_id,
                        }
                        resume_event = {
                            "start_step_index": int(retry_step_index),
                            "status": "retrying",
                            "trigger": "explicit_function_call",
                            "resume_step_index": int(retry_step_index),
                            "source_state_id": retry_step.source_state_id,
                        }
                        function_session.resume_events.append(resume_event)
                consecutive_stalled_observations = 0
                current_screen_read_evidence.clear()
                current_screen_satisfied_conditions.clear()
                last_confirmed_rejected_action = None
                current_episode_used_experience_step_indices.clear()
                replay = await execute_function(
                    bound_function,
                    host=self.host,
                    plugins=self.plugins,
                    observation=observation,
                    start_step_index=int(retry_step_index or 0),
                    trace_start_index=len(trace),
                    resume_metadata=retry_metadata,
                    installed_packages=self.installed_packages,
                    state_loader=(
                        self.catalog.get_state if self.catalog is not None else None
                    ),
                    checker_page_similarity=(
                        self.config.runtime.checker_page_similarity
                    ),
                    checker_action_confidence=(
                        self.config.runtime.checker_action_confidence
                    ),
                )
                actions_executed += replay.actions_executed
                replay_trace = list(replay.detail.get("trace") or ())
                trace.extend(replay_trace)
                checker_decisions.extend(
                    replay.detail.get("checker_decisions") or ()
                )
                observation = replay.final_state or observation
                previous_action = Action(planned_call.name, dict(planned_call.arguments))
                previous_action_result = "succeeded" if replay.success else "failed"
                if replay.success:
                    if resume_event is not None:
                        resume_event["status"] = "succeeded"
                    function_session.mark_completed()
                    previous_action_error = None
                else:
                    if resume_event is not None:
                        resume_event["status"] = "failed"
                        resume_event["error"] = replay.error or "function_replay_failed"
                    function_session.mark_failed(replay)
                    previous_action_error = replay.error or "function_replay_failed"
                if planner_steps >= self.config.runtime.max_steps:
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=selected_function.id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error=None if replay.success else (previous_action_error or "max_steps_exceeded"),
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": "step_completed" if replay.success else "max_steps_exceeded"},
                    )
                continue
            try:
                planned = _action_from_tool_call(planned_call)
            except ValueError as error:
                previous_action_error = str(error)
                previous_action = Action(planned_call.name, dict(planned_call.arguments))
                previous_action_result = "failed"
                clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                clean_extra["previous_action"] = previous_action.to_dict()
                clean_extra["previous_action_result"] = "failed"
                clean_extra["previous_action_error"] = previous_action_error
                observation = Observation(
                    xml=observation.xml,
                    package_name=observation.package_name,
                    activity_name=observation.activity_name,
                    image_base64=observation.image_base64,
                    extra=clean_extra,
                )
                if hasattr(self.planner, "commit_pending_result"):
                    self.planner.commit_pending_result(observation)
                clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                clean_extra.pop("collection", None)
                observation = Observation(
                    xml=observation.xml,
                    package_name=observation.package_name,
                    activity_name=observation.activity_name,
                    image_base64=observation.image_base64,
                    extra=clean_extra,
                )
                if planner_steps >= self.config.runtime.max_steps:
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error=previous_action_error or "max_steps_exceeded",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": "max_steps_exceeded"},
                    )
                continue
            if planned.tool == "finished":
                content = str(planned.args.get("content") or "")
                explicit_obs_id = planned.args.get("observation_id")
                has_expected = "expected_text" in planned.args and planned.args.get("expected_text") is not None
                expected_text = str(planned.args.get("expected_text") or "") if has_expected else None

                current_obs_id = ""
                if isinstance(observation.extra, dict):
                    current_obs_id = str(observation.extra.get("observation_id") or "").strip()
                current_state_id = str(
                    (observation.extra.get("state_id") if isinstance(observation.extra, dict) else "")
                    or ""
                ).strip()

                if explicit_obs_id is not None:
                    if str(explicit_obs_id).strip() != current_obs_id:
                        previous_action = planned
                        previous_action_result = "failed"
                        previous_action_error = "finished_observation_expired"
                        clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                        clean_extra["previous_action"] = planned.to_dict()
                        clean_extra["previous_action_result"] = "failed"
                        clean_extra["previous_action_error"] = "finished_observation_expired"
                        observation = Observation(
                            xml=observation.xml,
                            package_name=observation.package_name,
                            activity_name=observation.activity_name,
                            image_base64=observation.image_base64,
                            extra=clean_extra,
                        )
                        if planner_steps >= self.config.runtime.max_steps:
                            return finish(
                                False,
                                profile=profile,
                                trace=trace,
                                function_id=function_session.selected_id or function_session.failed_id,
                                actions_executed=actions_executed,
                                model_calls=model_calls,
                                llm_usage=llm_usage,
                                fallback_steps=fallback_steps,
                                error="max_steps_exceeded",
                                final_state=observation,
                                planner_diagnostics=planner_diagnostics,
                                terminal_detail={"done_reason": "max_steps_exceeded"},
                            )
                        continue

                matched_status = "model_asserted"
                matched_source = "model"
                matched_node_id = None

                if has_expected:
                    match_res = match_expected_text_in_observation(observation, expected_text or "")
                    if match_res is None:
                        previous_action = planned
                        previous_action_result = "failed"
                        previous_action_error = "finished_evidence_mismatch"
                        clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                        clean_extra["previous_action"] = planned.to_dict()
                        clean_extra["previous_action_result"] = "failed"
                        clean_extra["previous_action_error"] = "finished_evidence_mismatch"
                        observation = Observation(
                            xml=observation.xml,
                            package_name=observation.package_name,
                            activity_name=observation.activity_name,
                            image_base64=observation.image_base64,
                            extra=clean_extra,
                        )
                        if planner_steps >= self.config.runtime.max_steps:
                            return finish(
                                False,
                                profile=profile,
                                trace=trace,
                                function_id=function_session.selected_id or function_session.failed_id,
                                actions_executed=actions_executed,
                                model_calls=model_calls,
                                llm_usage=llm_usage,
                                fallback_steps=fallback_steps,
                                error="max_steps_exceeded",
                                final_state=observation,
                                planner_diagnostics=planner_diagnostics,
                                terminal_detail={"done_reason": "max_steps_exceeded"},
                            )
                        continue
                    matched_source, matched_node_id = match_res
                    matched_status = "matched"

                finished_evidence = {
                    "observation_id": current_obs_id or None,
                    "state_id": current_state_id or None,
                    "status": matched_status,
                    "source": matched_source,
                    "matched_node_id": matched_node_id,
                }
                planner_diagnostics["finished_evidence"] = finished_evidence

                return finish(
                    True,
                    profile=profile,
                    trace=trace,
                    function_id=function_session.selected_id or function_session.failed_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    fallback_steps=fallback_steps,
                    final_state=observation,
                    planner_diagnostics=planner_diagnostics,
                    terminal_detail={
                        "done_reason": "finished",
                        "finished_content": content,
                        "finished_evidence": finished_evidence,
                    },
                )
            if planned.tool == "abort":
                message = str(planned.args.get("value") or "").strip() or "vlm_aborted"
                return finish(
                    False,
                    profile=profile,
                    trace=trace,
                    function_id=function_session.selected_id or function_session.failed_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    fallback_steps=fallback_steps,
                    error=message,
                    final_state=observation,
                    planner_diagnostics=planner_diagnostics,
                    terminal_detail={"done_reason": "abort"},
                )
            if planned.tool == "info":
                question = str(planned.args.get("value") or "").strip()
                if not question:
                    previous_action_error = "info_question_required"
                    previous_action = planned
                    previous_action_result = "failed"
                    continue
                try:
                    pending_user_input = str(
                        await _await(_request_input(self.host, question))
                    )
                except InputRequired as error:
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="input_required",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={
                            "done_reason": "waiting_input",
                            "finished_content": error.question,
                        },
                    )
                except Exception as error:  # noqa: BLE001
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error=f"request_input_failed:{error}",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                    )
                previous_action_error = None
                previous_action = None
                previous_action_result = None
                continue
            if planned.tool == "get_state":
                args = planned.args or {}
                ocr_arg = args.get("ocr")
                ocr_flag = bool(ocr_arg) if ocr_arg is not None else None
                region_keys = ("x1", "y1", "x2", "y2")
                has_any_region = any(k in args and args[k] is not None for k in region_keys)
                has_all_region = all(k in args and args[k] is not None for k in region_keys)
                region = None
                if has_any_region:
                    if not has_all_region:
                        previous_action_error = "invalid_get_state_region:incomplete_coordinates"
                        previous_action = planned
                        previous_action_result = "failed"
                        observation = await self._observe(screenshot=True)
                        continue
                    try:
                        x1 = float(args["x1"])
                        y1 = float(args["y1"])
                        x2 = float(args["x2"])
                        y2 = float(args["y2"])
                    except (ValueError, TypeError):
                        previous_action_error = "invalid_get_state_region:non_numeric_coordinates"
                        previous_action = planned
                        previous_action_result = "failed"
                        observation = await self._observe(screenshot=True)
                        continue
                    if not (0 <= x1 < x2 <= 1000 and 0 <= y1 < y2 <= 1000):
                        previous_action_error = "invalid_get_state_region:out_of_bounds_or_zero_area"
                        previous_action = planned
                        previous_action_result = "failed"
                        observation = await self._observe(screenshot=True)
                        continue
                    region = {"x1": x1, "y1": y1, "x2": x2, "y2": y2}
                before_observation = observation
                observation = await self._observe(
                    screenshot=True,
                    ocr=ocr_flag,
                    region=region,
                )
                screen_changed = _is_screen_substantively_changed(
                    before_observation,
                    observation,
                )
                read_roi = (
                    (float(region["x1"]), float(region["y1"]), float(region["x2"]), float(region["y2"]))
                    if region else None
                )
                read_sig = (bool(ocr_flag), read_roi)
                after_pkg = str(observation.package_name or "").strip()
                is_empty_bg = (after_pkg in {"", "-", "None", "android"}) and not str(observation.xml or "").strip()

                if screen_changed:
                    current_screen_read_evidence.clear()
                    current_screen_satisfied_conditions.clear()
                    current_screen_read_evidence.add(read_sig)
                    current_episode_used_experience_step_indices.clear()
                    has_progress = True
                elif is_empty_bg:
                    has_progress = False
                elif read_sig in current_screen_read_evidence:
                    has_progress = False
                else:
                    current_screen_read_evidence.add(read_sig)
                    has_progress = True

                if has_progress:
                    consecutive_stalled_observations = 0
                    last_confirmed_rejected_action = None
                else:
                    consecutive_stalled_observations += 1

                extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                if has_progress:
                    extra.pop("observation_stalled", None)
                    extra.pop("consecutive_stalled_observations", None)
                else:
                    extra["observation_stalled"] = True
                    extra["consecutive_stalled_observations"] = consecutive_stalled_observations

                observation = Observation(
                    xml=observation.xml,
                    package_name=observation.package_name,
                    activity_name=observation.activity_name,
                    image_base64=observation.image_base64,
                    extra=extra,
                )
                previous_action_error = None
                previous_action = planned
                previous_action_result = "succeeded"

                if consecutive_stalled_observations >= 2:
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="no_progress_detected",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": "no_progress_detected"},
                    )

                if planner_steps >= self.config.runtime.max_steps:
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="max_steps_exceeded",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": "max_steps_exceeded"},
                    )
                continue
            if planned.tool == "collect_screen":
                had_any_collection = True
                args = planned.args or {}
                raw_dir = args.get("direction")
                raw_x1 = args.get("x1")
                raw_y1 = args.get("y1")
                raw_x2 = args.get("x2")
                raw_y2 = args.get("y2")
                raw_max_pages = args.get("max_pages")
                raw_max_items = args.get("max_items")
                raw_ocr = args.get("ocr")

                # 1. 严格参数校验
                args_valid = True
                if not isinstance(raw_dir, str) or raw_dir not in {"down", "up"}:
                    args_valid = False
                for c in (raw_x1, raw_y1, raw_x2, raw_y2):
                    if isinstance(c, bool) or not isinstance(c, (int, float)) or not math.isfinite(float(c)):
                        args_valid = False
                        break
                if args_valid:
                    fx1, fy1, fx2, fy2 = float(raw_x1), float(raw_y1), float(raw_x2), float(raw_y2)
                    if not (0 <= fx1 < fx2 <= 1000 and 0 <= fy1 < fy2 <= 1000):
                        args_valid = False

                if args_valid and raw_max_pages is not None:
                    if (
                        isinstance(raw_max_pages, bool)
                        or not isinstance(raw_max_pages, (int, float))
                        or not math.isfinite(float(raw_max_pages))
                        or int(raw_max_pages) != raw_max_pages
                        or not (1 <= raw_max_pages <= 20)
                    ):
                        args_valid = False

                if args_valid and raw_max_items is not None:
                    if (
                        isinstance(raw_max_items, bool)
                        or not isinstance(raw_max_items, (int, float))
                        or not math.isfinite(float(raw_max_items))
                        or int(raw_max_items) != raw_max_items
                        or not (1 <= raw_max_items <= 200)
                    ):
                        args_valid = False

                if args_valid and raw_ocr is not None and not isinstance(raw_ocr, bool):
                    args_valid = False

                if not args_valid:
                    previous_action_error = "invalid_collect_screen_arguments"
                    previous_action = planned
                    previous_action_result = "failed"
                    clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                    clean_extra["previous_action"] = planned.to_dict()
                    clean_extra["previous_action_result"] = "failed"
                    clean_extra["previous_action_error"] = "invalid_collect_screen_arguments"
                    observation = Observation(
                        xml=observation.xml,
                        package_name=observation.package_name,
                        activity_name=observation.activity_name,
                        image_base64=observation.image_base64,
                        extra=clean_extra,
                    )
                    if hasattr(self.planner, "commit_pending_result"):
                        self.planner.commit_pending_result(observation)
                    clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                    clean_extra.pop("collection", None)
                    observation = Observation(
                        xml=observation.xml,
                        package_name=observation.package_name,
                        activity_name=observation.activity_name,
                        image_base64=observation.image_base64,
                        extra=clean_extra,
                    )
                    if planner_steps >= self.config.runtime.max_steps:
                        return finish(
                            False,
                            profile=profile,
                            trace=trace,
                            function_id=function_session.selected_id or function_session.failed_id,
                            actions_executed=actions_executed,
                            model_calls=model_calls,
                            llm_usage=llm_usage,
                            fallback_steps=fallback_steps,
                            error="invalid_collect_screen_arguments",
                            final_state=observation,
                            planner_diagnostics=planner_diagnostics,
                            terminal_detail={"done_reason": "max_steps_exceeded"},
                        )
                    continue

                direction = str(raw_dir)
                x1 = float(raw_x1)
                y1 = float(raw_y1)
                x2 = float(raw_x2)
                y2 = float(raw_y2)
                max_pages = int(raw_max_pages if raw_max_pages is not None else 5)
                max_items = int(raw_max_items if raw_max_items is not None else 100)
                ocr = bool(raw_ocr) if raw_ocr is not None else False

                # 2. 绑定开始时的 display_id 与 package_name；检查空后台
                initial_pkg = str(observation.package_name or "").strip()
                initial_display_id = _extract_display_id(observation)

                is_empty_bg = (not initial_pkg or initial_pkg in {"-", "None"}) or (
                    initial_pkg == "android" and not str(observation.xml or "").strip()
                )
                if is_empty_bg:
                    previous_action_error = "collect_screen_no_app"
                    previous_action = planned
                    previous_action_result = "failed"
                    call_coll = {
                        "items": [],
                        "pages_scanned": 0,
                        "truncated": False,
                        "partial": True,
                        "stop_reason": "collect_screen_no_app",
                        "overlap_uncertain": False,
                        "ocr_status": "not_requested",
                    }
                    clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                    clean_extra["previous_action"] = planned.to_dict()
                    clean_extra["previous_action_result"] = "failed"
                    clean_extra["previous_action_error"] = "collect_screen_no_app"
                    clean_extra["collection"] = call_coll
                    observation = Observation(
                        xml=observation.xml,
                        package_name=observation.package_name,
                        activity_name=observation.activity_name,
                        image_base64=observation.image_base64,
                        extra=clean_extra,
                    )
                    if hasattr(self.planner, "commit_pending_result"):
                        self.planner.commit_pending_result(observation)
                    clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                    clean_extra.pop("collection", None)
                    observation = Observation(
                        xml=observation.xml,
                        package_name=observation.package_name,
                        activity_name=observation.activity_name,
                        image_base64=observation.image_base64,
                        extra=clean_extra,
                    )
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="collect_screen_no_app",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": "collect_screen_no_app"},
                    )

                if initial_display_id is None:
                    previous_action_error = "display_unavailable"
                    previous_action = planned
                    previous_action_result = "failed"
                    call_coll = {
                        "items": [],
                        "pages_scanned": 0,
                        "truncated": False,
                        "partial": True,
                        "stop_reason": "display_unavailable",
                        "overlap_uncertain": False,
                        "ocr_status": "not_requested" if not ocr else "unavailable",
                    }
                    clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                    clean_extra["previous_action"] = planned.to_dict()
                    clean_extra["previous_action_result"] = "failed"
                    clean_extra["previous_action_error"] = "display_unavailable"
                    clean_extra["collection"] = call_coll
                    observation = Observation(
                        xml=observation.xml,
                        package_name=observation.package_name,
                        activity_name=observation.activity_name,
                        image_base64=observation.image_base64,
                        extra=clean_extra,
                    )
                    if hasattr(self.planner, "commit_pending_result"):
                        self.planner.commit_pending_result(observation)
                    clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                    clean_extra.pop("collection", None)
                    observation = Observation(
                        xml=observation.xml,
                        package_name=observation.package_name,
                        activity_name=observation.activity_name,
                        image_base64=observation.image_base64,
                        extra=clean_extra,
                    )
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="display_unavailable",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": "display_unavailable"},
                    )

                # display_id 已校验完毕

                # 3. 循环采集
                ocr_status = "not_requested" if not ocr else "ready"
                stop_reason: str | None = None
                truncated = False
                partial = False

                if ocr:
                    obs_ocr = observation.extra.get("ocr") if isinstance(observation.extra, dict) else None
                    if not (isinstance(obs_ocr, dict) and obs_ocr.get("status") == "ready"):
                        try:
                            observation = await self._observe(screenshot=True, ocr=True, region={"x1": x1, "y1": y1, "x2": x2, "y2": y2})
                        except asyncio.CancelledError:
                            raise
                        except Exception:
                            stop_reason = "observe_failed"
                            partial = True

                        if not partial:
                            if observation is None:
                                stop_reason = "observe_failed"
                                partial = True
                            else:
                                curr_pkg = str(observation.package_name or "").strip()
                                curr_display = _extract_display_id(observation)
                                if curr_pkg != initial_pkg:
                                    stop_reason = "package_changed"
                                    partial = True
                                elif curr_display is None or curr_display != initial_display_id:
                                    stop_reason = "display_unavailable"
                                    partial = True

                    if not partial:
                        if isinstance(observation.extra, dict) and "ocr" in observation.extra:
                            ocr_data = observation.extra["ocr"]
                            ocr_status = ocr_data.get("status", "unavailable") if isinstance(ocr_data, dict) else "unavailable"
                        else:
                            ocr_status = "unavailable"

                call_start_page_index = total_pages_scanned
                pages_scanned = 0 if partial else 1
                curr_page_idx = call_start_page_index
                call_items: list[dict[str, Any]] = []
                page_signatures: list[str] = [_observation_signature(observation)] if not partial else []
                consecutive_no_new = 0
                overlap_uncertain_any = False

                if not partial:
                    # 提取首页（页面索引采用 run 级别单调递增的访问页码序列，避免跨 collect 调用产生页码重复与歧义；不以 state_id 构造假时间序）
                    p0_lines = extract_page_lines(observation, (x1, y1, x2, y2), include_ocr=ocr)
                    for item in p0_lines:
                        item["page_index"] = curr_page_idx
                        if len(call_items) >= max_items:
                            stop_reason = "item_limit"
                            truncated = True
                            break
                        t = str(item.get("text") or "")
                        if total_collected_text_length + len(t) > MAX_RUN_COLLECTION_TEXT_LENGTH:
                            stop_reason = "text_limit"
                            truncated = True
                            break
                        call_items.append(item)
                        total_collected_text_length += len(t)
                        accumulated_collection_items.append(item)
                        if len(call_items) >= max_items:
                            stop_reason = "item_limit"
                            truncated = True
                            break

                    previous_page_lines = p0_lines

                    if not stop_reason and pages_scanned >= max_pages:
                        stop_reason = "page_limit"
                        truncated = True
                # 开始跨页滚动
                while not stop_reason and pages_scanned < max_pages:
                    if len(call_items) >= max_items:
                        stop_reason = "item_limit"
                        truncated = True
                        break
                    if planner_steps >= self.config.runtime.max_steps:
                        stop_reason = "step_limit"
                        truncated = True
                        break
                    if fallback_this_turn and max_fallback_steps is not None and fallback_steps >= max(0, int(max_fallback_steps)):
                        stop_reason = "step_limit"
                        truncated = True
                        break

                    center_x = (x1 + x2) / 2.0
                    height = y2 - y1
                    # 缩短行程并降低抬手速度，给原生惯性滚动留下相邻页重叠。
                    if direction == "down":
                        sy = y1 + 0.7 * height
                        ey = y1 + 0.3 * height
                        swipe_dir = "up"
                    else:
                        sy = y1 + 0.3 * height
                        ey = y1 + 0.7 * height
                        swipe_dir = "down"

                    swipe_action = Action(
                        "swipe",
                        {
                            "direction": swipe_dir,
                            "x1": round(center_x, 2),
                            "y1": round(sy, 2),
                            "x2": round(center_x, 2),
                            "y2": round(ey, 2),
                            "duration_ms": 600,
                        },
                    )

                    planner_steps += 1
                    if fallback_this_turn:
                        fallback_steps += 1

                    swipe_host = _CollectSwipeHost(self.host)
                    try:
                        step = await execute_robust_action(
                            swipe_action,
                            observation=observation,
                            host=swipe_host,
                            plugins=self.plugins,
                            installed_packages=self.installed_packages,
                        )
                    except asyncio.CancelledError:
                        raise
                    except Exception:
                        if swipe_host.observe_failed:
                            actions_executed += 1
                            stop_reason = "observe_failed"
                        else:
                            stop_reason = "swipe_failed"
                        partial = True
                        break

                    try:
                        trace.extend(
                            await record_execution(
                                self.host,
                                step,
                                trace_start_index=len(trace),
                                metadata=planner_metadata,
                            )
                        )
                    except asyncio.CancelledError:
                        raise
                    except Exception:
                        actions_executed += step.actions_executed
                        stop_reason = "swipe_failed"
                        partial = True
                        break
                    actions_executed += step.actions_executed

                    if not step.success:
                        if step.error and ("observe" in step.error.lower() or "observation" in step.error.lower()):
                            stop_reason = "observe_failed"
                        else:
                            stop_reason = "swipe_failed"
                        partial = True
                        break

                    if ocr:
                        try:
                            observation = await self._observe(screenshot=True, ocr=True, region={"x1": x1, "y1": y1, "x2": x2, "y2": y2})
                        except asyncio.CancelledError:
                            raise
                        except Exception:
                            stop_reason = "observe_failed"
                            partial = True
                            break
                        if isinstance(observation.extra, dict) and "ocr" in observation.extra:
                            ocr_data = observation.extra["ocr"]
                            ocr_status = ocr_data.get("status", "unavailable") if isinstance(ocr_data, dict) else "unavailable"
                        else:
                            ocr_status = "unavailable"
                    else:
                        try:
                            observation = step.after or await self._observe(screenshot=True)
                        except asyncio.CancelledError:
                            raise
                        except Exception:
                            stop_reason = "observe_failed"
                            partial = True
                            break
                    if observation is None:
                        stop_reason = "observe_failed"
                        partial = True
                        break

                    curr_pkg = str(observation.package_name or "").strip()
                    curr_display = _extract_display_id(observation)
                    if curr_pkg != initial_pkg:
                        stop_reason = "package_changed"
                        partial = True
                        break
                    if curr_display is None or curr_display != initial_display_id:
                        stop_reason = "display_unavailable"
                        partial = True
                        break

                    pages_scanned += 1
                    curr_page_idx = call_start_page_index + pages_scanned - 1
                    curr_sig = _observation_signature(observation)
                    if curr_sig in page_signatures:
                        stop_reason = "no_new_content"
                        break
                    page_signatures.append(curr_sig)

                    curr_lines = extract_page_lines(observation, (x1, y1, x2, y2), include_ocr=ocr)
                    for item in curr_lines:
                        item["page_index"] = curr_page_idx

                    new_items, uncertain = merge_page_lines(previous_page_lines, curr_lines, direction)
                    if uncertain:
                        overlap_uncertain_any = True

                    if not new_items:
                        consecutive_no_new += 1
                        if consecutive_no_new >= 2:
                            stop_reason = "no_new_content"
                            break
                    else:
                        consecutive_no_new = 0

                    previous_page_lines = curr_lines

                    for item in new_items:
                        if len(call_items) >= max_items:
                            stop_reason = "item_limit"
                            truncated = True
                            break
                        t = str(item.get("text") or "")
                        if total_collected_text_length + len(t) > MAX_RUN_COLLECTION_TEXT_LENGTH:
                            stop_reason = "text_limit"
                            truncated = True
                            break
                        call_items.append(item)
                        total_collected_text_length += len(t)
                        accumulated_collection_items.append(item)
                        if len(call_items) >= max_items:
                            stop_reason = "item_limit"
                            truncated = True
                            break
                    if stop_reason:
                        break

                if not stop_reason:
                    if pages_scanned >= max_pages:
                        stop_reason = "page_limit"
                        truncated = True
                    else:
                        stop_reason = "no_new_content"

                total_pages_scanned += pages_scanned
                if truncated:
                    any_collection_truncated = True
                if partial:
                    any_collection_partial = True
                last_collection_stop_reason = stop_reason
                if overlap_uncertain_any:
                    any_overlap_uncertain = True
                last_ocr_status = ocr_status

                success = not partial
                call_coll = {
                    "items": list(call_items),
                    "pages_scanned": pages_scanned,
                    "truncated": truncated,
                    "partial": partial,
                    "stop_reason": stop_reason,
                    "overlap_uncertain": overlap_uncertain_any,
                    "ocr_status": ocr_status,
                }

                previous_action_error = None if success else stop_reason
                previous_action = planned
                previous_action_result = "succeeded" if success else "failed"

                clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                clean_extra["previous_action"] = planned.to_dict()
                clean_extra["previous_action_result"] = previous_action_result
                if previous_action_error:
                    clean_extra["previous_action_error"] = previous_action_error
                else:
                    clean_extra.pop("previous_action_error", None)
                clean_extra["collection"] = call_coll
                observation = Observation(
                    xml=observation.xml,
                    package_name=observation.package_name,
                    activity_name=observation.activity_name,
                    image_base64=observation.image_base64,
                    extra=clean_extra,
                )

                if hasattr(self.planner, "commit_pending_result"):
                    self.planner.commit_pending_result(observation)

                clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                clean_extra.pop("collection", None)
                observation = Observation(
                    xml=observation.xml,
                    package_name=observation.package_name,
                    activity_name=observation.activity_name,
                    image_base64=observation.image_base64,
                    extra=clean_extra,
                )
                if partial or not success:
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error=stop_reason or "collect_screen_partial",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": stop_reason or "collect_screen_partial"},
                    )

                if planner_steps >= self.config.runtime.max_steps:
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="max_steps_exceeded",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": "max_steps_exceeded"},
                    )
                if fallback_this_turn and max_fallback_steps is not None and fallback_steps >= max(0, int(max_fallback_steps)):
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="fallback_budget_exhausted",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                    )
                continue
            action_sig = _action_canonical_signature(planned)
            obs_sig = _observation_signature(observation)
            if (
                last_confirmed_rejected_action is not None
                and (action_sig, obs_sig) == last_confirmed_rejected_action
            ):
                consecutive_stalled_observations += 1
                previous_action = planned
                previous_action_result = "failed"
                previous_action_error = "repeated_rejected_action"
                clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
                clean_extra["previous_action"] = planned.to_dict()
                clean_extra["previous_action_result"] = "failed"
                clean_extra["previous_action_error"] = "repeated_rejected_action"
                clean_extra["observation_stalled"] = True
                clean_extra["consecutive_stalled_observations"] = consecutive_stalled_observations
                observation = Observation(
                    xml=observation.xml,
                    package_name=observation.package_name,
                    activity_name=observation.activity_name,
                    image_base64=observation.image_base64,
                    extra=clean_extra,
                )
                if consecutive_stalled_observations >= 2:
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="no_progress_detected",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": "no_progress_detected"},
                    )
                if planner_steps >= self.config.runtime.max_steps:
                    return finish(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=function_session.selected_id or function_session.failed_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="repeated_rejected_action",
                        final_state=observation,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={"done_reason": "max_steps_exceeded"},
                    )
                continue
            before_act_observation = observation
            step = await execute_robust_action(
                planned,
                observation=observation,
                host=self.host,
                plugins=self.plugins,
                installed_packages=self.installed_packages,
            )
            attributed_exp_ids = _resolve_step_app_experience_ids(
                selected_experiences,
                planned,
                step,
                before_act_observation,
            )
            step_metadata = dict(planner_metadata)
            if attributed_exp_ids:
                step_metadata["app_experience_ids"] = list(attributed_exp_ids)
            recorded_steps = await record_execution(
                self.host,
                step,
                trace_start_index=len(trace),
                metadata=step_metadata,
            )
            trace.extend(recorded_steps)
            if attributed_exp_ids:
                for rs in recorded_steps:
                    s_idx = rs.get("step_index") if isinstance(rs, dict) else getattr(rs, "step_index", None)
                    if s_idx is not None:
                        current_episode_used_experience_step_indices.append(int(s_idx))
            actions_executed += step.actions_executed
            observation = step.after or observation
            screen_changed = _is_screen_substantively_changed(
                before_act_observation,
                observation,
            )

            if screen_changed:
                current_screen_read_evidence.clear()
                current_screen_satisfied_conditions.clear()
                consecutive_stalled_observations = 0
                last_confirmed_rejected_action = None
                current_episode_used_experience_step_indices.clear()
                has_act_progress = True
            elif planned.tool == "wait":
                wait_cond = planned.args.get("condition") if isinstance(planned.args, dict) else None
                wait_target = planned.args.get("target_description") if isinstance(planned.args, dict) else None

                if wait_cond is None:
                    has_act_progress = False
                    consecutive_stalled_observations += 1
                elif not step.success:
                    has_act_progress = False
                    consecutive_stalled_observations += 1
                else:
                    cond_sig = (str(wait_cond).strip(), str(wait_target or "").strip())
                    if cond_sig in current_screen_satisfied_conditions:
                        has_act_progress = False
                        consecutive_stalled_observations += 1
                    else:
                        current_screen_satisfied_conditions.add(cond_sig)
                        has_act_progress = True
                        consecutive_stalled_observations = 0

                if not step.success and _is_confirmed_non_executed_error(step.error):
                    last_confirmed_rejected_action = (
                        _action_canonical_signature(planned),
                        _observation_signature(before_act_observation),
                    )
                else:
                    last_confirmed_rejected_action = None
            else:
                has_act_progress = step.success
                if step.success:
                    consecutive_stalled_observations = 0
                    last_confirmed_rejected_action = None
                else:
                    if _is_confirmed_non_executed_error(step.error):
                        last_confirmed_rejected_action = (
                            _action_canonical_signature(planned),
                            _observation_signature(before_act_observation),
                        )
                    else:
                        last_confirmed_rejected_action = None

            clean_extra = dict(observation.extra) if isinstance(observation.extra, dict) else {}
            if planned.tool == "wait" and not has_act_progress:
                clean_extra["observation_stalled"] = True
                clean_extra["consecutive_stalled_observations"] = consecutive_stalled_observations
            else:
                clean_extra.pop("observation_stalled", None)
                clean_extra.pop("consecutive_stalled_observations", None)

            if isinstance(step.detail, dict) and "wait_diagnostics" in step.detail:
                clean_extra["wait_diagnostics"] = dict(step.detail["wait_diagnostics"])
            observation = Observation(
                xml=observation.xml,
                package_name=observation.package_name,
                activity_name=observation.activity_name,
                image_base64=observation.image_base64,
                extra=clean_extra,
            )
            previous_action_error = (
                None if step.success else step.error or "fallback_action_failed"
            )
            if planned.tool == "wait" and consecutive_stalled_observations >= 2:
                return finish(
                    False,
                    profile=profile,
                    trace=trace,
                    function_id=function_session.selected_id or function_session.failed_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    fallback_steps=fallback_steps,
                    error="no_progress_detected",
                    final_state=observation,
                    planner_diagnostics=planner_diagnostics,
                    terminal_detail={"done_reason": "no_progress_detected"},
                )
            if planner_steps >= self.config.runtime.max_steps:
                return finish(
                    False,
                    profile=profile,
                    trace=trace,
                    function_id=function_session.selected_id or function_session.failed_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    fallback_steps=fallback_steps,
                    error=previous_action_error or "max_steps_exceeded",
                    final_state=observation,
                    planner_diagnostics=planner_diagnostics,
                    terminal_detail={"done_reason": "max_steps_exceeded"},
                )
            previous_action = planned
            previous_action_result = "succeeded" if step.success else "failed"

        return finish(
            False,
            profile=profile,
            trace=trace,
            function_id=function_session.selected_id or function_session.failed_id,
            actions_executed=actions_executed,
            model_calls=model_calls,
            llm_usage=llm_usage,
            fallback_steps=fallback_steps,
            error=previous_action_error or "max_steps_exceeded",
            final_state=observation,
            planner_diagnostics=planner_diagnostics,
            terminal_detail={"done_reason": "max_steps_exceeded"},
        )

    async def _observe(
        self,
        *,
        screenshot: bool,
        ocr: bool | None = None,
        region: dict[str, float] | None = None,
    ) -> Observation:
        kwargs: dict[str, Any] = {
            "xml": True,
            "screenshot": screenshot,
            "app_info": True,
        }
        if ocr is not None:
            kwargs["ocr"] = ocr
        if region is not None:
            kwargs["region"] = region
        return Observation.from_value(
            await _await(
                self.host.observe(**kwargs)
            )
        )

    async def acall_tool(
        self,
        tool_call: ToolCall | dict[str, Any],
        *,
        experiment: Experiment | str | None = None,
    ) -> RunResult:
        return await self._execute(
            "",
            ToolCall.from_value(tool_call),
            experiment=experiment,
        )

    async def arun(
        self,
        goal: str,
        *,
        experiment: Experiment | str | None = None,
    ) -> RunResult:
        return await self._execute(
            str(goal),
            None,
            experiment=experiment,
        )

    async def _recall(
        self,
        goal: str,
        *,
        observation: Observation,
        source_states: dict[str, Observation | None],
        limit: int | None = None,
    ) -> RecallResult:
        for function in self.store.functions.values():
            if not function.steps:
                continue
            source_state_id = function.steps[0].source_state_id
            if source_state_id in source_states:
                continue
            source_state = (
                self.catalog.get_state(source_state_id)
                if self.catalog is not None
                else None
            )
            if source_state is None and self.host is not None:
                get_state = getattr(self.host, "get_state", None)
                if callable(get_state):
                    try:
                        value = await _await(get_state(source_state_id))
                        source_state = (
                            Observation.from_value(value) if value is not None else None
                        )
                    except Exception:  # noqa: BLE001
                        source_state = None
            source_states[source_state_id] = source_state

        resolved_limit = (
            self.config.runtime.max_function_tools if limit is None else int(limit)
        )
        return recall_functions(
            str(goal),
            observation=observation,
            functions=self.store.functions,
            source_states=source_states,
            limit=max(0, int(resolved_limit)),
        )

    def call_tool(
        self,
        tool_call: ToolCall | dict[str, Any],
        *,
        experiment: Experiment | str | None = None,
    ) -> RunResult:
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(
                self.acall_tool(tool_call, experiment=experiment)
            )
        raise RuntimeError(
            "OmniFlow.call_tool cannot run inside an event loop; await acall_tool"
        )

    def run(
        self,
        goal: str,
        *,
        experiment: Experiment | str | None = None,
    ) -> RunResult:
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(self.arun(str(goal), experiment=experiment))
        raise RuntimeError("OmniFlow.run cannot run inside an event loop; await arun")

    def _result(
        self,
        success: bool,
        *,
        profile: Experiment,
        trace: list[dict[str, Any]],
        function_id: str | None = None,
        actions_executed: int = 0,
        model_calls: int = 0,
        llm_usage: dict[str, Any] | None = None,
        fallback_steps: int = 0,
        error: str | None = None,
        final_state: Observation | None = None,
        planner_diagnostics: dict[str, Any] | None = None,
        function_resolution: dict[str, Any] | None = None,
        terminal_detail: dict[str, Any] | None = None,
        planner_steps: int = 0,
    ) -> RunResult:
        detail: dict[str, Any] = {
            "experiment": profile.name,
            "trace": list(trace),
            "runtime_limits": {
                "max_steps": int(self.config.runtime.max_steps),
                "max_fallback_steps": (
                    int(self.config.runtime.max_fallback_steps)
                    if self.config.runtime.max_fallback_steps is not None
                    else None
                ),
            },
            "planner_steps": max(0, int(planner_steps)),
        }
        usage = dict(llm_usage or {})
        tracked_model_calls = _usage_model_calls(usage, fallback=0)
        if tracked_model_calls < model_calls:
            usage["untracked_model_calls"] = model_calls - tracked_model_calls
            usage["model_calls"] = model_calls
        usage["token_usage_status"] = token_usage_status(usage)
        detail["llm_usage"] = usage
        if planner_diagnostics:
            detail["planner_diagnostics"] = dict(planner_diagnostics)
        if function_resolution is not None:
            detail["function_resolution"] = dict(function_resolution)
        if terminal_detail:
            detail.update(terminal_detail)
        return RunResult(
            success,
            function_id,
            actions_executed,
            model_calls,
            fallback_steps,
            error,
            final_state,
            detail,
        )


def _experiment(value: Experiment | str | None) -> Experiment:
    if isinstance(value, Experiment):
        return value
    return Experiment.for_method(str(value or "ours"))


def _action_from_tool_call(tool_call: ToolCall) -> Action:
    return Action.from_value(
        canonicalize_action(
            {
                "tool": tool_call.name,
                "args": tool_call.arguments,
            },
            persisted_only=False,
            allow_non_action=True,
        )
    )


def _direct_function_fallback_goal(
    function: Function,
    arguments: dict[str, Any],
) -> str:
    return (
        f'Continue Function "{function.name}" from the current screen after '
        "offline replay could not map its next step. Do not repeat actions that "
        "already succeeded. "
        f"Requested arguments: {json.dumps(arguments, ensure_ascii=False, sort_keys=True)}. "
        f"{function.description}"
    ).strip()


def _function_execution_evidence(
    trace: list[dict[str, Any]],
    *,
    function: Function,
    final_observation: Observation,
    succeeded: bool,
) -> dict[str, Any]:
    steps: list[dict[str, Any]] = []
    for raw_step in trace:
        if not isinstance(raw_step, dict):
            continue
        metadata = raw_step.get("metadata")
        if not isinstance(metadata, dict) or str(
            metadata.get("function_id") or ""
        ).strip() != function.id:
            continue
        try:
            action = Action.from_value(raw_step.get("action"))
        except (TypeError, ValueError):
            continue
        result = raw_step.get("result")
        success = isinstance(result, dict) and result.get("success") is True
        step = {
            "step_index": int(raw_step.get("step_index") or len(steps)),
            "before_state_id": str(raw_step.get("before_state_id") or ""),
            "after_state_id": str(raw_step.get("after_state_id") or ""),
            "tool": action.tool,
            "success": success,
        }
        if not success and isinstance(result, dict) and str(
            result.get("error") or ""
        ).strip():
            step["error"] = str(result["error"])
        steps.append(step)
    final_state_id = str(final_observation.extra.get("state_id") or "").strip()
    if not final_state_id and steps:
        final_state_id = str(steps[-1]["after_state_id"] or "").strip()
    return {
        "schema_version": "omniflow.function-execution-evidence.v1",
        "function_id": function.id,
        "function_name": function.name,
        "function_description": function.description,
        "replay_status": "actions_succeeded" if succeeded else "actions_failed",
        "official_validator_status": "pending",
        "steps": steps,
        "final_observation": {
            "state_id": final_state_id,
            "package_name": str(final_observation.package_name or ""),
            "activity_name": str(final_observation.activity_name or ""),
        },
    }


_CONFIRMED_NON_EXECUTED_REASONS = frozenset({
    # Selector rejections (Python semantic grounding / contracts)
    "target_selector_missing",
    "target_selector_mismatch",
    "target_ambiguous",
    "action_semantic_grounding_blocked",
    "invalid_wait_condition",
    "invalid_wait_duration",
    "wait_condition_unobservable",
    "finished_observation_expired",
    # AndroidGuiSafetyGuard selector rejections
    "action_target_missing",
    "action_target_ambiguous",
    "action_target_stale",
    "action_target_invisible",
    "action_target_disabled",
    "action_target_out_of_viewport",
    "action_target_moved",
    # Observation rejections
    "action_observation_expired",
    "observation_expired",
    "action_display_mismatch",
    # Occlusion rejections
    "action_target_occluded",
    "action_swipe_obstructed",
    "fully_occluded_by_blockers",
})


def _is_confirmed_non_executed_error(error: str | None) -> bool:
    if not error:
        return False
    err = str(error).strip().lower()
    for excluded in ("timeout", "transport", "connect", "network", "cancel", "interrupted"):
        if excluded in err:
            return False
    base_err = err.split(":")[0].strip()
    return base_err in _CONFIRMED_NON_EXECUTED_REASONS


def _action_canonical_signature(action: Action) -> tuple[str, str]:
    tool = str(action.tool or "").strip()
    raw_args = dict(action.args) if isinstance(action.args, dict) else {}
    canonical_args_str = json.dumps(raw_args, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return (tool, canonical_args_str)


def _observation_signature(observation: Observation | None) -> tuple[str, str, str, str]:
    if observation is None:
        return ("", "", "", "")
    return (
        str(observation.package_name or "").strip(),
        str(observation.activity_name or "").strip(),
        str(observation.xml or "").strip(),
        str(observation.image_base64 or "").strip(),
    )


def _same_observation(
    before: Observation | None,
    after: Observation | None,
) -> bool:
    if before is None or after is None:
        return False
    return (
        before.package_name,
        before.activity_name,
        before.xml,
        before.image_base64,
    ) == (
        after.package_name,
        after.activity_name,
        after.xml,
        after.image_base64,
    )


def _is_screen_substantively_changed(
    before_obs: Observation | None,
    after_obs: Observation | None,
) -> bool:
    if before_obs is None or after_obs is None:
        return False

    # 1. 前台包名或 Activity 发生变化
    if (before_obs.package_name or "") != (after_obs.package_name or ""):
        return True
    if (before_obs.activity_name or "") != (after_obs.activity_name or ""):
        return True

    # 2. 空后台状态（无前台包名且无 XML 树）绝不因动态截图判定为有实质进展
    after_pkg = str(after_obs.package_name or "").strip()
    is_empty_bg = (after_pkg in {"", "-", "None", "android"}) and not str(after_obs.xml or "").strip()
    if is_empty_bg:
        return False

    # 3. 非空屏状态下沿用既有 _same_observation 比对（支持 Canvas/地图/视频等纯视觉更新）
    return not _same_observation(before_obs, after_obs)


def _optional_step_index(value: Any) -> int | None:
    try:
        step_index = int(value)
    except (TypeError, ValueError):
        return None
    return step_index if step_index >= 0 else None


async def _await(value: Any) -> Any:
    return await value if inspect.isawaitable(value) else value


def _take_planner_metadata(planner: Planner) -> dict[str, Any]:
    take_metadata = getattr(planner, "take_metadata", None)
    if not callable(take_metadata):
        return {}
    value = take_metadata()
    return dict(value) if isinstance(value, dict) else {}


def _merge_planner_diagnostics(
    diagnostics: dict[str, Any],
    metadata: dict[str, Any],
) -> None:
    rejected_calls = metadata.get("rejected_tool_calls")
    if not isinstance(rejected_calls, list):
        return
    accumulated = diagnostics.setdefault("rejected_tool_calls", [])
    for value in rejected_calls:
        if not isinstance(value, dict):
            continue
        error = str(value.get("error") or "").strip()
        if not error:
            continue
        item: dict[str, Any] = {"error": error}
        try:
            turn_index = int(value.get("turn_index"))
        except (TypeError, ValueError):
            turn_index = -1
        if turn_index >= 0:
            item["turn_index"] = turn_index
        tool = str(value.get("tool") or "").strip()
        if tool:
            item["tool"] = tool
        if "arguments" in value:
            item["arguments"] = value.get("arguments")
        if item not in accumulated:
            accumulated.append(item)


def _take_llm_usage(component: Any) -> dict[str, Any] | None:
    take_usage = getattr(component, "take_usage", None)
    if not callable(take_usage):
        return None
    value = take_usage()
    return dict(value) if isinstance(value, dict) else {}


def _usage_model_calls(
    usage: dict[str, Any] | None,
    *,
    fallback: int,
) -> int:
    if usage is None:
        return max(0, int(fallback))
    try:
        return max(0, int(usage.get("model_calls") or 0))
    except (TypeError, ValueError):
        return max(0, int(fallback))


async def _request_input(host: Host, question: str) -> str:
    request_input = getattr(host, "request_input", None)
    if not callable(request_input):
        raise RuntimeError("request_input_not_supported")
    return str(await _await(request_input(question)))


def _extract_visible_resource_ids(xml_str: str | None) -> list[str]:
    if not xml_str or not isinstance(xml_str, str):
        return []
    try:
        root = ET.fromstring(xml_str)
    except Exception:
        return []
    visible_ids: list[str] = []
    for node in iter_visible_nodes(root):
        rid = str(node.attrib.get("resource-id") or "").strip()
        if rid:
            visible_ids.append(rid)
    return visible_ids


def _resolve_step_app_experience_ids(
    selected_experiences: list[dict[str, Any]],
    planned: Action,
    step: Any,
    before_observation: Observation,
) -> list[str]:
    if not selected_experiences:
        return []

    attributed_ids: list[str] = []
    before_xml = before_observation.xml or ""

    before_root: ET.Element | None = None
    if before_xml:
        try:
            before_root = ET.fromstring(before_xml)
        except Exception:
            before_root = None

    planned_tool = str(planned.tool or "").strip()
    planned_args = planned.args if isinstance(planned.args, dict) else {}
    planned_res_id = str(planned_args.get("node_resource_id") or "").strip()
    planned_node_id = str(planned_args.get("node_id") or "").strip()

    step_action = step.get("action") if isinstance(step, dict) else getattr(step, "action", None)
    if isinstance(step_action, dict):
        step_action_tool = str(step_action.get("tool") or step_action.get("action_type") or "").strip()
        raw_args = step_action.get("args") or {}
        step_action_args = raw_args if isinstance(raw_args, dict) else {}
    elif step_action is not None:
        step_action_tool = str(getattr(step_action, "tool", None) or getattr(step_action, "action_type", "") or "").strip()
        raw_args = getattr(step_action, "args", {})
        step_action_args = raw_args if isinstance(raw_args, dict) else {}
    else:
        step_action_tool = planned_tool
        step_action_args = {}

    step_detail = step.get("detail") if isinstance(step, dict) else getattr(step, "detail", None)
    sg = step_detail.get("semantic_grounding") if isinstance(step_detail, dict) else {}
    if not isinstance(sg, dict):
        metadata = step.get("metadata") if isinstance(step, dict) else getattr(step, "metadata", {})
        if isinstance(metadata, dict):
            transfer = metadata.get("transfer") if isinstance(metadata.get("transfer"), dict) else {}
            sg = transfer.get("semantic_grounding") or metadata.get("semantic_grounding") or {}
    if not isinstance(sg, dict):
        sg = {}

    grounded_res_id = str(
        sg.get("node_resource_id")
        or step_action_args.get("node_resource_id")
        or ""
    ).strip()
    grounded_node_id = str(
        sg.get("node_id")
        or step_action_args.get("node_id")
        or ""
    ).strip()
    grounded_class = str(
        sg.get("class")
        or sg.get("target_class")
        or step_action_args.get("class")
        or ""
    ).strip()

    act_res = step.get("result") if isinstance(step, dict) else getattr(step, "result", None)
    act_extra = act_res.get("extra") if isinstance(act_res, dict) else getattr(act_res, "extra", None)
    diag = act_extra.get("diagnostics") if isinstance(act_extra, dict) else {}
    if not isinstance(diag, dict):
        diag = (
            (step.get("metadata") or {}).get("action_result", {}).get("diagnostics")
            if isinstance(step, dict)
            else (getattr(step, "metadata", {}) or {}).get("action_result", {}).get("diagnostics", {})
        )
    if not isinstance(diag, dict):
        diag = {}
    host_res_id = str(diag.get("target_resource_id") or "").strip()
    host_class = str(diag.get("target_class") or "").strip()
    for exp in selected_experiences:
        exp_id = exp.get("id")
        if not exp_id:
            continue
        rule = exp.get("rule") if isinstance(exp.get("rule"), dict) else exp
        rule_action = str(rule.get("action") or "").strip()
        rule_res_id = str(rule.get("target_resource_id") or "").strip()
        rule_class = str(rule.get("target_class") or "").strip()

        if rule_action != planned_tool and rule_action != step_action_tool:
            continue

        matching_node: ET.Element | None = None
        if before_root is not None and rule_res_id:
            for node in iter_visible_nodes(before_root):
                if node.attrib.get("resource-id") == rule_res_id:
                    matching_node = node
                    break

        actual_xml_class = matching_node.attrib.get("class") if matching_node is not None else ""

        if getattr(step, "success", False) is True:
            matched_res = (
                rule_res_id == grounded_res_id
                or rule_res_id == host_res_id
                or rule_res_id == planned_res_id
            )
            if not matched_res:
                continue

            if rule_class:
                if host_class and host_class != rule_class:
                    continue
                if actual_xml_class and actual_xml_class != rule_class:
                    continue

            attributed_ids.append(exp_id)
        else:
            step_err = str(
                (step.get("error") if isinstance(step, dict) else getattr(step, "error", ""))
                or (act_res.get("error") if isinstance(act_res, dict) else getattr(act_res, "error", ""))
                or ""
            )
            diag_reason = str(diag.get("reason") or diag.get("error") or "").strip()
            diag_guard = str(diag.get("guard_status") or "").strip()

            is_attributable_err = any(
                ae in step_err or ae in diag_reason or ae in diag_guard
                for ae in (
                    "target_selector_missing",
                    "target_selector_mismatch",
                    "target_ambiguous",
                    "action_target_missing",
                    "action_target_ambiguous",
                    "target_identity_mismatch",
                    "no_progress_detected",
                    "no_effect",
                )
            )
            if not is_attributable_err:
                continue

            matched_target = False
            effective_node_id = grounded_node_id or planned_node_id
            if rule_res_id and (
                rule_res_id == grounded_res_id
                or rule_res_id == host_res_id
                or rule_res_id == planned_res_id
            ):
                matched_target = True
            elif effective_node_id and before_root is not None:
                for node in iter_visible_nodes(before_root):
                    nid = node.attrib.get("id") or node.attrib.get("node_id")
                    if nid == effective_node_id and node.attrib.get("resource-id") == rule_res_id:
                        matched_target = True
                        break

            if matched_target:
                if rule_class:
                    if host_class and host_class != rule_class:
                        continue
                    if actual_xml_class and actual_xml_class != rule_class:
                        continue
                    if grounded_class and grounded_class != rule_class:
                        continue
                attributed_ids.append(exp_id)

    return attributed_ids
