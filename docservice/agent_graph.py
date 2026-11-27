"""Private LangGraph workflow runner for Leenax Studio.

The primary Spring backend owns identity, permissions, provider accounts and job
records. This service receives only a short-lived private service token and
scoped IDs; model calls are delegated back to the backend so credentials never
leave its encrypted provider account system.
"""
from __future__ import annotations

import os
os.environ.setdefault("LANGGRAPH_STRICT_MSGPACK", "true")

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Annotated, Any, TypedDict
from uuid import UUID

from fastapi import APIRouter, Header, HTTPException
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from pydantic import BaseModel, ConfigDict, Field
from psycopg.conninfo import make_conninfo

router = APIRouter(prefix="/agent-workflows", tags=["agent workflows"])


class RunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    workflowExecutionId: UUID
    workflowId: UUID
    workflowName: str = Field(min_length=1, max_length=160)
    userId: UUID
    projectId: UUID | None = None
    threadId: str = Field(min_length=1, max_length=120)
    definition: dict[str, Any]
    input: dict[str, Any]
    decision: str | None = Field(default=None, pattern="^(APPROVE|REJECT)$")


class WorkflowState(TypedDict, total=False):
    user_id: str
    project_id: str | None
    workflow_execution_id: str
    workflow_id: str
    workflow_name: str
    definition: dict[str, Any]
    input: dict[str, Any]
    outputs: Annotated[dict[str, Any], lambda left, right: {**(left or {}), **(right or {})}]
    current_node: Annotated[str | None, lambda _left, right: right]
    approval_decision: str | None
    cancelled: bool


def validate_graph(definition: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    nodes = definition.get("nodes")
    edges = definition.get("edges")
    if not isinstance(nodes, list) or not 1 <= len(nodes) <= 32:
        raise ValueError("A workflow needs between 1 and 32 agent nodes.")
    if not isinstance(edges, list) or len(edges) > 64:
        raise ValueError("A workflow can have at most 64 connections.")
    ids: set[str] = set()
    for node in nodes:
        if not isinstance(node, dict):
            raise ValueError("Workflow nodes must be objects.")
        node_id = node.get("id")
        agent_id = node.get("agentId")
        if not isinstance(node_id, str) or not node_id or len(node_id) > 64 or not all(c.isalnum() or c in "_-" for c in node_id):
            raise ValueError("Workflow node ids must be short alphanumeric identifiers.")
        if node_id in ids:
            raise ValueError("Workflow node ids must be unique.")
        try:
            UUID(str(agent_id))
        except (ValueError, TypeError):
            raise ValueError("Every workflow node must refer to a saved agent.") from None
        ids.add(node_id)
    indegree = {key: 0 for key in ids}
    outgoing: dict[str, list[str]] = {key: [] for key in ids}
    seen_edges: set[tuple[str, str]] = set()
    for edge in edges:
        if not isinstance(edge, dict):
            raise ValueError("Workflow connections must be objects.")
        source, target = edge.get("source"), edge.get("target")
        pair = (source, target)
        if source not in ids or target not in ids or source == target or pair in seen_edges:
            raise ValueError("Workflow connections must join two different existing nodes once.")
        seen_edges.add(pair)
        condition = edge.get("condition")
        if condition is not None and (not isinstance(condition, dict) or not isinstance(condition.get("path"), str) or "equals" not in condition):
            raise ValueError("Conditional connections need a JSON path and an equals value.")
        outgoing[source].append(target)
        indegree[target] += 1
    ready = [node for node, degree in indegree.items() if degree == 0]
    visited = 0
    while ready:
        node = ready.pop()
        visited += 1
        for target in outgoing[node]:
            indegree[target] -= 1
            if indegree[target] == 0:
                ready.append(target)
    if visited != len(ids):
        raise ValueError("Workflow connections contain a cycle.")
    return nodes, edges


def _conninfo() -> str:
    direct = os.getenv("AGENT_DATABASE_URL")
    if direct:
        return direct
    return make_conninfo(
        host=os.getenv("PGHOST", "postgres"),
        port=os.getenv("PGPORT", "5432"),
        dbname=os.getenv("PGDATABASE", "leenax"),
        user=os.getenv("PGUSER", "leenax"),
        password=os.getenv("PGPASSWORD", ""),
        connect_timeout=5,
        application_name="leenax-langgraph",
    )


def _service_auth(supplied: str | None) -> tuple[str, str]:
    expected = os.getenv("AGENT_SERVICE_TOKEN", "")
    if not expected or supplied is None or not __import__("hmac").compare_digest(expected, supplied):
        raise HTTPException(status_code=401, detail="Agent service authentication failed.")
    backend = os.getenv("BACKEND_INTERNAL_URL", "http://backend:8080").rstrip("/")
    return expected, backend


def _request_json(url: str, method: str, token: str, body: dict[str, Any] | None = None, timeout: float = 20) -> dict[str, Any]:
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Accept": "application/json",
        "X-Leenax-Agent-Service": token,
        **({"Content-Type": "application/json"} if data is not None else {}),
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read(1_000_001)
            if len(raw) > 1_000_000:
                raise RuntimeError("The backend response exceeded the allowed size.")
            return json.loads(raw or b"{}")
    except urllib.error.HTTPError as exc:
        if exc.code in (401, 403, 404, 409):
            raise RuntimeError("The backend denied this workflow agent operation.") from None
        raise RuntimeError(f"The backend agent service returned HTTP {exc.code}.") from None
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        raise RuntimeError("The backend agent service could not be reached or returned invalid JSON.") from None


def _json_path(obj: Any, path: str) -> Any:
    value = obj
    for part in path.split(".") if path else []:
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value


def _mapped_input(node: dict[str, Any], state: WorkflowState) -> dict[str, Any]:
    mapping = node.get("inputMapping")
    if mapping is None:
        return state.get("input", {})
    if not isinstance(mapping, dict) or len(mapping) > 80:
        raise RuntimeError("This workflow node has an invalid input mapping.")
    output: dict[str, Any] = {}
    for key, source in mapping.items():
        if not isinstance(key, str) or len(key) > 80:
            raise RuntimeError("This workflow node has an invalid input mapping.")
        if not isinstance(source, str):
            output[key] = source
        elif source.startswith("$input."):
            output[key] = _json_path(state.get("input", {}), source[7:])
        elif source.startswith("$nodes.") and "." in source[7:]:
            node_id, path = source[7:].split(".", 1)
            output[key] = _json_path(state.get("outputs", {}).get(node_id, {}), path)
        else:
            raise RuntimeError("Input mappings may only read from $input or $nodes paths.")
    return output


def _agent_call(node: dict[str, Any], state: WorkflowState, token: str, backend: str) -> dict[str, Any]:
    execution_id = state["workflow_execution_id"]
    user_id = state["user_id"]
    project_id = state.get("project_id")
    base = f"{backend}/api/v1/internal/agents/workflows/{execution_id}"
    request = {
        "userId": user_id,
        "projectId": project_id,
        "workflowExecutionId": execution_id,
        "input": _mapped_input(node, state),
    }
    started = _request_json(f"{base}/run/{node['agentId']}", "POST", token, request, timeout=20)
    agent_execution_id = started.get("executionId")
    timeout_seconds = max(5, min(int(started.get("timeoutSeconds", 90)), 900))
    if not agent_execution_id:
        raise RuntimeError("The backend did not queue the agent step.")
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        current = _request_json(
            f"{base}/runs/{agent_execution_id}?" + urllib.parse.urlencode({"userId": user_id, "projectId": project_id or ""}),
            "GET", token, timeout=20,
        )
        status = current.get("status")
        if status == "COMPLETED":
            result = current.get("output")
            if not isinstance(result, dict):
                raise RuntimeError("The agent step returned no structured output.")
            if len(json.dumps(result, ensure_ascii=False)) > 200_000:
                raise RuntimeError("Agent output is too large for workflow memory; save large content as a project asset and return its reference.")
            return result
        if status in ("FAILED", "CANCELLED"):
            raise RuntimeError(current.get("error") or f"The agent step {status.lower()}.")
        time.sleep(1.5)
    raise RuntimeError("The agent step timed out.")


def _approval_route(state: WorkflowState, node_id: str, outgoing: list[dict[str, Any]]) -> str | list[str]:
    decision = state.get("approval_decision")
    if decision == "REJECT":
        return "__end__"
    if decision != "APPROVE":
        raise RuntimeError("Approval checkpoint resumed without an explicit decision.")
    return _route_for_outputs(state, node_id, outgoing)


def _route_for_outputs(state: WorkflowState, node_id: str, outgoing: list[dict[str, Any]]) -> str | list[str]:
    edges = [edge for edge in outgoing if edge.get("source") == node_id]
    conditional = [edge for edge in edges if edge.get("condition") is not None]
    if not conditional:
        targets = [edge["target"] for edge in edges]
        return targets if targets else "__end__"
    output = state.get("outputs", {}).get(node_id, {})
    for edge in conditional:
        condition = edge["condition"]
        if _json_path(output, condition["path"]) == condition["equals"]:
            return edge["target"]
    defaults = [edge["target"] for edge in edges if edge.get("condition") is None]
    return defaults if defaults else "__end__"


def build_graph(definition: dict[str, Any], token: str, backend: str, checkpointer: Any, runner=None):
    nodes, edges = validate_graph(definition)
    agent_runner = runner or (lambda selected, state: _agent_call(selected, state, token, backend))
    builder = StateGraph(WorkflowState)
    node_ids = {node["id"] for node in nodes}
    gate_ids = {node_id: f"approval__{node_id}" for node_id in node_ids}

    for node in nodes:
        node_id = node["id"]
        def run(state: WorkflowState, selected: dict[str, Any] = node) -> dict[str, Any]:
            result = agent_runner(selected, state)
            return {"outputs": {selected["id"]: result}, "current_node": selected["id"]}
        builder.add_node(node_id, run)
        if node.get("requiresApproval") is True:
            gate = gate_ids[node_id]
            def approval(state: WorkflowState, selected_id: str = node_id) -> dict[str, Any]:
                decision = interrupt({
                    "type": "agent_approval",
                    "nodeId": selected_id,
                    "workflowExecutionId": state["workflow_execution_id"],
                    "output": state.get("outputs", {}).get(selected_id, {}),
                })
                action = decision.get("decision") if isinstance(decision, dict) else decision
                action = str(action).upper()
                if action not in ("APPROVE", "REJECT"):
                    raise RuntimeError("Approval decision must be APPROVE or REJECT.")
                return {"approval_decision": action, "current_node": selected_id}
            builder.add_node(gate, approval)

    roots = [node["id"] for node in nodes if not any(e.get("target") == node["id"] for e in edges)]
    for node_id in roots:
        builder.add_edge(START, node_id)

    by_source: dict[str, list[dict[str, Any]]] = {}
    for edge in edges:
        by_source.setdefault(edge["source"], []).append(edge)
    for node in nodes:
        node_id = node["id"]
        source = gate_ids[node_id] if node.get("requiresApproval") is True else node_id
        outgoing = by_source.get(node_id, [])
        if node.get("requiresApproval") is True:
            # Route through an interrupt node after the agent has produced its
            # output. The persisted graph can then resume from this checkpoint.
            builder.add_edge(node_id, source)
            route_map: dict[str, Any] = {"__end__": END}
            for edge in outgoing:
                route_map[edge["target"]] = edge["target"]
            builder.add_conditional_edges(source, lambda state, n=node_id, e=outgoing: _approval_route(state, n, e), route_map)
        elif not outgoing:
            builder.add_edge(source, END)
        elif any(edge.get("condition") is not None for edge in outgoing):
            route_map = {"__end__": END}
            for edge in outgoing:
                route_map[edge["target"]] = edge["target"]
            builder.add_conditional_edges(source, lambda state, n=node_id, e=outgoing: _route_for_outputs(state, n, e), route_map)
        else:
            for edge in outgoing:
                builder.add_edge(source, edge["target"])
    return builder.compile(checkpointer=checkpointer)


@router.post("/run")
def run_workflow(body: RunRequest, x_leenax_agent_service: str | None = Header(default=None)):
    token, backend = _service_auth(x_leenax_agent_service)
    try:
        nodes, _ = validate_graph(body.definition)
        if len(json.dumps(body.input, ensure_ascii=False)) > 65_536:
            raise ValueError("Workflow inputs must be under 64 KB; pass media references instead of file content.")
        with PostgresSaver.from_conn_string(_conninfo()) as checkpointer:
            checkpointer.setup()
            graph = build_graph(body.definition, token, backend, checkpointer)
            config = {"configurable": {"thread_id": body.threadId}}
            if body.decision:
                result = graph.invoke(Command(resume={"decision": body.decision}), config=config)
            else:
                result = graph.invoke({
                    "user_id": str(body.userId),
                    "project_id": str(body.projectId) if body.projectId else None,
                    "workflow_execution_id": str(body.workflowExecutionId),
                    "workflow_id": str(body.workflowId),
                    "workflow_name": body.workflowName,
                    "definition": body.definition,
                    "input": body.input,
                    "outputs": {},
                    "current_node": None,
                    "approval_decision": None,
                }, config=config)
            snapshot = graph.get_state(config)
        waiting = bool(snapshot and snapshot.next)
        state = snapshot.values if snapshot else result
        if waiting:
            return {"status": "WAITING_APPROVAL", "currentNode": state.get("current_node"), "output": {"outputs": state.get("outputs", {}), "pendingNode": state.get("current_node")}}
        if state.get("approval_decision") == "REJECT":
            return {"status": "COMPLETED", "currentNode": state.get("current_node"), "output": {"outputs": state.get("outputs", {}), "rejectedAt": state.get("current_node")}}
        return {"status": "COMPLETED", "currentNode": None, "output": {"outputs": state.get("outputs", {})}}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from None
    except HTTPException:
        raise
    except Exception:
        # Provider and callback details are persisted in the backend execution records.
        raise HTTPException(status_code=502, detail="The agent workflow could not be completed. Inspect its execution history for the failed step.") from None
