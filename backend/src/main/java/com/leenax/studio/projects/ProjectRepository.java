package com.leenax.studio.projects;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID>, JpaSpecificationExecutor<Project> {

    List<Project> findTop5ByStatusNotOrderByUpdatedAtDesc(Project.Status status);

    long countByStatus(Project.Status status);

    static Specification<Project> hasStatus(Project.Status status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<Project> notStatus(Project.Status status) {
        return (root, query, cb) -> cb.notEqual(root.get("status"), status);
    }

    static Specification<Project> nameContains(String text) {
        String pattern = "%" + text.toLowerCase()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\');
    }
}
