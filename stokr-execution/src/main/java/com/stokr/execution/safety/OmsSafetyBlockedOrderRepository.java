package com.stokr.execution.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface OmsSafetyBlockedOrderRepository extends JpaRepository<OmsSafetyBlockedOrder, Long> {

    long countByCreatedAtAfter(Instant since);
}
