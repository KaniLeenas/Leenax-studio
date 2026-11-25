package com.leenax.studio.projects;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.auth.CurrentUser;
import com.leenax.studio.auth.User;
import com.leenax.studio.auth.UserRepository;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.media.MediaAssetRepository;
import com.leenax.studio.projects.ProjectDtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Projects and their members. The database shows each person only the projects they
 * belong to (administrators see all); this service adds the role checks that give a
 * clear answer before a write is attempted.
 */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final MediaAssetRepository media;
    private final ProjectMembers members;
    private final CurrentUser current;
    private final UserRepository users;
    private final AuditService audit;

    public ProjectService(ProjectRepository projects, MediaAssetRepository media, ProjectMembers members,
                          CurrentUser current, UserRepository users, AuditService audit) {
        this.projects = projects;
        this.media = media;
        this.members = members;
        this.current = current;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<ProjectResponse> list(Project.Status status, String search, boolean includeArchived, int page, int size) {
        Specification<Project> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and(ProjectRepository.hasStatus(status));
        } else if (!includeArchived) {
            spec = spec.and(ProjectRepository.notStatus(Project.Status.ARCHIVED));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(ProjectRepository.nameContains(search.trim()));
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Project> found = projects.findAll(spec, pageable);
        List<UUID> ids = found.getContent().stream().map(Project::getId).toList();
        User me = current.require();
        Map<UUID, ProjectMembers.Role> roles = members.rolesOf(me.getId(), ids);
        Map<UUID, Long> counts = members.counts(ids);
        return found.map(p -> ProjectResponse.of(p, media.countByProjectId(p.getId()),
                roleName(me, roles.get(p.getId())), counts.getOrDefault(p.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID id) {
        return response(find(id));
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest req) {
        User me = current.require();
        if (!me.canCreateProjects()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Ask an administrator for permission to create projects");
        }
        Project p = new Project(req.name().trim());
        p.setDescription(blankToNull(req.description()));
        if (req.aspectRatio() != null) p.setAspectRatio(req.aspectRatio());
        if (req.language() != null) p.setLanguage(req.language());
        if (req.kind() != null) p.setKind(req.kind());
        applySubject(p, req.subject());
        p = projects.saveAndFlush(p);   // the database makes the creator its owner (V9 trigger)
        audit.record("PROJECT_CREATED", "project", p.getId(), Map.of("kind", p.getKind().name()));
        return ProjectResponse.of(p, 0, ProjectMembers.Role.OWNER.name(), 1);
    }

    @Transactional
    public ProjectResponse update(UUID id, UpdateProjectRequest req) {
        Project p = find(id);
        ProjectMembers.Role role = requireRole(p, ProjectMembers.Role::canEdit, "Only the project's owners and editors can change it");
        if (req.reviewerNotAuthor() != null && req.reviewerNotAuthor() != p.isReviewerNotAuthor()) {
            // Letting authors approve their own work is a governance choice: administrators only.
            if (!current.isAdmin()) throw new ApiException(ErrorCode.FORBIDDEN, "Only an administrator can change who may approve");
            p.setReviewerNotAuthor(req.reviewerNotAuthor());
        }
        if (req.name() != null) {
            if (req.name().isBlank()) throw ApiException.badRequest("Name cannot be blank");
            p.setName(req.name().trim());
        }
        if (req.description() != null) p.setDescription(blankToNull(req.description()));
        if (req.status() != null) {
            if (req.status() == Project.Status.ARCHIVED && role != ProjectMembers.Role.OWNER && !current.isAdmin()) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Only an owner can archive the project");
            }
            p.setStatus(req.status());
        }
        if (req.aspectRatio() != null) p.setAspectRatio(req.aspectRatio());
        if (req.language() != null) p.setLanguage(req.language());
        if (req.kind() != null) p.setKind(req.kind());
        applySubject(p, req.subject());
        projects.flush();
        return response(p);
    }

    /** Deletes the project. Its content stays with whoever made it, outside any project (ON DELETE SET NULL). */
    @Transactional
    public void delete(UUID id) {
        Project p = find(id);
        requireRole(p, r -> r == ProjectMembers.Role.OWNER, "Only an owner can delete the project");
        projects.delete(p);
        audit.record("PROJECT_DELETED", "project", id, Map.of("name", p.getName()));
    }

    /** Projects that may be the same subject: same module code, or the same name. */
    @Transactional(readOnly = true)
    public List<SimilarProject> similar(String name, String moduleCode) {
        List<SimilarProject> out = new ArrayList<>();
        String code = blankToNull(moduleCode);
        String n = blankToNull(name);
        for (Project p : projects.findAll()) {
            if (out.size() >= 5) break;
            if (code != null && p.getModuleCode() != null && p.getModuleCode().equalsIgnoreCase(code)) {
                out.add(new SimilarProject(p.getId(), p.getName(), p.getModuleCode(), p.getAcademicYear(), "Same module code"));
            } else if (n != null && normalise(p.getName()).equals(normalise(n))) {
                out.add(new SimilarProject(p.getId(), p.getName(), p.getModuleCode(), p.getAcademicYear(), "Same name"));
            }
        }
        return out;
    }

    // ---- members --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MemberResponse> members(UUID projectId) {
        find(projectId);   // visible to the asker, or 404
        return members.list(projectId).stream().map(MemberResponse::of).toList();
    }

    @Transactional
    public List<MemberResponse> addMember(UUID projectId, MemberRequest r) {
        Project p = find(projectId);
        requireRole(p, x -> x == ProjectMembers.Role.OWNER, "Only an owner can add people");
        User u = users.findById(r.userId()).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getStatus() == User.Status.DEACTIVATED) throw ApiException.badRequest(u.getDisplayName() + "'s account is deactivated");
        members.put(projectId, u.getId(), r.role(), current.id());
        audit.record("PROJECT_MEMBER_ADDED", "project", projectId, Map.of("user", u.getId().toString(), "role", r.role().name()));
        return members(projectId);
    }

    @Transactional
    public List<MemberResponse> changeRole(UUID projectId, UUID userId, RoleRequest r) {
        Project p = find(projectId);
        requireRole(p, x -> x == ProjectMembers.Role.OWNER, "Only an owner can change roles");
        ProjectMembers.Role before = members.role(projectId, userId).orElseThrow(() -> ApiException.notFound("Member"));
        if (before == ProjectMembers.Role.OWNER && r.role() != ProjectMembers.Role.OWNER && members.owners(projectId) <= 1) {
            throw new ApiException(ErrorCode.CONFLICT, "A project needs at least one owner. Make someone else an owner first.");
        }
        members.put(projectId, userId, r.role(), current.id());
        audit.record("PROJECT_MEMBER_ROLE", "project", projectId, Map.of("user", userId.toString(), "from", before.name(), "to", r.role().name()));
        return members(projectId);
    }

    /** Owners remove anyone; anyone may leave. The last owner can't go. */
    @Transactional
    public List<MemberResponse> removeMember(UUID projectId, UUID userId) {
        Project p = find(projectId);
        User me = current.require();
        if (!me.getId().equals(userId)) {
            requireRole(p, x -> x == ProjectMembers.Role.OWNER, "Only an owner can remove people");
        }
        ProjectMembers.Role role = members.role(projectId, userId).orElseThrow(() -> ApiException.notFound("Member"));
        if (role == ProjectMembers.Role.OWNER && members.owners(projectId) <= 1) {
            throw new ApiException(ErrorCode.CONFLICT, "A project needs at least one owner. Make someone else an owner first.");
        }
        members.remove(projectId, userId);
        audit.record("PROJECT_MEMBER_REMOVED", "project", projectId, Map.of("user", userId.toString()));
        return me.getId().equals(userId) && !me.isAdmin() ? List.of() : members(projectId);
    }

    /**
     * For other modules (documents, ...): the project if the asker can see it (else 404),
     * and with {@code edit}, only for its owners and editors and for administrators (else 403).
     */
    public Project requireAccess(UUID projectId, boolean edit, String message) {
        Project p = find(projectId);
        if (edit) requireRole(p, ProjectMembers.Role::canEdit, message);
        return p;
    }

    /** Whether the asker may approve, reject or ask for changes here (owner, reviewer or administrator). */
    public boolean canReview(UUID projectId) {
        User me = current.require();
        return me.isAdmin() || members.role(projectId, me.getId()).map(ProjectMembers.Role::canReview).orElse(false);
    }

    /** Whether the asker may change things in this project (owner, editor or administrator). */
    public boolean canEdit(UUID projectId) {
        User me = current.require();
        return me.isAdmin() || members.role(projectId, me.getId()).map(ProjectMembers.Role::canEdit).orElse(false);
    }

    /** Whether the asker is this project's owner or an administrator (who may authorise YouTube publishing). */
    public boolean isOwner(UUID projectId) {
        User me = current.require();
        return me.isAdmin() || members.role(projectId, me.getId()).map(r -> r == ProjectMembers.Role.OWNER).orElse(false);
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    Project find(UUID id) {
        return projects.findById(id).orElseThrow(() -> ApiException.notFound("Project"));
    }

    /** The asker's role; administrators count as owners everywhere. */
    private ProjectMembers.Role requireRole(Project p, java.util.function.Predicate<ProjectMembers.Role> allowed, String message) {
        User me = current.require();
        if (me.isAdmin()) return ProjectMembers.Role.OWNER;
        ProjectMembers.Role role = members.role(p.getId(), me.getId())
                .orElseThrow(() -> ApiException.notFound("Project"));
        if (!allowed.test(role)) throw new ApiException(ErrorCode.FORBIDDEN, message);
        return role;
    }

    /** Responses for a few projects at once (the dashboard's recent projects), with your role in each. */
    @Transactional(readOnly = true)
    public List<ProjectResponse> responses(List<Project> list) {
        if (list.isEmpty()) return List.of();
        List<UUID> ids = list.stream().map(Project::getId).toList();
        User me = current.require();
        Map<UUID, ProjectMembers.Role> roles = members.rolesOf(me.getId(), ids);
        Map<UUID, Long> counts = members.counts(ids);
        return list.stream().map(p -> ProjectResponse.of(p, media.countByProjectId(p.getId()),
                roleName(me, roles.get(p.getId())), counts.getOrDefault(p.getId(), 0L))).toList();
    }

    private ProjectResponse response(Project p) {
        User me = current.require();
        Optional<ProjectMembers.Role> role = members.role(p.getId(), me.getId());
        return ProjectResponse.of(p, media.countByProjectId(p.getId()), roleName(me, role.orElse(null)),
                members.counts(List.of(p.getId())).getOrDefault(p.getId(), 0L));
    }

    static String roleName(User me, ProjectMembers.Role role) {
        if (role != null) return role.name();
        return me.isAdmin() ? "ADMIN" : null;
    }

    private static void applySubject(Project p, Subject s) {
        if (s == null) return;
        p.setSubjectName(blankToNull(s.subjectName()));
        p.setModuleCode(blankToNull(s.moduleCode()));
        p.setAcademicYear(blankToNull(s.academicYear()));
        p.setSemester(blankToNull(s.semester()));
        p.setUniversity(blankToNull(s.university()));
        p.setDepartment(blankToNull(s.department()));
    }

    static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
