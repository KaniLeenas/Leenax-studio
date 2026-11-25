package com.leenax.studio.dashboard;

import com.leenax.studio.media.MediaAssetRepository;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.projects.Project;
import com.leenax.studio.projects.ProjectDtos.ProjectResponse;
import com.leenax.studio.projects.ProjectRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final ProjectRepository projects;
    private final MediaAssetRepository media;
    private final MediaService mediaService;

    private final EducationDashboard education;

    public DashboardController(ProjectRepository projects, MediaAssetRepository media, MediaService mediaService, EducationDashboard education) {
        this.education = education;
        this.projects = projects;
        this.media = media;
        this.mediaService = mediaService;
    }

    public record Stats(long totalProjects, long activeProjects, long completedProjects,
                        long videos, long images, long audio, long storageBytes) {
    }

    public record DashboardResponse(Stats stats, List<ProjectResponse> recentProjects, List<MediaResponse> recentMedia) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public DashboardResponse summary() {
        long total = projects.count();
        long completed = projects.countByStatus(Project.Status.COMPLETED);
        long archived = projects.countByStatus(Project.Status.ARCHIVED);
        Stats stats = new Stats(
                total,
                total - completed - archived,
                completed,
                media.countByKind(MediaKind.VIDEO),
                media.countByKind(MediaKind.IMAGE),
                media.countByKind(MediaKind.AUDIO),
                media.totalSizeBytes());

        List<ProjectResponse> recentProjects = projects.findTop5ByStatusNotOrderByUpdatedAtDesc(Project.Status.ARCHIVED)
                .stream()
                .map(p -> ProjectResponse.of(p, media.countByProjectId(p.getId())))
                .toList();
        List<MediaResponse> recentMedia = mediaService.recent();
        return new DashboardResponse(stats, recentProjects, recentMedia);
    }

    /** Module 02 (E10): university work in numbers, what's waiting for you, running jobs and recent decisions. */
    @GetMapping("/education")
    @Transactional(readOnly = true)
    public EducationDashboard.Overview education() {
        return education.overview();
    }
}
