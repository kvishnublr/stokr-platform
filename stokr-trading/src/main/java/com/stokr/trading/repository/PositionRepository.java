package com.stokr.trading.repository;

import com.stokr.trading.domain.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PositionRepository extends JpaRepository<Position, UUID> {

    Optional<Position> findByIdAndDeletedFalse(UUID id);

    List<Position> findByUserIdAndDeletedFalse(UUID userId);

    List<Position> findByUserIdAndStatusAndDeletedFalse(UUID userId, String status);

    List<Position> findByUserIdAndSymbolAndDeletedFalse(UUID userId, String symbol);

    Optional<Position> findByUserIdAndSymbolAndStatusAndDeletedFalse(UUID userId, String symbol, String status);

    List<Position> findByInstanceIdAndDeletedFalse(UUID instanceId);

    Optional<Position> findByInstanceIdAndSymbolAndStatusAndDeletedFalse(UUID instanceId, String symbol, String status);

    List<Position> findByStatusAndDeletedFalse(String status);

    @Query("SELECT COALESCE(SUM(p.pnl), 0) FROM Position p WHERE p.userId = :userId AND p.status = 'OPEN' AND p.deleted = false")
    BigDecimal sumOpenPnlByUserId(UUID userId);

    @Query("SELECT COALESCE(SUM(p.unrealizedPnl), 0) FROM Position p WHERE p.userId = :userId AND p.status = 'OPEN' AND p.deleted = false")
    BigDecimal sumUnrealizedPnlByUserId(UUID userId);

    @Query("SELECT COUNT(p) FROM Position p WHERE p.userId = :userId AND p.status = 'OPEN' AND p.deleted = false")
    int countOpenPositionsByUserId(UUID userId);

    List<Position> findByUserIdAndStatusAndDeletedFalseOrderByUpdatedAtDesc(UUID userId, String status);
}
