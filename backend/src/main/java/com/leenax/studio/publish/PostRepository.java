package com.leenax.studio.publish;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    List<Post> findTop50ByOrderByUpdatedAtDesc();

    List<Post> findTop100ByStatusOrderByUpdatedAtDesc(Post.Status status);

    @Query("""
            select p from Post p
            where (p.scheduledAt >= :from and p.scheduledAt < :to)
               or (p.publishedAt >= :from and p.publishedAt < :to)
            order by coalesce(p.publishedAt, p.scheduledAt)
            """)
    List<Post> findInRange(Instant from, Instant to);
}
