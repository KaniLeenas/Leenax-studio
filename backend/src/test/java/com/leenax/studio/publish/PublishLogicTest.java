package com.leenax.studio.publish;

import com.leenax.studio.publish.PlatformRules.MediaFacts;
import com.leenax.studio.social.SocialPlatform;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.leenax.studio.publish.PostTarget.Status.AWAITING_USER;
import static com.leenax.studio.publish.PostTarget.Status.CANCELLED;
import static com.leenax.studio.publish.PostTarget.Status.DRAFT;
import static com.leenax.studio.publish.PostTarget.Status.FAILED;
import static com.leenax.studio.publish.PostTarget.Status.PROCESSING;
import static com.leenax.studio.publish.PostTarget.Status.PUBLISHED;
import static com.leenax.studio.publish.PostTarget.Status.SCHEDULED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishLogicTest {

    private static final MediaFacts SHORT = new MediaFacts(20_000_000, 30_000L, 1080, 1920);

    private static List<String> errors(SocialPlatform p, Map<String, Object> s, MediaFacts m) {
        List<String> e = new ArrayList<>();
        PlatformRules.normalize(p, s, m, e);
        return e;
    }

    @Test
    void youtubeNeedsTitlePrivacyAndKidsAnswer() {
        List<String> e = errors(SocialPlatform.YOUTUBE, Map.of(), SHORT);
        assertEquals(3, e.size(), e.toString());
        Map<String, Object> ok = new HashMap<>(Map.of("title", "My video", "privacy", "unlisted", "madeForKids", false,
                "tags", List.of("#travel", "travel", "Sri Lanka")));
        List<String> none = new ArrayList<>();
        Map<String, Object> clean = PlatformRules.normalize(SocialPlatform.YOUTUBE, ok, SHORT, none);
        assertTrue(none.isEmpty(), none.toString());
        assertEquals(List.of("travel", "Sri Lanka"), clean.get("tags"));
        assertEquals("22", clean.get("categoryId"));
        assertTrue(errors(SocialPlatform.YOUTUBE, Map.of("title", "a<b", "privacy", "public", "madeForKids", true), SHORT)
                .get(0).contains("< or >"));
        assertEquals(1, errors(SocialPlatform.YOUTUBE, Map.of("title", "x".repeat(101), "privacy", "public", "madeForKids", true), SHORT).size());
    }

    @Test
    void reelLengthLimitsDifferBetweenFacebookAndInstagram() {
        MediaFacts twoMinutes = new MediaFacts(50_000_000, 120_000L, 1080, 1920);
        assertEquals(1, errors(SocialPlatform.FACEBOOK, Map.of(), twoMinutes).size());
        assertTrue(errors(SocialPlatform.INSTAGRAM, Map.of(), twoMinutes).isEmpty());
        assertEquals(1, errors(SocialPlatform.INSTAGRAM, Map.of(), new MediaFacts(400L * 1024 * 1024, 60_000L, 1080, 1920)).size());
        assertEquals(1, errors(SocialPlatform.INSTAGRAM, Map.of(), new MediaFacts(1000, 2_000L, 1080, 1920)).size());
        assertEquals(1, errors(SocialPlatform.FACEBOOK, Map.of(), new MediaFacts(1000, 10_000L, 480, 854)).size());
        String tags = "#a ".repeat(31);
        assertEquals(1, errors(SocialPlatform.INSTAGRAM, Map.of("caption", tags), SHORT).size());
        assertEquals(31, PlatformRules.hashtags(tags));
        assertEquals(0, PlatformRules.hashtags("email me at a#b"));
    }

    @Test
    void tiktokRequiresAnExplicitPrivacyChoiceAndConsent() {
        List<String> e = errors(SocialPlatform.TIKTOK, Map.of("caption", "hi"), SHORT);
        assertEquals(2, e.size(), e.toString());
        assertTrue(errors(SocialPlatform.TIKTOK, Map.of("privacy", "SELF_ONLY", "musicConsent", true), SHORT).isEmpty());
        assertEquals(1, errors(SocialPlatform.TIKTOK, Map.of("privacy", "SELF_ONLY", "musicConsent", true, "brandContent", true), SHORT).size());
        assertTrue(errors(SocialPlatform.TIKTOK, Map.of("mode", "INBOX"), SHORT).isEmpty());
        Map<String, Object> clean = PlatformRules.normalize(SocialPlatform.TIKTOK,
                Map.of("privacy", "PUBLIC_TO_EVERYONE", "musicConsent", true), SHORT, new ArrayList<>());
        assertEquals(false, clean.get("allowComment"));   // interactions are off unless chosen
    }

    @Test
    void postStatusRollsUpFromTargets() {
        assertEquals(Post.Status.DRAFT, PublishStore.rollup(Post.Status.DRAFT, List.of(DRAFT, DRAFT)));
        assertEquals(Post.Status.SCHEDULED, PublishStore.rollup(Post.Status.DRAFT, List.of(SCHEDULED, SCHEDULED)));
        assertEquals(Post.Status.PUBLISHING, PublishStore.rollup(Post.Status.SCHEDULED, List.of(SCHEDULED, PROCESSING)));
        assertEquals(Post.Status.PUBLISHING, PublishStore.rollup(Post.Status.SCHEDULED, List.of(SCHEDULED, PUBLISHED)));
        assertEquals(Post.Status.PUBLISHED, PublishStore.rollup(Post.Status.PUBLISHING, List.of(PUBLISHED, AWAITING_USER)));
        assertEquals(Post.Status.PARTIAL, PublishStore.rollup(Post.Status.PUBLISHING, List.of(PUBLISHED, FAILED)));
        assertEquals(Post.Status.FAILED, PublishStore.rollup(Post.Status.PUBLISHING, List.of(FAILED, CANCELLED)));
        assertEquals(Post.Status.CANCELLED, PublishStore.rollup(Post.Status.SCHEDULED, List.of(CANCELLED)));
        assertEquals(Post.Status.DRAFT, PublishStore.rollup(Post.Status.DRAFT, List.of()));
    }

    @Test
    void retriesBackOffUpToAnHour() {
        assertEquals(Duration.ofSeconds(30), PublishProcessor.backoff(1));
        assertEquals(Duration.ofSeconds(60), PublishProcessor.backoff(2));
        assertEquals(Duration.ofSeconds(3600), PublishProcessor.backoff(12));
    }

    @Test
    void schedulingRejectsPastAndFarFutureTimes() {
        java.time.Instant now = java.time.Instant.now();
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> PublishService.checkTime(now.minusSeconds(3600)));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> PublishService.checkTime(now.plus(Duration.ofDays(400))));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> PublishService.checkTime(null));
        assertTrue(!PublishService.checkTime(now.minusSeconds(10)).isBefore(now));
    }

    @Test
    void schedulingATargetClearsEarlierProgress() {
        PostTarget t = new PostTarget(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), SocialPlatform.YOUTUBE, "Ch", Map.of());
        t.getState().put("videoId", "old");
        t.setStatus(FAILED);
        t.setErrorMessage("boom");
        t.setAttempts(4);
        t.schedule(java.time.Instant.EPOCH);
        assertEquals(SCHEDULED, t.getStatus());
        assertTrue(t.getState().isEmpty());
        assertEquals(0, t.getAttempts());
        assertEquals(null, t.getErrorMessage());
    }

    @Test
    void tamilHashtagsAreWholeWords() {
        assertEquals(3, PlatformRules.hashtags("#தமிழ் #மதுரை food #madurai"));
        assertEquals(1, PlatformRules.hashtags("சாப்பாடு#தமிழ் #உணவு"));   // a # inside a word isn't a hashtag
    }
}
