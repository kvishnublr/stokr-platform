package com.stokr.admin.service;

import com.stokr.admin.audit.OperationalHistoryService;
import com.stokr.admin.dto.OperationsSnapshotDto;
import com.stokr.admin.telemetry.BrokerSessionRegistryService;
import com.stokr.admin.telemetry.MarketDataFreshnessService;
import com.stokr.admin.telemetry.MarketPlaneMonitorService;
import com.stokr.admin.telemetry.OperationalIncidentService;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.repository.BacktestJobRepository;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.telemetry.SignalDistributionTelemetryService;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.service.BrokerOperationalCircuitService;
import com.stokr.risk.service.KillSwitchService;
import com.stokr.risk.service.LiveTradingArmingService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.telemetry.ScannerExecutionTelemetryService;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.repository.BrokerAccountRepository;
import jakarta.persistence.EntityManager;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Aggregated operational telemetry for the admin cockpit (monolith — not microservices).
 */
@Service
public class AdminOperationalSnapshotService {

    private static final Set<OrderState> STUCK_ORDER_STATES = Set.of(
            OrderState.PENDING_SUBMISSION,
            OrderState.SUBMITTED,
            OrderState.RISK_CHECK,
            OrderState.VALIDATED
    );

    private final KillSwitchService killSwitchService;
    private final LiveTradingArmingService liveTradingArmingService;
    private final RabbitAdmin rabbitAdmin;
    private final ObjectProvider<SimpUserRegistry> simpUserRegistry;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplate;
    private final ObjectProvider<DataSource> dataSource;
    private final AuthUserRepository authUserRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final BacktestJobRepository backtestJobRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final OmsExecutionRepository omsExecutionRepository;
    private final StrategySignalRepository strategySignalRepository;
    private final BrokerOperationalCircuitService brokerOperationalCircuit;
    private final EntityManager entityManager;
    private final BrokerSessionRegistryService brokerSessionRegistryService;
    private final MarketDataFreshnessService marketDataFreshnessService;
    private final OperationalIncidentService operationalIncidentService;
    private final BrokerAccountRepository brokerAccountRepository;
    private final SignalDistributionTelemetryService signalDistributionTelemetryService;
    private final MarketPlaneMonitorService marketPlaneMonitorService;
    private final OperationalHistoryService operationalHistoryService;
    private final ScannerExecutionTelemetryService scannerExecutionTelemetryService;
    private final PlatformMarketFeedService platformMarketFeedService;

    @Autowired
    public AdminOperationalSnapshotService(
            KillSwitchService killSwitchService,
            LiveTradingArmingService liveTradingArmingService,
            RabbitAdmin rabbitAdmin,
            ObjectProvider<SimpUserRegistry> simpUserRegistry,
            ObjectProvider<StringRedisTemplate> stringRedisTemplate,
            ObjectProvider<DataSource> dataSource,
            AuthUserRepository authUserRepository,
            StrategyInstanceRepository strategyInstanceRepository,
            StrategyDefinitionRepository strategyDefinitionRepository,
            BacktestJobRepository backtestJobRepository,
            OmsOrderRepository omsOrderRepository,
            OmsExecutionRepository omsExecutionRepository,
            StrategySignalRepository strategySignalRepository,
            BrokerOperationalCircuitService brokerOperationalCircuit,
            EntityManager entityManager,
            BrokerSessionRegistryService brokerSessionRegistryService,
            MarketDataFreshnessService marketDataFreshnessService,
            OperationalIncidentService operationalIncidentService,
            BrokerAccountRepository brokerAccountRepository,
            SignalDistributionTelemetryService signalDistributionTelemetryService,
            MarketPlaneMonitorService marketPlaneMonitorService,
            OperationalHistoryService operationalHistoryService,
            ScannerExecutionTelemetryService scannerExecutionTelemetryService,
            PlatformMarketFeedService platformMarketFeedService
    ) {
        this.killSwitchService = killSwitchService;
        this.liveTradingArmingService = liveTradingArmingService;
        this.rabbitAdmin = rabbitAdmin;
        this.simpUserRegistry = simpUserRegistry;
        this.stringRedisTemplate = stringRedisTemplate;
        this.dataSource = dataSource;
        this.authUserRepository = authUserRepository;
        this.strategyInstanceRepository = strategyInstanceRepository;
        this.strategyDefinitionRepository = strategyDefinitionRepository;
        this.backtestJobRepository = backtestJobRepository;
        this.omsOrderRepository = omsOrderRepository;
        this.omsExecutionRepository = omsExecutionRepository;
        this.strategySignalRepository = strategySignalRepository;
        this.brokerOperationalCircuit = brokerOperationalCircuit;
        this.entityManager = entityManager;
        this.brokerSessionRegistryService = brokerSessionRegistryService;
        this.marketDataFreshnessService = marketDataFreshnessService;
        this.operationalIncidentService = operationalIncidentService;
        this.brokerAccountRepository = brokerAccountRepository;
        this.signalDistributionTelemetryService = signalDistributionTelemetryService;
        this.marketPlaneMonitorService = marketPlaneMonitorService;
        this.operationalHistoryService = operationalHistoryService;
        this.scannerExecutionTelemetryService = scannerExecutionTelemetryService;
        this.platformMarketFeedService = platformMarketFeedService;
    }

    @Transactional(readOnly = true)
    public OperationsSnapshotDto snapshot() {
        Instant now = Instant.now();
        Map<String, Object> marketFreshness = marketDataFreshnessService.snapshot(now);
        Map<String, Object> marketInfra = marketInfra(now, marketFreshness);
        Map<String, Object> replayInfra = replayInfra(now);
        Map<String, Object> oms = omsSection(now);
        Map<String, Object> system = systemSection(now);
        Map<String, Object> brokerSessions = brokerSessionRegistryService.snapshot(now);
        Map<String, Object> scannerTelemetry = scannerTelemetry(now);
        Map<String, Object> signalDistribution = signalDistribution(now);
        Map<String, Object> traderExecutionHealth = traderExecutionHealth();
        Map<String, Object> marketPlane = marketPlaneMonitorService.snapshot(now);
        Map<String, Object> operationalHistory = operationalHistoryService.snapshotSection();
        Map<String, Object> platformMarketFeed = canonicalPlatformMarketFeedSnapshot(now);
        @SuppressWarnings("unchecked")
        Map<String, Object> rabbit = (Map<String, Object>) system.get("rabbitQueues");
        List<Map<String, Object>> incidents = operationalIncidentService.evaluate(
                now,
                system,
                marketFreshness,
                replayInfra,
                rabbit,
                brokerSessions
        );
        return new OperationsSnapshotDto(
                now,
                marketInfra,
                replayInfra,
                oms,
                system,
                brokerSessions,
                marketFreshness,
                scannerTelemetry,
                signalDistribution,
                traderExecutionHealth,
                incidents,
                marketPlane,
                operationalHistory,
                platformMarketFeed
        );
    }

    /**
     * Identical payload shape to {@link com.stokr.user.broker.PlatformMarketFeedService#infrastructureSnapshot()}
     * (same service call). Never null; on failure returns explicit DISCONNECTED vendor rows plus {@code error}.
     */
    private Map<String, Object> canonicalPlatformMarketFeedSnapshot(Instant collectedAt) {
        try {
            Map<String, Object> m = platformMarketFeedService.infrastructureSnapshot();
            if (m == null || m.isEmpty()) {
                return minimalFailedPlatform("infrastructureSnapshot returned null/empty", collectedAt);
            }
            Object vObj = m.get("vendors");
            if (!(vObj instanceof Map<?, ?> v) || v.isEmpty()) {
                return minimalFailedPlatform("infrastructureSnapshot missing vendors map", collectedAt);
            }
            return m;
        } catch (Exception ex) {
            return minimalFailedPlatform(ex.getClass().getSimpleName() + ": " + ex.getMessage(), collectedAt);
        }
    }

    private Map<String, Object> minimalFailedPlatform(String reason, Instant collectedAt) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("collectedAt", collectedAt.toString());
        root.put("plane", "PLATFORM_MARKET_FEED");
        root.put("error", reason);
        root.put("note", "Platform vendor map failed during operations snapshot assembly.");
        Map<String, Object> vendors = new LinkedHashMap<>();
        for (String code : List.of("ZERODHA", "UPSTOX", "ANGEL", "DHAN")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vendorCode", code);
            row.put("role", "PLATFORM_MARKET_FEED");
            row.put("connectionState", "DISCONNECTED");
            row.put("websocketState", "CLOSED");
            row.put("configured", false);
            row.put("operationalLivePath", false);
            row.put("operationalLivePathDetail", reason);
            row.put("detail", reason);
            vendors.put(code, row);
        }
        root.put("vendors", vendors);
        return root;
    }

    private Map<String, Object> marketInfra(Instant collectedAt, Map<String, Object> freshness) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plane", "MONOLITH_DB");
        m.put("note", "Vendor websocket packet rates not wired — candle store + broker_sessions are authoritative for this build.");
        m.put("collectedAt", collectedAt.toString());
        m.put("distinctSymbols", queryLong("select count(distinct symbol) from marketdata_candles where deleted = false"));
        m.put("candles1mTotal", queryLong("select count(*) from marketdata_candles where deleted = false and timeframe = '1m'"));
        m.put("candles5mTotal", queryLong("select count(*) from marketdata_candles where deleted = false and timeframe = '5m'"));
        m.put("latestCandleOpenTime1m", queryInstant("select max(open_time) from marketdata_candles where deleted = false and timeframe = '1m'"));
        m.put("latestCandleOpenTime5m", queryInstant("select max(open_time) from marketdata_candles where deleted = false and timeframe = '5m'"));
        m.put("globalBrokerHalt", brokerOperationalCircuit.isGlobalBrokerHalt());
        m.put("freshnessStatus", freshness.get("status"));
        m.put("latest1mLagSeconds", freshness.get("latest1mLagSeconds"));
        return m;
    }

    private Map<String, Object> replayInfra(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobsQueued", backtestJobRepository.countByStatusAndDeletedFalse(BacktestJobStatus.QUEUED));
        m.put("jobsRunning", backtestJobRepository.countByStatusAndDeletedFalse(BacktestJobStatus.RUNNING));
        m.put("jobsCompleted", backtestJobRepository.countByStatusAndDeletedFalse(BacktestJobStatus.COMPLETED));
        m.put("jobsFailed", backtestJobRepository.countByStatusAndDeletedFalse(BacktestJobStatus.FAILED));
        m.put("jobsCancelled", backtestJobRepository.countByStatusAndDeletedFalse(BacktestJobStatus.CANCELLED));
        m.put("jobsTotal", backtestJobRepository.countByDeletedFalse());
        m.put("activeReplayJobs", activeReplayJobRows());
        m.put("recentTerminalJobs", recentReplayJobs());
        m.put("note", "replay_diagnosis is persisted on each job at terminal state — see recentTerminalJobs; activeReplayJobs is QUEUED+RUNNING slice.");
        return m;
    }

    private List<Map<String, Object>> recentReplayJobs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BacktestJob j : backtestJobRepository.findTop15ByDeletedFalseOrderByUpdatedAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", j.getId().toString());
            row.put("status", j.getStatus().name());
            row.put("runId", j.getRunId() != null ? j.getRunId().toString() : null);
            row.put("progressPct", j.getProgress());
            row.put("processedBars", j.getProcessedBars());
            row.put("totalBars", j.getTotalBars());
            boolean terminal = j.getStatus() == BacktestJobStatus.COMPLETED
                    || j.getStatus() == BacktestJobStatus.FAILED
                    || j.getStatus() == BacktestJobStatus.CANCELLED;
            if (terminal) {
                row.put("replayDiagnosis", j.getReplayDiagnosis());
                row.put("candlesExpected", j.getReplayCandlesExpected());
                row.put("candlesProcessed", j.getReplayCandlesProcessed());
                row.put("signalsEmitted", j.getReplaySignalsEmitted());
                row.put("executionEvents", j.getReplayExecutionEvents());
                row.put("durationMs", j.getReplayDurationMs());
                row.put("messageSnippet", j.getMessage() != null && j.getMessage().length() > 200
                        ? j.getMessage().substring(0, 200)
                        : j.getMessage());
            } else {
                row.put("replayDiagnosis", null);
                row.put("messageSnippet", j.getMessage());
            }
            row.put("updatedAt", j.getUpdatedAt() != null ? j.getUpdatedAt().toString() : null);
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> omsSection(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        long orders = omsOrderRepository.countByDeletedFalse();
        long rejected = omsOrderRepository.countByDeletedFalseAndState(OrderState.REJECTED);
        long failed = omsOrderRepository.countByDeletedFalseAndState(OrderState.FAILED);
        m.put("ordersTotal", orders);
        m.put("ordersRejected", rejected);
        m.put("ordersFailed", failed);
        m.put("rejectRateApprox", orders <= 0 ? 0.0 : (rejected * 100.0) / orders);
        Double avgLat = omsExecutionRepository.averageLatencyMs(null, null, null);
        m.put("executionAvgLatencyMs", avgLat);
        m.put("signalsPersistedTotal", strategySignalRepository.countByDeletedFalse());
        Instant since1m = now.minusSeconds(60);
        long ordersLast60s = omsOrderRepository.countByDeletedFalseAndCreatedAtGreaterThanEqual(since1m);
        m.put("ordersCreatedLast60s", ordersLast60s);
        m.put("ordersPerSecApprox", ordersLast60s / 60.0);
        Instant stuckBefore = now.minusSeconds(300);
        m.put("stuckOrdersApprox", omsOrderRepository.countStuckOrders(STUCK_ORDER_STATES, stuckBefore));
        m.put("recentFailures", recentOmsFailureRows());
        return m;
    }

    private Map<String, Object> scannerTelemetry(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        Instant since = now.minus(Duration.ofMinutes(60));
        m.put("signalsEmittedLast60m", strategySignalRepository.countByCreatedAtAfterAndDeletedFalse(since));
        m.put("runningStrategyInstances", strategyInstanceRepository.countByRuntimeStateAndDeletedFalse("RUNNING"));
        m.put("catalogStrategies", strategyDefinitionRepository.countByDeletedFalse());
        m.put("strategyRows", scannerStrategyRows(now));
        m.put("note", "Per-scan latency histogram not instrumented — strategyRows + DB counts are authoritative for this build.");
        m.putAll(scannerExecutionTelemetryService.snapshotOverlay(now));
        strategySignalRepository.findFirstByDeletedFalseOrderByCreatedAtDesc()
                .ifPresentOrElse(
                        sig -> m.put("lastSignalCreatedAt", sig.getCreatedAt() != null ? sig.getCreatedAt().toString() : null),
                        () -> m.put("lastSignalCreatedAt", null));
        return m;
    }

    private Map<String, Object> signalDistribution(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        Instant since = now.minus(Duration.ofMinutes(60));
        m.put("signalsEmittedLast60m", strategySignalRepository.countByCreatedAtAfterAndDeletedFalse(since));
        m.put("signalsPersistedTotal", strategySignalRepository.countByDeletedFalse());
        m.put("signalsLiveLast60m", strategySignalRepository.countByCreatedAtAfterAndDeletedFalseAndBacktestRunIdNull(since));
        m.put("signalsReplayLast60m", strategySignalRepository.countByCreatedAtAfterAndDeletedFalseAndBacktestRunIdNotNull(since));
        Map<String, Object> rt = signalDistributionTelemetryService.snapshotOverlay();
        m.put("routingTelemetry", rt);
        m.put("signalsRoutedToOmsTotal", rt.get("omsIntentsSeenTotal"));
        m.put("signalsEmittedProcessTotal", rt.get("signalsEmittedTotal"));
        m.put("rabbitDispatchesTotal", rt.get("rabbitDispatchesTotal"));
        m.put("routingLatencyMsP50", rt.get("routingLatencyMsAvgSample"));
        m.put("deliveryFailuresApprox", rt.get("executionFailuresTotal"));
        m.put("executionAcksApprox", rt.get("ordersCreatedFromSignalTotal"));
        m.put("retryAttemptsTotal", rt.get("executionRetriesTotal"));
        m.put("dlqHandoffsTotal", rt.get("executionDlqTotal"));
        m.put("perTraderSignalDelivery", rt.get("perTraderDelivery"));
        m.put("recentSignalEvents", rt.get("recentSignalEvents"));
        m.put("recentSignals", recentSignalsForAdmin());
        m.put("websocketUsersApprox", websocketUsers());
        m.put("routedTradersApproxNote", "OMS dequeue + execution telemetry (in-process) — not a durable routing journal.");
        m.put("note", "Flat keys mirror routingTelemetry for cockpit panels; extend with DB journal for audit-grade lineage.");
        return m;
    }

    private Map<String, Object> traderExecutionHealth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registeredUsers", authUserRepository.countByDeletedFalse());
        m.put("liveTradingApprovedUsers", authUserRepository.countByLiveTradingApprovedTrueAndDeletedFalse());
        m.put("distinctUsersBrokerConnected", brokerAccountRepository.countDistinctUserIdsWithConnectedBroker());
        m.put("runningStrategies", strategyInstanceRepository.countByRuntimeStateAndDeletedFalse("RUNNING"));
        m.put("traderRows", traderExecutionRows());
        m.put("note", "Per-trader margin utilization not in DB — broker_sessions + last OMS activity shown in traderRows.");
        return m;
    }

    private Map<String, Object> systemSection(Instant collectedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("killSwitch", killSwitchService.isEnabled());
        m.put("liveTradingArmed", liveTradingArmingService.isArmed());
        m.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        m.put("heapUsedBytes", mem.getHeapMemoryUsage().getUsed());
        m.put("heapMaxBytes", mem.getHeapMemoryUsage().getMax());
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        m.put("osLoadAverage", os.getSystemLoadAverage());
        m.put("websocketUsersApprox", websocketUsers());
        m.put("rabbitQueues", rabbitQueues());
        m.put("redis", redisPing());
        m.put("database", databasePing());
        m.put("collectedAt", collectedAt.toString());
        return m;
    }

    private Map<String, Object> redisPing() {
        StringRedisTemplate redis = stringRedisTemplate.getIfAvailable();
        if (redis == null) {
            return Map.of("status", "UNKNOWN", "reason", "StringRedisTemplate not available in this context");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        try (RedisConnection c = redis.getConnectionFactory().getConnection()) {
            String pong = c.ping();
            long ms = Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
            out.put("status", "CONNECTED");
            out.put("pingMs", ms);
            out.put("pong", pong != null ? pong : "");
            try {
                Properties mem = c.serverCommands().info("memory");
                if (mem != null) {
                    out.put("usedMemoryHuman", mem.getProperty("used_memory_human"));
                    long used = parseLongSafe(mem.getProperty("used_memory"));
                    if (used >= 0) {
                        out.put("usedMemoryBytes", used);
                    }
                    long rss = parseLongSafe(mem.getProperty("used_memory_rss"));
                    if (rss >= 0) {
                        out.put("usedMemoryRssBytes", rss);
                    }
                }
                Properties stats = c.serverCommands().info("stats");
                if (stats != null) {
                    out.put("instantaneousOpsPerSec", stats.getProperty("instantaneous_ops_per_sec"));
                    long pubsub = parseLongSafe(stats.getProperty("pubsub_channels"));
                    if (pubsub >= 0) {
                        out.put("pubsubChannelsApprox", pubsub);
                    }
                }
            } catch (Exception ex) {
                out.put("extendedInfo", "unavailable: " + ex.getClass().getSimpleName());
            }
            return out;
        } catch (Exception ex) {
            return Map.of("status", "DISCONNECTED", "error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private Map<String, Object> databasePing() {
        Map<String, Object> m = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        try {
            Object one = entityManager.createNativeQuery("select 1").getSingleResult();
            long ms = Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
            m.put("status", "CONNECTED");
            m.put("pingMs", ms);
            m.put("probe", one != null ? one.toString() : "");
        } catch (Exception ex) {
            m.put("status", "DISCONNECTED");
            m.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return m;
        }
        DataSource ds = dataSource.getIfAvailable();
        if (ds instanceof HikariDataSource hds) {
            HikariPoolMXBean pool = hds.getHikariPoolMXBean();
            if (pool != null) {
                m.put("poolActiveConnections", pool.getActiveConnections());
                m.put("poolIdleConnections", pool.getIdleConnections());
                m.put("poolTotalConnections", pool.getTotalConnections());
                m.put("poolThreadsAwaitingConnection", pool.getThreadsAwaitingConnection());
            }
            m.put("poolName", hds.getPoolName());
            m.put("poolMaximumSize", hds.getMaximumPoolSize());
        }
        try {
            Object row = entityManager.createNativeQuery(
                    "select version, description from flyway_schema_history where success = true order by installed_rank desc limit 1"
            ).getSingleResult();
            if (row instanceof Object[] arr && arr.length >= 2) {
                m.put("flywayVersion", arr[0] != null ? arr[0].toString() : null);
                m.put("flywayDescription", arr[1] != null ? arr[1].toString() : null);
            }
        } catch (Exception ex) {
            m.put("flywayLookup", "unavailable: " + ex.getClass().getSimpleName());
        }
        return m;
    }

    private Map<String, Object> rabbitQueues() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(PipelineQueues.STRATEGY_SIGNAL, queueProps(PipelineQueues.STRATEGY_SIGNAL));
        out.put(PipelineQueues.STRATEGY_SIGNAL_DLQ, queueProps(PipelineQueues.STRATEGY_SIGNAL_DLQ));
        out.put(PipelineQueues.OMS_ORDER, queueProps(PipelineQueues.OMS_ORDER));
        out.put(PipelineQueues.OMS_ORDER_DLQ, queueProps(PipelineQueues.OMS_ORDER_DLQ));
        out.put(PipelineQueues.EXECUTION, queueProps(PipelineQueues.EXECUTION));
        out.put(PipelineQueues.EXECUTION_DLQ, queueProps(PipelineQueues.EXECUTION_DLQ));
        return out;
    }

    private Map<String, Object> queueProps(String queue) {
        try {
            Properties p = rabbitAdmin.getQueueProperties(queue);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("queueName", queue);
            if (p != null) {
                for (String name : p.stringPropertyNames()) {
                    map.put(name, p.getProperty(name));
                }
                enrichQueueDepthFromProps(p, map);
            } else {
                map.put("status", "UNKNOWN");
                map.put("detail", "RabbitAdmin returned null queue properties (queue missing or broker unreachable)");
            }
            return map;
        } catch (Exception ex) {
            return Map.of("queueName", queue, "status", "ERROR", "error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static void enrichQueueDepthFromProps(Properties p, Map<String, Object> map) {
        for (String name : p.stringPropertyNames()) {
            String upper = name.toUpperCase();
            String val = p.getProperty(name);
            if (val == null) {
                continue;
            }
            if ((upper.contains("MESSAGE") || upper.contains("MESSAGES")) && upper.contains("COUNT")) {
                map.putIfAbsent("messageCountApprox", parseLongSafe(val));
            }
            if (upper.contains("CONSUMER") && upper.contains("COUNT")) {
                map.putIfAbsent("consumerCountApprox", parseLongSafe(val));
            }
        }
    }

    private static long parseLongSafe(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        try {
            int dot = raw.indexOf('.');
            String n = dot > 0 ? raw.substring(0, dot) : raw;
            return Long.parseLong(n.trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private int websocketUsers() {
        SimpUserRegistry reg = simpUserRegistry.getIfAvailable();
        if (reg == null) {
            return -1;
        }
        try {
            return reg.getUsers().size();
        } catch (Exception ex) {
            return -1;
        }
    }

    private List<Map<String, Object>> activeReplayJobRows() {
        List<BacktestJob> jobs = backtestJobRepository.findTop12ByStatusInAndDeletedFalseOrderByUpdatedAtDesc(
                List.of(BacktestJobStatus.QUEUED, BacktestJobStatus.RUNNING));
        List<Map<String, Object>> out = new ArrayList<>();
        for (BacktestJob j : jobs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", j.getId().toString());
            row.put("status", j.getStatus().name());
            row.put("runId", j.getRunId() != null ? j.getRunId().toString() : null);
            row.put("progressPct", j.getProgress());
            row.put("processedBars", j.getProcessedBars());
            row.put("totalBars", j.getTotalBars());
            row.put("updatedAt", j.getUpdatedAt() != null ? j.getUpdatedAt().toString() : null);
            row.put("replayDiagnosis", j.getReplayDiagnosis());
            row.put("messageSnippet", j.getMessage() != null && j.getMessage().length() > 160
                    ? j.getMessage().substring(0, 160)
                    : j.getMessage());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> recentOmsFailureRows() {
        List<OmsOrder> list = omsOrderRepository.findRecentByStateIn(
                List.of(OrderState.REJECTED, OrderState.FAILED),
                PageRequest.of(0, 12));
        List<Map<String, Object>> out = new ArrayList<>();
        for (OmsOrder o : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.getId().toString());
            row.put("state", o.getState() != null ? o.getState().name() : null);
            row.put("symbol", o.getSymbol());
            row.put("side", o.getSide());
            row.put("strategyKey", o.getStrategyKey());
            row.put("executionMode", o.getExecutionMode() != null ? o.getExecutionMode().name() : null);
            row.put("brokerVendor", o.getBrokerVendor());
            row.put("updatedAt", o.getUpdatedAt() != null ? o.getUpdatedAt().toString() : null);
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> recentSignalsForAdmin() {
        List<StrategySignalEntity> list = strategySignalRepository.findTop30ByDeletedFalseOrderByCreatedAtDesc(
                PageRequest.of(0, 30));
        List<Map<String, Object>> out = new ArrayList<>();
        for (StrategySignalEntity s : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            row.put("strategyName", s.getStrategyName());
            row.put("signalType", s.getSignalType() != null ? s.getSignalType().name() : null);
            row.put("symbol", s.getSymbol());
            row.put("backtestRunId", s.getBacktestRunId() != null ? s.getBacktestRunId().toString() : null);
            row.put("pipeline", s.getPipeline());
            row.put("replay", s.getBacktestRunId() != null);
            row.put("rejectionPattern", s.getRejectionPattern());
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scannerStrategyRows(Instant now) {
        Instant since = now.minus(Duration.ofMinutes(60));
        try {
            List<Object[]> rows = entityManager.createNativeQuery("""
                            select d.strategy_key, d.enabled,
                              coalesce(sum(case when si.runtime_state = 'RUNNING' and coalesce(si.enabled, true) then 1 else 0 end), 0) as running_ct,
                              coalesce((
                                select count(*) from strategy_signals ss
                                inner join strategy_instances si2 on ss.instance_id = si2.id
                                where si2.definition_id = d.id and not ss.deleted and ss.created_at >= :since
                              ), 0) as sig60,
                              count(distinct si.id) as instance_ct
                            from strategy_definitions d
                            left join strategy_instances si on si.definition_id = d.id and not si.deleted
                            where not d.deleted
                            group by d.id, d.strategy_key, d.enabled
                            order by running_ct desc, sig60 desc, d.strategy_key
                            limit 24
                            """)
                    .setParameter("since", since)
                    .getResultList();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object[] row : rows) {
                if (row == null || row.length < 5) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("strategyKey", row[0] != null ? row[0].toString() : "");
                m.put("catalogEnabled", row[1] instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(row[1])));
                m.put("runningInstances", toLong(row[2]));
                m.put("signalsLast60m", toLong(row[3]));
                m.put("totalInstances", toLong(row[4]));
                m.put("scanFailuresApprox", null);
                m.put("haltedApprox", 0L);
                m.put("rejectedSignalsApprox", null);
                m.put("queueBacklogApprox", null);
                m.put("scanLatencyMsP50", null);
                out.add(m);
            }
            return out;
        } catch (DataAccessException | IllegalArgumentException ex) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> traderExecutionRows() {
        try {
            List<Object[]> rows = entityManager.createNativeQuery("""
                            select cast(u.id as varchar), u.username, u.live_trading_approved,
                              (select count(*) from broker_accounts b where b.user_id = u.id and not b.deleted
                                 and lower(trim(b.status)) = 'connected') as brokers_conn,
                              (select max(o.updated_at) from oms_orders o where o.user_id = u.id and not o.deleted) as last_order,
                              (select count(*) from oms_orders o where o.user_id = u.id and not o.deleted
                                 and o.state in ('FAILED','REJECTED') and o.updated_at > (current_timestamp - interval '24 hours')) as fails_24h
                            from auth_users u
                            where not u.deleted
                            order by coalesce(u.updated_at, u.created_at) desc
                            limit 15
                            """)
                    .getResultList();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object[] row : rows) {
                if (row == null || row.length < 6) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", row[0] != null ? row[0].toString() : null);
                m.put("username", row[1] != null ? row[1].toString() : null);
                m.put("liveApproved", row[2] instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(row[2])));
                m.put("brokersConnected", toLong(row[3]));
                m.put("lastOrderAt", row[4] != null ? row[4].toString() : null);
                m.put("omsFailures24h", toLong(row[5]));
                if (toLong(row[3]) > 0) {
                    m.put("routingState", "BROKER_SESSION_PRESENT");
                } else {
                    m.put("routingState", "NO_CONNECTED_BROKER");
                }
                m.put("marginState", "NOT_INSTRUMENTED");
                out.add(m);
            }
            return out;
        } catch (DataAccessException | IllegalArgumentException ex) {
            return List.of();
        }
    }

    private static long toLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private long queryLong(String sql) {
        try {
            Object r = entityManager.createNativeQuery(sql).getSingleResult();
            if (r instanceof Number n) {
                return n.longValue();
            }
            return 0L;
        } catch (DataAccessException | IllegalArgumentException ex) {
            return -1L;
        }
    }

    private String queryInstant(String sql) {
        try {
            Object r = entityManager.createNativeQuery(sql).getSingleResult();
            return r != null ? r.toString() : null;
        } catch (DataAccessException | IllegalArgumentException ex) {
            return null;
        }
    }
}
