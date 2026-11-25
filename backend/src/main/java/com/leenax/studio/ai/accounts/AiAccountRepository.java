package com.leenax.studio.ai.accounts;

import com.leenax.studio.ai.provider.ProviderCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AiAccountRepository extends JpaRepository<AiAccount, UUID> {

    List<AiAccount> findAllByOrderByProviderAscCreatedAtAsc();

    List<AiAccount> findByProvider(ProviderCode provider);

    @Query("select count(a) > 0 from AiAccount a where a.provider = :provider and lower(a.name) = lower(:name) and a.id <> :exceptId")
    boolean nameTaken(ProviderCode provider, String name, UUID exceptId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiAccount a set a.isDefault = false where a.provider = :provider and a.isDefault = true")
    void clearDefault(ProviderCode provider);
}
