package io.stokr.oms.repository;

import io.stokr.oms.domain.entity.ManualExitSuppression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ManualExitSuppressionRepository extends JpaRepository<ManualExitSuppression, UUID> {

    Optional<ManualExitSuppression> findByPositionId(UUID positionId);

    List<ManualExitSuppression> findByUserId(UUID userId);

    void deleteByPositionId(UUID positionId);
}
