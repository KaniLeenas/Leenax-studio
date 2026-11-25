package com.leenax.studio.media;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID>, JpaSpecificationExecutor<MediaAsset> {

    long countByProjectId(UUID projectId);

    long countByKind(MediaKind kind);

    @Query("select coalesce(sum(m.sizeBytes), 0) from MediaAsset m")
    long totalSizeBytes();

    List<MediaAsset> findTop8ByOrderByCreatedAtDesc();

    static Specification<MediaAsset> hasKind(MediaKind kind) {
        return (root, query, cb) -> cb.equal(root.get("kind"), kind);
    }

    static Specification<MediaAsset> inProject(UUID projectId) {
        return (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
    }

    static Specification<MediaAsset> unassigned() {
        return (root, query, cb) -> cb.isNull(root.get("projectId"));
    }

    static Specification<MediaAsset> filenameContains(String text) {
        String pattern = "%" + text.toLowerCase()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("originalFilename")), pattern, '\\');
    }
}
