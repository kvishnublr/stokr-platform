package com.stokr.oms.repository;

import com.stokr.oms.domain.PortfolioPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioPositionRepository extends JpaRepository<PortfolioPosition, UUID> {

    List<PortfolioPosition> findByUserIdAndDeletedFalse(UUID userId);

    Optional<PortfolioPosition> findByUserIdAndSymbolAndDeletedFalse(UUID userId, String symbol);
}
