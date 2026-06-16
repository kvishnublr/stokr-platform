package com.stokr.trading.repository;

import com.stokr.trading.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdAndDeletedFalse(UUID id);

    List<Order> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(UUID userId);

    Page<Order> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Order> findByInstanceIdAndDeletedFalseOrderByCreatedAtDesc(UUID instanceId);

    Optional<Order> findByBrokerOrderIdAndDeletedFalse(String brokerOrderId);

    Optional<Order> findBySignalIdAndDeletedFalse(UUID signalId);

    List<Order> findByUserIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(UUID userId, String status);

    List<Order> findByUserIdAndSymbolAndDeletedFalseOrderByCreatedAtDesc(UUID userId, String symbol);

    List<Order> findByCreatedAtBetweenAndDeletedFalseOrderByCreatedAtDesc(Instant start, Instant end);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.userId = :userId AND o.status = :status AND o.deleted = false")
    int countByUserIdAndStatus(UUID userId, String status);

    List<Order> findByInstanceIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(UUID instanceId, String status);
}
