package com.leenax.studio.thumbnails;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ThumbnailDesignRepository extends JpaRepository<ThumbnailDesign, UUID> {

    List<ThumbnailDesign> findByTemplateOrderByUpdatedAtDesc(boolean template);
}
