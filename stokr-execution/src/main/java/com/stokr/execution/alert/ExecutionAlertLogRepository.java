package com.stokr.execution.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExecutionAlertLogRepository extends JpaRepository<ExecutionAlertLog, UUID> {
}
