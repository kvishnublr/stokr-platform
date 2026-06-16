package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StrategyHeartbeatService {

    private final StrategyInstanceRepository instanceRepository;

    @Transactional
    public void touch(UUID instanceId) {
        StrategyInstance si = instanceRepository.findById(instanceId).filter(x -> !x.isDeleted()).orElse(null);
        if (si == null) {
            return;
        }
        si.setHeartbeatAt(Instant.now());
        instanceRepository.save(si);
    }
}
