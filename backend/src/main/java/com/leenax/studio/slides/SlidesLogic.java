package com.leenax.studio.slides;

import com.leenax.studio.documents.DocumentText;
import com.leenax.studio.qa.QaRules;
import com.leenax.studio.slides.Slides.Slide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source checks against the approved notes a deck was made from. They are word checks, not
 * judgements: a slide whose words aren't in the notes, a number the notes don't contain, or a
 * reference the notes don't list is pointed out for a person to look at.
 */
public final class SlidesLogic {

    private SlidesLogic() {
    }

    static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?(?![\\p{L}\\p{N}])");

    /** All the words a slide shows (title, bullets, term, text, table, caption, code). */
    public static String text(Slide s) {
        StringBuilder sb = new StringBuilder();
        add(sb, s.title());
        add(sb, s.subtitle());
        Slides.list(s.bullets()).forEach(b -> add(sb, b));
        add(sb, s.term());
        add(sb, s.text());
        if (s.table() != null) {
            Slides.list(s.table().headers()).forEach(h -> add(sb, h));
            Slides.list(s.table().rows()).forEach(r -> Slides.list(r).forEach(c -> add(sb, c)));
        }
        add(sb, s.caption());
        if (s.code() != null) add(sb, s.code().text());
        return sb.toString();
    }

    /**
     * Slides (1-based) whose content isn't found in the notes: each bullet, the definition and the
     * table rows are looked for; a slide is listed when fewer than half of them are found.
     * Title, references and revision slides are left out (they name or ask, not state).
     */
    public static List<Integer> notInNotes(List<Slide> slides, String notesText) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            Slide s = slides.get(i);
            if (List.of("TITLE", "REFERENCES", "REVISION").contains(s.type())) continue;
            List<String> claims = new ArrayList<>(Slides.list(s.bullets()));
            if (s.text() != null) claims.add(s.text());
            if (s.term() != null) claims.add(s.term());
            if (s.table() != null) Slides.list(s.table().rows()).forEach(r -> claims.add(String.join(" ", Slides.list(r))));
            if (claims.isEmpty()) continue;
            long found = claims.stream().filter(c -> QaRules.covered(c, notesText)).count();
            if (found * 2 < claims.size()) out.add(i + 1);
        }
        return out;
    }

    /** Numbers on the slides (not in code, not slide numbering) that the notes don't contain. */
    public static List<String> numbersNotInNotes(List<Slide> slides, String notesText) {
        Set<String> have = numbers(notesText);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < slides.size(); i++) {
            Slide s = slides.get(i);
            if (List.of("TITLE", "REFERENCES").contains(s.type())) continue;
            Slide noCode = new Slide(s.id(), s.type(), s.layout(), s.title(), s.subtitle(), s.bullets(), s.term(), s.text(), s.table(), null, null,
                    s.caption(), null, null, null);
            for (String n : numbers(text(noCode))) {
                if (!have.contains(n)) out.add(n + " (slide " + (i + 1) + ")");
            }
        }
        return new ArrayList<>(out);
    }

    /** Reference lines on references slides that aren't among the notes' references. */
    public static List<String> inventedReferences(List<Slide> slides, List<String> noteReferences) {
        Set<String> known = new HashSet<>();
        noteReferences.forEach(r -> known.add(key(r)));
        List<String> out = new ArrayList<>();
        for (Slide s : slides) {
            if (!"REFERENCES".equals(s.type())) continue;
            for (String b : Slides.list(s.bullets())) if (!known.contains(key(b))) out.add(b);
        }
        return out;
    }

    static Set<String> numbers(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) out.add(m.group().replace(',', '.'));
        return out;
    }

    static String key(String s) {
        return String.join(" ", DocumentText.words(s == null ? "" : s.toLowerCase(Locale.ROOT)));
    }

    private static void add(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) sb.append(s).append('\n');
    }
}
