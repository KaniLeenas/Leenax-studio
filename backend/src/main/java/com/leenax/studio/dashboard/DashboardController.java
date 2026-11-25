package com.leenax.studio.dashboard;

import com.leenax.studio.media.MediaAssetRepository;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.projects.Project;
import com.leenax.studio.projects.ProjectDtos.ProjectResponse;
import com.leenax.studio.projects.ProjectRepository;
import com.leenax.studio.projects.ProjectService;
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
    private final ProjectService projectService;

    public DashboardController(ProjectRepository projects, MediaAssetRepository media, MediaService mediaService, EducationDashboard education,
                               ProjectService projectService) {
        this.education = education;
        this.projects = projects;
        this.media = media;
        this.mediaService = mediaService;
        this.projectService = projectService;
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

        List<ProjectResponse> recentProjects = projectService.responses(
                projects.findTop5ByStatusNotOrderByUpdatedAtDesc(Project.Status.ARCHIVED));
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
