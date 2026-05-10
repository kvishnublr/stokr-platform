package com.stokr.risk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KillSwitchService {

    private static final String KEY = "stokr:kill_switch";

    private final StringRedisTemplate redis;

    public boolean isEnabled() {
        String v = redis.opsForValue().get(KEY);
        return v != null && ("1".equals(v) || Boolean.parseBoolean(v));
    }

    public void setEnabled(boolean enabled) {
        redis.opsForValue().set(KEY, enabled ? "1" : "0");
    }
}
