package com.stokr.execution.safety;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OmsExecutionDedupeService {

    private final OmsExecutionDedupeKeyRepository repository;

    @Value("${stokr.oms.dedupe.window-seconds:300}")
    private long windowSeconds;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    public String buildExecutionKey(String strategy, String symbol, String direction, LocalDate sessionDate) {
        return String.join("|",
                normalize(strategy),
                normalize(symbol),
                normalize(direction),
                sessionDate.toString());
    }

    /**
     * @return true if dedupe slot acquired; false if duplicate within active window
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = DataIntegrityViolationException.class)
    public boolean tryAcquire(
            String strategy,
            String symbol,
            String direction,
            UUID userId,
            UUID orderId,
            Instant now) {
        LocalDate session = now.atZone(zone).toLocalDate();
        String key = buildExecutionKey(strategy, symbol, direction, session);
        Instant expires = now.plus(windowSeconds, ChronoUnit.SECONDS);

        var existing = repository.findActiveByKey(key, now);
        if (existing.isPresent()) {
            log.warn("oms.dedupe.rejected key={} existingOrderId={}", key, existing.get().getOrderId());
            return false;
        }

        OmsExecutionDedupeKey row = new OmsExecutionDedupeKey();
        row.setExecutionKey(key);
        row.setStrategyName(normalize(strategy));
        row.setSymbol(normalize(symbol));
        row.setDirection(normalize(direction));
        row.setSessionDate(session);
        row.setUserId(userId);
        row.setOrderId(orderId);
        row.setCreatedAt(now);
        row.setExpiresAt(expires);
        try {
            repository.save(row);
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.warn("oms.dedupe.conflict key={}", key);
            return false;
        }
    }

    public long dedupeWindowSeconds() {
        return windowSeconds;
    }

    public long activeKeyCount() {
        return repository.count();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }
}
