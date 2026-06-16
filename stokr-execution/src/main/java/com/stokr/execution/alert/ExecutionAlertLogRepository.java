package com.stokr.execution.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ExecutionAlertLogRepository extends JpaRepository<ExecutionAlertLog, UUID> {

    List<ExecutionAlertLog> findTop200ByDeletedFalseOrderByCreatedAtDesc();

    List<ExecutionAlertLog> findTop200ByDeletedFalseAndAlertTypeInOrderByCreatedAtDesc(Collection<String> alertTypes);

    long countByDeletedFalseAndAlertTypeInAndCreatedAtAfter(Collection<String> alertTypes, Instant after);
}
