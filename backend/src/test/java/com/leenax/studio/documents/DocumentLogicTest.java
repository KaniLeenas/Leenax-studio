package com.leenax.studio.documents;

import com.leenax.studio.documents.DocumentDtos.SearchHit;
import com.leenax.studio.documents.DocumentDtos.SubjectCheck;
import com.leenax.studio.documents.DocumentStore.Candidate;
import com.leenax.studio.documents.DocumentText.Chunk;
import com.leenax.studio.documents.DocumentText.PageText;
import com.leenax.studio.documents.DocumentText.Snippet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentLogicTest {

    // ---- words -------------------------------------------------------------------------------------

    @Test
    void tamilWordsKeepTheirVowelSignsAndPulli() {
        assertEquals(List.of("தரவுத்தள", "முறைமைகள்", "பற்றிய"), DocumentText.words("தரவுத்தள முறைமைகள், பற்றிய."));
        // OCR's zero-width non-joiner after pulli ends the word without breaking it.
        assertEquals(List.of("முறைமைகள்", "பற்றிய"), DocumentText.words("முறைமைகள்\u200c பற்றிய"));
    }

    @Test
    void termsAreDistinctLowerCaseAndSkipSingleLetters() {
        assertArrayEquals(new String[]{"normal", "form", "3nf", "is"},
                DocumentText.terms("Normal form a 3NF is normal FORM"));
    }

    @Test
    void decomposedTamilIsNormalisedSoSearchStillMatches() {
        // "கொ" typed as KA + E + AA (decomposed) and as KA + O (composed) are the same word.
        String decomposed = "\u0B95\u0BC6\u0BBE\u0B9F\u0BC1";
        String composed = "\u0B95\u0BCA\u0B9F\u0BC1";
        assertEquals(DocumentText.words(composed), DocumentText.words(decomposed));
    }

    @Test
    void queryWordsAreCappedAtEight() {
        assertEquals(8, DocumentText.queryWords("aa bb cc dd ee ff gg hh ii jj").size());
        assertEquals(List.of("acid"), DocumentText.queryWords("  a ACID !! "));
        assertTrue(DocumentText.queryWords("% _ ' ;").isEmpty());
    }

    // ---- passages ----------------------------------------------------------------------------------

    @Test
    void passagesNeverCrossPagesAndKeepPageNumbers() {
        List<Chunk> chunks = DocumentText.chunk(List.of(
                new PageText(1, "First paragraph.\n\nSecond paragraph."),
                new PageText(2, ""),
                new PageText(3, "Third page.")));
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).pageNo());
        assertEquals("First paragraph.\n\nSecond paragraph.", chunks.get(0).text());
        assertEquals(3, chunks.get(1).pageNo());
        assertEquals(1, chunks.get(1).ordinal());
    }

    @Test
    void longPagesSplitAtSentencesAndNothingIsLost() {
        String sentence = "Concurrency control keeps transactions isolated from each other. ";
        String page = sentence.repeat(60) + "\n\n" + "பரிவர்த்தனைகள் ".repeat(200);
        List<Chunk> chunks = DocumentText.chunk(List.of(new PageText(7, page)));
        assertTrue(chunks.size() >= 4, "got " + chunks.size());
        for (Chunk c : chunks) {
            assertEquals(7, c.pageNo());
            assertTrue(c.text().length() <= DocumentText.MAX_CHARS + 2, "too long: " + c.text().length());
        }
        String joined = String.join(" ", chunks.stream().map(Chunk::text).toList());
        assertEquals(DocumentText.words(page), DocumentText.words(joined));
        assertTrue(chunks.get(0).text().endsWith("other."));
    }

    @Test
    void aWordLongerThanAPassageIsCutOutsideCombiningMarks() {
        String longTamil = "கொ".repeat(1200); // no spaces at all
        for (Chunk c : DocumentText.chunk(List.of(new PageText(1, longTamil)))) {
            int first = c.text().codePointAt(0);
            int type = Character.getType(first);
            assertFalse(type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK);
        }
    }

    // ---- ranking and snippets ----------------------------------------------------------------------

    @Test
    void prefixSearchFindsTamilWordsWithEndings() {
        List<String> q = DocumentText.queryWords("கணினி");
        Snippet s = DocumentText.snippet("இது கணினியின் நினைவகம் பற்றியது", q);
        assertEquals(1, s.highlights().size());
        assertEquals("கணினியின்", s.text().substring(s.highlights().get(0).start(), s.highlights().get(0).end()));
    }

    @Test
    void snippetIsAWindowAroundTheFirstMatchWithEveryMatchMarked() {
        String text = "Intro words ".repeat(30) + "The ACID properties: atomicity, consistency, isolation, durability. "
                + "ACID matters. " + "Tail words ".repeat(40);
        Snippet s = DocumentText.snippet(text, List.of("acid"));
        assertTrue(s.cutBefore() && s.cutAfter());
        assertTrue(s.text().length() <= DocumentText.SNIPPET_CHARS);
        assertEquals(2, s.highlights().size());
        s.highlights().forEach(h -> assertEquals("ACID", s.text().substring(h.start(), h.end())));
        assertFalse(s.text().startsWith(" "));
    }

    @Test
    void wholeWordAndMoreQueryWordsRankHigher() {
        List<String> q = List.of("normal", "form");
        int both = DocumentText.score("third normal form", q);
        int prefixOnly = DocumentText.score("normalisation of tables", q);
        int whole = DocumentText.score("normal tables", q);
        assertTrue(both > whole && whole > prefixOnly);
    }

    @Test
    void searchResultsAreRankedAndCapped() {
        UUID d = UUID.randomUUID(), v = UUID.randomUUID();
        List<Candidate> found = new java.util.ArrayList<>();
        for (int i = 1; i <= 50; i++) found.add(new Candidate(d, "Lecture", v, 1, i, "index words"));
        found.add(new Candidate(d, "Lecture", v, 1, 99, "index index index words"));
        List<SearchHit> hits = DocumentService.rank(found, List.of("index"));
        assertEquals(DocumentService.SEARCH_RESULTS, hits.size());
        assertEquals(99, hits.get(0).pageNo());
        assertEquals(1, hits.get(1).pageNo());
    }

    // ---- subject check -----------------------------------------------------------------------------

    @Test
    void subjectCheckComparesModuleCodesLoosely() {
        assertEquals("MATCH", SubjectCheck.of(List.of("CSC2041"), "csc 2041").result());
        assertEquals("MATCH", SubjectCheck.of(List.of("IT1050", "CSC2041"), "CSC-2041").result());
        assertEquals("DIFFERENT", SubjectCheck.of(List.of("IT1050"), "CSC2041").result());
        assertEquals("UNKNOWN", SubjectCheck.of(List.of(), "CSC2041").result());
        assertEquals("UNKNOWN", SubjectCheck.of(List.of("IT1050"), " ").result());
    }

    // ---- file types --------------------------------------------------------------------------------

    @Test
    void acceptsCourseDocumentsByContent() {
        assertEquals("pdf", type("%PDF-1.7 ...", "notes.bin"));
        assertEquals("docx", type("PK\u0003\u0004....[Content_Types].xml word/document.xml", "x"));
        assertEquals("pptx", type("PK\u0003\u0004....ppt/slides/slide1.xml", "x"));
        assertEquals("odt", type("PK\u0003\u0004mimetypeapplication/vnd.oasis.opendocument.text", "x"));
        assertEquals("doc", DocumentTypes.detect(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1), "old.DOC").orElseThrow().extension());
        assertEquals("jpg", DocumentTypes.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0), "scan").orElseThrow().extension());
        assertEquals("tiff", type("II*\0rest", "scan.tif"));
        assertEquals("txt", type("சுருக்கம்: lecture notes", "notes.txt"));
    }

    @Test
    void refusesEverythingElse() {
        assertTrue(DocumentTypes.detect("<svg xmlns='x'/>".getBytes(StandardCharsets.UTF_8), "a.svg").isEmpty());
        assertTrue(DocumentTypes.detect("MZ\u0090\u0000".getBytes(StandardCharsets.ISO_8859_1), "a.exe").isEmpty());
        assertTrue(DocumentTypes.detect("<html>hi</html>".getBytes(StandardCharsets.UTF_8), "a.html").isEmpty());
        assertTrue(DocumentTypes.detect("PK\u0003\u0004 something else".getBytes(StandardCharsets.ISO_8859_1), "a.zip").isEmpty());
        assertTrue(DocumentTypes.detect(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1), "a.xls").isEmpty());
        assertTrue(DocumentTypes.detect(new byte[]{'a', 'b', 0, 'c'}, "a.txt").isEmpty());
    }

    @Test
    void utf8TextCutMidCharacterStillCountsAsText() {
        byte[] tamil = "தமிழ்".getBytes(StandardCharsets.UTF_8);
        byte[] cut = java.util.Arrays.copyOf(tamil, tamil.length - 1);
        assertTrue(DocumentTypes.isUtf8Text(cut));
        assertFalse(DocumentTypes.isUtf8Text(bytes(0xC3, 0x28, 0x41, 0x42, 0x43)));
    }

    // ---- the document service's answers ------------------------------------------------------------

    @Test
    void readsTheServiceAnswer() {
        String json = """
                {"pageCount":2,"pagesRead":2,"truncated":false,"ocrPages":1,"converted":true,"language":"mixed",
                 "moduleCodes":["CSC2041"],"title":"CSC2041 Database Systems","extra":1,
                 "pages":[{"page":1,"text":"a","method":"TEXT","confidence":null},{"page":2,"text":"b","method":"OCR","confidence":91.5}]}
                """;
        DocumentReader.Result r = DocumentReader.parse(200, json.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, r.pages().size());
        assertEquals(Double.valueOf(91.5), r.pages().get(1).confidence());
        assertEquals(List.of("CSC2041"), r.moduleCodes());
    }

    @Test
    void badFilesFailForGoodAndServiceTroubleIsRetried() {
        DocumentReader.ReadFailure bad = assertThrows(DocumentReader.ReadFailure.class,
                () -> DocumentReader.parse(422, "{\"detail\":\"This PDF can't be opened (damaged or password-protected)\"}".getBytes()));
        assertFalse(bad.retryable());
        assertEquals("This PDF can't be opened (damaged or password-protected)", bad.getMessage());
        assertTrue(assertThrows(DocumentReader.ReadFailure.class, () -> DocumentReader.parse(503, "oops".getBytes())).retryable());
        assertTrue(assertThrows(DocumentReader.ReadFailure.class, () -> DocumentReader.parse(200, "{".getBytes())).retryable());
    }

    @Test
    void retriesWaitLongerEachTime() {
        assertEquals(60, DocumentProcessor.retryDelaySeconds(1));
        assertEquals(300, DocumentProcessor.retryDelaySeconds(2));
    }

    // ---- names -------------------------------------------------------------------------------------

    @Test
    void titlesAndFilenamesAreCleaned() {
        assertEquals("Lecture 3 Normalisation", DocumentService.titleFrom("C:\\Users\\kani\\Lecture_3__Normalisation.pdf"));
        assertEquals("Untitled document", DocumentService.titleFrom(".pdf"));
        assertEquals("பாடம் 1.docx", DocumentService.titleFrom("பாடம் 1.docx.docx"));
        assertEquals("evil.pdf", DocumentService.cleanFilename("../../evil.pdf", "pdf"));
        assertEquals("document.pdf", DocumentService.cleanFilename("", "pdf"));
        assertEquals("{\"CSC2041\",\"a\\\"b\"}", DocumentStore.pgArray(new String[]{"CSC2041", "a\"b"}));
        assertEquals("unknown", DocumentStore.language("fr"));
        assertEquals("ab", DocumentStore.stripNul("a\0b"));
    }

    private static String type(String header, String filename) {
        return DocumentTypes.detect(header.getBytes(StandardCharsets.UTF_8), filename).map(DocumentTypes.Type::extension).orElse(null);
    }

    private static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }
}
