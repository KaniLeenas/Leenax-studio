package com.leenax.studio.videos;

import com.leenax.studio.syllabus.AnalysisStore.JobRow;
import com.leenax.studio.videos.VideoService.CreateRequest;
import com.leenax.studio.videos.VideoService.NarrateRequest;
import com.leenax.studio.videos.VideoService.Overview;
import com.leenax.studio.videos.VideoService.RenderInfo;
import com.leenax.studio.videos.VideoService.StartRender;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lesson and quiz videos (E8). Plans are edited and reviewed through the approval routes
 * (/reviews/resources/{id} and the rest); these are the video studio's own.
 */
@RestController
@RequestMapping("/api/v1")
public class VideoController {

    private final VideoService videos;

    public VideoController(VideoService videos) {
        this.videos = videos;
    }

    @GetMapping("/projects/{projectId}/videos")
    public Overview overview(@PathVariable UUID projectId) {
        return videos.overview(projectId);
    }

    /** Plans a video from approved content; returns {resourceId}. */
    @PostMapping("/projects/{projectId}/videos")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(@PathVariable UUID projectId, @Valid @RequestBody CreateRequest req) {
        return Map.of("resourceId", videos.create(projectId, req));
    }

    @PostMapping("/videos/{resourceId}/narration")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRow narrate(@PathVariable UUID resourceId, @Valid @RequestBody NarrateRequest req) {
        return videos.narrate(resourceId, req);
    }

    @GetMapping("/videos/{resourceId}/preview")
    public Map<String, Object> preview(@PathVariable UUID resourceId, @RequestParam UUID versionId) {
        return videos.preview(resourceId, versionId);
    }

    @PostMapping("/videos/{resourceId}/renders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RenderInfo render(@PathVariable UUID resourceId, @Valid @RequestBody StartRender req) {
        return videos.render(resourceId, req);
    }

    @GetMapping("/videos/{resourceId}/renders")
    public List<RenderInfo> renders(@PathVariable UUID resourceId, @RequestParam(required = false) UUID versionId) {
        return videos.renders(resourceId, versionId);
    }
}
