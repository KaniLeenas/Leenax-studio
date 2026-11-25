package com.leenax.studio.subtitles;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubtitleTrackRepository extends JpaRepository<SubtitleTrack, UUID> {

    List<SubtitleTrack> findByMediaIdOrderByCreatedAtAsc(UUID mediaId);
}
