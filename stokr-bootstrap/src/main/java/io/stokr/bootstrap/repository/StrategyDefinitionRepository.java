package io.stokr.bootstrap.repository;

import io.stokr.bootstrap.domain.entity.StrategyDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyDefinitionRepository extends JpaRepository<StrategyDefinition, UUID> {

    Optional<StrategyDefinition> findByUserIdAndStrategyName(UUID userId, String strategyName);

    List<StrategyDefinition> findByUserId(UUID userId);

    List<StrategyDefinition> findByComplianceReviewStatus(String status);

    List<StrategyDefinition> findByIsDocumentedFalse();
}
