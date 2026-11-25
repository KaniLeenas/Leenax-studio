package com.leenax.studio.characters;

import com.leenax.studio.media.MediaDtos.MediaResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CharacterDtos {

    private CharacterDtos() {
    }

    public record CharacterRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description,
                                   @Size(max = 2000) String appearance, @Size(max = 1000) String clothing,
                                   UUID voiceProfileId, UUID projectId,
                                   @Size(max = 8) List<UUID> referenceMediaIds) {
    }

    public record CharacterResponse(UUID id, UUID projectId, String name, String description, String appearance,
                                    String clothing, UUID voiceProfileId, String voiceName, List<MediaResponse> references,
                                    Instant createdAt, Instant updatedAt) {
    }
}
