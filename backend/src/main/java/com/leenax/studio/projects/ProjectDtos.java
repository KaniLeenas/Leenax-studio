package com.leenax.studio.projects;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 5000) String description,
            Project.AspectRatio aspectRatio,
            Project.Language language,
            Project.Kind kind,
            @Valid Subject subject) {
    }

    /** University details of an education project; every field optional. */
    public record Subject(
            @Size(max = 200) String subjectName,
            @Size(max = 40) String moduleCode,
            @Size(max = 20) String academicYear,
            @Size(max = 20) String semester,
            @Size(max = 200) String university,
            @Size(max = 200) String department) {
    }

    public record MemberRequest(@NotNull UUID userId, @NotNull ProjectMembers.Role role) {
    }

    public record RoleRequest(@NotNull ProjectMembers.Role role) {
    }

    public record MemberResponse(UUID userId, String displayName, String email, ProjectMembers.Role role,
                                 String status, Instant addedAt) {
        static MemberResponse of(ProjectMembers.Member m) {
            return new MemberResponse(m.userId(), m.displayName(), m.email(), m.role(), m.status(), m.addedAt());
        }
    }

    /** A project that may be the same subject as one being created. */
    public record SimilarProject(UUID id, String name, String moduleCode, String academicYear, String reason) {
    }

    /** Partial update: null fields are left unchanged. */
    public record UpdateProjectRequest(
            @Size(min = 1, max = 160) String name,
            @Size(max = 5000) String description,
            Project.Status status,
            Project.AspectRatio aspectRatio,
            Project.Language language,
            Project.Kind kind,
            @Valid Subject subject,
            Boolean reviewerNotAuthor) {
    }

    public record ProjectResponse(
            UUID id,
            String name,
            String description,
            Project.Status status,
            Project.AspectRatio aspectRatio,
            Project.Language language,
            Project.Kind kind,
            Subject subject,
            boolean reviewerNotAuthor,
            /** The asking person's role, or ADMIN for an administrator who isn't a member. */
            String myRole,
            long memberCount,
            long mediaCount,
            Instant createdAt,
            Instant updatedAt) {

        public static ProjectResponse of(Project p, long mediaCount, String myRole, long memberCount) {
            Subject subject = p.getKind() == Project.Kind.EDUCATION
                    ? new Subject(p.getSubjectName(), p.getModuleCode(), p.getAcademicYear(), p.getSemester(),
                    p.getUniversity(), p.getDepartment())
                    : null;
            return new ProjectResponse(p.getId(), p.getName(), p.getDescription(), p.getStatus(),
                    p.getAspectRatio(), p.getLanguage(), p.getKind(), subject, p.isReviewerNotAuthor(), myRole,
                    memberCount, mediaCount, p.getCreatedAt(), p.getUpdatedAt());
        }
    }
}
