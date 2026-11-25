package com.leenax.studio.ai.models;

import com.leenax.studio.ai.provider.ProviderCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiModelRepository extends JpaRepository<AiModel, UUID> {

    List<AiModel> findAllByOrderBySortOrderAscDisplayNameAsc();

    List<AiModel> findByProviderOrderBySortOrderAsc(ProviderCode provider);

    boolean existsByProviderAndModelCode(ProviderCode provider, String modelCode);
}
