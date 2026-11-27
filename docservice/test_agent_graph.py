from uuid import uuid4

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from agent_graph import build_graph, validate_graph


def node(key: str, *, approval: bool = False):
    return {"id": key, "agentId": str(uuid4()), "requiresApproval": approval}


def state(definition):
    return {
        "user_id": str(uuid4()),
        "project_id": str(uuid4()),
        "workflow_execution_id": str(uuid4()),
        "workflow_id": str(uuid4()),
        "workflow_name": "test workflow",
        "definition": definition,
        "input": {"topic": "photosynthesis"},
        "outputs": {},
        "current_node": None,
        "approval_decision": None,
    }


def test_validate_graph_rejects_cycles():
    definition = {"nodes": [node("one"), node("two")], "edges": [
        {"source": "one", "target": "two"}, {"source": "two", "target": "one"},
    ]}
    with pytest.raises(ValueError, match="cycle"):
        validate_graph(definition)


def test_graph_stops_at_real_approval_interrupt_then_resumes():
    first, second = node("script", approval=True), node("mcq")
    definition = {"nodes": [first, second], "edges": [{"source": "script", "target": "mcq"}]}
    graph = build_graph(definition, "unused", "unused", InMemorySaver(),
                        runner=lambda selected, _state: {"result": selected["id"]})
    config = {"configurable": {"thread_id": str(uuid4())}}

    result = graph.invoke(state(definition), config=config)
    snapshot = graph.get_state(config)
    assert snapshot.next == ("approval__script",)
    assert result["outputs"]["script"] == {"result": "script"}

    result = graph.invoke(Command(resume={"decision": "APPROVE"}), config=config)
    assert result["outputs"]["mcq"] == {"result": "mcq"}
    assert not graph.get_state(config).next


def test_rejecting_approval_stops_downstream_agents():
    definition = {"nodes": [node("research", approval=True), node("script")], "edges": [{"source": "research", "target": "script"}]}
    calls = []
    graph = build_graph(definition, "unused", "unused", InMemorySaver(),
                        runner=lambda selected, _state: calls.append(selected["id"]) or {"result": selected["id"]})
    config = {"configurable": {"thread_id": str(uuid4())}}
    graph.invoke(state(definition), config=config)
    result = graph.invoke(Command(resume={"decision": "REJECT"}), config=config)
    assert calls == ["research"]
    assert result["approval_decision"] == "REJECT"
    assert "script" not in result["outputs"]


def test_conditional_routes_use_structured_output_without_code_execution():
    first, yes, no = node("research"), node("verified"), node("review")
    definition = {"nodes": [first, yes, no], "edges": [
        {"source": "research", "target": "verified", "condition": {"path": "decision", "equals": "ok"}},
        {"source": "research", "target": "review"},
    ]}
    graph = build_graph(definition, "unused", "unused", InMemorySaver(), runner=lambda selected, _state: {"decision": "ok"})
    result = graph.invoke(state(definition), config={"configurable": {"thread_id": str(uuid4())}})
    assert "verified" in result["outputs"]
    assert "review" not in result["outputs"]


def test_independent_branches_run_in_parallel_and_merge_outputs():
    left, right = node("analysis"), node("quiz")
    definition = {"nodes": [left, right], "edges": []}
    calls = []
    graph = build_graph(definition, "unused", "unused", InMemorySaver(),
                        runner=lambda selected, _state: calls.append(selected["id"]) or {"node": selected["id"]})
    result = graph.invoke(state(definition), config={"configurable": {"thread_id": str(uuid4())}})
    assert set(calls) == {"analysis", "quiz"}
    assert set(result["outputs"]) == {"analysis", "quiz"}
