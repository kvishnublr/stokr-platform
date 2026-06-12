package com.stokr.bootstrap.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RateLimiterService rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiterService(redisTemplate);
        ReflectionTestUtils.setField(rateLimiter, "enabled", true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void allowRequest_decrementsTokensOnFirstRequest() {
        UUID userId = UUID.randomUUID();
        when(valueOperations.get(anyString())).thenReturn(null);

        assertTrue(rateLimiter.allowRequest(userId, RateLimiterService.RateLimitEndpoint.ORDERS));

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                contains("rate_limit:ORDERS"),
                valueCaptor.capture(),
                eq(60L),
                eq(TimeUnit.SECONDS)
        );
        assertTrue(valueCaptor.getValue().startsWith("99,"));
    }

    @Test
    void getRateLimitState_parsesStoredCsv() {
        UUID userId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        when(valueOperations.get(contains("rate_limit:PORTFOLIO"))).thenReturn("150," + now + ",5,2,0");

        assertEquals(150, rateLimiter.getTokensRemaining(userId, RateLimiterService.RateLimitEndpoint.PORTFOLIO));
        assertEquals(2, rateLimiter.getQueuedRequestCount(userId, RateLimiterService.RateLimitEndpoint.PORTFOLIO));
    }
}
