package com.leenax.studio.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.documents.DocumentText;
import com.leenax.studio.notes.Learning.Bank;
import com.leenax.studio.notes.Learning.Code;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.notes.Learning.Reference;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * What a question bank must be. Drafts may hold unfinished questions; before a bank is
 * sent for review or approved, every question needs a stem, four different options, one
 * key and an explanation, with no "all/none of the above" unless the bank allows it, and
 * no question repeated.
 */
@Component
public class McqRules implements Review.ContentHandler {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_QUESTIONS = 300;
    /** Stems this alike are the same question; between SIMILAR and DUPLICATE they're shown as similar. */
    static final double DUPLICATE = 0.85;
    static final double SIMILAR = 0.6;

    /** "All/none of the above" and friends, in English and Tamil. */
    static final Pattern ALL_NONE = Pattern.compile(
            "(?iU)\\b(all|none|both|neither)\\s+of\\s+(the\\s+)?(above|these|them|those)\\b|\\ball\\s+the\\s+above\\b"
                    + "|மேற்கண்ட\\s*அனைத்தும்|மேலே\\s*உள்ள\\s*அனைத்தும்|மேற்கூறிய\\s*அனைத்தும்|இவை\\s*அனைத்தும்|எதுவுமில்லை|எதுவும்\\s*இல்லை");

    @Override
    public Kind kind() {
        return Kind.MCQ;
    }

    /** dependsOn: the approved notes version the bank was written from. */
    @Override
    public JsonNode normalise(JsonNode raw, UUID dependsOn) {
        Bank b = read(raw);
        if (b.topicId() == null || b.topicId().isBlank()) throw ApiException.badRequest("A question bank belongs to a topic");
        List<Question> in = b.questions() == null ? List.of() : b.questions();
        if (in.size() > MAX_QUESTIONS) throw ApiException.badRequest("A bank can hold at most " + MAX_QUESTIONS + " questions");
        Set<String> ids = new HashSet<>();
        List<Question> out = new ArrayList<>();
        for (Question q : in) {
            if (q == null) continue;
            out.add(clean(q, ids));
        }
        return JSON.valueToTree(new Bank(b.topicId().strip(), NotesRules.cut(b.topicTitle(), 300), dependsOn,
                b.notesLevel() != null && Learning.LEVELS.contains(b.notesLevel()) ? b.notesLevel() : null,
                Boolean.TRUE.equals(b.allowAllNone()), out));
    }

    static Question clean(Question q, Set<String> ids) {
        String id = q.id() == null || q.id().isBlank() ? "q" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFL) : q.id().strip();
        if (!id.matches("[A-Za-z0-9_-]{1,40}") || !ids.add(id)) throw ApiException.badRequest("Question id “" + id + "” is invalid or repeated");
        List<String> options = q.options() == null ? List.of() : q.options();
        if (options.size() != 4) throw ApiException.badRequest("Every question has exactly four options");
        List<String> opts = options.stream().map(o -> text(o, 1000, "An option")).map(o -> o == null ? "" : o).toList();
        List<String> why = null;
        if (q.optionExplanations() != null && !q.optionExplanations().isEmpty()) {
            if (q.optionExplanations().size() != 4) throw ApiException.badRequest("Option explanations come four to a question");
            why = q.optionExplanations().stream().map(o -> text(o, 1000, "An option explanation")).map(o -> o == null ? "" : o).toList();
        }
        Integer answer = q.answer();
        if (answer != null && (answer < 0 || answer > 3)) throw ApiException.badRequest("The answer must be option A, B, C or D");
        String style = q.style() != null && Learning.STYLES.contains(q.style()) ? q.style() : "EXAM";
        String difficulty = q.difficulty() != null && Learning.DIFFICULTIES.contains(q.difficulty()) ? q.difficulty() : null;
        Code code = null;
        if (q.code() != null && q.code().text() != null && !q.code().text().isBlank()) {
            String lang = q.code().language() == null ? null : q.code().language().strip().toLowerCase(Locale.ROOT);
            code = new Code(lang != null && lang.matches("[a-z0-9+#._-]{1,30}") ? lang : null, limit(q.code().text(), 5000, "Question code"));
        }
        Reference src = q.source() == null ? null : new Reference(NotesRules.cut(q.source().label(), 200), q.source().documentId(),
                q.source().versionId(), q.source().page(), NotesRules.cut(q.source().note(), 500), q.source().found());
        String verification = q.verification() != null && Learning.VERIFICATION.contains(q.verification()) ? q.verification() : "UNVERIFIED";
        return new Question(id, style, difficulty, text(q.stem(), 3000, "A question"), code, opts, answer,
                text(q.explanation(), 4000, "An explanation"), why, text(q.subtopic(), 300, "A subtopic"), src, verification);
    }

    // ---- checks ------------------------------------------------------------------------------------

    @Override
    public List<Check> checks(JsonNode content, Context ctx) {
        Bank b = read(content);
        List<Check> out = new ArrayList<>(questionChecks(b));
        List<UUID> approvedNotes = ctx.approvedOfKind().apply(Kind.NOTES);
        out.add(0, b.notesVersionId() != null && approvedNotes.contains(b.notesVersionId())
                ? Check.pass("notes", "Written from approved notes")
                : Check.fail("notes", "The notes this bank was written from aren't the approved version any more. Edit to update it."));
        List<String> elsewhere = new ArrayList<>();
        for (JsonNode other : ctx.others().get()) {
            Bank o = read(other);
            for (Question q : list(b.questions())) {
                for (Question x : list(o.questions())) {
                    if (similarity(q.stem(), x.stem()) >= DUPLICATE) elsewhere.add(number(b, q) + " (also in " + o.topicTitle() + ")");
                }
            }
        }
        if (!elsewhere.isEmpty()) out.add(Check.warn("elsewhere", "Also in another bank: " + names(elsewhere)));
        return out;
    }

    /** The per-question rules, used for checks and to drop bad generated questions. */
    static List<Check> questionChecks(Bank b) {
        List<Question> qs = list(b.questions());
        List<Check> out = new ArrayList<>();
        out.add(qs.isEmpty() ? Check.fail("count", "Add at least one question") : Check.pass("count", qs.size() + " questions"));
        boolean allowAllNone = Boolean.TRUE.equals(b.allowAllNone());
        Map<String, List<String>> problems = new LinkedHashMap<>();
        for (Question q : qs) {
            for (String p : problems(q, allowAllNone)) problems.computeIfAbsent(p, k -> new ArrayList<>()).add(number(b, q));
        }
        if (problems.isEmpty() && !qs.isEmpty()) {
            out.add(Check.pass("questions", "Every question has four different options, one key and an explanation"));
        }
        problems.forEach((p, where) -> out.add(Check.fail("q:" + p, capital(p) + ": " + names(where))));
        List<String> dups = new ArrayList<>(), similar = new ArrayList<>();
        for (int i = 0; i < qs.size(); i++) {
            for (int j = i + 1; j < qs.size(); j++) {
                double s = similarity(qs.get(i).stem(), qs.get(j).stem());
                if (s >= DUPLICATE) dups.add(number(b, qs.get(i)) + " and " + number(b, qs.get(j)));
                else if (s >= SIMILAR) similar.add(number(b, qs.get(i)) + " and " + number(b, qs.get(j)));
            }
        }
        if (!dups.isEmpty()) out.add(Check.fail("duplicates", "The same question twice: " + names(dups)));
        if (!similar.isEmpty()) out.add(Check.warn("similar", "Very similar questions: " + names(similar)));
        return out;
    }

    /** What's wrong with one question, as short phrases (empty when it's fine). */
    public static List<String> problems(Question q, boolean allowAllNone) {
        List<String> out = new ArrayList<>();
        if (blank(q.stem())) out.add("no question text");
        List<String> opts = q.options() == null ? List.of() : q.options();
        if (opts.size() != 4 || opts.stream().anyMatch(McqRules::blank)) out.add("needs four options");
        else if (new HashSet<>(opts.stream().map(McqRules::key).toList()).size() < 4) out.add("options repeat");
        if (q.answer() == null || q.answer() < 0 || q.answer() > 3) out.add("no correct answer marked");
        if (blank(q.explanation())) out.add("no explanation");
        if (!allowAllNone && opts.stream().anyMatch(o -> o != null && ALL_NONE.matcher(o).find())) out.add("“all/none of the above” option");
        return out;
    }

    /** How alike two question stems are, 0 to 1: shared word pairs (Jaccard), in any language. */
    public static double similarity(String a, String b) {
        Set<String> x = pairs(a), y = pairs(b);
        if (x.isEmpty() || y.isEmpty()) return Objects.equals(key(a), key(b)) && !blank(a) ? 1 : 0;
        Set<String> both = new HashSet<>(x);
        both.retainAll(y);
        Set<String> any = new HashSet<>(x);
        any.addAll(y);
        return (double) both.size() / any.size();
    }

    private static Set<String> pairs(String s) {
        List<String> w = DocumentText.words(s);
        Set<String> out = new HashSet<>();
        if (w.size() == 1) out.add(w.get(0));
        for (int i = 0; i + 1 < w.size(); i++) out.add(w.get(i) + " " + w.get(i + 1));
        return out;
    }

    public static String key(String s) {
        return s == null ? "" : String.join(" ", DocumentText.words(s));
    }

    @Override
    public List<String> diff(JsonNode before, JsonNode after) {
        Bank a = read(before), b = read(after);
        Map<String, Question> was = new LinkedHashMap<>(), now = new LinkedHashMap<>();
        list(a.questions()).forEach(q -> was.put(q.id(), q));
        list(b.questions()).forEach(q -> now.put(q.id(), q));
        List<String> out = new ArrayList<>();
        now.forEach((id, q) -> {
            Question o = was.get(id);
            if (o == null) out.add("Added " + number(b, q));
            else {
                if (!Objects.equals(o.answer(), q.answer())) out.add("Changed the answer of " + number(b, q));
                if (!Objects.equals(o.stem(), q.stem()) || !Objects.equals(o.options(), q.options())) out.add("Reworded " + number(b, q));
                if (!Objects.equals(o.explanation(), q.explanation())) out.add("Changed the explanation of " + number(b, q));
            }
        });
        was.forEach((id, q) -> {
            if (!now.containsKey(id)) out.add("Removed " + number(a, q));
        });
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    // ---- helpers -----------------------------------------------------------------------------------

    static String number(Bank b, Question q) {
        return "question " + (list(b.questions()).indexOf(q) + 1);
    }

    public static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String names(List<String> items) {
        String s = String.join(", ", items.subList(0, Math.min(6, items.size())));
        return items.size() > 6 ? s + " and " + (items.size() - 6) + " more" : s;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String text(String s, int max, String what) {
        if (s == null) return null;
        String t = Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("[\\u200B-\\u200D\\uFEFF]", "").strip();
        if (t.isEmpty()) return null;
        return limit(t, max, what);
    }

    private static String limit(String s, int max, String what) {
        if (s.length() > max) throw ApiException.badRequest(what + " is longer than " + max + " characters");
        return s;
    }

    public static Bank read(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw ApiException.badRequest("A question bank must be a JSON object");
        try {
            return JSON.treeToValue(raw, Bank.class);
        } catch (Exception e) {
            throw ApiException.badRequest("The question bank couldn't be read");
        }
    }
}
