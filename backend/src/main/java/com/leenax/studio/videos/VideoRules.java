package com.leenax.studio.videos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.slides.Slides;
import com.leenax.studio.slides.SlidesRules;
import com.leenax.studio.videos.Videos.Narration;
import com.leenax.studio.videos.Videos.Plan;
import com.leenax.studio.videos.Videos.Question;
import com.leenax.studio.videos.Videos.Quiz;
import com.leenax.studio.videos.Videos.Scene;
import com.leenax.studio.videos.Videos.Voice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A lesson or quiz video's plan as an approval stage. Cleaning checks the scenes and drops a
 * narration that no longer matches the words or the voice. Whether the content matches its
 * approved source, and whether this exact version was rendered and passed its file checks, are
 * checked in VideoStore (they need the database).
 */
public abstract class VideoRules implements Review.ContentHandler {

    static final ObjectMapper JSON = new ObjectMapper();

    abstract Videos.Kind videoKind();

    @Override
    public boolean editableByHand() {
        return true;
    }

    @Override
    public JsonNode normalise(JsonNode raw, UUID dependsOn) {
        Plan p = read(raw);
        Videos.Kind k = videoKind();
        if (!k.name().equals(p.kind())) throw ApiException.badRequest("Not a " + k.name().toLowerCase(Locale.ROOT) + " video");
        if (p.topicId() == null || p.topicId().isBlank()) throw ApiException.badRequest("A video needs its topic");
        if (k == Videos.Kind.LESSON && (p.level() == null || !Learning.LEVELS.contains(p.level()))) throw ApiException.badRequest("A lesson video needs its notes level");
        String language = "TA".equalsIgnoreCase(p.language()) ? "TA" : "EN";
        String aspect = Videos.ASPECTS.contains(p.aspect()) ? p.aspect() : "16:9";
        String style = Slides.STYLES.containsKey(p.style()) ? p.style() : (k == Videos.Kind.QUIZ ? "INFOGRAPHIC" : "LECTURE");
        List<Scene> in = Videos.list(p.scenes());
        if (in.size() > Videos.MAX_SCENES) throw ApiException.badRequest("At most " + Videos.MAX_SCENES + " scenes");
        List<String> kinds = k == Videos.Kind.LESSON ? Videos.LESSON_SCENES : Videos.QUIZ_SCENES;
        List<Scene> scenes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Scene s : in) {
            if (s == null) continue;
            if (s.id() == null || !s.id().matches("[A-Za-z0-9_-]{1,60}") || !ids.add(s.id())) throw ApiException.badRequest("Each scene needs its own id");
            if (!kinds.contains(s.kind())) throw ApiException.badRequest("A " + k.name().toLowerCase(Locale.ROOT) + " video can't have a " + s.kind() + " scene");
            Slides.Slide slide = null;
            if ("SLIDE".equals(s.kind())) {
                if (s.slide() == null) throw ApiException.badRequest("A slide scene needs its slide");
                slide = SlidesRules.clean(s.slide(), new HashSet<>());
            }
            Question q = "QUESTION".equals(s.kind()) ? question(s.question()) : null;
            List<String> lines = Videos.list(s.lines()).stream().map(x -> limit(x, 300)).filter(Objects::nonNull).limit(20).toList();
            List<String> keywords = Videos.list(s.keywords()).stream().map(x -> limit(x, 100)).filter(Objects::nonNull).distinct().limit(8).toList();
            scenes.add(new Scene(s.id(), s.kind(), limit(s.title(), 200), lines.isEmpty() ? null : lines, slide, q, keywords.isEmpty() ? null : keywords,
                    text(s.narration()), "QUESTION".equals(s.kind()) ? text(s.answerNarration()) : null, sizes(s.sizes())));
        }
        Voice v = p.voice();
        Voice voice = v == null ? null : new Voice(v.profileId(), v.accountId(), limit(v.voiceId(), 120), limit(v.model(), 120),
                v.speed() == null || v.speed() < 0.5 || v.speed() > 2 ? null : Math.round(v.speed() * 100) / 100.0, limit(v.label(), 200));
        if (voice != null && voice.profileId() == null && (voice.accountId() == null || voice.voiceId() == null)) voice = null;
        Quiz quiz = null;
        if (k == Videos.Kind.QUIZ) {
            int c = p.quiz() == null || p.quiz().countdown() == null ? 5 : p.quiz().countdown();
            if (c < 3 || c > 30) throw ApiException.badRequest("The countdown is 3 to 30 seconds");
            quiz = new Quiz(c);
        }
        Plan clean = new Plan(k.name(), p.topicId().strip(), limit(p.topicTitle(), 200), k == Videos.Kind.LESSON ? p.level() : null, p.sourceVersionId(),
                p.seoVersionId(), limit(p.title(), 200), language, style, aspect, voice, quiz, scenes, null, p.captions() == null || p.captions());
        Narration n = p.narration();
        boolean current = n != null && n.mediaId() != null && Objects.equals(n.scriptHash(), VideoPlans.scriptHash(clean))
                && segmentsCover(n, clean);
        return JSON.valueToTree(new Plan(clean.kind(), clean.topicId(), clean.topicTitle(), clean.level(), clean.sourceVersionId(), clean.seoVersionId(),
                clean.title(), clean.language(), clean.style(), clean.aspect(), clean.voice(), clean.quiz(), clean.scenes(), current ? n : null, clean.captions()));
    }

    static boolean segmentsCover(Narration n, Plan p) {
        Set<String> have = new HashSet<>();
        Videos.list(n.segments()).forEach(s -> have.add(s.sceneId() + "/" + s.part()));
        return VideoPlans.spoken(p).stream().allMatch(s -> have.contains(s.sceneId() + "/" + s.part()));
    }

    /** Text sizes for a slide's boxes, in points, within what the fit check allows. */
    static java.util.Map<String, Double> sizes(java.util.Map<String, Double> in) {
        if (in == null) return null;
        java.util.Map<String, Double> out = new java.util.TreeMap<>();
        for (var e : in.entrySet()) {
            if (e.getKey() != null && e.getKey().matches("[a-z]{1,20}") && e.getValue() != null && e.getValue() >= 8 && e.getValue() <= 60) out.put(e.getKey(), e.getValue());
        }
        return out.isEmpty() ? null : out;
    }

    static Question question(Question q) {
        if (q == null || q.stem() == null || q.stem().isBlank()) throw ApiException.badRequest("A question scene needs its question");
        List<String> opts = Videos.list(q.options());
        if (opts.size() != 4 || opts.stream().anyMatch(o -> o == null || o.isBlank())) throw ApiException.badRequest("A question needs four options");
        if (q.answer() == null || q.answer() < 0 || q.answer() > 3) throw ApiException.badRequest("A question needs its answer (A to D)");
        return new Question(limit(q.questionId(), 60), q.number(), q.total(), text(q.stem()), q.code(), opts.stream().map(o -> limit(o, 500)).toList(),
                q.answer(), text(q.explanation()));
    }

    // ---- checks ------------------------------------------------------------------------------------

    @Override
    public List<Check> checks(JsonNode content, Context ctx) {
        Plan p = read(content);
        List<Scene> scenes = Videos.list(p.scenes());
        List<Check> out = new ArrayList<>();
        if (scenes.isEmpty()) {
            out.add(Check.fail("scenes", "The video has no scenes"));
            return out;
        }
        out.add(Check.pass("scenes", scenes.size() + " scenes, " + VideoPlans.characters(p) + " characters to speak"));
        List<String> silent = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            Scene s = scenes.get(i);
            if (s.narration() == null || ("QUESTION".equals(s.kind()) && s.answerNarration() == null)) silent.add(String.valueOf(i + 1));
        }
        out.add(silent.isEmpty() ? Check.pass("narration-text", "Every scene has narration")
                : Check.fail("narration-text", "No narration on scene " + String.join(", ", silent)));
        if (videoKind() == Videos.Kind.QUIZ) {
            long q = scenes.stream().filter(s -> "QUESTION".equals(s.kind())).count();
            out.add(q > 0 ? Check.pass("questions", q + " question" + (q == 1 ? "" : "s") + ", " + p.quiz().countdown() + " s countdown each")
                    : Check.fail("questions", "The quiz has no questions"));
        }
        out.add(p.voice() != null ? Check.pass("voice", "Voice: " + (p.voice().label() == null ? "chosen" : p.voice().label()))
                : Check.fail("voice", "Choose a voice"));
        out.add(p.narration() != null ? Check.pass("narration", "Narration made" + (p.narration().voiceLabel() == null ? "" : " with " + p.narration().voiceLabel()))
                : Check.fail("narration", "Make the narration: it's missing, or the words or voice changed since it was made"));
        return out;
    }

    @Override
    public List<String> diff(JsonNode before, JsonNode after) {
        Plan a = read(before), b = read(after);
        List<String> out = new ArrayList<>();
        if (!Objects.equals(a.voice(), b.voice())) out.add("Voice changed");
        if (!Objects.equals(a.aspect(), b.aspect())) out.add("Shape: " + b.aspect());
        if (!Objects.equals(a.style(), b.style())) out.add("Style: " + Slides.STYLES.getOrDefault(b.style(), b.style()));
        if (!Objects.equals(a.captions(), b.captions())) out.add(Boolean.FALSE.equals(b.captions()) ? "Captions off" : "Captions on");
        if (!Objects.equals(a.quiz(), b.quiz()) && b.quiz() != null) out.add("Countdown: " + b.quiz().countdown() + " s");
        List<String> ia = Videos.list(a.scenes()).stream().map(Scene::id).toList(), ib = Videos.list(b.scenes()).stream().map(Scene::id).toList();
        long added = ib.stream().filter(x -> !ia.contains(x)).count(), removed = ia.stream().filter(x -> !ib.contains(x)).count();
        if (added > 0) out.add(added + " scene" + (added == 1 ? "" : "s") + " added");
        if (removed > 0) out.add(removed + " scene" + (removed == 1 ? "" : "s") + " removed");
        if (!ia.stream().filter(ib::contains).toList().equals(ib.stream().filter(ia::contains).toList())) out.add("Scenes reordered");
        int changed = 0;
        for (Scene s : Videos.list(b.scenes())) {
            for (Scene t : Videos.list(a.scenes())) {
                if (t.id().equals(s.id()) && (!Objects.equals(t.narration(), s.narration()) || !Objects.equals(t.answerNarration(), s.answerNarration()))) changed++;
            }
        }
        if (changed > 0) out.add("Narration changed on " + changed + " scene" + (changed == 1 ? "" : "s"));
        if (a.narration() != null && b.narration() != null && !Objects.equals(a.narration().mediaId(), b.narration().mediaId())) out.add("New narration");
        return out;
    }

    public static Plan read(JsonNode raw) {
        try {
            return JSON.treeToValue(raw, Plan.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Not a video plan");
        }
    }

    static String limit(String s, int max) {
        if (s == null) return null;
        String t = s.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        if (t.length() > max) throw ApiException.badRequest("Too long (at most " + max + " characters): “" + (t.length() > 40 ? t.substring(0, 40) + "…" : t) + "”");
        return t;
    }

    static String text(String s) {
        if (s == null) return null;
        String t = s.replace("\r\n", "\n").strip();
        if (t.isEmpty()) return null;
        if (t.length() > Videos.MAX_NARRATION) throw ApiException.badRequest("Narration is at most " + Videos.MAX_NARRATION + " characters a scene");
        return t;
    }

    @org.springframework.stereotype.Component
    public static class Lesson extends VideoRules {
        @Override
        public Kind kind() {
            return Kind.LESSON_VIDEO;
        }

        @Override
        Videos.Kind videoKind() {
            return Videos.Kind.LESSON;
        }
    }

    @org.springframework.stereotype.Component
    public static class QuizVideo extends VideoRules {
        @Override
        public Kind kind() {
            return Kind.QUIZ_VIDEO;
        }

        @Override
        Videos.Kind videoKind() {
            return Videos.Kind.QUIZ;
        }
    }
}
