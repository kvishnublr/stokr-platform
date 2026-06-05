package io.stokr.bootstrap.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "redis_health_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RedisHealthLog {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @Column(name = "check_time")
    private LocalDateTime checkTime;

    @Column(name = "is_healthy")
    private Boolean isHealthy;

    @Column(name = "connection_active")
    private Boolean connectionActive;

    @Column(name = "connection_factory_state")
    private String connectionFactoryState;

    @Column(name = "connections_received")
    private Integer connectionsReceived;

    @Column(name = "connections_rejected")
    private Integer connectionsRejected;

    @Column(name = "current_connections")
    private Integer currentConnections;

    @Column(name = "memory_used_mb")
    private Double memoryUsedMb;

    @Column(name = "memory_peak_mb")
    private Double memoryPeakMb;

    @Column(name = "cache_hits")
    private Long cacheHits;

    @Column(name = "cache_misses")
    private Long cacheMisses;

    @Column(name = "miss_rate_percent")
    private Double missRatePercent;

    @Column(name = "ops_per_second")
    private Integer opsPerSecond;

    @Column(name = "avg_latency_ms")
    private Double avgLatencyMs;

    @Column(name = "has_issues")
    private Boolean hasIssues;

    @Column(name = "issues_json", columnDefinition = "TEXT")
    private String issuesJson;

    @Column(name = "auto_recovery_attempted")
    private Boolean autoRecoveryAttempted;

    @Column(name = "recovery_successful")
    private Boolean recoverySuccessful;

    @Transient
    private Boolean isSynthetic = false;
}
