package com.stokr.bootstrap.recovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecoveryStateStore {

    private static final String REDIS_KEY_PREFIX = "stokr:platform:recovery:state:";

    private final PlatformRecoveryProperties properties;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, OperationalRecoveryState> memory = new ConcurrentHashMap<>();

    public OperationalRecoveryState load() {
        String key = redisKey();
        StringRedisTemplate redis = stringRedisTemplate.getIfAvailable();
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(key);
                if (json != null && !json.isBlank()) {
                    return deserialize(json);
                }
            } catch (Exception ex) {
                log.warn("platform.recovery.state_load_failed {}", ex.toString());
            }
        }
        return memory.getOrDefault(key, OperationalRecoveryState.empty());
    }

    public void save(OperationalRecoveryState state) {
        String key = redisKey();
        memory.put(key, state);
        StringRedisTemplate redis = stringRedisTemplate.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, serialize(state));
        } catch (Exception ex) {
            log.warn("platform.recovery.state_save_failed {}", ex.toString());
        }
    }

    public void markSuccess() {
        OperationalRecoveryState current = load();
        save(current.withSuccess(Instant.now()));
    }

    private String redisKey() {
        return REDIS_KEY_PREFIX + properties.getServiceKey();
    }

    private String serialize(OperationalRecoveryState state) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new StateDto(
                state.failureSignature() != null ? state.failureSignature().name() : null,
                state.lastActionTaken() != null ? state.lastActionTaken().name() : RecoveryActionType.NONE.name(),
                state.attemptCount(),
                state.consecutiveUnhealthyCycles(),
                state.lastActionAt() != null ? state.lastActionAt().toString() : null,
                state.lastSuccessAt() != null ? state.lastSuccessAt().toString() : null,
                state.updatedAt() != null ? state.updatedAt().toString() : Instant.now().toString()
        ));
    }

    private OperationalRecoveryState deserialize(String json) throws JsonProcessingException {
        StateDto dto = objectMapper.readValue(json, StateDto.class);
        return new OperationalRecoveryState(
                dto.failureSignature() != null ? OperationalFailureSignature.valueOf(dto.failureSignature()) : null,
                dto.lastActionTaken() != null ? RecoveryActionType.valueOf(dto.lastActionTaken()) : RecoveryActionType.NONE,
                dto.attemptCount(),
                dto.consecutiveUnhealthyCycles(),
                dto.lastActionAt() != null ? Instant.parse(dto.lastActionAt()) : null,
                dto.lastSuccessAt() != null ? Instant.parse(dto.lastSuccessAt()) : null,
                dto.updatedAt() != null ? Instant.parse(dto.updatedAt()) : Instant.now()
        );
    }

    private record StateDto(
            String failureSignature,
            String lastActionTaken,
            int attemptCount,
            int consecutiveUnhealthyCycles,
            String lastActionAt,
            String lastSuccessAt,
            String updatedAt
    ) {
    }
}
