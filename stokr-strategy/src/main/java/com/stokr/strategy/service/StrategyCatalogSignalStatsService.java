package com.stokr.strategy.service;

import com.stokr.strategy.dto.StrategyCatalogSignalStatsDto;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StrategyCatalogSignalStatsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final StrategySignalRepository signalRepository;

    @Transactional(readOnly = true)
    public List<StrategyCatalogSignalStatsDto> signalsTodayByStrategyKey() {
        Instant since = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        Map<String, StrategyCatalogSignalStatsDto> byKey = new LinkedHashMap<>();
        for (Object[] row : signalRepository.countLiveSignalsSinceGroupedByStrategyName(since)) {
            String key = row[0] == null ? "" : String.valueOf(row[0]).trim().toUpperCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            Instant last = row[2] instanceof Instant i ? i : null;
            byKey.put(key, new StrategyCatalogSignalStatsDto(key, count, last));
        }
        return List.copyOf(byKey.values());
    }
}
