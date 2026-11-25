package com.leenax.studio.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.notes.MaterialsService;
import com.leenax.studio.notes.NoteDoc;
import com.leenax.studio.notes.NotesRules;
import com.leenax.studio.slides.Slides.Deck;
import com.leenax.studio.slides.Slides.Slide;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The checks on a deck that need more than its own content (E7): the approvals it rests on
 * ("no presentation before academic and SEO approval"), diagrams drawn, images present, and the
 * source checks against the approved notes (references must come from the notes).
 */
@Component
public class SlidesStore implements Review.Gate {

    private final JdbcTemplate jdbc;

    public SlidesStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The approved version id of a topic's resource of this kind (level only for notes and decks), if any. */
    public Optional<UUID> approvedVersion(UUID projectId, Kind kind, String topicId, String level) {
        return jdbc.queryForList("select v.id from resources r join resource_versions v on v.resource_id = r.id where r.project_id = ? and r.kind = ?"
                        + " and r.topic_id = ? and r.level is not distinct from ? and v.status = 'APPROVED' order by v.version_no desc limit 1",
                UUID.class, projectId, kind.name(), topicId, level).stream().findFirst();
    }

    public Optional<String> content(UUID versionId) {
        return jdbc.queryForList("select content::text from resource_versions where id = ?", String.class, versionId).stream().findFirst();
    }

    public boolean drawn(UUID projectId, String kind, String source) {
        Integer n = jdbc.queryForObject("select count(*) from note_figures where project_id = ? and kind = ? and source_hash = ?", Integer.class,
                projectId, kind, NoteDoc.sha256(source.strip()));
        return n != null && n > 0;
    }

    /** An image in this project's media (so everyone on the project, reviewers included, can see it). */
    public boolean imageOk(UUID projectId, UUID assetId) {
        Integer n = jdbc.queryForObject("select count(*) from media_assets where id = ? and kind = 'IMAGE' and project_id = ?",
                Integer.class, assetId, projectId);
        return n != null && n > 0;
    }

    @Override
    public List<Check> checks(UUID projectId, UUID resourceId, Kind kind, String contentHash) {
        if (kind != Kind.SLIDES) return List.of();
        String content = jdbc.queryForList("select content::text from resource_versions where resource_id = ? and content_hash = ?"
                + " order by version_no desc limit 1", String.class, resourceId, contentHash).stream().findFirst().orElse(null);
        if (content == null) return List.of();
        Deck d = SlidesRules.read(MaterialsService.read(content));
        List<Slide> slides = Slides.list(d.slides());
        List<Check> out = new ArrayList<>();

        // the approvals a deck rests on (the database checks these again on approval)
        Optional<UUID> notes = approvedVersion(projectId, Kind.NOTES, d.topicId(), d.level());
        Optional<UUID> qa = approvedVersion(projectId, Kind.QA, d.topicId(), null);
        Optional<UUID> seo = approvedVersion(projectId, Kind.SEO, d.topicId(), null);
        out.add(notes.isPresent() ? Check.pass("notes-approved", Learning.levelLabel(d.level()) + " notes are approved")
                : Check.fail("notes-approved", "The " + Learning.levelLabel(d.level()).toLowerCase(Locale.ROOT) + " notes for this topic aren't approved"));
        out.add(qa.isPresent() ? Check.pass("qa-approved", "The academic QA report is approved")
                : Check.fail("qa-approved", "The topic's academic QA report isn't approved"));
        out.add(seo.isPresent() ? Check.pass("seo-approved", "The SEO report is approved")
                : Check.fail("seo-approved", "The topic's SEO report isn't approved"));
        if (notes.isPresent() && d.notesVersionId() != null && !notes.get().equals(d.notesVersionId())) {
            out.add(Check.warn("notes-version", "Made from an earlier version of the notes; the approved notes have changed since. Check it or make the deck again."));
        }

        // diagrams drawn and images present, so the export shows them
        List<String> undrawn = new ArrayList<>(), noImage = new ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            Slide s = slides.get(i);
            if (s.figure() != null && !drawn(projectId, s.figure().kind(), s.figure().source())) undrawn.add(String.valueOf(i + 1));
            if (s.image() != null && !imageOk(projectId, s.image().assetId())) noImage.add(String.valueOf(i + 1));
        }
        if (slides.stream().anyMatch(s -> s.figure() != null)) {
            out.add(undrawn.isEmpty() ? Check.pass("diagrams", "Every diagram is drawn")
                    : Check.fail("diagrams", "Diagram on slide " + String.join(", ", undrawn) + " isn't drawn yet or has an error. Open the slide to draw it."));
        }
        if (!noImage.isEmpty()) out.add(Check.fail("images", "The image on slide " + String.join(", ", noImage) + " is missing, or isn't an image in this project's media"));

        // the source checks, against the approved notes
        Optional<String> notesContent = notes.flatMap(this::content);
        if (notesContent.isPresent()) {
            Learning.Notes n = NotesRules.read(MaterialsService.read(notesContent.get()));
            String notesText = NoteDoc.plainText(n.doc());
            List<Integer> far = SlidesLogic.notInNotes(slides, notesText);
            out.add(far.isEmpty() ? Check.pass("source", "Each slide's content is found in the approved notes")
                    : Check.warn("source", "Slide " + String.join(", ", far.stream().map(String::valueOf).toList())
                    + ": most of the content isn't found in the approved notes. Check it against them."));
            List<String> numbers = SlidesLogic.numbersNotInNotes(slides, notesText);
            out.add(numbers.isEmpty() ? Check.pass("accuracy", "Every number on the slides appears in the notes")
                    : Check.warn("accuracy", "Numbers not found in the notes: " + String.join(", ", numbers.stream().limit(8).toList()) + ". Check each one."));
            List<String> refs = Slides.list(n.references()).stream().map(Learning.Reference::label).toList();
            List<String> invented = SlidesLogic.inventedReferences(slides, refs);
            out.add(invented.isEmpty() ? Check.pass("references-from-notes", "References come from the notes")
                    : Check.fail("references-from-notes", "References not in the notes: " + String.join("; ", invented.stream().limit(3).toList())
                    + ". Only the notes' references can be listed."));
        }
        return out;
    }
}
