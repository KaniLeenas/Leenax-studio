package com.leenax.studio.notes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.ReviewStore;
import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.notes.Learning.Code;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.projects.ProjectService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Randomised quizzes (E4), drawn only from approved question banks. The seed is kept, and
 * the quiz keeps a copy of its questions, so it exports the same way later.
 */
@Service
public class QuizService {

    static final ObjectMapper JSON = new ObjectMapper();

    public record QuizRequest(@NotBlank @Size(max = 200) String title, @NotEmpty List<UUID> bankIds, @Min(1) @Max(200) int count,
                              List<String> difficulties, List<String> styles, boolean shuffleOptions, Long seed) {
    }

    /** A question as it appears in the quiz, options already in quiz order. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuizQuestion(int number, UUID bankId, String bankTitle, int bankVersionNo, String questionId, String style,
                               String difficulty, String stem, Code code, List<String> options, int answer, String explanation,
                               List<String> optionExplanations, String subtopic, String source, String verification) {
    }

    public record Quiz(UUID id, String title, long seed, JsonNode config, List<QuizQuestion> questions, String createdBy, Instant createdAt) {
    }

    public record QuizSummary(UUID id, String title, int questionCount, long seed, String createdBy, Instant createdAt) {
    }

    /** One approved bank's questions, ready to draw from. */
    record Pool(UUID bankId, String title, int versionNo, List<Question> questions) {
    }

    private final JdbcTemplate jdbc;
    private final ReviewStore reviews;
    private final ProjectService projects;
    private final AuditService audit;

    public QuizService(JdbcTemplate jdbc, ReviewStore reviews, ProjectService projects, AuditService audit) {
        this.jdbc = jdbc;
        this.reviews = reviews;
        this.projects = projects;
        this.audit = audit;
    }

    public Quiz create(UUID projectId, QuizRequest req) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors can make quizzes");
        List<Pool> pools = new ArrayList<>();
        List<String> notApproved = new ArrayList<>();
        for (UUID bankId : req.bankIds().stream().distinct().toList()) {
            ResourceRow r = reviews.resource(bankId).filter(x -> x.kind() == Kind.MCQ && x.projectId().equals(projectId))
                    .orElseThrow(() -> ApiException.badRequest("A chosen question bank isn't in this project"));
            VersionRow v = reviews.approved(r.id()).flatMap(x -> reviews.version(x.id())).orElse(null);
            if (v == null) {
                notApproved.add(r.title());
                continue;
            }
            pools.add(new Pool(r.id(), r.title(), v.versionNo(), McqRules.read(MaterialsService.read(v.content())).questions()));
        }
        if (!notApproved.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "Quizzes use approved question banks only. Not approved yet: " + String.join(", ", notApproved));
        }
        long seed = req.seed() != null ? req.seed() : new SecureRandom().nextLong() & 0xFFFFFFFFFFFFL;
        List<QuizQuestion> questions = draw(pools, req.count(), MaterialsService.filter(req.difficulties(), Learning.DIFFICULTIES),
                MaterialsService.filter(req.styles(), Learning.STYLES), req.shuffleOptions(), seed);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("bankIds", req.bankIds());
        config.put("count", req.count());
        config.put("difficulties", req.difficulties());
        config.put("styles", req.styles());
        config.put("shuffleOptions", req.shuffleOptions());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into quizzes (id, project_id, title, seed, config, questions) values (?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                id, projectId, req.title().strip(), seed, write(config), write(questions));
        audit.record("QUIZ_CREATED", "project", projectId, Map.of("questions", questions.size()));
        return get(id);
    }

    /**
     * Picks {@code count} questions from the pools that match the filters, in random order,
     * with options shuffled if asked. The same pools, filters and seed always give the same quiz.
     */
    static List<QuizQuestion> draw(List<Pool> pools, int count, List<String> difficulties, List<String> styles, boolean shuffleOptions,
                                   long seed) {
        record Pick(Pool pool, Question q) {
        }
        List<Pick> all = new ArrayList<>();
        for (Pool p : pools) {
            for (Question q : p.questions()) {
                if (!difficulties.isEmpty() && (q.difficulty() == null || !difficulties.contains(q.difficulty()))) continue;
                if (!styles.isEmpty() && !styles.contains(q.style())) continue;
                if (!McqRules.problems(q, true).isEmpty()) continue;
                all.add(new Pick(p, q));
            }
        }
        if (all.size() < count) {
            throw ApiException.badRequest("Only " + all.size() + (all.size() == 1 ? " question matches" : " questions match")
                    + " in the approved banks you chose. Ask for fewer, or widen the filters.");
        }
        Random rnd = new Random(seed);
        Collections.shuffle(all, rnd);
        List<QuizQuestion> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Pick pk = all.get(i);
            Question q = pk.q();
            List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3));
            if (shuffleOptions) Collections.shuffle(order, rnd);
            List<String> opts = order.stream().map(k -> q.options().get(k)).toList();
            List<String> why = q.optionExplanations() == null ? null : order.stream().map(k -> q.optionExplanations().get(k)).toList();
            out.add(new QuizQuestion(i + 1, pk.pool().bankId(), pk.pool().title(), pk.pool().versionNo(), q.id(), q.style(), q.difficulty(),
                    q.stem(), q.code(), opts, order.indexOf(q.answer()), q.explanation(), why, q.subtopic(),
                    q.source() == null ? null : q.source().label(), q.verification()));
        }
        return out;
    }

    public List<QuizSummary> list(UUID projectId) {
        projects.requireAccess(projectId, false, null);
        return jdbc.query("""
                select q.id, q.title, jsonb_array_length(q.questions), q.seed, u.display_name, q.created_at
                  from quizzes q left join users u on u.id = q.created_by where q.project_id = ? order by q.created_at desc
                """, (rs, i) -> new QuizSummary(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getString(5),
                instant(rs.getTimestamp(6))), projectId);
    }

    public Quiz get(UUID id) {
        return jdbc.query("""
                select q.id, q.title, q.seed, q.config::text, q.questions::text, u.display_name, q.created_at
                  from quizzes q left join users u on u.id = q.created_by where q.id = ?
                """, (rs, i) -> new Quiz(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), MaterialsService.read(rs.getString(4)),
                readQuestions(rs.getString(5)), rs.getString(6), instant(rs.getTimestamp(7))), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Quiz"));
    }

    public UUID projectOf(UUID quizId) {
        return jdbc.queryForList("select project_id from quizzes where id = ?", UUID.class, quizId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Quiz"));
    }

    public void delete(UUID id) {
        UUID project = projectOf(id);
        projects.requireAccess(project, true, "Only the project's owners and editors can delete quizzes");
        if (jdbc.update("delete from quizzes where id = ?", id) == 0) throw ApiException.notFound("Quiz");
    }

    private static List<QuizQuestion> readQuestions(String s) {
        try {
            return JSON.readValue(s, new TypeReference<List<QuizQuestion>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Stored quiz isn't readable", e);
        }
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
