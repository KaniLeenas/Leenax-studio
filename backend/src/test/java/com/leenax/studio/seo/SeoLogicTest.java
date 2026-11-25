package com.leenax.studio.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.seo.Seo.Idea;
import com.leenax.studio.seo.Seo.Metric;
import com.leenax.studio.seo.SeoLogic.Weights;
import com.leenax.studio.social.YouTubeClient.VideoInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeoLogicTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

    static VideoInfo video(String id, String title, String published, long seconds, Long views) {
        return new VideoInfo(id, title, "c", "Channel", published, "", List.of(), seconds, null, views, null, null, "en");
    }

    // ---- the specification's check: no number without a source or an "estimated" label ------------

    @Test
    void everyNumberNeedsASourceOrAnEstimatedLabel() {
        assertEquals(List.of(), SeoLogic.unlabelledNumbers(json("{'views':{'value':1200,'source':'YouTube Data API videos.list','retrievedAt':'x'},"
                + "'score':{'value':0.42,'estimated':true,'method':'m','inputs':{'R':{'value':1,'estimated':true}}},"
                + "'likes':{'value':null,'note':'Hidden by the channel'},'rank':3,'name':'n','ok':true}")));
        assertEquals(List.of("$.titles[0].views"), SeoLogic.unlabelledNumbers(json("{'titles':[{'text':'x','views':5000}]}")));
        assertEquals(List.of("$.score.value"), SeoLogic.unlabelledNumbers(json("{'score':{'value':0.9,'method':'guess'}}")));
        assertEquals(List.of("$.score.inputs.D.value"), SeoLogic.unlabelledNumbers(json("{'score':{'value':0.4,'estimated':true,'inputs':{'D':{'value':0.7}}}}")));
        assertEquals(List.of("$.likes.value (empty without a reason)"), SeoLogic.unlabelledNumbers(json("{'likes':{'value':null}}")));
        assertEquals(List.of("$.volume.value"), SeoLogic.unlabelledNumbers(json("{'volume':{'value':10000,'source':'  '}}")));
    }

    @Test
    void aPlantedUnlabelledNumberBlocksTheReport() {
        ObjectNode report = (ObjectNode) new SeoReportRules().normalise(sampleReport(), null);
        Context ctx = new Context(UUID.randomUUID(), null, k -> null, k -> null, k -> List.of(), List::of);
        List<Check> ok = new SeoReportRules().checks(report, ctx);
        assertTrue(ok.stream().allMatch(c -> c.ok() || !c.blocking()), ok.toString());
        report.put("searchVolume", 12000);
        List<Check> c = new SeoReportRules().checks(report, ctx);
        Check labels = c.stream().filter(x -> x.code().equals("labels")).findFirst().orElseThrow();
        assertFalse(labels.ok());
        assertTrue(labels.blocking());
        assertTrue(labels.message().contains("$.searchVolume"), labels.message());
    }

    @Test
    void saving_rebuildsNumbersSoTheyKeepTheirLabels() {
        ObjectNode raw = (ObjectNode) sampleReport();
        ((ObjectNode) raw.path("chapters").get(1).path("start")).remove("estimated");
        ((ObjectNode) raw.path("titles").get(0)).put("views", 99999);
        JsonNode saved = new SeoReportRules().normalise(raw, null);
        assertEquals(List.of(), SeoLogic.unlabelledNumbers(saved));
        assertTrue(saved.path("chapters").get(1).path("start").path("estimated").asBoolean());
        assertEquals(0, saved.path("chapters").get(0).path("start").path("value").asInt());
    }

    // ---- the opportunity score ---------------------------------------------------------------------

    @Test
    void competitionCountsRecentLongAndHighlyViewedResults() {
        List<VideoInfo> top = List.of(
                video("a", "3NF explained", "2026-06-01T00:00:00Z", 900, 250_000L),   // recent, long, many views
                video("b", "Normal forms", "2019-01-01T00:00:00Z", 300, 1_000L),       // none
                video("c", "DBMS 3NF", "2026-10-01T00:00:00Z", 200, null));            // recent only
        Metric c = SeoLogic.competition(top, NOW, Weights.defaults(), "2026-11-23T10:00:00Z");
        assertEquals(0.44, c.value());
        assertTrue(c.estimated());
        assertEquals(2.0, c.inputs().get("recent").value());
        assertEquals("Counted by Leenax", c.inputs().get("long").source());
        assertEquals(3.0, c.inputs().get("results").value());
        assertEquals(Seo.YT_SEARCH, c.inputs().get("results").source());
    }

    @Test
    void withoutTrendsTheScoreIsPartialWithHalfWeights() {
        Metric r = Metric.estimate(1.0, "0-1", "all words found");
        Metric c = Metric.estimate(0.4, "0-1", "c");
        Metric g = Metric.estimate(0.6, "0-1", "g");
        Metric partial = SeoLogic.score(r, null, c, g, Weights.defaults());
        assertEquals(0.6, partial.value());   // 0.5 × 0.6 + 0.5 × 0.6
        assertEquals("Partial: no Trends value", partial.note());
        assertEquals(0.5, partial.inputs().get("wC").value());
        assertNull(partial.inputs().get("D"));
        Metric d = SeoLogic.demand(List.of(10.0, 20.0, 60.0, 80.0), "multiTimeline.csv", "2026-11-20T00:00:00Z", "Sri Lanka");
        assertEquals(0.43, d.value());
        assertTrue(d.source().contains("multiTimeline.csv"));
        Metric full = SeoLogic.score(r, d, c, g, Weights.defaults());
        assertEquals(0.53, full.value());     // 0.4 × 0.43 + 0.3 × 0.6 + 0.3 × 0.6 = 0.532
        assertNull(full.note());
        assertTrue(full.method().contains("not a search volume"));
        assertEquals(List.of(), SeoLogic.unlabelledNumbers(JSON.valueToTree(full)));
        assertNull(SeoLogic.score(r, null, null, g, Weights.defaults()), "no score without YouTube data");
    }

    @Test
    void relevanceAndGapAreReadFromWords() {
        Metric r = SeoLogic.relevance("third normal form tutorial", "Normalisation\nThird normal form removes transitive dependencies");
        assertEquals(1.0, r.value());
        assertEquals(0.5, SeoLogic.relevance("bcnf normal", "normal forms").value());
        Metric g = SeoLogic.gap("transitive dependency", List.of(video("a", "Transitive dependency in 3NF", null, 1, 1L), video("b", "SQL joins", null, 1, 1L)));
        assertEquals(0.5, g.value());
    }

    // ---- Google Trends CSV -------------------------------------------------------------------------

    @Test
    void trendsFilesAreReadInBothLayouts() {
        SeoLogic.Trends t = SeoLogic.parseTrends("Category: All categories\n\nWeek,normalization: (Sri Lanka),சார்பு நீக்கம்: (Sri Lanka)\n"
                + "2025-11-23,45,<1\n2025-11-30,100,3\n");
        assertEquals("Sri Lanka", t.geo());
        assertEquals("WEEK", t.period());
        assertEquals(List.of("normalization", "சார்பு நீக்கம்"), t.terms());
        assertEquals(List.of(0.5, 3.0), t.series().get("சார்பு நீக்கம்"));
        assertEquals("2025-11-30", t.last().toString());
        SeoLogic.Trends n = SeoLogic.parseTrends("﻿Time,\"3nf, dbms\"\n2025-01,12\n2025-02,20\n");
        assertEquals("OTHER", n.period());
        assertEquals(List.of("3nf, dbms"), n.terms());
        assertEquals("3nf, dbms", SeoLogic.matchTerm("3NF DBMS", List.of("normalization", "3nf, dbms")));
        assertThrows(ApiException.class, () -> SeoLogic.parseTrends("name,views\nx,100000\n"));
        assertThrows(ApiException.class, () -> SeoLogic.parseTrends("Week,a\n2025-01-01,150\n"));
        assertThrows(ApiException.class, () -> SeoLogic.parseTrends("Week,a\nlast week,10\n"));
    }

    // ---- titles ------------------------------------------------------------------------------------

    @Test
    void titlesAreCheckedForLengthKeywordsAndClickbait() {
        List<String> kw = List.of("third normal form");
        assertEquals(List.of(), SeoLogic.checkTitle("Third Normal Form (3NF) in DBMS, explained with examples", kw, null, null, null).warnings());
        assertTrue(SeoLogic.checkTitle("You WON'T BELIEVE this normal form trick!!", kw, null, null, null).warnings().contains("Sounds like clickbait"));
        assertTrue(SeoLogic.checkTitle("x".repeat(101), kw, null, null, null).warnings().get(0).startsWith("Longer than YouTube allows"));
        assertFalse(SeoLogic.usable(SeoLogic.checkTitle("x".repeat(101), kw, null, null, null)));
        assertTrue(SeoLogic.checkTitle("A ".repeat(40), kw, null, null, null).warnings().stream().anyMatch(w -> w.startsWith("May be cut off")));
        assertTrue(SeoLogic.checkTitle("SQL joins for beginners", kw, null, null, null).warnings().contains("Has none of the primary keywords"));
        assertEquals(26.0, SeoLogic.checkTitle("மூன்றாம் இயல்பு நிலை (3NF)", List.of(), null, null, null).length().value());
    }

    @Test
    void editingATitleDropsTheAisScoresButKeepsTheRules() {
        Map<String, Metric> ai = Map.of("clarity", Metric.estimate(4, "1-5", "AI"));
        Seo.TitleCheck scored = SeoLogic.checkTitle("Third normal form explained", List.of(), ai, Metric.estimate(4, "1-5", "mean"), "clear");
        assertNotNull(SeoLogic.recheck("Third normal form explained", scored, List.of()).ai());
        Seo.TitleCheck edited = SeoLogic.recheck("Third normal form, explained simply", scored, List.of());
        assertNull(edited.ai());
        assertNull(edited.overall());
        assertTrue(edited.warnings().contains("Edited after the AI scored it: not scored"));
    }

    // ---- reading the AI ----------------------------------------------------------------------------

    @Test
    void keywordIdeasKeepOnlyTheLanguagesAskedAndNoRepeats() {
        List<Idea> ideas = SeoAi.readIdeas(json("{'keywords':[{'text':'3NF in DBMS','language':'en','kind':'primary','intent':'LEARN'},"
                + "{'text':'3nf  in dbms','language':'en','kind':'SECONDARY'},{'text':'மூன்றாம் இயல்பு நிலை','language':'Tamil','kind':'question'},"
                + "{'text':'3NF en español','language':'es','kind':'PRIMARY'},{'text':'','language':'en'}]}"), List.of("en", "ta"), "gpt-x");
        assertEquals(2, ideas.size());
        assertEquals("PRIMARY", ideas.get(0).kind());
        assertEquals("ta", ideas.get(1).language());
        assertEquals("QUESTION", ideas.get(1).kind());
        assertEquals("AI suggestion (gpt-x)", ideas.get(0).source());
        List<String> q = SeoService.pickQueries(List.of(new Idea("a1", "en", "SECONDARY", null, null, null), new Idea("a2", "en", "PRIMARY", null, null, null),
                new Idea("t1", "ta", "PRIMARY", null, null, null), new Idea("a3", "en", "QUESTION", null, null, null)), List.of("en", "ta"), 3);
        assertEquals(List.of("a2", "t1", "a3"), q);
    }

    @Test
    void thePlanIsReadWithItsScoresLabelledAndAClickbaitPickReplaced() {
        StringBuilder titles = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            titles.append(i == 0 ? "" : ",").append("{'text':'Third normal form lesson ").append(i).append("','style':'TUTORIAL','language':'en',"
                    + "'scores':{'clarity':").append(i % 5 + 1).append(",'accuracy':5},'reasoning':'r'}");
        }
        titles.append(",{'text':'SHOCKING normal form secret!!','style':'EXAM','language':'en','scores':{'clarity':5,'accuracy':5}}");
        JsonNode reply = json("{'analysis':{'titleStrategies':'Most use 3NF in the title','contentGaps':['BCNF']},'titles':[" + titles
                + "],'recommended':11,'recommendedReason':'Most clicks','description':'About 3NF','tags':['3nf','#dbms','3nf'],"
                + "'hashtags':['dbms','#3NF'],'chapters':[{'title':'Intro','startSeconds':15},{'title':'2NF','startSeconds':120}],"
                + "'strategies':{'SHORTS':{'titleIdea':'3NF in 60 seconds','keywords':['3nf']}}}");
        SeoAi.Plan p = SeoAi.readPlan(reply, List.of("third normal form"), List.of("en"), "gpt-x");
        assertEquals(12, p.titles().size());
        assertNotNull(p.recommendedId());
        assertFalse(p.titles().stream().filter(t -> t.id().equals(p.recommendedId())).findFirst().orElseThrow().text().contains("SHOCKING"));
        assertTrue(p.recommendedReason().startsWith("Chosen by Leenax"));
        assertEquals(List.of("3nf", "dbms"), p.tags());
        assertEquals(List.of("#dbms", "#3NF"), p.hashtags());
        assertEquals(0.0, p.chapters().get(0).start().value(), "YouTube needs the first chapter at 0:00");
        assertTrue(p.chapters().get(1).start().estimated());
        assertTrue(p.titles().get(0).check().ai().get("clarity").estimated());
        assertEquals(List.of(), SeoLogic.unlabelledNumbers(JSON.valueToTree(p)));
    }

    @Test
    void tagsStayWithinYouTubesLimit() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) many.add("normalisation tag " + i);
        List<String> t = SeoAi.tags(many);
        int total = t.stream().mapToInt(x -> x.length() + (x.contains(" ") ? 2 : 0)).sum() + t.size() - 1;
        assertTrue(total <= Seo.TAGS_MAX_CHARS, String.valueOf(total));
    }

    // ---- a minimal report --------------------------------------------------------------------------

    static JsonNode sampleReport() {
        StringBuilder titles = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            titles.append(i == 1 ? "" : ",").append("{'id':'t").append(i).append("','text':'Third normal form explained, part ").append(i)
                    .append("','style':'EDUCATIONAL','language':'en'}");
        }
        return json("{'topicId':'t5','topicTitle':'1NF to 3NF','researchId':'" + UUID.randomUUID() + "','titles':[" + titles + "],"
                + "'chosenTitleId':'t1','description':'A lesson on 3NF.','primaryKeywords':['third normal form'],'tags':['3nf'],"
                + "'chapters':[{'title':'Intro','start':{'value':0,'estimated':true}},{'title':'3NF','start':{'value':95,'estimated':true}},"
                + "{'title':'Recap','start':{'value':400,'estimated':true}}],"
                + "'strategies':{'LESSON':{},'MCQ':{},'EXAM':{},'REVISION':{},'SHORTS':{}}}");
    }
}
