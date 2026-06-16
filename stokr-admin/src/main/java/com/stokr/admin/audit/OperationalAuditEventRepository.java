package com.stokr.admin.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OperationalAuditEventRepository extends JpaRepository<OperationalAuditEvent, UUID> {

    List<OperationalAuditEvent> findTop50ByDeletedFalseOrderByCreatedAtDesc();
}
