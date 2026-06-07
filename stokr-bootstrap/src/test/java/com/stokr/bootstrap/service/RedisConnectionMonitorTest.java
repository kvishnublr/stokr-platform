package com.stokr.bootstrap.service;

import com.stokr.bootstrap.domain.entity.RedisHealthLog;
import com.stokr.bootstrap.repository.RedisHealthLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;

import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisConnectionMonitorTest {

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection redisConnection;

    @Mock
    private RedisServerCommands serverCommands;

    @Mock
    private RedisHealthLogRepository healthLogRepository;

    @InjectMocks
    private RedisConnectionMonitor redisMonitor;

    @Test
    void testMonitorRedisHealth_HealthyConnectionDoesNotPersist() {
        when(connectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.serverCommands()).thenReturn(serverCommands);
        when(serverCommands.info("memory")).thenReturn(new Properties());
        when(serverCommands.info("stats")).thenReturn(new Properties());

        redisMonitor.monitorRedisHealth();

        verify(healthLogRepository, never()).save(any(RedisHealthLog.class));
    }

    @Test
    void testMonitorRedisHealth_DetectsConnectionFactorySTOPPED() {
        when(connectionFactory.getConnection()).thenReturn(null);
        when(healthLogRepository.save(any(RedisHealthLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        redisMonitor.monitorRedisHealth();

        verify(healthLogRepository).save(argThat(log ->
                Boolean.FALSE.equals(log.getIsHealthy()) &&
                        Boolean.TRUE.equals(log.getHasIssues())
        ));
    }

    @Test
    void testMonitorRedisHealth_ExceptionHandling_RecordsFailure() {
        when(connectionFactory.getConnection()).thenThrow(new RuntimeException("Redis connection failed"));
        when(healthLogRepository.save(any(RedisHealthLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        redisMonitor.monitorRedisHealth();

        verify(healthLogRepository, atLeast(1)).save(any(RedisHealthLog.class));
    }
}
