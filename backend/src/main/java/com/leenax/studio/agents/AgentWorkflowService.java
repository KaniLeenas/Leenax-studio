package com.leenax.studio.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.access.Actor;
import com.leenax.studio.access.ActorContext;
import com.leenax.studio.auth.CurrentUser;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.projects.ProjectMembers;
import com.leenax.studio.projects.ProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.leenax.studio.agents.WorkflowDtos.*;

@Service
public class AgentWorkflowService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentService agents;
    private final CurrentUser current;
    private final ProjectMembers members;
    private final ProjectService projects;
    private final AuditService audit;
    private final RestClient http;
    private final String docserviceUrl;
    private final String serviceToken;

    public AgentWorkflowService(JdbcTemplate jdbc, ObjectMapper mapper, AgentService agents, CurrentUser current,
                               ProjectMembers members, ProjectService projects, AuditService audit,
                               @Value("${leenax.docservice.url:http://localhost:7200}") String docserviceUrl,
                               @Value("${leenax.agents.service-token:}") String serviceToken) {
        this.jdbc = jdbc; this.mapper = mapper; this.agents = agents; this.current = current; this.members = members;
        this.projects = projects; this.audit = audit; this.docserviceUrl = docserviceUrl; this.serviceToken = serviceToken;
        this.http = RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
            setConnectTimeout(Duration.ofSeconds(5)); setReadTimeout(Duration.ofMinutes(20));
        }}).build();
    }

    public WorkflowPage list(int page, int size, UUID projectId, String search) {
        int p = Math.max(0, page), s = Math.min(100, Math.max(1, size));
        StringBuilder where = new StringBuilder(" where true"); List<Object> args = new ArrayList<>();
        if (projectId != null) { where.append(" and project_id=?"); args.add(projectId); }
        if (search != null && !search.isBlank()) { where.append(" and (name ilike ? or description ilike ?)"); args.add("%" + search.strip() + "%"); args.add("%" + search.strip() + "%"); }
        Long total = jdbc.queryForObject("select count(*) from ai_workflows" + where, Long.class, args.toArray());
        List<Object> paged = new ArrayList<>(args); paged.add(s); paged.add((long) p * s);
        List<WorkflowSummary> items = jdbc.query("select id,name,description,project_id,created_by,enabled,version,updated_at from ai_workflows" + where
                + " order by updated_at desc limit ? offset ?", (rs, i) -> summary(rs), paged.toArray());
        return new WorkflowPage(p, s, total == null ? 0 : total, items);
    }

    public WorkflowResponse get(UUID id) {
        WorkflowResponse w = jdbc.query("select id,name,description,project_id,created_by,definition::text,enabled,version,created_at,updated_at from ai_workflows where id=?",
                AgentWorkflowService::workflow, id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Workflow"));
        requireView(w);
        return w;
    }

    @Transactional
    public WorkflowResponse create(WorkflowRequest request) {
        requireManage(request.projectId());
        validateDefinition(request.definition(), request.projectId());
        UUID actor = current.id();
        UUID id = jdbc.queryForObject("insert into ai_workflows(name,description,project_id,created_by,definition,enabled) values (?,?,?,?,?::jsonb,?) returning id",
                UUID.class, request.name().strip(), blank(request.description()), request.projectId(), actor, json(request.definition()), request.enabled() == null || request.enabled());
        audit.record("AI_WORKFLOW_CREATED", "ai_workflow", id, Map.of("nodes", request.definition().path("nodes").size()));
        return get(id);
    }

    @Transactional
    public WorkflowResponse update(UUID id, WorkflowRequest request) {
        WorkflowResponse before = get(id);
        requireManage(before.projectId());
        if (!java.util.Objects.equals(before.projectId(), request.projectId())) requireManage(request.projectId());
        validateDefinition(request.definition(), request.projectId());
        int changed = jdbc.update("update ai_workflows set name=?,description=?,project_id=?,definition=?::jsonb,enabled=?,version=version+1,updated_at=now() where id=? and version=?",
                request.name().strip(), blank(request.description()), request.projectId(), json(request.definition()), request.enabled() == null || request.enabled(), id, before.version());
        if (changed == 0) throw new ApiException(ErrorCode.CONFLICT, "This workflow changed in another session. Reload and try again.");
        audit.record("AI_WORKFLOW_UPDATED", "ai_workflow", id, Map.of("version", before.version() + 1));
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        WorkflowResponse w = get(id); requireManage(w.projectId());
        jdbc.update("delete from ai_workflows where id=?", id);
        audit.record("AI_WORKFLOW_DELETED", "ai_workflow", id, Map.of());
    }

    @Transactional
    public WorkflowExecution execute(UUID id, JsonNode input) {
        WorkflowResponse w = get(id);
        if (!w.enabled()) throw new ApiException(ErrorCode.CONFLICT, "This workflow is disabled.");
        if (input == null || !input.isObject()) throw ApiException.badRequest("Workflow input must be a JSON object.");
        if (json(input).length() > 65_536) throw ApiException.badRequest("Workflow inputs must be under 64 KB; pass media references instead of file content.");
        validateDefinition(w.definition(), w.projectId());
        UUID user = current.id();
        Long active = jdbc.queryForObject("select count(*) from ai_workflow_executions where created_by=? and status in ('PENDING','QUEUED','RUNNING','WAITING_APPROVAL','PAUSED')", Long.class, user);
        if (active != null && active >= 2) throw new ApiException(ErrorCode.CONFLICT, "You have reached your active workflow limit. Wait for a run to finish before starting another.");
        UUID idExec = UUID.randomUUID();
        jdbc.update("""
                insert into ai_workflow_executions(id,workflow_id,workflow_name,project_id,created_by,status,graph_thread_id,input)
                values (?,?,?,?,?,'QUEUED',?,?::jsonb)
                """, idExec, w.id(), w.name(), w.projectId(), user, idExec.toString(), json(input));
        jdbc.update("update ai_workflow_executions set next_attempt_at=now() where id=?", idExec);
        audit.record("AI_WORKFLOW_EXECUTION_QUEUED", "ai_workflow_execution", idExec, Map.of("workflowId", id.toString()));
        return execution(idExec);
    }

    public WorkflowExecutionPage executions(int page, int size, UUID workflowId, UUID projectId, String status) {
        int p = Math.max(0, page), s = Math.min(100, Math.max(1, size));
        StringBuilder where = new StringBuilder(" where true"); List<Object> args = new ArrayList<>();
        if (projectId != null) { where.append(" and project_id=?"); args.add(projectId); }
        if (workflowId != null) { where.append(" and workflow_id=?"); args.add(workflowId); }
        if (status != null && !status.isBlank()) { where.append(" and status=?"); args.add(status.toUpperCase()); }
        if (!current.isAdmin()) { where.append(" and created_by=?"); args.add(current.id()); }
        Long total = jdbc.queryForObject("select count(*) from ai_workflow_executions" + where, Long.class, args.toArray());
        List<Object> paged = new ArrayList<>(args); paged.add(s); paged.add((long) p * s);
        List<WorkflowExecution> items = jdbc.query("select * from ai_workflow_executions" + where + " order by created_at desc limit ? offset ?",
                AgentWorkflowService::executionRow, paged.toArray());
        return new WorkflowExecutionPage(p, s, total == null ? 0 : total, items);
    }

    public WorkflowExecution execution(UUID id) {
        WorkflowExecution e = jdbc.query("select * from ai_workflow_executions where id=?", AgentWorkflowService::executionRow, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Workflow execution"));
        if (!current.isAdmin() && !current.id().equals(e.createdBy())) throw new ApiException(ErrorCode.FORBIDDEN, "Only the person who started this workflow can inspect it.");
        return e;
    }

    @Transactional
    public WorkflowExecution approve(UUID id, boolean approved) {
        WorkflowExecution e = execution(id);
        if (!"WAITING_APPROVAL".equals(e.status())) throw ApiException.badRequest("This workflow is not waiting for approval.");
        String decision = approved ? "APPROVE" : "REJECT";
        jdbc.update("update ai_workflow_executions set status='QUEUED',resume_decision=?,next_attempt_at=now(),locked_until=null where id=?", decision, id);
        audit.record(approved ? "AI_WORKFLOW_APPROVED" : "AI_WORKFLOW_REJECTED", "ai_workflow_execution", id, Map.of("node", e.currentNode() == null ? "" : e.currentNode()));
        return execution(id);
    }

    @Transactional
    public WorkflowExecution pause(UUID id) {
        WorkflowExecution e = execution(id);
        if (!"WAITING_APPROVAL".equals(e.status())) throw ApiException.badRequest("Workflows can be paused at a human approval checkpoint.");
        jdbc.update("update ai_workflow_executions set status='PAUSED' where id=?", id);
        return execution(id);
    }

    @Transactional
    public WorkflowExecution resume(UUID id) {
        WorkflowExecution e = execution(id);
        if (!"PAUSED".equals(e.status())) throw ApiException.badRequest("Only a paused workflow can be resumed.");
        jdbc.update("update ai_workflow_executions set status='WAITING_APPROVAL' where id=?", id);
        return execution(id);
    }

    @Transactional
    public WorkflowExecution cancel(UUID id) {
        WorkflowExecution e = execution(id);
        if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(e.status())) return e;
        jdbc.update("update ai_workflow_executions set status='CANCELLED',completed_at=now(),locked_until=null where id=?", id);
        return execution(id);
    }

    @Transactional
    public WorkflowExecution retry(UUID id) {
        WorkflowExecution e = execution(id);
        if (!"FAILED".equals(e.status()) || e.workflowId() == null) throw ApiException.badRequest("Only failed runs with an available workflow can be retried.");
        return execute(e.workflowId(), e.input());
    }

    /** Worker lease. The backend queue is durable; graph checkpoints live in PostgreSQL via LangGraph. */
    @Transactional
    public List<UUID> claim(int limit) {
        return jdbc.queryForList("""
                update ai_workflow_executions set status='RUNNING',locked_until=now()+interval '25 minutes',started_at=coalesce(started_at,now())
                 where id in (select id from ai_workflow_executions where status in ('QUEUED','RUNNING') and next_attempt_at<=now()
                    and (locked_until is null or locked_until<now()) order by created_at limit ? for update skip locked)
                returning id
                """, UUID.class, limit);
    }

    public UUID owner(UUID id) { return jdbc.queryForList("select created_by from ai_workflow_executions where id=?", UUID.class, id).stream().findFirst().orElse(null); }

    public void requireInternalActive(UUID executionId, UUID userId, UUID projectId) {
        boolean active = ActorContext.call(Actor.worker(userId), () -> jdbc.queryForList("""
                select id from ai_workflow_executions where id=? and created_by=? and project_id is not distinct from ? and status='RUNNING'
                """, UUID.class, executionId, userId, projectId).size() == 1);
        if (!active) throw new ApiException(ErrorCode.CONFLICT, "This workflow is no longer accepting agent tasks.");
    }

    public void process(UUID id) {
        WorkflowExecution e = jdbc.query("select * from ai_workflow_executions where id=?", AgentWorkflowService::executionRow, id).stream().findFirst().orElse(null);
        if (e == null || "CANCELLED".equals(e.status())) return;
        WorkflowResponse w = jdbc.query("select id,name,description,project_id,created_by,definition::text,enabled,version,created_at,updated_at from ai_workflows where id=?",
                AgentWorkflowService::workflow, e.workflowId()).stream().findFirst().orElse(null);
        if (w == null) { fail(id, "The workflow configuration no longer exists."); return; }
        if (serviceToken == null || serviceToken.isBlank()) { fail(id, "The internal agent service is not configured."); return; }
        try {
            GraphRequest request = new GraphRequest(id, w.id(), w.name(), e.createdBy(), w.projectId(), e.id().toString(), w.definition(), e.input(), getDecision(id));
            GraphResponse response = http.post().uri(docserviceUrl + "/agent-workflows/run")
                    .header("X-Leenax-Agent-Service", serviceToken).body(request).retrieve().body(GraphResponse.class);
            if (response == null) throw new IllegalStateException("Agent service returned an empty response");
            if ("WAITING_APPROVAL".equals(response.status())) {
                jdbc.update("update ai_workflow_executions set status='WAITING_APPROVAL',current_node=?,output=?::jsonb,locked_until=null,resume_decision=null where id=? and status='RUNNING'",
                        response.currentNode(), json(response.output()), id);
            } else if ("COMPLETED".equals(response.status())) {
                jdbc.update("update ai_workflow_executions set status='COMPLETED',output=?::jsonb,current_node=null,completed_at=now(),locked_until=null,resume_decision=null where id=? and status='RUNNING'",
                        json(response.output()), id);
            } else if ("CANCELLED".equals(response.status())) {
                jdbc.update("update ai_workflow_executions set status='CANCELLED',completed_at=now(),locked_until=null where id=? and status='RUNNING'", id);
            } else fail(id, safe(response.error() == null ? "Workflow execution failed." : response.error()));
        } catch (Exception ex) {
            fail(id, safe(ex.getMessage() == null ? "Workflow execution failed." : ex.getMessage()));
        }
    }

    private String getDecision(UUID id) { return jdbc.queryForList("select resume_decision from ai_workflow_executions where id=?", String.class, id).stream().findFirst().orElse(null); }
    private void fail(UUID id, String message) {
        jdbc.update("update ai_workflow_executions set status='FAILED',error_message=?,completed_at=now(),locked_until=null where id=? and status='RUNNING'", safe(message), id);
    }

    private void validateDefinition(JsonNode definition, UUID projectId) {
        if (definition == null || !definition.isObject()) throw ApiException.badRequest("Workflow definition must be an object.");
        JsonNode nodes = definition.path("nodes"), edges = definition.path("edges");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 32) throw ApiException.badRequest("A workflow needs between 1 and 32 agent nodes.");
        if (!edges.isArray() || edges.size() > 64) throw ApiException.badRequest("A workflow can have at most 64 connections.");
        Map<String, UUID> ids = new HashMap<>();
        for (JsonNode node : nodes) {
            String key = node.path("id").asText("");
            if (!key.matches("[A-Za-z0-9_-]{1,64}") || ids.containsKey(key)) throw ApiException.badRequest("Workflow node ids must be unique, short identifiers.");
            UUID agentId;
            try { agentId = UUID.fromString(node.path("agentId").asText()); } catch (Exception ex) { throw ApiException.badRequest("Every workflow node must select an agent."); }
            AgentDtos.AgentResponse agent = agents.get(agentId);
            agents.requireCanExecute(agent);
            agents.assertModelAllowed(agent.accountId(), agent.model());
            if (!java.util.Objects.equals(agent.projectId(), projectId)) throw ApiException.badRequest("A workflow can only use agents from the same project scope.");
            ids.put(key, agentId);
        }
        Map<String, Integer> degree = new HashMap<>(); Map<String, List<String>> out = new HashMap<>();
        ids.keySet().forEach(k -> { degree.put(k, 0); out.put(k, new ArrayList<>()); });
        Set<String> edgeIds = new HashSet<>();
        for (JsonNode edge : edges) {
            String from = edge.path("source").asText(""), to = edge.path("target").asText("");
            if (!ids.containsKey(from) || !ids.containsKey(to) || from.equals(to) || !edgeIds.add(from + "->" + to)) throw ApiException.badRequest("Workflow connections must connect two different existing nodes once.");
            JsonNode condition = edge.path("condition");
            if (!condition.isMissingNode() && !condition.isNull() && (!condition.isObject() || !condition.path("path").isTextual() || !condition.has("equals"))) {
                throw ApiException.badRequest("Conditional connections need a JSON path and an equals value.");
            }
            out.get(from).add(to); degree.compute(to, (k, v) -> v + 1);
        }
        ArrayDeque<String> ready = new ArrayDeque<>(); degree.forEach((k, v) -> { if (v == 0) ready.add(k); });
        int visited = 0;
        while (!ready.isEmpty()) { String node = ready.remove(); visited++; for (String next : out.get(node)) degree.compute(next, (k, v) -> { int n = v - 1; if (n == 0) ready.add(k); return n; }); }
        if (visited != ids.size()) throw ApiException.badRequest("Workflow connections contain a cycle.");
    }

    private void requireManage(UUID projectId) {
        if (projectId == null) { current.requireAdmin(); return; }
        projects.get(projectId);
        if (!current.isAdmin() && members.role(projectId, current.id()).filter(r -> r == ProjectMembers.Role.OWNER).isEmpty()) throw new ApiException(ErrorCode.FORBIDDEN, "Only project owners or administrators can manage workflows.");
    }
    private void requireView(WorkflowResponse workflow) {
        if (workflow.projectId() == null) { if (!current.isAdmin() && !current.id().equals(workflow.createdBy())) throw new ApiException(ErrorCode.FORBIDDEN, "You cannot view this workflow."); }
        else projects.get(workflow.projectId());
    }
    private static WorkflowSummary summary(ResultSet rs) throws SQLException { return new WorkflowSummary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getBoolean(6), rs.getInt(7), instant(rs.getTimestamp(8))); }
    private static WorkflowResponse workflow(ResultSet rs, int row) throws SQLException { return new WorkflowResponse(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), parse(rs.getString(6)), rs.getBoolean(7), rs.getInt(8), instant(rs.getTimestamp(9)), instant(rs.getTimestamp(10))); }
    private static WorkflowExecution executionRow(ResultSet rs, int row) throws SQLException { return new WorkflowExecution(rs.getObject("id", UUID.class), rs.getObject("workflow_id", UUID.class), rs.getString("workflow_name"), rs.getObject("project_id", UUID.class), rs.getObject("created_by", UUID.class), rs.getString("status"), rs.getString("current_node"), parse(rs.getString("input")), parse(rs.getString("output")), rs.getString("error_message"), instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")), instant(rs.getTimestamp("created_at"))); }
    private String json(Object o) { try { return mapper.writeValueAsString(o); } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON", e); } }
    private static JsonNode parse(String text) { try { return text == null ? null : new ObjectMapper().readTree(text); } catch (Exception e) { return null; } }
    private static String blank(String v) { return v == null ? "" : v.strip(); }
    private static String safe(String v) { String clean = v.replaceAll("(?i)(api[_ -]?key|token|secret)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[redacted]"); return clean.length() > 900 ? clean.substring(0, 900) : clean; }
    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
