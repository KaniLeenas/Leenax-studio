package com.leenax.studio.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.provider.ProviderCode;
import com.leenax.studio.ai.text.TextAi;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.auth.CurrentUser;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.projects.ProjectMembers;
import com.leenax.studio.projects.ProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.leenax.studio.agents.AgentDtos.*;

@Service
public class AgentService {
    private static final Pattern CATEGORY = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    private static final Set<String> PERMISSIONS = Set.of("EXECUTE", "EDIT", "VIEW_LOGS", "VIEW_INPUT", "VIEW_OUTPUT");
    private static final String SELECT_AGENT = """
            select id,name,description,category,instructions,account_id,model,temperature,max_output_tokens,
                   input_schema::text,output_schema::text,tools::text,permissions::text,timeout_seconds,retry_count,
                   project_id,created_by,enabled,version,created_at,updated_at
              from ai_agents where id=? and deleted_at is null
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CurrentUser current;
    private final ProjectMembers members;
    private final ProjectService projects;
    private final AccountService accounts;
    private final TextAi textAi;
    private final AuditService audit;
    private final boolean allowPaidModels;

    public AgentService(JdbcTemplate jdbc, ObjectMapper mapper, CurrentUser current, ProjectMembers members,
                        ProjectService projects, AccountService accounts, TextAi textAi, AuditService audit,
                        @Value("${leenax.agents.allow-paid-models:false}") boolean allowPaidModels) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.current = current;
        this.members = members;
        this.projects = projects;
        this.accounts = accounts;
        this.textAi = textAi;
        this.audit = audit;
        this.allowPaidModels = allowPaidModels;
    }

    public AgentDashboard dashboard() {
        return jdbc.queryForObject("""
                select (select count(*) from ai_agents where deleted_at is null),
                       (select count(*) from ai_agents where deleted_at is null and enabled),
                       (select count(*) from ai_agents where deleted_at is null and not enabled),
                       (select count(*) from ai_agent_executions where status in ('RUNNING','WAITING_APPROVAL')),
                       (select count(*) from ai_agent_executions where status='FAILED'),
                       (select count(*) from ai_agent_executions where status='COMPLETED'),
                       (select count(*) from ai_agent_executions where status in ('PENDING','QUEUED'))
                """, (rs, n) -> new AgentDashboard(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getLong(7)));
    }

    public AgentPolicy policy() { return new AgentPolicy(allowPaidModels); }

    public AgentPage list(int page, int size, UUID projectId, String category, String search, Boolean enabled) {
        int p = Math.max(0, page), s = Math.min(100, Math.max(1, size));
        StringBuilder where = new StringBuilder(" where deleted_at is null");
        List<Object> args = new ArrayList<>();
        if (projectId != null) { where.append(" and project_id=?"); args.add(projectId); }
        if (category != null && !category.isBlank()) { where.append(" and category=?"); args.add(category.toUpperCase()); }
        if (search != null && !search.isBlank()) { where.append(" and (name ilike ? or description ilike ?)"); args.add("%" + search.strip() + "%"); args.add("%" + search.strip() + "%"); }
        if (enabled != null) { where.append(" and enabled=?"); args.add(enabled); }
        Long total = jdbc.queryForObject("select count(*) from ai_agents" + where, Long.class, args.toArray());
        List<Object> paged = new ArrayList<>(args); paged.add(s); paged.add((long) p * s);
        List<AgentSummary> rows = jdbc.query("select id,name,category,project_id,enabled,model,version,updated_at from ai_agents" + where
                        + " order by updated_at desc limit ? offset ?", (rs, i) -> new AgentSummary(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getBoolean(5), rs.getString(6), rs.getInt(7), instant(rs.getTimestamp(8))), paged.toArray());
        return new AgentPage(p, s, total == null ? 0 : total, rows);
    }

    public AgentResponse get(UUID id) {
        AgentResponse a = jdbc.query(SELECT_AGENT, AgentService::agent, id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Agent"));
        requireCanView(a);
        return a;
    }

    /** Background execution workers act through ActorContext and must not resolve an HTTP CurrentUser. */
    AgentResponse getForExecution(UUID id) {
        return jdbc.query(SELECT_AGENT, AgentService::agent, id).stream().findFirst().orElse(null);
    }

    @Transactional
    public AgentResponse create(AgentRequest request) {
        requireCanManage(request.projectId());
        validate(request);
        UUID owner = current.id();
        String input = json(request.inputSchema() == null ? mapper.createObjectNode().put("type", "object") : request.inputSchema());
        String output = json(request.outputSchema() == null ? mapper.createObjectNode().put("type", "object") : request.outputSchema());
        List<String> tools = cleanTools(request.tools());
        List<String> perms = cleanPermissions(request.permissions());
        UUID id = jdbc.queryForObject("""
                insert into ai_agents(name,description,category,instructions,account_id,model,temperature,max_output_tokens,
                    input_schema,output_schema,tools,permissions,timeout_seconds,retry_count,project_id,created_by,enabled)
                values (?,?,?,?,?,?,?, ?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?, ?,?) returning id
                """, UUID.class, request.name().strip(), blank(request.description()), request.category().toUpperCase(),
                request.instructions().strip(), request.accountId(), request.model().strip(), request.temperature(), request.maxOutputTokens(),
                input, output, json(tools), json(perms), request.timeoutSeconds(), request.retryCount(), request.projectId(), owner,
                request.enabled() == null || request.enabled());
        saveVersion(id, 1, agentConfig(request, tools, perms), owner);
        audit.record("AI_AGENT_CREATED", "ai_agent", id, Map.of("category", request.category().toUpperCase()));
        return get(id);
    }

    @Transactional
    public AgentResponse update(UUID id, AgentRequest request) {
        AgentResponse before = get(id);
        requireCanManage(before.projectId());
        if (!java.util.Objects.equals(before.projectId(), request.projectId())) requireCanManage(request.projectId());
        validate(request);
        List<String> tools = cleanTools(request.tools());
        List<String> perms = cleanPermissions(request.permissions());
        int version = before.version() + 1;
        int changed = jdbc.update("""
                update ai_agents set name=?,description=?,category=?,instructions=?,account_id=?,model=?,temperature=?,max_output_tokens=?,
                    input_schema=?::jsonb,output_schema=?::jsonb,tools=?::jsonb,permissions=?::jsonb,timeout_seconds=?,retry_count=?,
                    project_id=?,enabled=?,version=?,updated_at=now() where id=? and version=? and deleted_at is null
                """, request.name().strip(), blank(request.description()), request.category().toUpperCase(), request.instructions().strip(),
                request.accountId(), request.model().strip(), request.temperature(), request.maxOutputTokens(),
                json(request.inputSchema() == null ? mapper.createObjectNode().put("type", "object") : request.inputSchema()),
                json(request.outputSchema() == null ? mapper.createObjectNode().put("type", "object") : request.outputSchema()),
                json(tools), json(perms), request.timeoutSeconds(), request.retryCount(), request.projectId(),
                request.enabled() == null || request.enabled(), version, id, before.version());
        if (changed == 0) throw new ApiException(com.leenax.studio.common.ErrorCode.CONFLICT, "This agent changed in another session. Reload and try again.");
        saveVersion(id, version, agentConfig(request, tools, perms), current.id());
        audit.record("AI_AGENT_UPDATED", "ai_agent", id, Map.of("version", version));
        return get(id);
    }

    @Transactional
    public AgentResponse duplicate(UUID id, String name) {
        AgentResponse a = get(id);
        requireCanManage(a.projectId());
        AgentRequest copy = new AgentRequest(name == null || name.isBlank() ? a.name() + " copy" : name.strip(), a.description(), a.category(),
                a.instructions(), a.accountId(), a.model(), a.temperature(), a.maxOutputTokens(), a.inputSchema(), a.outputSchema(),
                a.tools(), a.permissions(), a.timeoutSeconds(), a.retryCount(), a.projectId(), false);
        return create(copy);
    }

    @Transactional
    public void restoreVersion(UUID id, int version) {
        AgentResponse currentAgent = get(id);
        requireCanManage(currentAgent.projectId());
        String config = jdbc.query("select configuration::text from ai_agent_versions where agent_id=? and version=?", (rs, i) -> rs.getString(1), id, version)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Agent version"));
        try {
            AgentRequest r = mapper.readValue(config, AgentRequest.class);
            update(id, r);
        } catch (Exception e) { throw new IllegalStateException("Saved agent version could not be restored", e); }
    }

    @Transactional
    public void delete(UUID id) {
        AgentResponse a = get(id);
        requireCanManage(a.projectId());
        jdbc.update("update ai_agents set deleted_at=now(), enabled=false, updated_at=now() where id=?", id);
        audit.record("AI_AGENT_DELETED", "ai_agent", id, Map.of());
    }

    @Transactional
    public AgentResponse setEnabled(UUID id, boolean enabled) {
        AgentResponse a = get(id);
        requireCanManage(a.projectId());
        jdbc.update("update ai_agents set enabled=?,updated_at=now() where id=?", enabled, id);
        audit.record(enabled ? "AI_AGENT_ENABLED" : "AI_AGENT_DISABLED", "ai_agent", id, Map.of());
        return get(id);
    }

    public List<AgentTemplate> templates() {
        return jdbc.query("select template_key,name,description,category,configuration::text from ai_agent_templates order by category,name",
                (rs, i) -> new AgentTemplate(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), parse(rs.getString(5))));
    }

    public List<AgentTool> tools() {
        return jdbc.query("select id,tool_key,name,description,input_schema::text,output_schema::text,permission,timeout_seconds,enabled from ai_tool_registry order by name",
                (rs, i) -> new AgentTool(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        parse(rs.getString(5)), parse(rs.getString(6)), rs.getString(7), rs.getInt(8), rs.getBoolean(9)));
    }

    public List<AgentVersion> versions(UUID id) {
        AgentResponse a = get(id);
        requireCanManage(a.projectId());
        return jdbc.query("select version,configuration::text,changed_by,created_at from ai_agent_versions where agent_id=? order by version desc",
                (rs, i) -> new AgentVersion(rs.getInt(1), parse(rs.getString(2)), rs.getObject(3, UUID.class), instant(rs.getTimestamp(4))), id);
    }

    public void requireCanExecute(AgentResponse agent) {
        if (!agent.enabled()) throw new ApiException(com.leenax.studio.common.ErrorCode.CONFLICT, "This agent is disabled.");
        if (!agent.permissions().contains("EXECUTE")) throw new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "This agent is not configured for execution.");
        if (agent.projectId() == null) {
            if (!current.isAdmin()) throw new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "Only an administrator can run a studio-wide agent.");
        } else {
            projects.get(agent.projectId());
            ProjectMembers.Role role = members.role(agent.projectId(), current.id()).orElse(null);
            if (!current.isAdmin() && (role == null || !role.canEdit())) throw new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "Only project owners and editors can run this agent.");
        }
    }

    void requireCanExecuteForUser(AgentResponse agent, UUID userId, UUID projectId) {
        String role = jdbc.query("select role from users where id=? and status='ACTIVE'", (rs, n) -> rs.getString(1), userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "The workflow owner is no longer active."));
        if (!agent.enabled() || !agent.permissions().contains("EXECUTE")) throw new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "This agent is not enabled for execution.");
        if (!java.util.Objects.equals(agent.projectId(), projectId)) throw new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "The agent is outside this workflow project.");
        if (agent.projectId() == null) {
            if (!"ADMIN".equals(role)) throw new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "Only an administrator can run a studio-wide agent.");
        } else if (!"ADMIN".equals(role) && members.role(agent.projectId(), userId).filter(ProjectMembers.Role::canEdit).isEmpty()) {
            throw new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "Only project owners and editors can run this agent.");
        }
    }

    private void requireCanView(AgentResponse agent) {
        if (agent.projectId() == null) {
            if (!current.isAdmin() && !agent.createdBy().equals(current.id())) throw new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "You cannot view this agent.");
        } else projects.get(agent.projectId());
    }

    private void requireCanManage(UUID projectId) {
        if (projectId == null) {
            current.requireAdmin();
            return;
        }
        projects.get(projectId);
        if (!current.isAdmin() && members.role(projectId, current.id()).filter(r -> r == ProjectMembers.Role.OWNER).isEmpty()) {
            throw new ApiException(com.leenax.studio.common.ErrorCode.FORBIDDEN, "Only project owners or administrators can manage project agents.");
        }
    }

    private void validate(AgentRequest r) {
        if (!CATEGORY.matcher(r.category().toUpperCase()).matches()) throw ApiException.badRequest("Choose a valid agent category.");
        if (r.instructions() == null || r.instructions().isBlank()) throw ApiException.badRequest("System instructions are required.");
        if (r.accountId() == null || r.model() == null || r.model().isBlank()) throw ApiException.badRequest("Choose an AI account and model.");
        assertModelAllowed(r.accountId(), r.model()); // provider credentials remain in the existing account vault.
        validateSchema(r.inputSchema());
        validateSchema(r.outputSchema());
    }

    /** Agent runs default to local or the free-tier router; other hosted providers require owner configuration. */
    public void assertModelAllowed(UUID accountId, String model) {
        var account = accounts.get(accountId);
        if (!allowPaidModels && (account.getProvider() != ProviderCode.OLLAMA && account.getProvider() != ProviderCode.FREE_LLM_API
                || model != null && model.toLowerCase(java.util.Locale.ROOT).endsWith(":cloud"))) {
            throw ApiException.badRequest("Free-only agent mode is on. Choose a local Ollama model or a model routed through FreeLLMAPI.");
        }
    }

    private void validateSchema(JsonNode schema) {
        if (schema == null) return;
        if (!schema.isObject() || !schema.path("type").asText("object").equals("object")) throw ApiException.badRequest("Agent schemas must describe a JSON object.");
        JsonNode required = schema.path("required");
        if (!required.isMissingNode() && (!required.isArray() || required.size() > 80)) throw ApiException.badRequest("Agent schema required fields are invalid.");
        JsonNode props = schema.path("properties");
        if (!props.isMissingNode() && (!props.isObject() || props.size() > 80)) throw ApiException.badRequest("Agent schema properties are invalid.");
        schema.fields().forEachRemaining(e -> {
            if (e.getKey().startsWith("$")) throw ApiException.badRequest("External schema references are not supported.");
        });
    }

    void validateExecutionInput(AgentResponse agent, JsonNode input) {
        validateSchema(agent.inputSchema());
        if (input == null || !input.isObject()) throw ApiException.badRequest("Execution input must be a JSON object.");
        JsonNode required = agent.inputSchema().path("required");
        if (required.isArray()) for (JsonNode field : required) {
            if (!input.has(field.asText())) throw ApiException.badRequest("Missing required input field: " + field.asText());
        }
        JsonNode props = agent.inputSchema().path("properties");
        if (props.isObject()) props.fields().forEachRemaining(entry -> {
            JsonNode value = input.get(entry.getKey());
            if (value == null) return;
            String type = entry.getValue().path("type").asText("");
            boolean valid = switch (type) {
                case "string" -> value.isTextual(); case "number" -> value.isNumber(); case "integer" -> value.isIntegralNumber();
                case "boolean" -> value.isBoolean(); case "array" -> value.isArray(); case "object" -> value.isObject(); default -> true;
            };
            if (!valid) throw ApiException.badRequest("Input field " + entry.getKey() + " must be " + type + ".");
        });
    }

    private List<String> cleanTools(List<String> requested) {
        List<String> clean = requested == null ? List.of() : requested.stream().distinct().toList();
        if (clean.size() > 20) throw ApiException.badRequest("An agent can have at most 20 tools.");
        Set<String> allowed = new HashSet<>(jdbc.queryForList("select tool_key from ai_tool_registry where enabled", String.class));
        if (!allowed.containsAll(clean)) throw ApiException.badRequest("One or more selected tools are not available.");
        return clean;
    }

    private List<String> cleanPermissions(List<String> requested) {
        List<String> clean = requested == null || requested.isEmpty() ? List.of("EXECUTE", "VIEW_LOGS", "VIEW_OUTPUT") : requested.stream().distinct().toList();
        if (!PERMISSIONS.containsAll(clean)) throw ApiException.badRequest("Agent permissions contain an unknown capability.");
        return clean;
    }

    private void saveVersion(UUID id, int version, JsonNode request, UUID actor) {
        jdbc.update("insert into ai_agent_versions(agent_id,version,configuration,changed_by) values (?,?,?::jsonb,?)",
                id, version, json(request), actor);
    }

    private JsonNode agentConfig(AgentRequest r, List<String> tools, List<String> permissions) {
        AgentRequest normalized = new AgentRequest(r.name(), r.description(), r.category(), r.instructions(), r.accountId(), r.model(),
                r.temperature(), r.maxOutputTokens(), r.inputSchema() == null ? mapper.createObjectNode().put("type", "object") : r.inputSchema(),
                r.outputSchema() == null ? mapper.createObjectNode().put("type", "object") : r.outputSchema(), tools, permissions,
                r.timeoutSeconds(), r.retryCount(), r.projectId(), r.enabled());
        return mapper.valueToTree(normalized);
    }

    private static AgentResponse agent(ResultSet rs, int row) throws SQLException {
        return new AgentResponse(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, UUID.class), rs.getString(7), rs.getDouble(8), rs.getInt(9), parse(rs.getString(10)), parse(rs.getString(11)),
                stringList(rs.getString(12)), stringList(rs.getString(13)), rs.getInt(14), rs.getInt(15), rs.getObject(16, UUID.class),
                rs.getObject(17, UUID.class), rs.getBoolean(18), rs.getInt(19), instant(rs.getTimestamp(20)), instant(rs.getTimestamp(21)));
    }

    private static String blank(String s) { return s == null ? "" : s.strip(); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON value", e); } }
    private static JsonNode parse(String text) { try { return text == null ? null : new ObjectMapper().readTree(text); } catch (Exception e) { throw new IllegalStateException("Stored agent data is invalid", e); } }
    private static List<String> stringList(String text) { JsonNode node = parse(text); if (node == null || !node.isArray()) return List.of(); List<String> out = new ArrayList<>(); node.forEach(n -> out.add(n.asText())); return List.copyOf(out); }
    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
