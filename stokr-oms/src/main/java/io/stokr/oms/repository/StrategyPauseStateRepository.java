package io.stokr.oms.repository;

import io.stokr.oms.domain.entity.StrategyPauseState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyPauseStateRepository extends JpaRepository<StrategyPauseState, UUID> {

    Optional<StrategyPauseState> findByUserIdAndStrategyName(UUID userId, String strategyName);

    List<StrategyPauseState> findByUserId(UUID userId);

    @Query("SELECT sp FROM StrategyPauseState sp WHERE sp.currentState IN ('EXIT_ALL_PAUSED', 'KILLSWITCH_PAUSED')")
    List<StrategyPauseState> findAllPausedStrategies();

    @Query("SELECT sp FROM StrategyPauseState sp WHERE sp.userId = ?1 AND sp.currentState IN ('EXIT_ALL_PAUSED', 'KILLSWITCH_PAUSED')")
    List<StrategyPauseState> findPausedStrategiesForUser(UUID userId);
}
