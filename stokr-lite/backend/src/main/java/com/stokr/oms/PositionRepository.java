package com.stokr.oms;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PositionRepository extends JpaRepository<Position, Long> {
    List<Position> findByDeploymentId(Long deploymentId);
    Optional<Position> findByDeploymentIdAndSymbol(Long deploymentId, String symbol);
    List<Position> findByDeploymentIdAndQuantityNot(Long deploymentId, Integer quantity);
}
