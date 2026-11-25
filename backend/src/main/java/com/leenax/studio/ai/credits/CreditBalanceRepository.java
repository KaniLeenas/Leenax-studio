package com.leenax.studio.ai.credits;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CreditBalanceRepository extends JpaRepository<CreditBalance, UUID> {

    List<CreditBalance> findByAccountIdOrderByExpiresAtAscLabelAsc(UUID accountId);

    List<CreditBalance> findByAccountIdIn(Collection<UUID> accountIds);

    /** Derived delete; must run inside a transaction (CreditService does). */
    void deleteByAccountIdAndSource(UUID accountId, CreditBalance.Source source);
}
