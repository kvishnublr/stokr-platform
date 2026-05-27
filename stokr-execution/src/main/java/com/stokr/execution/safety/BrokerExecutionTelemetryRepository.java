package com.stokr.execution.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BrokerExecutionTelemetryRepository extends JpaRepository<BrokerExecutionTelemetry, Long> {

    Optional<BrokerExecutionTelemetry> findByOrderId(UUID orderId);

    @org.springframework.data.jpa.repository.Query("""
            select coalesce(avg(t.ackLatencyMs), 0) from BrokerExecutionTelemetry t
            where t.ackLatencyMs is not null and t.createdAt >= :since
            """)
    Double avgAckLatencyMsSince(@org.springframework.data.repository.query.Param("since") Instant since);

    long countByCreatedAtAfter(Instant since);
}
