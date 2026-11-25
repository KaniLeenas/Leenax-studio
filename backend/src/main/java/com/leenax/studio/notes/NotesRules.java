package com.leenax.studio.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.notes.Learning.Notes;
import com.leenax.studio.notes.Learning.Reference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** What notes are allowed to be, and what must hold before they are approved. */
@Component
public class NotesRules implements Review.ContentHandler {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int MIN_CHARS = 300;

    @Override
    public Kind kind() {
        return Kind.NOTES;
    }

    /** dependsOn: the approved topic list version the notes were written from. */
    @Override
    public JsonNode normalise(JsonNode raw, UUID dependsOn) {
        Notes n = read(raw);
        if (n.topicId() == null || n.topicId().isBlank()) throw ApiException.badRequest("Notes belong to a topic");
        if (n.level() == null || !Learning.LEVELS.contains(n.level())) throw ApiException.badRequest("Choose a level for the notes");
        JsonNode doc = NoteDoc.clean(n.doc() == null ? JSON.createObjectNode().put("type", "doc") : n.doc());
        List<Reference> refs = new ArrayList<>();
        for (Reference r : n.references() == null ? List.<Reference>of() : n.references()) {
            if (r == null || r.label() == null || r.label().isBlank()) continue;
            refs.add(new Reference(cut(r.label(), 200), r.documentId(), r.versionId(), r.page() != null && r.page() > 0 ? r.page() : null,
                    cut(r.note(), 500), r.found()));
            if (refs.size() > 100) throw ApiException.badRequest("Notes can list at most 100 references");
        }
        return JSON.valueToTree(new Notes(n.topicId().strip(), cut(n.topicTitle(), 300), n.level(), dependsOn, doc, refs, cut(n.model(), 120)));
    }

    @Override
    public List<Check> checks(JsonNode content, Context ctx) {
        Notes n = read(content);
        List<Check> out = new ArrayList<>();
        UUID topics = ctx.approvedVersionOf().apply(Kind.TOPICS);
        if (topics == null) {
            out.add(Check.fail("topics", "The topic list isn't approved. Notes are approved only for approved topics."));
        } else if (!topics.equals(n.topicsVersionId())) {
            out.add(Check.fail("topics", "The topic list has changed since these notes were written. Check the topic still fits, then edit to update."));
        } else if (!selectedTopics(ctx.approvedContentOf().apply(Kind.TOPICS)).contains(n.topicId())) {
            out.add(Check.fail("topics", "“" + n.topicTitle() + "” is no longer an included topic."));
        } else {
            out.add(Check.pass("topics", "Written for an approved topic"));
        }
        int chars = NoteDoc.plainText(n.doc()).length();
        out.add(chars >= MIN_CHARS ? Check.pass("length", String.format("%,d characters", chars))
                : Check.fail("length", "The notes are too short to approve (" + chars + " characters)"));
        List<Reference> refs = n.references() == null ? List.of() : n.references();
        long unfound = refs.stream().filter(r -> r.documentId() != null && !Boolean.TRUE.equals(r.found())).count();
        if (refs.isEmpty()) out.add(Check.warn("references", "No references to the course documents"));
        else if (unfound > 0) out.add(Check.warn("references", unfound + " of " + refs.size() + " references weren't found word for word on their page"));
        else out.add(Check.pass("references", refs.size() + " references"));
        return out;
    }

    @Override
    public List<String> diff(JsonNode before, JsonNode after) {
        Notes a = read(before), b = read(after);
        List<String> out = new ArrayList<>();
        Set<String> was = new LinkedHashSet<>(NoteDoc.headings(a.doc())), now = new LinkedHashSet<>(NoteDoc.headings(b.doc()));
        now.stream().filter(h -> !was.contains(h)).forEach(h -> out.add("Added section “" + h + "”"));
        was.stream().filter(h -> !now.contains(h)).forEach(h -> out.add("Removed section “" + h + "”"));
        String ta = NoteDoc.plainText(a.doc()), tb = NoteDoc.plainText(b.doc());
        if (!ta.equals(tb)) out.add("Text changed (" + String.format("%,d", ta.length()) + " → " + String.format("%,d", tb.length()) + " characters)");
        if (!Objects.equals(a.references(), b.references())) out.add("References changed");
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    /** Ids of the included topics in an approved topic list. */
    public static Set<String> selectedTopics(JsonNode topics) {
        Set<String> out = new HashSet<>();
        if (topics == null) return out;
        collect(topics.path("nodes"), out);
        return out;
    }

    private static void collect(JsonNode nodes, Set<String> out) {
        for (JsonNode n : nodes) {
            if (n.path("selected").asBoolean(true)) out.add(n.path("id").asText());
            collect(n.path("children"), out);
        }
    }

    public static Notes read(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw ApiException.badRequest("Notes must be a JSON object");
        try {
            return JSON.treeToValue(raw, Notes.class);
        } catch (Exception e) {
            throw ApiException.badRequest("The notes couldn't be read");
        }
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
