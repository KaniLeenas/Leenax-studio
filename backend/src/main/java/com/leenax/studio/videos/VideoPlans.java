package com.leenax.studio.videos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.slides.Slides;
import com.leenax.studio.videos.Videos.Plan;
import com.leenax.studio.videos.Videos.Question;
import com.leenax.studio.videos.Videos.Quiz;
import com.leenax.studio.videos.Videos.Scene;
import com.leenax.studio.videos.Videos.Voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Plans made from approved content, without AI: the narration is the presentation's approved
 * speaker notes, or the approved questions read aloud. The few linking sentences (introduction,
 * outro, "the answer is …") are fixed wording in English or Tamil, editable before narration.
 */
public final class VideoPlans {

    private VideoPlans() {
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final String[] LETTERS = {"A", "B", "C", "D"};

    // ---- lesson ------------------------------------------------------------------------------------

    public static Plan lesson(Slides.Deck deck, UUID deckVersionId, UUID seoVersionId, String videoTitle, List<String> keywords, String language,
                              String aspect, Voice voice) {
        boolean ta = "TA".equals(language);
        String topic = deck.topicTitle() == null ? deck.title() : deck.topicTitle();
        String title = videoTitle != null && !videoTitle.isBlank() ? videoTitle : (deck.title() == null ? topic : deck.title());
        List<Scene> scenes = new ArrayList<>();
        Slides.Slide first = Videos.list(deck.slides()).isEmpty() ? null : deck.slides().get(0);
        List<String> introLines = new ArrayList<>();
        if (first != null && "TITLE".equals(first.type()) && first.subtitle() != null) introLines.add(first.subtitle());
        scenes.add(new Scene("intro", "INTRO", title, introLines, null, null, null,
                ta ? "இந்தப் பாடத்தில்: " + topic + "." : "In this lesson: " + topic + ".", null, null));
        int n = 0;
        for (Slides.Slide s : Videos.list(deck.slides())) {
            if ("TITLE".equals(s.type())) continue;
            n++;
            String narration = s.notes() != null && !s.notes().isBlank() ? s.notes().strip() : spoken(s, ta);
            scenes.add(new Scene("s" + n + "-" + s.id(), "SLIDE", s.title(), null, s, null, keywordsIn(s, keywords), clip(narration), null,
                    sizes(deck.fit(), s.id())));
        }
        scenes.add(new Scene("outro", "OUTRO", ta ? "நன்றி" : "Thanks for watching", List.of(topic), null, null, null,
                ta ? topic + " பற்றிய இந்தப் பாடம் இத்துடன் நிறைவடைகிறது. பார்த்தமைக்கு நன்றி." : "That's the end of this lesson on " + topic + ". Thanks for watching.", null, null));
        return new Plan(Videos.Kind.LESSON.name(), deck.topicId(), topic, deck.level(), deckVersionId, seoVersionId, title, language,
                deck.style() == null ? "LECTURE" : deck.style(), aspect, voice, null, scenes, null, true);
    }

    /** The sizes the presentation's fit check chose for a slide's text boxes. */
    static Map<String, Double> sizes(com.fasterxml.jackson.databind.JsonNode fit, String slideId) {
        if (fit == null) return null;
        for (com.fasterxml.jackson.databind.JsonNode s : fit.path("slides")) {
            if (!slideId.equals(s.path("id").asText())) continue;
            Map<String, Double> out = new java.util.TreeMap<>();
            for (com.fasterxml.jackson.databind.JsonNode b : s.path("boxes")) {
                if (b.path("fits").asBoolean(false)) out.put(b.path("field").asText(), b.path("size").asDouble());
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    /** A slide without speaker notes is read as it is: its title and text. */
    static String spoken(Slides.Slide s, boolean ta) {
        StringBuilder sb = new StringBuilder();
        if (s.title() != null) sb.append(s.title()).append(". ");
        if (s.term() != null) sb.append(s.term()).append(": ");
        if (s.text() != null) sb.append(s.text()).append(' ');
        for (String b : Videos.list(s.bullets())) sb.append(b).append(". ");
        if (s.caption() != null) sb.append(s.caption()).append('.');
        String out = sb.toString().replaceAll("\\.\\s*\\.", ".").strip();
        return out.isEmpty() ? (ta ? "அடுத்த பகுதி." : "Next.") : out;
    }

    /** The approved keywords (and a definition's term) that appear on the slide, to highlight. */
    static List<String> keywordsIn(Slides.Slide s, List<String> keywords) {
        String text = (String.join(" ", Videos.list(s.bullets())) + " " + (s.text() == null ? "" : s.text())).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (s.term() != null) out.add(s.term());
        for (String k : Videos.list(keywords)) {
            if (k != null && k.length() >= 3 && text.contains(k.toLowerCase(Locale.ROOT)) && !out.contains(k)) out.add(k);
            if (out.size() >= 5) break;
        }
        return out.isEmpty() ? null : out;
    }

    // ---- quiz --------------------------------------------------------------------------------------

    public static Plan quiz(Learning.Bank bank, UUID bankVersionId, UUID seoVersionId, String videoTitle, String language, String aspect,
                            Voice voice, int countdown, int maxQuestions) {
        boolean ta = "TA".equals(language);
        String topic = bank.topicTitle();
        List<Learning.Question> qs = Videos.list(bank.questions()).stream()
                .filter(q -> q.options() != null && q.options().size() == 4 && q.answer() != null && q.answer() >= 0 && q.answer() < 4)
                .limit(Math.max(1, maxQuestions)).toList();
        int total = qs.size();
        List<Scene> scenes = new ArrayList<>();
        String title = videoTitle != null && !videoTitle.isBlank() ? videoTitle : (ta ? topic + ": வினாடி வினா" : topic + ": quiz");
        scenes.add(new Scene("quiz-intro", "QUIZ_INTRO", title,
                List.of(ta ? total + " கேள்விகள்" : total + " question" + (total == 1 ? "" : "s"),
                        ta ? "ஒவ்வொன்றுக்கும் " + countdown + " வினாடிகள்" : countdown + " seconds each"), null, null, null,
                ta ? topic + " பற்றி நீங்கள் அறிந்ததைச் சோதிப்போம். " + total + " கேள்விகள் உள்ளன; ஒவ்வொன்றுக்கும் " + countdown + " வினாடிகள்."
                        : "Let's test what you know about " + topic + ". There " + (total == 1 ? "is 1 question" : "are " + total + " questions")
                          + ", with " + countdown + " seconds for each.", null, null));
        List<String> recap = new ArrayList<>();
        for (int i = 0; i < qs.size(); i++) {
            Learning.Question q = qs.get(i);
            Question data = new Question(q.id(), i + 1, total, q.stem(), q.code(), q.options(), q.answer(), q.explanation());
            scenes.add(new Scene("q" + (i + 1), "QUESTION", (ta ? "கேள்வி " : "Question ") + (i + 1), null, null, data, null,
                    clip(read(data, ta)), clip(answer(data, ta)), null));
            recap.add((i + 1) + ". " + LETTERS[q.answer()] + ": " + q.options().get(q.answer()));
        }
        scenes.add(new Scene("summary", "SUMMARY", ta ? "விடைகள்" : "Answers", recap.stream().limit(20).toList(), null, null, null,
                ta ? "உங்கள் சரியான விடைகளை எண்ணுங்கள்: " + total + " இல் உங்கள் மதிப்பெண் என்ன?"
                        : "Count your correct answers to get your score out of " + total + ".", null, null));
        scenes.add(new Scene("outro", "OUTRO", ta ? "நன்றி" : "Thanks for playing", List.of(topic), null, null, null,
                ta ? "பார்த்தமைக்கு நன்றி. அடுத்த பாடத்தில் சந்திப்போம்." : "Thanks for watching. See you in the next lesson.", null, null));
        return new Plan(Videos.Kind.QUIZ.name(), bank.topicId(), topic, null, bankVersionId, seoVersionId, title, language, "INFOGRAPHIC", aspect, voice,
                new Quiz(countdown), scenes, null, true);
    }

    static String read(Question q, boolean ta) {
        StringBuilder sb = new StringBuilder(ta ? "கேள்வி " : "Question ").append(q.number()).append(". ").append(q.stem().strip());
        if (!q.stem().strip().matches(".*[.?!:]$")) sb.append('.');
        for (int i = 0; i < 4; i++) sb.append(' ').append(LETTERS[i]).append(": ").append(q.options().get(i).strip()).append('.');
        return sb.toString().replaceAll("\\.\\.", ".");
    }

    static String answer(Question q, boolean ta) {
        String opt = q.options().get(q.answer()).strip();
        StringBuilder sb = new StringBuilder(ta ? "சரியான விடை " : "The answer is ").append(LETTERS[q.answer()]).append(": ").append(opt).append('.');
        if (q.explanation() != null && !q.explanation().isBlank()) sb.append(' ').append(q.explanation().strip());
        return sb.toString().replaceAll("\\.\\.", ".");
    }

    // ---- what is spoken ----------------------------------------------------------------------------

    /** One spoken part, in order. */
    public record Spoken(String sceneId, String part, String text) {
    }

    public static List<Spoken> spoken(Plan p) {
        List<Spoken> out = new ArrayList<>();
        for (Scene s : Videos.list(p.scenes())) {
            if (s.narration() != null && !s.narration().isBlank()) out.add(new Spoken(s.id(), "MAIN", s.narration().strip()));
            if ("QUESTION".equals(s.kind()) && s.answerNarration() != null && !s.answerNarration().isBlank()) {
                out.add(new Spoken(s.id(), "ANSWER", s.answerNarration().strip()));
            }
        }
        return out;
    }

    /** What the narration depends on: the words, their order and the voice. */
    public static String scriptHash(Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", p.language());
        Voice v = p.voice();
        m.put("voice", v == null ? null : List.of(String.valueOf(v.profileId()), String.valueOf(v.accountId()), String.valueOf(v.voiceId()),
                String.valueOf(v.model()), String.valueOf(v.speed())));
        m.put("parts", spoken(p).stream().map(s -> List.of(s.sceneId(), s.part(), s.text())).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(m).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static int characters(Plan p) {
        return spoken(p).stream().mapToInt(s -> s.text().length()).sum();
    }

    static String clip(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() > Videos.MAX_NARRATION ? t.substring(0, Videos.MAX_NARRATION - 1).stripTrailing() + "…" : t;
    }
}
