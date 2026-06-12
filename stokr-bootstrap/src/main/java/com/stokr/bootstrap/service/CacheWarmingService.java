package com.stokr.bootstrap.service;

import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.strategy.repository.StrategyExecutionConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Release_v2 Phase 2: Cache Warming Service
 *
 * @since Release_v2 Phase 2
 */
@Slf4j
@Service
@Profile("v2")
@RequiredArgsConstructor
public class CacheWarmingService {

    private final CachedUserProfileService userProfileService;
    private final CachedPortfolioSummaryService portfolioService;
    private final AuthUserRepository authUserRepository;
    private final PortfolioPositionRepository positionRepository;
    private final StrategyExecutionConfigRepository strategyExecutionConfigRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        log.info("Starting cache warmup on application startup...");

        try {
            warmupUserProfiles();
            warmupPortfolioPositions();
            warmupStrategyConfigs();
            log.info("Cache warmup completed successfully");
        } catch (Exception e) {
            log.error("Error during cache warmup (non-blocking)", e);
        }
    }

    private void warmupUserProfiles() {
        log.info("Warming up user profiles...");

        List<UUID> activeUsers = authUserRepository.findTop15ByDeletedFalseOrderByUpdatedAtDesc()
                .stream()
                .map(u -> u.getId())
                .toList();

        for (UUID userId : activeUsers) {
            try {
                userProfileService.getProfile(userId);
            } catch (Exception e) {
                log.warn("Failed to warm profile for user {}: {}", userId, e.getMessage());
            }
        }

        log.info("Warmed {} user profiles", activeUsers.size());
    }

    private void warmupPortfolioPositions() {
        log.info("Warming up portfolio positions...");

        Set<UUID> activeTraders = new LinkedHashSet<>();
        for (PortfolioPosition position : positionRepository.findAllRealOpenPositions()) {
            if (position.getUserId() != null) {
                activeTraders.add(position.getUserId());
            }
            if (activeTraders.size() >= 100) {
                break;
            }
        }

        for (UUID userId : activeTraders) {
            try {
                portfolioService.getPositionSummary(userId);
            } catch (Exception e) {
                log.warn("Failed to warm positions for user {}: {}", userId, e.getMessage());
            }
        }

        log.info("Warmed {} portfolio positions", activeTraders.size());
    }

    private void warmupStrategyConfigs() {
        log.info("Warming up strategy configurations...");

        List<String> strategies = strategyExecutionConfigRepository
                .findAllByUserIdIsNullAndDeletedFalseOrderByStrategyKeyAsc()
                .stream()
                .map(cfg -> cfg.getStrategyKey())
                .filter(key -> key != null && !key.isBlank())
                .toList();

        log.info("Warmed {} strategy configuration keys", strategies.size());
    }

    @Scheduled(fixedDelay = 300000)
    public void refreshStrategyConfigs() {
        log.debug("Refreshing strategy configurations...");
        try {
            warmupStrategyConfigs();
        } catch (Exception e) {
            log.warn("Error refreshing strategy configs", e);
        }
    }

    public void warmupUserOnDemand(UUID userId) {
        log.info("Warming cache for new user: {}", userId);

        try {
            userProfileService.getProfile(userId);
            portfolioService.getPositionSummary(userId);
            log.info("User {} warmup completed", userId);
        } catch (Exception e) {
            log.warn("Error warming cache for user {}", userId, e);
        }
    }

    public static class CacheWarmupStats {
        public long lastWarmupAt;
        public int usersWarmed;
        public int strategiesWarmed;
    }
}
