package com.stokr.backtest.service;

import com.stokr.backtest.repository.BacktestMetricsRepository;
import com.stokr.backtest.web.dto.StrategyLeaderboardRowDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StrategyResearchQueryService {

    private final BacktestMetricsRepository metricsRepository;

    @Transactional(readOnly = true)
    public List<StrategyLeaderboardRowDto> leaderboard(UUID userId) {
        return metricsRepository.leaderboardRowsRaw(userId).stream()
                .map(r -> new StrategyLeaderboardRowDto(
                        (String) r[0],
                        toBd(r[1]),
                        toBd(r[2]),
                        toBd(r[3]),
                        r[4] instanceof Number n ? n.longValue() : 0L
                ))
                .toList();
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(o.toString());
    }
}
