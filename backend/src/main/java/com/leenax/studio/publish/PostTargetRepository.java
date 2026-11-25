package com.leenax.studio.publish;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PostTargetRepository extends JpaRepository<PostTarget, UUID> {

    List<PostTarget> findByPostIdOrderByCreatedAtAsc(UUID postId);

    List<PostTarget> findByPostIdInOrderByCreatedAtAsc(Collection<UUID> postIds);
}
