package com.leenax.studio.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.Review.Status;
import com.leenax.studio.approvals.ReviewStore;
import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.notes.MaterialsService;
import com.leenax.studio.projects.ProjectService;
import com.leenax.studio.publish.Authorisations;
import com.leenax.studio.publish.PublishDtos.Action;
import com.leenax.studio.publish.PublishDtos.PostRequest;
import com.leenax.studio.publish.PublishDtos.PostResponse;
import com.leenax.studio.publish.PublishDtos.TargetRequest;
import com.leenax.studio.publish.PublishService;
import com.leenax.studio.social.SocialAccount;
import com.leenax.studio.social.SocialAccountRepository;
import com.leenax.studio.social.SocialAccountService;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialPlatform;
import com.leenax.studio.social.YouTubeClient;
import com.leenax.studio.videos.VideoStore;
import com.leenax.studio.videos.VideoStore.RenderRow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * YouTube Publishing Manager (E9, module 22): approved lesson and quiz videos, published with the
 * approved SEO title, description and tags, after an explicit authorisation by the project owner
 * or an administrator. The post itself runs through the studio's publisher (Phase 6), which
 * uploads, retries and records the video id and address.
 */
@Service
public class EduPublishService {

    /** YouTube Data API quota costs of the calls a publication makes (units; the default daily quota is 10,000). */
    static final int UNITS_UPLOAD = 1600, UNITS_THUMBNAIL = 50, UNITS_PLAYLIST_ITEM = 50, UNITS_CAPTION = 400, UNITS_STATUS_POLLS = 10;
    static final String CATEGORY_EDUCATION = "27";

    private final ReviewStore reviews;
    private final VideoStore videos;
    private final YouTubeStore store;
    private final ProjectService projects;
    private final PublishService publish;
    private final SocialAccountRepository accountRows;
    private final SocialAccountService accounts;
    private final YouTubeClient youtube;
    private final AuditService audit;

    public EduPublishService(ReviewStore reviews, VideoStore videos, YouTubeStore store, ProjectService projects, PublishService publish,
                             SocialAccountRepository accountRows, SocialAccountService accounts, YouTubeClient youtube, AuditService audit) {
        this.reviews = reviews;
        this.videos = videos;
        this.store = store;
        this.projects = projects;
        this.publish = publish;
        this.accountRows = accountRows;
        this.accounts = accounts;
        this.youtube = youtube;
        this.audit = audit;
    }

    // ---- reading -------------------------------------------------------------------------------------

    public record Channel(UUID id, String name, String username, String avatarUrl, String status, boolean canManage, boolean canReadAnalytics,
                          String lastError) {
    }

    public record Ready(UUID resourceId, String kind, String level, String topicId, String title, UUID versionId, int versionNo,
                        UUID mediaId, boolean seoApproved, boolean captionsAvailable, List<UUID> publicationIds) {
    }

    public record Overview(boolean canAuthorise, List<Channel> channels, List<Ready> videos, List<YouTubeStore.Publication> publications,
                           Map<String, Integer> quotaUnits) {
    }

    public Overview overview(UUID projectId) {
        projects.requireAccess(projectId, false, null);
        List<YouTubeStore.Publication> pubs = store.publications(projectId);
        List<Ready> ready = new ArrayList<>();
        for (Kind k : List.of(Kind.LESSON_VIDEO, Kind.QUIZ_VIDEO)) {
            for (ResourceRow r : reviews.resources(projectId, k)) {
                Optional<Checked> c = checked(r);
                if (c.isEmpty()) continue;
                boolean seo = videos.approved(projectId, Kind.SEO, r.topicId(), null).isPresent();
                List<UUID> mine = pubs.stream().filter(p -> p.resourceId().equals(r.id())).map(YouTubeStore.Publication::id).toList();
                ready.add(new Ready(r.id(), k == Kind.LESSON_VIDEO ? "LESSON" : "QUIZ", r.level(), r.topicId(), r.title(), c.get().version().id(),
                        c.get().version().versionNo(), c.get().render().outputMediaId(), seo, !captions(c.get()).isEmpty(), mine));
            }
        }
        Map<String, Integer> units = new LinkedHashMap<>();
        units.put("upload", UNITS_UPLOAD);
        units.put("thumbnail", UNITS_THUMBNAIL);
        units.put("playlistItem", UNITS_PLAYLIST_ITEM);
        units.put("caption", UNITS_CAPTION);
        units.put("dailyDefault", 10_000);
        return new Overview(projects.isOwner(projectId), channels(), ready, pubs, units);
    }

    List<Channel> channels() {
        List<Channel> out = new ArrayList<>();
        for (SocialAccount a : accountRows.findAllByOrderByPlatformAscDisplayNameAsc()) {
            if (a.getPlatform() != SocialPlatform.YOUTUBE) continue;
            out.add(new Channel(a.getId(), a.getDisplayName(), a.getUsername(), a.getAvatarUrl(), a.getStatus().name(),
                    YouTubeClient.granted(a.getScopes(), YouTubeClient.SCOPE_MANAGE), YouTubeClient.granted(a.getScopes(), YouTubeClient.SCOPE_ANALYTICS),
                    a.getLastError()));
        }
        return out;
    }

    /** The approved version of a video and its render that passed the file checks. */
    record Checked(ResourceRow resource, VersionRow version, RenderRow render) {
    }

    Optional<Checked> checked(ResourceRow r) {
        Optional<VersionRow> v = reviews.approved(r.id());
        if (v.isEmpty()) return Optional.empty();
        VersionRow full = reviews.version(v.get().id()).orElseThrow();
        return videos.renders(r.id()).stream()
                .filter(x -> x.contentHash().equals(full.contentHash()) && "COMPLETED".equals(x.status()) && x.outputMediaId() != null
                        && x.validation() != null && x.validation().path("ok").asBoolean(false))
                .findFirst().map(x -> new Checked(r, full, x));
    }

    private List<Srt.Cue> captions(Checked c) {
        return store.renderDocument(c.render().renderJobId()).map(Srt::cues).orElse(List.of());
    }

    public record TitleOption(String id, String text, boolean chosen) {
    }

    public record CaptionFile(String language, String name, int cues, String srt) {
    }

    public record Prepared(UUID resourceId, UUID versionId, int versionNo, UUID mediaId, String topicId, String videoTitle, UUID seoVersionId,
                           List<TitleOption> titles, String description, List<String> tags, List<String> hashtags,
                           List<String> playlistIdeas, CaptionFile captions, String language, List<String> problems) {
    }

    /** What a publication would carry: only approved text, and captions from the checked render. */
    public Prepared prepare(UUID projectId, UUID resourceId) {
        projects.requireAccess(projectId, false, null);
        Checked c = requireChecked(projectId, resourceId);
        Optional<UUID> seoV = videos.approved(projectId, Kind.SEO, c.resource().topicId(), null);
        if (seoV.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "This topic's SEO report isn't approved. Its approved title, description and tags are what gets published.");
        }
        JsonNode seo = MaterialsService.read(videos.content(seoV.get()).orElseThrow());
        String chosen = seo.path("chosenTitleId").asText(null);
        List<TitleOption> titles = new ArrayList<>();
        for (JsonNode t : seo.path("titles")) {
            String text = t.path("text").asText("").strip();
            if (!text.isEmpty()) titles.add(new TitleOption(t.path("id").asText(), text, t.path("id").asText().equals(chosen)));
        }
        titles.sort((a, b) -> Boolean.compare(b.chosen(), a.chosen()));
        List<String> problems = new ArrayList<>();
        if (titles.isEmpty()) problems.add("The approved SEO report has no titles");
        String description = seo.path("description").asText("");
        if (description.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5000) {
            problems.add("The approved description is longer than YouTube's 5000 bytes. Shorten it in the SEO report and approve it again.");
        }
        if (description.contains("<") || description.contains(">")) problems.add("YouTube doesn't allow < or > in a description. Edit the SEO report.");
        List<String> tags = strings(seo.path("tags"));
        if (tagChars(tags) > 500) problems.add("The approved tags are longer than YouTube's 500 characters. Remove some in the SEO report.");
        JsonNode plan = MaterialsService.read(c.version().content());
        String language = plan.path("language").asText("EN");
        List<Srt.Cue> cues = captions(c);
        CaptionFile caps = cues.isEmpty() ? null : new CaptionFile(Srt.language(language), Srt.trackName(language), cues.size(), Srt.write(cues));
        List<String> playlists = new ArrayList<>();
        for (JsonNode p : seo.path("playlists")) if (!p.path("title").asText("").isBlank()) playlists.add(p.path("title").asText().strip());
        return new Prepared(resourceId, c.version().id(), c.version().versionNo(), c.render().outputMediaId(), c.resource().topicId(),
                c.resource().title(), seoV.get(), titles, description, tags, strings(seo.path("hashtags")), playlists, caps, language, problems);
    }

    private Checked requireChecked(UUID projectId, UUID resourceId) {
        ResourceRow r = reviews.resource(resourceId).filter(x -> x.projectId().equals(projectId))
                .filter(x -> x.kind() == Kind.LESSON_VIDEO || x.kind() == Kind.QUIZ_VIDEO)
                .orElseThrow(() -> ApiException.notFound("Video"));
        return checked(r).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT,
                "Only an approved video with a render that passed the file checks can be published."));
    }

    // ---- channel and playlists -------------------------------------------------------------------------

    public record ChannelDetails(String channelId, String title, String customUrl, String thumbnail, Long subscribers, Long videos, Long views,
                                 boolean subscribersHidden, List<YouTubeClient.Playlist> playlists, String note) {
    }

    public ChannelDetails channel(UUID projectId, UUID accountId) {
        projects.requireAccess(projectId, false, null);
        SocialAccount a = youtubeAccount(accountId);
        try {
            String token = accounts.accessToken(a.getId());
            YouTubeClient.ChannelInfo info = youtube.channelInfo(token);
            List<YouTubeClient.Playlist> lists = List.of();
            String note = null;
            try {
                lists = youtube.playlists(token);
            } catch (SocialException e) {
                note = "Playlists couldn't be read: " + e.getMessage();
            }
            return new ChannelDetails(info.id(), info.title(), info.customUrl(), info.thumbnail(), info.subscribers(), info.videos(), info.views(),
                    info.subscribersHidden(), lists, note);
        } catch (SocialException e) {
            throw fromSocial(e);
        }
    }

    public record NewPlaylist(@NotBlank @Size(max = 150) String title, @Size(max = 5000) String description,
                              @NotNull @Pattern(regexp = "public|unlisted|private") String privacy) {
    }

    public YouTubeClient.Playlist createPlaylist(UUID projectId, UUID accountId, NewPlaylist req) {
        requireOwner(projectId);
        SocialAccount a = youtubeAccount(accountId);
        if (!YouTubeClient.granted(a.getScopes(), YouTubeClient.SCOPE_MANAGE)) throw reconnect(a);
        String title = req.title().strip();
        if (title.contains("<") || title.contains(">")) throw ApiException.badRequest("A playlist title can't contain < or >");
        try {
            YouTubeClient.Playlist p = youtube.createPlaylist(accounts.accessToken(a.getId()), title, req.description(), req.privacy());
            audit.record("YOUTUBE_PLAYLIST_CREATED", "social_account", a.getId(), Map.of("playlist", p.id(), "project", projectId.toString()));
            return p;
        } catch (SocialException e) {
            throw fromSocial(e);
        }
    }

    // ---- the authorisation step ----------------------------------------------------------------------------

    public record PublishRequest(
            @NotNull UUID resourceId,
            /** The approved version the person reviewed; refused if a newer version was approved since. */
            @NotNull UUID versionId,
            @NotNull UUID accountId,
            @NotBlank @Size(max = 64) String titleId,
            @NotNull @Pattern(regexp = "public|unlisted|private|scheduled") String visibility,
            /** For visibility "scheduled": when YouTube makes it public. */
            Instant publishAt,
            /** Upload now, or at a later time (the studio uploads then). */
            @NotNull Action when,
            Instant uploadAt,
            UUID thumbnailMediaId,
            @Size(max = 5) List<String> playlistIds,
            boolean addCaptions,
            boolean addHashtags,
            @NotNull Boolean madeForKids,
            @NotNull Boolean alteredOrSynthetic,
            boolean notifySubscribers,
            @Pattern(regexp = "\\d{1,3}") String categoryId,
            /** "The publisher confirms the rights before upload." */
            boolean rightsConfirmed,
            /** Needed when the video will be public now or later. */
            boolean confirmPublic) {
    }

    public record Published(UUID publicationId, PostResponse post) {
    }

    @Transactional
    public Published authoriseAndPublish(UUID projectId, PublishRequest req) {
        requireOwner(projectId);
        Checked c = requireChecked(projectId, req.resourceId());
        if (!c.version().id().equals(req.versionId())) {
            throw new ApiException(ErrorCode.CONFLICT, "A newer version of this video was approved. Review it and authorise again.");
        }
        if (!req.rightsConfirmed()) {
            throw ApiException.badRequest("Confirm that you hold the rights to everything in this video before it is uploaded");
        }
        if (req.when() == Action.DRAFT) throw ApiException.badRequest("Choose to upload now or at a set time");
        SocialAccount a = youtubeAccount(req.accountId());
        if (a.getStatus() != SocialAccount.Status.CONNECTED) throw reconnect(a);
        boolean extras = (req.playlistIds() != null && !req.playlistIds().isEmpty()) || req.addCaptions();
        if (extras && !YouTubeClient.granted(a.getScopes(), YouTubeClient.SCOPE_MANAGE)) {
            throw new ApiException(ErrorCode.CONFLICT, a.getDisplayName() + " was connected before playlists and captions were supported. "
                    + "Reconnect it in Publishing → Accounts to allow them, or publish without them.");
        }
        Prepared p = prepare(projectId, req.resourceId());
        if (!p.problems().isEmpty()) throw new ApiException(ErrorCode.CONFLICT, String.join(" ", p.problems()));
        TitleOption title = p.titles().stream().filter(t -> t.id().equals(req.titleId())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Choose one of the approved titles"));
        if (title.text().length() > 100) throw ApiException.badRequest("That approved title is longer than YouTube's 100 characters. Choose another.");

        String description = p.description();
        if (req.addHashtags() && !p.hashtags().isEmpty()) {
            // added only when the person ticks it, and shown to them before they authorise
            description = description.stripTrailing() + "\n\n" + String.join(" ", p.hashtags().stream().map(h -> h.startsWith("#") ? h : "#" + h).toList());
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("title", title.text());
        s.put("description", description);
        s.put("tags", p.tags());
        s.put("categoryId", req.categoryId() == null ? CATEGORY_EDUCATION : req.categoryId());
        boolean scheduled = "scheduled".equals(req.visibility());
        s.put("privacy", scheduled ? "private" : req.visibility());
        if (scheduled) {
            if (req.publishAt() == null) throw ApiException.badRequest("Choose when YouTube should make the video public");
            s.put("publishAt", req.publishAt().toString());
            Instant upload = req.when() == Action.SCHEDULE && req.uploadAt() != null ? req.uploadAt() : Instant.now();
            if (req.publishAt().isBefore(upload.plus(java.time.Duration.ofHours(1)))) {
                throw ApiException.badRequest("Make it public at least an hour after the upload starts, so YouTube has time to process it");
            }
        }
        s.put("madeForKids", req.madeForKids());
        s.put("aiGenerated", req.alteredOrSynthetic());
        s.put("notifySubscribers", req.notifySubscribers());
        if (req.playlistIds() != null && !req.playlistIds().isEmpty()) s.put("playlistIds", req.playlistIds());
        if (req.addCaptions()) {
            if (p.captions() == null) throw new ApiException(ErrorCode.CONFLICT, "This render has no captions to upload");
            s.put("captions", List.of(Map.of("language", p.captions().language(), "name", p.captions().name(), "srt", p.captions().srt())));
        }
        PostRequest post = new PostRequest(title.text(), p.mediaId(), req.thumbnailMediaId(), req.when(), req.uploadAt(),
                List.of(new TargetRequest(a.getId(), s)), req.confirmPublic());
        PostResponse created = publish.create(post, new Authorisations.Education(projectId, c.version().id(), true));
        UUID targetId = created.targets().get(0).id();
        UUID pubId = UUID.randomUUID();
        store.addPublication(pubId, projectId, req.resourceId(), c.version().id(), c.resource().topicId(), title.text(), targetId);
        audit.record("YOUTUBE_PUBLISH_AUTHORISED", "resource", req.resourceId(), Map.of("version", c.version().versionNo(),
                "account", a.getDisplayName(), "visibility", req.visibility(), "post", created.id().toString()));
        return new Published(pubId, created);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void requireOwner(UUID projectId) {
        projects.requireAccess(projectId, false, null);
        if (!projects.isOwner(projectId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the project owner or an administrator can authorise YouTube publishing");
        }
    }

    private SocialAccount youtubeAccount(UUID id) {
        SocialAccount a = accounts.find(id);
        if (a.getPlatform() != SocialPlatform.YOUTUBE) throw ApiException.badRequest("Choose a YouTube channel");
        return a;
    }

    static ApiException reconnect(SocialAccount a) {
        return new ApiException(ErrorCode.CONFLICT, a.getDisplayName() + " needs to be reconnected (Publishing → Accounts) to allow this.");
    }

    /** Reports what YouTube said; nothing is retried here (uploads retry on their own). */
    static ApiException fromSocial(SocialException e) {
        return switch (e.kind()) {
            case RECONNECT -> new ApiException(ErrorCode.CONFLICT, e.getMessage() + " Reconnect it in Publishing → Accounts.");
            case RATE_LIMITED -> new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, e.getMessage());
            case NOT_CONFIGURED -> new ApiException(ErrorCode.SERVICE_UNAVAILABLE, e.getMessage());
            case TEMPORARY -> new ApiException(ErrorCode.SERVICE_UNAVAILABLE, e.getMessage() + " Try again in a few minutes.");
            case REJECTED -> new ApiException(ErrorCode.CONFLICT, e.getMessage());
        };
    }

    static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> {
            String t = x.asText("").strip();
            if (!t.isEmpty()) out.add(t);
        });
        return out;
    }

    /** YouTube counts tag length with quotes around tags that have spaces, and commas between. */
    static int tagChars(List<String> tags) {
        int n = 0, i = 0;
        for (String t : tags) n += t.length() + (t.contains(" ") ? 2 : 0) + (i++ > 0 ? 1 : 0);
        return n;
    }
}
