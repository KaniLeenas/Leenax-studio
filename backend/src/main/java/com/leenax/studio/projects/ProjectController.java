package com.leenax.studio.projects;

import com.leenax.studio.common.PageResponse;
import com.leenax.studio.projects.ProjectDtos.CreateProjectRequest;
import com.leenax.studio.projects.ProjectDtos.ProjectResponse;
import com.leenax.studio.projects.ProjectDtos.UpdateProjectRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ProjectResponse> list(@RequestParam(required = false) Project.Status status,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(defaultValue = "false") boolean includeArchived,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "24") int size) {
        return PageResponse.of(service.list(status, search, includeArchived, page, size));
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    public ProjectResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProjectRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /** Projects that may already be this subject (same module code or name), to avoid duplicates. */
    @GetMapping("/similar")
    public java.util.List<ProjectDtos.SimilarProject> similar(@RequestParam(required = false) String name,
                                                              @RequestParam(required = false) String moduleCode) {
        return service.similar(name, moduleCode);
    }

    @GetMapping("/{id}/members")
    public java.util.List<ProjectDtos.MemberResponse> members(@PathVariable UUID id) {
        return service.members(id);
    }

    @PostMapping("/{id}/members")
    public java.util.List<ProjectDtos.MemberResponse> addMember(@PathVariable UUID id, @Valid @RequestBody ProjectDtos.MemberRequest r) {
        return service.addMember(id, r);
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}/members/{userId}")
    public java.util.List<ProjectDtos.MemberResponse> changeRole(@PathVariable UUID id, @PathVariable UUID userId,
                                                                 @Valid @RequestBody ProjectDtos.RoleRequest r) {
        return service.changeRole(id, userId, r);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public java.util.List<ProjectDtos.MemberResponse> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        return service.removeMember(id, userId);
    }
}
