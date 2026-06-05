package com.stokr.bootstrap.repository;

import com.stokr.bootstrap.domain.entity.RedisHealthLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface RedisHealthLogRepository extends JpaRepository<RedisHealthLog, UUID> {

    RedisHealthLog findFirstByOrderByCheckTimeDesc();

    List<RedisHealthLog> findTop10ByIsHealthyFalseOrderByCheckTimeDesc();

    @Query("SELECT rhl FROM RedisHealthLog rhl WHERE rhl.hasIssues = true AND rhl.checkTime > ?1")
    List<RedisHealthLog> findIssuesSince(LocalDateTime since);

    List<RedisHealthLog> findByAutoRecoveryAttemptedTrueOrderByCheckTimeDesc();
}
