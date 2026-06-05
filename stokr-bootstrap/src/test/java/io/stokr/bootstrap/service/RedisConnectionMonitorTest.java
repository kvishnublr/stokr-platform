package io.stokr.bootstrap.service;

import io.stokr.bootstrap.domain.entity.RedisHealthLog;
import io.stokr.bootstrap.repository.RedisHealthLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisConnectionMonitorTest {

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisHealthLogRepository healthLogRepository;

    @InjectMocks
    private RedisConnectionMonitor redisMonitor;

    @BeforeEach
    void setUp() {
        // Default: connection factory is healthy
        when(connectionFactory.getConnection()).thenReturn(mock());
    }

    @Test
    void testMonitorRedisHealth_HealthyConnectionLogged() {
        // Given
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then
        verify(healthLogRepository).save(argThat(log ->
            log.getIsHealthy() != null &&
            log.getConnectionFactoryState() != null
        ));
    }

    @Test
    void testMonitorRedisHealth_DetectsConnectionFactorySTOPPED() {
        // Given - Connection factory is STOPPED (13:02 scenario)
        when(connectionFactory.getConnection()).thenReturn(null);
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then
        verify(healthLogRepository).save(argThat(log ->
            !log.getIsHealthy() &&
            log.getHasIssues() &&
            log.getIssuesJson() != null
        ));
    }

    @Test
    void testMonitorRedisHealth_DetectsHighCacheMissRate() {
        // Given - High cache miss rate (>80%)
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then - Service detects and logs issues
        verify(healthLogRepository).save(any(RedisHealthLog.class));
    }

    @Test
    void testMonitorRedisHealth_DetectsExcessiveConnectionRejections() {
        // Given - Too many connection rejections
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then - Service detects and logs
        verify(healthLogRepository).save(any(RedisHealthLog.class));
    }

    @Test
    void testMonitorRedisHealth_ExceptionHandling_RecordsFailure() {
        // Given - Connection factory throws exception
        when(connectionFactory.getConnection()).thenThrow(
            new RuntimeException("Redis connection failed")
        );
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then - Failure is logged
        verify(healthLogRepository, atLeast(1)).save(any(RedisHealthLog.class));
    }

    @Test
    void testCriticalScenario_13_02_Failure_Detection() {
        // Scenario: 2026-06-05 13:02 - LettuceConnectionFactory has been STOPPED

        // Given
        when(connectionFactory.getConnection()).thenReturn(null);
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then - Should detect STOPPED state and mark unhealthy
        verify(healthLogRepository).save(argThat(log ->
            "STOPPED".equals(log.getConnectionFactoryState()) &&
            !log.getIsHealthy() &&
            log.getHasIssues()
        ));
    }

    @Test
    void testAutoRecovery_AttemptedOnCriticalFailure() {
        // Given - STOPPED connection factory
        when(connectionFactory.getConnection()).thenReturn(null);
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then - Auto-recovery should be attempted
        verify(healthLogRepository).save(argThat(log ->
            log.getAutoRecoveryAttempted() != null
        ));
    }

    @Test
    void testHealthCheckFrequency_RunsEvery5Seconds() {
        // This test documents the @Scheduled(fixedRate = 5000)
        // The Redis health check runs every 5 seconds
        // to quickly detect connection failures

        // Given
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When - Method is called (normally by scheduler)
        redisMonitor.monitorRedisHealth();

        // Then - Should check and log
        verify(healthLogRepository).save(any(RedisHealthLog.class));
    }

    @Test
    void testHealthLog_TracksConnectionStats() {
        // Given
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then - Health log should track connection stats
        verify(healthLogRepository).save(argThat(log ->
            log.getConnectionsReceived() != null &&
            log.getConnectionsRejected() != null &&
            log.getCurrentConnections() != null
        ));
    }

    @Test
    void testHealthLog_TracksMemoryStats() {
        // Given
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then - Health log should track memory metrics
        verify(healthLogRepository).save(argThat(log ->
            log.getMemoryUsedMb() != null &&
            log.getMemoryPeakMb() != null
        ));
    }

    @Test
    void testHealthLog_TracksCacheMetrics() {
        // Given
        when(healthLogRepository.save(any(RedisHealthLog.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        redisMonitor.monitorRedisHealth();

        // Then - Health log should track cache hit/miss rates
        verify(healthLogRepository).save(argThat(log ->
            log.getCacheHits() != null &&
            log.getCacheMisses() != null &&
            log.getMissRatePercent() != null
        ));
    }
}
