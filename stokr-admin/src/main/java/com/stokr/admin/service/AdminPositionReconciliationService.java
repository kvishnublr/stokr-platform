package com.stokr.admin.service;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.portfolio.PortfolioAccountingService;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategyExecutionConfigRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.user.broker.BrokerExecutionCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminPositionReconciliationService {

    private final PortfolioPositionRepository positionRepository;
    private final StrategySignalRepository signalRepository;
    private final StrategyExecutionConfigRepository configRepository;
    private final AuthUserRepository authUserRepository;
    private final BrokerExecutionCredentialService brokerCredentials;
    private final BrokerPositionTruthService brokerTruthService;
    private final PortfolioAccountingService portfolioAccountingService;

    @Value("${stokr.admin.position-reconciliation.stale-hours:24}")
    private int staleHours;

    public Map<String, Object> reconciliation() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(staleHours, ChronoUnit.HOURS);

        UUID primaryTrader = brokerCredentials.primaryTraderUserId().orElse(null);
        BrokerPositionTruthSnapshot brokerSnap = primaryTrader != null
                ? brokerTruthService.syncUser(primaryTrader)
                : BrokerPositionTruthSnapshot.empty(false);

        Map<String, BigDecimal> brokerQtyBySymbol = brokerSnap.positions().stream()
                .collect(Collectors.toMap(
                        row -> normalizeSymbol(row.symbol()),
                        BrokerPositionTruthSnapshot.BrokerTruthPositionRow::brokerQty,
                        BigDecimal::add,
                        LinkedHashMap::new));

        boolean brokerConnected = brokerSnap.brokerConnected();
        Map<String, StrategySignalEntity> signalByKey = indexRunningSignals(signalRepository.findActiveRunningSignals());

        List<PortfolioPosition> open = positionRepository.findAllRealOpenPositions();
        Set<UUID> userIds = open.stream().map(PortfolioPosition::getUserId).collect(Collectors.toSet());
        Map<UUID, String> userEmails = loadUserEmails(userIds);

        Map<String, Long> openCountByStrategy = open.stream()
                .filter(p -> p.getStrategyKey() != null && !p.getStrategyKey().isBlank())
                .collect(Collectors.groupingBy(p -> p.getStrategyKey().trim().toUpperCase(Locale.ROOT), Collectors.counting()));

        Map<String, Integer> maxPositionsByStrategy = loadMaxPositions(openCountByStrategy.keySet());

        List<Map<String, Object>> rows = new ArrayList<>();
        int ghostCount = 0;
        int blockingCount = 0;

        for (PortfolioPosition pos : open) {
            String strategyKey = pos.getStrategyKey() != null ? pos.getStrategyKey().trim() : "";
            String symbolNorm = normalizeSymbol(pos.getSymbol());
            String signalKey = signalIndexKey(strategyKey, symbolNorm);

            boolean zeroPrice = pos.getAvgPrice() == null || pos.getAvgPrice().compareTo(BigDecimal.ZERO) == 0;
            boolean stale = pos.getUpdatedAt() != null && pos.getUpdatedAt().isBefore(staleBefore);
            BigDecimal brokerQty = brokerConnected ? brokerQtyBySymbol.getOrDefault(symbolNorm, BigDecimal.ZERO) : null;
            boolean brokerFlat = brokerConnected && brokerQty != null
                    && brokerQty.compareTo(BigDecimal.ZERO) == 0;
            boolean ghost = zeroPrice || stale || brokerFlat;

            String strategyUpper = strategyKey.toUpperCase(Locale.ROOT);
            long strategyOpen = strategyKey.isBlank() ? 0 : openCountByStrategy.getOrDefault(strategyUpper, 0L);
            int maxPos = strategyKey.isBlank() ? 0 : maxPositionsByStrategy.getOrDefault(strategyUpper, 0);
            boolean blocking = maxPos > 0 && strategyOpen >= maxPos;

            List<String> flags = new ArrayList<>();
            if (ghost) {
                flags.add("GHOST");
                ghostCount++;
            }
            if (blocking) {
                flags.add("BLOCKING");
                blockingCount++;
            }

            StrategySignalEntity sig = signalByKey.get(signalKey);
            boolean brokerMatch = brokerQty != null && pos.getQuantity() != null
                    && brokerQty.compareTo(pos.getQuantity()) == 0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("positionId", pos.getId());
            row.put("strategyKey", strategyKey.isBlank() ? null : strategyKey);
            row.put("symbol", pos.getSymbol());
            row.put("quantity", pos.getQuantity());
            row.put("avgPrice", pos.getAvgPrice());
            row.put("userId", pos.getUserId());
            row.put("userEmail", userEmails.get(pos.getUserId()));
            row.put("updatedAt", pos.getUpdatedAt() != null ? pos.getUpdatedAt().toString() : null);
            row.put("brokerQty", brokerQty);
            row.put("brokerMatch", brokerConnected ? brokerMatch : null);
            row.put("brokerConnected", brokerConnected);
            row.put("signal", sig != null ? signalSnapshot(sig) : null);
            row.put("flags", flags);
            row.put("status", deriveStatus(flags));
            row.put("maxPositionsForStrategy", maxPos > 0 ? maxPos : null);
            row.put("strategyOpenCount", strategyKey.isBlank() ? null : strategyOpen);
            if (ghost) {
                Map<String, Boolean> ghostReasons = new LinkedHashMap<>();
                ghostReasons.put("zeroPrice", zeroPrice);
                ghostReasons.put("stale", stale);
                ghostReasons.put("brokerFlat", brokerFlat);
                row.put("ghostReasons", ghostReasons);
            }
            rows.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOpen", open.size());
        summary.put("ghostCount", ghostCount);
        summary.put("blockingCount", blockingCount);
        summary.put("strategiesAtCapacity", countStrategiesAtCapacity(openCountByStrategy, maxPositionsByStrategy));
        summary.put("primaryTraderUserId", primaryTrader);
        summary.put("brokerConnected", brokerConnected);
        summary.put("brokerSyncState", brokerSnap.syncState() != null ? brokerSnap.syncState().name() : null);
        summary.put("staleThresholdHours", staleHours);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedAt", now.toString());
        out.put("summary", summary);
        out.put("rows", rows);
        return out;
    }

    public Map<String, Object> clearGhostPositions() {
        int cleared = portfolioAccountingService.clearZeroPriceGhostPositions();
        Map<String, Object> out = reconciliation();
        out.put("clearedGhosts", cleared);
        return out;
    }

    private static String deriveStatus(List<String> flags) {
        if (flags.isEmpty()) {
            return "OK";
        }
        if (flags.size() == 1) {
            return flags.get(0);
        }
        return String.join(",", flags);
    }

    private static int countStrategiesAtCapacity(
            Map<String, Long> openCountByStrategy,
            Map<String, Integer> maxPositionsByStrategy
    ) {
        int count = 0;
        for (Map.Entry<String, Integer> e : maxPositionsByStrategy.entrySet()) {
            if (e.getValue() > 0 && openCountByStrategy.getOrDefault(e.getKey(), 0L) >= e.getValue()) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Integer> loadMaxPositions(Set<String> strategyKeys) {
        Map<String, Integer> out = new HashMap<>();
        for (String key : strategyKeys) {
            configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(key)
                    .ifPresent(cfg -> out.put(key, cfg.getMaxPositions()));
        }
        return out;
    }

    private Map<String, StrategySignalEntity> indexRunningSignals(List<StrategySignalEntity> signals) {
        Map<String, StrategySignalEntity> out = new LinkedHashMap<>();
        for (StrategySignalEntity s : signals) {
            String sk = s.getStrategyName() != null ? s.getStrategyName().trim() : "";
            String sym = normalizeSymbol(s.getSymbol());
            String key = signalIndexKey(sk, sym);
            out.putIfAbsent(key, s);
        }
        return out;
    }

    private static String signalIndexKey(String strategyKey, String symbolNorm) {
        return strategyKey.toUpperCase(Locale.ROOT) + "|" + symbolNorm;
    }

    private static Map<String, Object> signalSnapshot(StrategySignalEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("outcomeStatus", s.getOutcomeStatus());
        m.put("signalType", s.getSignalType() != null ? s.getSignalType().name() : null);
        m.put("symbol", s.getSymbol());
        m.put("strategyName", s.getStrategyName());
        m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        return m;
    }

    private Map<UUID, String> loadUserEmails(Set<UUID> userIds) {
        Map<UUID, String> out = new HashMap<>();
        for (UUID id : userIds) {
            Optional<AuthUser> user = authUserRepository.findById(id);
            user.ifPresent(u -> out.put(id, u.getEmail()));
        }
        return out;
    }

    static String normalizeSymbol(String symbol) {
        return BrokerPositionTruthService.normalizeSymbol(symbol);
    }
}
