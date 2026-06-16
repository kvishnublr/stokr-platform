package com.stokr.trading.repository;

import com.stokr.trading.domain.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyRepository extends JpaRepository<Strategy, UUID> {

    Optional<Strategy> findByIdAndDeletedFalse(UUID id);

    List<Strategy> findByOrganizationIdAndDeletedFalse(UUID organizationId);

    List<Strategy> findByCreatorIdAndDeletedFalse(UUID creatorId);

    List<Strategy> findByIsPublicTrueAndDeletedFalse();

    @Query("SELECT COUNT(s) FROM Strategy s WHERE s.organizationId = :orgId AND s.deleted = false")
    int countByOrganizationId(UUID organizationId);

    List<Strategy> findByOrganizationIdAndIsActiveTrueAndDeletedFalse(UUID organizationId);

    Optional<Strategy> findByOrganizationIdAndNameAndDeletedFalse(UUID organizationId, String name);

    boolean existsByOrganizationIdAndNameAndDeletedFalse(UUID organizationId, String name);
}
