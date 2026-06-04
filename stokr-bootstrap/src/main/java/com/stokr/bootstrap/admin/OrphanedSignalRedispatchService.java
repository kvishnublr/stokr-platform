package com.stokr.bootstrap.admin;

import com.stokr.common.pipeline.OmsIntentDispatcher;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.operational.StrategyExecutionModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrphanedSignalRedispatchService {

    private final EntityManager entityManager;
    private final OmsOrderRepository orderRepository;
    private final OmsIntentDispatcher omsIntentDispatcher;
    private final StrategyExecutionModeService executionModeService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> redispatchSessionOrphans(Instant anchor) {
        Instant now = anchor != null ? anchor : Instant.now();
        LocalDate session = now.atZone(zone).toLocalDate();
        Instant sessionStart = session.atStartOfDay(zone).toInstant();

        @SuppressWarnings("unchecked")
        List<UUID> orphanIds = entityManager.createNativeQuery("""
                select s.id from strategy_signals s
                where s.deleted = false
                  and s.created_at >= :sessionStart
                  and coalesce(s.is_test_trade, false) = false
                  and s.signal_type is not null
                  and s.signal_type <> 'HOLD'
                  and not exists (
                    select 1 from oms_orders o
                    where o.deleted = false and o.signal_id = s.id
                  )
                order by s.created_at asc
                limit 50
                """)
                .setParameter("sessionStart", sessionStart)
                .getResultList();

        List<StrategySignalEntity> candidates = orphanIds.stream()
                .map(id -> entityManager.find(StrategySignalEntity.class, id))
                .filter(s -> s != null)
                .toList();

        List<Map<String, Object>> redispatched = new ArrayList<>();
        int skipped = 0;

        for (StrategySignalEntity signal : candidates) {
            if (!orderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(signal.getId()).isEmpty()) {
                skipped++;
                continue;
            }
            String strategyKey = signal.getStrategyName() != null ? signal.getStrategyName() : "UNKNOWN";
            String mode = executionModeService.modeFor(strategyKey).name();
            UUID userId = signal.getUserId() != null ? signal.getUserId() : systemUserId;
            SignalPersistedMessage msg = new SignalPersistedMessage(
                    signal.getId(),
                    userId,
                    UUID.randomUUID().toString(),
                    signal.getBacktestRunId(),
                    mode
            );
            try {
                omsIntentDispatcher.dispatch(msg, true);
                redispatched.add(Map.of(
                        "signalId", signal.getId().toString(),
                        "strategy", strategyKey,
                        "symbol", signal.getSymbol(),
                        "mode", mode
                ));
                log.info("orphan_signal.redispatched signalId={} strategy={} symbol={} mode={}",
                        signal.getId(), strategyKey, signal.getSymbol(), mode);
            } catch (Exception ex) {
                log.warn("orphan_signal.redispatch_failed signalId={} {}", signal.getId(), ex.toString());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionDate", session.toString());
        out.put("candidates", candidates.size());
        out.put("skippedHasOrder", skipped);
        out.put("redispatched", redispatched.size());
        out.put("details", redispatched);
        return out;
    }
}
