package com.stokr.chartink;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TraderConfigService {

    private final TraderConfigRepository repository;

    @Transactional(readOnly = true)
    public TraderConfig getConfig(Long userId) {
        return repository.findByUserId(userId)
                .orElseGet(() -> {
                    log.info("Creating default trader config for user {}", userId);
                    TraderConfig cfg = TraderConfig.defaultsFor(userId);
                    return repository.save(cfg);
                });
    }

    @Transactional
    public TraderConfig updateConfig(Long userId, TraderConfig patch) {
        TraderConfig cfg = getConfig(userId);

        if (patch.getMode() != null) cfg.setMode(patch.getMode());
        if (patch.getCapital() != null) cfg.setCapital(patch.getCapital());
        if (patch.getMaxPositions() > 0) cfg.setMaxPositions(patch.getMaxPositions());
        if (patch.getMinSharePrice() != null) cfg.setMinSharePrice(patch.getMinSharePrice());
        if (patch.getMaxSharePrice() != null) cfg.setMaxSharePrice(patch.getMaxSharePrice());
        if (patch.getStopLossPct() != null) cfg.setStopLossPct(patch.getStopLossPct());
        if (patch.getTargetPct() != null) cfg.setTargetPct(patch.getTargetPct());
        if (patch.getMaxDailyLoss() != null) cfg.setMaxDailyLoss(patch.getMaxDailyLoss());
        if (patch.getMinTradeGapMinutes() >= 0) cfg.setMinTradeGapMinutes(patch.getMinTradeGapMinutes());
        if (patch.getMaxConsecutiveLosses() > 0) cfg.setMaxConsecutiveLosses(patch.getMaxConsecutiveLosses());
        cfg.setEnabled(patch.isEnabled());

        return repository.save(cfg);
    }

    @Transactional
    public void toggleMode(Long userId, TraderConfig.Mode mode) {
        TraderConfig cfg = getConfig(userId);
        cfg.setMode(mode);
        repository.save(cfg);
        log.info("User {} switched to {} mode", userId, mode);
    }
}
