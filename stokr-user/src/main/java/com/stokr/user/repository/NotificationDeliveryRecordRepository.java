package com.stokr.user.repository;

import com.stokr.user.domain.NotificationDeliveryRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationDeliveryRecordRepository extends JpaRepository<NotificationDeliveryRecord, UUID> {
}
