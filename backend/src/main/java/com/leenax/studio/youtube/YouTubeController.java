package com.leenax.studio.youtube;

import com.leenax.studio.social.YouTubeClient;
import com.leenax.studio.youtube.EduPublishService.ChannelDetails;
import com.leenax.studio.youtube.EduPublishService.NewPlaylist;
import com.leenax.studio.youtube.EduPublishService.Overview;
import com.leenax.studio.youtube.EduPublishService.Prepared;
import com.leenax.studio.youtube.EduPublishService.PublishRequest;
import com.leenax.studio.youtube.EduPublishService.Published;
import com.leenax.studio.youtube.YouTubeAnalyticsService.Dashboard;
import com.leenax.studio.youtube.YouTubeAnalyticsService.ReachStatus;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** YouTube publishing (module 22) and analytics (module 23) for a project (E9). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/youtube")
public class YouTubeController {

    private final EduPublishService publishing;
    private final YouTubeAnalyticsService analytics;

    public YouTubeController(EduPublishService publishing, YouTubeAnalyticsService analytics) {
        this.publishing = publishing;
        this.analytics = analytics;
    }

    @GetMapping
    public Overview overview(@PathVariable UUID projectId) {
        return publishing.overview(projectId);
    }

    @GetMapping("/videos/{resourceId}/prepare")
    public Prepared prepare(@PathVariable UUID projectId, @PathVariable UUID resourceId) {
        return publishing.prepare(projectId, resourceId);
    }

    @GetMapping("/channels/{accountId}")
    public ChannelDetails channel(@PathVariable UUID projectId, @PathVariable UUID accountId) {
        return publishing.channel(projectId, accountId);
    }

    @PostMapping("/channels/{accountId}/playlists")
    @ResponseStatus(HttpStatus.CREATED)
    public YouTubeClient.Playlist createPlaylist(@PathVariable UUID projectId, @PathVariable UUID accountId, @Valid @RequestBody NewPlaylist req) {
        return publishing.createPlaylist(projectId, accountId, req);
    }

    /** The authorisation step: records who authorised what, then schedules the upload. */
    @PostMapping("/publications")
    @ResponseStatus(HttpStatus.CREATED)
    public Published publish(@PathVariable UUID projectId, @Valid @RequestBody PublishRequest req) {
        return publishing.authoriseAndPublish(projectId, req);
    }

    // ---- analytics ---------------------------------------------------------------------------------------

    @GetMapping("/analytics")
    public Dashboard dashboard(@PathVariable UUID projectId, @RequestParam UUID accountId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.dashboard(projectId, accountId, from, to);
    }

    /** Fetches the period from YouTube and keeps it as a snapshot. */
    @PostMapping("/analytics/refresh")
    public Dashboard refresh(@PathVariable UUID projectId, @RequestParam UUID accountId,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.refresh(projectId, accountId, from, to);
    }

    @GetMapping("/analytics/snapshots")
    public List<YouTubeStore.SetSummary> snapshots(@PathVariable UUID projectId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.history(projectId, from, to);
    }

    @GetMapping("/analytics/snapshots/{fetchedAt}")
    public Dashboard snapshot(@PathVariable UUID projectId, @PathVariable Instant fetchedAt, @RequestParam UUID accountId) {
        return analytics.snapshotSet(projectId, accountId, fetchedAt);
    }

    @PostMapping("/analytics/reach")
    public ReachStatus startReach(@PathVariable UUID projectId, @RequestParam UUID accountId) {
        return analytics.startReach(projectId, accountId);
    }

    @PostMapping("/analytics/reach/import")
    public ReachStatus importReach(@PathVariable UUID projectId, @RequestParam UUID accountId) {
        return analytics.importReachNow(projectId, accountId);
    }
}
