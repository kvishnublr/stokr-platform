package com.stokr.strategy.operational;

import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OperationalSessionSummaryService {

    private final OperationalSessionSummaryRepository summaryRepository;
    private final StrategyRuntimeHealthRepository runtimeHealthRepository;
    private final StrategyExecutionModeService executionModeService;
    private final FeedHealthMonitorService feedHealthMonitorService;
    private final EntityManager entityManager;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Transactional
    public OperationalSessionSummary generateForSession(LocalDate sessionDate) {
        Instant sessionStart = ZonedDateTime.of(sessionDate, java.time.LocalTime.of(9, 15), zone).toInstant();
        Instant sessionEnd = ZonedDateTime.of(sessionDate, java.time.LocalTime.of(15, 30), zone).toInstant();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessionDate", sessionDate.toString());
        summary.put("generatedAt", Instant.now().toString());

        List<StrategyRuntimeHealth> healthRows =
                runtimeHealthRepository.findBySessionDateOrderByStrategyNameAsc(sessionDate);
        summary.put("strategyRuntimeHealth", healthRows.stream().map(this::healthMap).toList());
        summary.put("executionModes", executionModeService.allModes());

        summary.put("signalsByStrategy", querySignalsByStrategy(sessionStart, sessionEnd));
        summary.put("avgHoldSecondsByStrategy", queryAvgHoldByStrategy(sessionStart, sessionEnd));
        summary.put("exitCategoryBreakdown", queryExitCategories(sessionStart, sessionEnd));
        summary.put("integrityRejectionCount", queryLong(
                "select count(*) from market_data_integrity_rejections where session_date = :d",
                sessionDate));
        summary.put("feedHealth", feedHealthMonitorService.snapshotMap(Instant.now()));
        summary.put("staleFeedIncidents", feedHealthMonitorService.staleFeedIncidents());
        summary.put("feedOutageSeconds", feedHealthMonitorService.totalOutageSeconds());
        summary.put("strategyUptimePct", computeUptimePct(healthRows));

        OperationalSessionSummary row = summaryRepository.findBySessionDate(sessionDate)
                .orElseGet(OperationalSessionSummary::new);
        row.setSessionDate(sessionDate);
        row.setSummaryJson(summary);
        row.setCreatedAt(Instant.now());
        OperationalSessionSummary saved = summaryRepository.save(row);
        log.info("operational.session_summary.generated date={} strategies={}", sessionDate, healthRows.size());
        return saved;
    }

    private Map<String, Object> healthMap(StrategyRuntimeHealth h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategyName", h.getStrategyName());
        m.put("executionMode", h.getExecutionMode());
        m.put("scansAttempted", h.getScansAttempted());
        m.put("signalsGenerated", h.getSignalsGenerated());
        m.put("tradesOpened", h.getTradesOpened());
        m.put("tradesClosed", h.getTradesClosed());
        m.put("rejectionRate", h.getRejectionRate());
        m.put("avgHoldSeconds", h.getAvgHoldSeconds());
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> querySignalsByStrategy(Instant start, Instant end) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                select strategy_name,
                       count(*)::bigint as total,
                       count(*) filter (where outcome_status = 'TARGET_HIT')::bigint as wins,
                       count(*) filter (where outcome_status in ('STOPLOSS_HIT','SL_HIT'))::bigint as losses
                from strategy_signals
                where deleted = false and backtest_run_id is null and is_test_trade = false
                  and created_at >= :start and created_at <= :end
                group by strategy_name
                order by total desc
                """)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategyName", String.valueOf(r[0]));
            m.put("signals", ((Number) r[1]).longValue());
            m.put("wins", ((Number) r[2]).longValue());
            m.put("losses", ((Number) r[3]).longValue());
            return m;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryAvgHoldByStrategy(Instant start, Instant end) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                select strategy_name, avg(hold_seconds)::bigint
                from strategy_exit_telemetry
                where exit_time >= :start and exit_time <= :end
                group by strategy_name
                order by avg(hold_seconds) desc
                """)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategyName", String.valueOf(r[0]));
            m.put("avgHoldSeconds", r[1] instanceof Number n ? n.longValue() : 0L);
            return m;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryExitCategories(Instant start, Instant end) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                select exit_category, count(*)::bigint
                from strategy_exit_telemetry
                where exit_time >= :start and exit_time <= :end
                group by exit_category
                order by count(*) desc
                """)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exitCategory", String.valueOf(r[0]));
            m.put("count", ((Number) r[1]).longValue());
            return m;
        }).toList();
    }

    private long queryLong(String sql, LocalDate sessionDate) {
        Object r = entityManager.createNativeQuery(sql)
                .setParameter("d", sessionDate)
                .getSingleResult();
        return r instanceof Number n ? n.longValue() : 0L;
    }

    private double computeUptimePct(List<StrategyRuntimeHealth> rows) {
        if (rows.isEmpty()) {
            return 0.0;
        }
        long attempted = rows.stream().mapToLong(StrategyRuntimeHealth::getScansAttempted).sum();
        long blocked = rows.stream().mapToLong(r -> r.getScansBlockedIntegrity() + r.getScansBlockedFeed()).sum();
        if (attempted <= 0) {
            return 100.0;
        }
        return Math.max(0.0, (attempted - blocked) * 100.0 / attempted);
    }
}
