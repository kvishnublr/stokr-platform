package com.stokr.bootstrap.trader;

import com.stokr.common.exception.BadRequestException;
import com.stokr.marketdata.service.MarketDataCoverageService;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.dto.StrategyCatalogItemDto;
import com.stokr.strategy.dto.StrategyRuntimeMetricsDto;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.service.StrategyCatalogQueryService;
import com.stokr.strategy.service.StrategyInstanceLifecycleService;
import com.stokr.strategy.service.StrategyRuntimeObservabilityService;
import com.stokr.strategy.service.StrategySubscriptionService;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import com.stokr.user.broker.ZerodhaConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class IntradayReadinessService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<SetupDef> INTRADAY_SETUPS = List.of(
            new SetupDef("NSE Spike Detection", "NSE_SPIKE_DETECTION"),
            new SetupDef("Gap Fills", "GAP_FILL"),
            new SetupDef("VWAP Bounces", "VWAP_BOUNCE"),
            new SetupDef("Sector Laggards", "SECTOR_LAGGARD"),
            new SetupDef("Early Breakouts", "EARLY_BREAKOUT")
    );
    private static final long ACTION_COOLDOWN_SECONDS = 8;
    private final Map<String, Instant> actionCooldown = new ConcurrentHashMap<>();

    private final StrategyCatalogQueryService strategyCatalogQueryService;
    private final StrategyRuntimeObservabilityService strategyRuntimeObservabilityService;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final StrategyInstanceLifecycleService strategyInstanceLifecycleService;
    private final StrategySubscriptionService strategySubscriptionService;
    private final ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;
    private final ZerodhaConnectionService zerodhaConnectionService;
    private final PlatformMarketFeedService platformMarketFeedService;
    private final MarketDataCoverageService marketDataCoverageService;

    @Transactional(readOnly = true)
    public IntradayReadinessResponse readiness(UUID userId) {
        List<ReadinessIssue> blockers = new ArrayList<>();
        List<ReadinessIssue> warnings = new ArrayList<>();
        List<ReadinessIssue> info = new ArrayList<>();

        var catalogPage = strategyCatalogQueryService.catalogForCatalog(PageRequest.of(0, 300), userId, null, null);
        List<StrategyCatalogItemDto> catalog = catalogPage.getContent();
        Map<String, StrategyCatalogItemDto> catalogByCode = new LinkedHashMap<>();
        for (StrategyCatalogItemDto c : catalog) {
            catalogByCode.put((c.code() == null ? "" : c.code().trim().toUpperCase(Locale.ROOT)), c);
        }

        List<StrategyRuntimeMetricsDto> runtime = strategyRuntimeObservabilityService.metricsForUser(userId);
        Map<String, StrategyRuntimeMetricsDto> runtimeByKey = new LinkedHashMap<>();
        for (StrategyRuntimeMetricsDto r : runtime) {
            runtimeByKey.put((r.strategyKey() == null ? "" : r.strategyKey().trim().toUpperCase(Locale.ROOT)), r);
        }

        List<StrategyInstance> instances = strategyInstanceRepository.findAllForUserWithDefinition(userId);
        Map<String, StrategyInstance> instanceByKey = new LinkedHashMap<>();
        for (StrategyInstance i : instances) {
            if (i.getDefinition() == null || i.getDefinition().getStrategyKey() == null) {
                continue;
            }
            instanceByKey.put(i.getDefinition().getStrategyKey().trim().toUpperCase(Locale.ROOT), i);
        }

        ZerodhaBrokerOperationsService.BrokerStatusDto broker = zerodhaBrokerOperationsService.status(userId);
        FeedHealth feed = buildFeedHealth(info, warnings, blockers);
        BrokerHealth brokerHealth = buildBrokerHealth(broker, blockers, warnings);
        RuntimeHealth runtimeHealth = buildRuntimeHealth(runtime, warnings, blockers);
        MarketSessionInfo session = detectSession();

        List<StrategyReadinessRow> strategies = new ArrayList<>();
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(7));
        for (SetupDef setup : INTRADAY_SETUPS) {
            String key = setup.strategyKey().toUpperCase(Locale.ROOT);
            StrategyCatalogItemDto cat = catalogByCode.get(key);
            StrategyInstance inst = instanceByKey.get(key);
            StrategyRuntimeMetricsDto met = runtimeByKey.get(key);

            boolean subscriptionOk = cat != null && cat.subscribed() && cat.subscriptionEnabled();
            String runtimeStateObserved = met != null && met.runtimeState() != null && !met.runtimeState().isBlank()
                    ? met.runtimeState()
                    : (inst != null && inst.getRuntimeState() != null ? inst.getRuntimeState() : "STOPPED");
            boolean runtimeOk = upper(runtimeStateObserved).contains("RUN");
            boolean healthOk = met != null && !"BAD".equalsIgnoreCase(upper(met.health()));
            boolean brokerOk = broker.connected() && broker.tokenValid();
            boolean feedOk = "HEALTHY".equalsIgnoreCase(feed.severity());

            String symbol = inst != null && inst.getSymbol() != null && !inst.getSymbol().isBlank() ? inst.getSymbol() : "NIFTY_FUT";
            var coverage = marketDataCoverageService.assessReadiness(symbol, "1m", from, now, "REPLAY", true);
            boolean historicalOk = coverage.ready();
            HistoricalCoverage summary = new HistoricalCoverage(
                    coverage.symbol(),
                    coverage.timeframe(),
                    coverage.state(),
                    coverage.detail(),
                    coverage.coverageStart() != null ? coverage.coverageStart().toString() : null,
                    coverage.coverageEnd() != null ? coverage.coverageEnd().toString() : null,
                    coverage.latestCandleAt() != null ? coverage.latestCandleAt().toString() : null,
                    coverage.freshnessAgeSeconds()
            );

            String status;
            if (!subscriptionOk || !brokerOk || !feedOk || !historicalOk) {
                status = "BLOCKED";
            } else if (!runtimeOk || !healthOk) {
                status = "DEGRADED";
            } else if (upper(met.runtimeState()).contains("RECOVER")) {
                status = "RECOVERING";
            } else {
                status = "READY";
            }

            if (!subscriptionOk) {
                blockers.add(new ReadinessIssue(
                        "CRITICAL", "SUBSCRIPTION_DISABLED",
                        setup.title() + " subscription disabled",
                        "Enable strategy subscription before market open.",
                        "refresh_subscriptions", setup.strategyKey()
                ));
            }
            if (!historicalOk) {
                warnings.add(new ReadinessIssue(
                        "WARNING", "HISTORICAL_" + upper(coverage.state()),
                        setup.title() + " historical coverage " + upper(coverage.state()),
                        coverage.detail(),
                        "open_backfill", setup.strategyKey()
                ));
            }
            if (!runtimeOk) {
                blockers.add(new ReadinessIssue(
                        "CRITICAL", "RUNTIME_STOPPED",
                        setup.title() + " runtime is not running",
                        "Restart strategy runtime.",
                        "restart_runtime", setup.strategyKey()
                ));
            }

            strategies.add(new StrategyReadinessRow(
                    setup.title(),
                    setup.strategyKey(),
                    upper(runtimeStateObserved),
                    upper(feed.status()),
                    upper(broker.health()),
                    subscriptionOk ? "READY" : "BLOCKED",
                    upper(summary.state()),
                    met != null && met.lastSignalAt() != null ? met.lastSignalAt().toString() : null,
                    status,
                    summary
            ));
        }

        HistoricalCoverageAggregate historical = aggregateHistorical(strategies);

        if ("PRE_MARKET".equals(session.sessionState())) {
            info.add(new ReadinessIssue("INFO", "PRE_MARKET", "Pre-market checklist recommended", "Run checklist before 09:15 IST.", "run_checklist", null));
        }
        if ("POST_MARKET".equals(session.sessionState()) || "WEEKEND".equals(session.sessionState()) || "HOLIDAY".equals(session.sessionState())) {
            info.add(new ReadinessIssue("INFO", "SESSION_LIMITATION", "Live execution outside market hours", "Backtest and readiness checks remain available.", null, null));
        }

        Map<String, Integer> severityCounts = Map.of(
                "CRITICAL", blockers.size(),
                "WARNING", warnings.size(),
                "INFO", info.size()
        );
        String overall = !blockers.isEmpty() ? "BLOCKED" : (!warnings.isEmpty() ? "WARNING" : "READY");
        return new IntradayReadinessResponse(
                overall,
                Instant.now().toString(),
                feed,
                brokerHealth,
                runtimeHealth,
                session,
                strategies,
                blockers,
                warnings,
                info,
                severityCounts,
                historical
        );
    }

    @Transactional
    public ReadinessActionResult runAction(UUID userId, String action, String strategyKey) {
        String normalized = upper(action);
        enforceCooldown(userId, normalized);
        log.info("intraday.readiness.action userId={} action={} strategyKey={}", userId, normalized, strategyKey);
        return switch (normalized) {
            case "RECONNECT_FEED" -> {
                Map<String, Object> r = platformMarketFeedService.refreshFromKite("ZERODHA");
                yield new ReadinessActionResult(true, "Feed reconnect requested", r, null);
            }
            case "RENEW_BROKER_SESSION" -> {
                Map<String, Object> refresh = platformMarketFeedService.refreshAllZerodhaTokens(Duration.ofHours(2));
                boolean platformOk = Boolean.TRUE.equals(refresh.get("platformRefreshed"));
                int traderRefreshed = refresh.get("traderRefreshed") instanceof Number n ? n.intValue() : 0;
                if (platformOk || traderRefreshed > 0) {
                    yield new ReadinessActionResult(
                            true,
                            "Broker session renewed automatically",
                            refresh,
                            null
                    );
                }
                var auth = zerodhaConnectionService.beginAuthorization(userId);
                Map<String, Object> payload = Map.of(
                        "authorizeUrl", auth.authorizeUrl(),
                        "expiresAt", auth.stateExpiresAt(),
                        "autoRefreshAttempt", refresh
                );
                yield new ReadinessActionResult(true, "Broker re-auth required", payload, "Open authorizeUrl and complete login.");
            }
            case "RELOAD_INSTRUMENT_CACHE" -> {
                Map<String, Object> r = platformMarketFeedService.notImplemented("reload_instrument_cache");
                yield new ReadinessActionResult(false, "Instrument cache reload is not wired in this build", r, "Use broker infrastructure connect/refresh flow.");
            }
            case "REFRESH_SUBSCRIPTIONS" -> {
                int enabled = refreshSubscriptions(userId, strategyKey);
                yield new ReadinessActionResult(true, "Subscription refresh completed", Map.of("enabledCount", enabled), null);
            }
            case "RESTART_RUNTIME" -> {
                int restarted = restartRuntimes(userId, strategyKey);
                yield new ReadinessActionResult(true, "Runtime restart completed", Map.of("restartedCount", restarted), null);
            }
            default -> throw new BadRequestException("Unknown readiness action: " + action);
        };
    }

    private void enforceCooldown(UUID userId, String action) {
        String key = userId + ":" + action;
        Instant now = Instant.now();
        Instant prev = actionCooldown.put(key, now);
        if (prev != null && Duration.between(prev, now).getSeconds() < ACTION_COOLDOWN_SECONDS) {
            throw new BadRequestException("Action is cooling down. Try again in a few seconds.");
        }
    }

    private int refreshSubscriptions(UUID userId, String strategyKey) {
        String filter = strategyKey == null ? "" : upper(strategyKey);
        var page = strategyCatalogQueryService.catalogForCatalog(PageRequest.of(0, 300), userId, null, null);
        int count = 0;
        for (StrategyCatalogItemDto row : page.getContent()) {
            String code = upper(row.code());
            if (filter.isBlank()) {
                if (INTRADAY_SETUPS.stream().noneMatch(s -> s.strategyKey().equalsIgnoreCase(code))) {
                    continue;
                }
            } else if (!filter.equals(code)) {
                continue;
            }
            if (!row.subscriptionEnabled()) {
                strategySubscriptionService.toggle(userId, row.id());
                count++;
            }
        }
        return count;
    }

    private int restartRuntimes(UUID userId, String strategyKey) {
        String filter = strategyKey == null ? "" : upper(strategyKey);
        int restarted = 0;
        List<StrategyInstance> instances = strategyInstanceRepository.findAllForUserWithDefinition(userId);
        for (StrategyInstance instance : instances) {
            if (instance.getDefinition() == null || instance.getDefinition().getStrategyKey() == null) {
                continue;
            }
            String key = upper(instance.getDefinition().getStrategyKey());
            if (filter.isBlank()) {
                boolean intraday = INTRADAY_SETUPS.stream().anyMatch(s -> s.strategyKey().equalsIgnoreCase(key));
                if (!intraday) continue;
            } else if (!filter.equals(key)) {
                continue;
            }
            strategyInstanceLifecycleService.pause(userId, instance.getId());
            strategyInstanceLifecycleService.start(userId, instance.getId());
            restarted++;
        }
        return restarted;
    }

    private FeedHealth buildFeedHealth(List<ReadinessIssue> info, List<ReadinessIssue> warnings, List<ReadinessIssue> blockers) {
        Map<String, Object> snapshot = platformMarketFeedService.vendorSnapshot("ZERODHA");
        String connection = upper(String.valueOf(snapshot.getOrDefault("connectionState", "UNKNOWN")));
        String ws = upper(String.valueOf(snapshot.getOrDefault("websocketState", "UNKNOWN")));
        long tickAgeSec = asLong(snapshot.get("latestTickAgeSeconds"));
        long heartbeatAgeSec = asLong(snapshot.get("heartbeatAgeSeconds"));
        long reconnectCount = asLong(snapshot.get("reconnectCount"));
        boolean operationalLivePath = asBool(snapshot.get("operationalLivePath"));

        String severity = "HEALTHY";
        String status = "LIVE";
        if (!operationalLivePath || tickAgeSec > 15 || heartbeatAgeSec > 15 || !ws.contains("OPEN")) {
            severity = "CRITICAL";
            status = "STALE";
            blockers.add(new ReadinessIssue(
                    "CRITICAL",
                    "FEED_DISCONNECTED",
                    "Market feed is not healthy",
                    String.valueOf(snapshot.getOrDefault("operationalLivePathDetail", "Feed disconnected or stale")),
                    "reconnect_feed",
                    null
            ));
        } else if (tickAgeSec > 5 || heartbeatAgeSec > 5) {
            severity = "WARNING";
            status = "DELAYED";
            warnings.add(new ReadinessIssue(
                    "WARNING",
                    "FEED_STALE_WARNING",
                    "Feed tick/heartbeat delay detected",
                    "Tick age " + tickAgeSec + "s, heartbeat age " + heartbeatAgeSec + "s",
                    "reconnect_feed",
                    null
            ));
        } else if (reconnectCount > 0) {
            info.add(new ReadinessIssue(
                    "INFO",
                    "FEED_RECOVERED",
                    "Feed reconnects observed",
                    "Reconnect count: " + reconnectCount,
                    null,
                    null
            ));
        }
        return new FeedHealth(
                status,
                severity,
                connection,
                ws,
                tickAgeSec,
                heartbeatAgeSec,
                reconnectCount,
                asLong(snapshot.get("feedLagMs")),
                asLong(snapshot.get("subscriptionCount")),
                String.valueOf(snapshot.getOrDefault("lastTickAt", null)),
                String.valueOf(snapshot.getOrDefault("lastHeartbeatAt", null)),
                String.valueOf(snapshot.getOrDefault("operationalLivePathDetail", ""))
        );
    }

    private BrokerHealth buildBrokerHealth(ZerodhaBrokerOperationsService.BrokerStatusDto broker, List<ReadinessIssue> blockers, List<ReadinessIssue> warnings) {
        if (!broker.connected() || !broker.tokenValid()) {
            blockers.add(new ReadinessIssue(
                    "CRITICAL",
                    "BROKER_TOKEN_INVALID",
                    "Broker session not ready",
                    "Reconnect broker session before live operations.",
                    "renew_broker_session",
                    null
            ));
        } else if (!"HEALTHY".equalsIgnoreCase(broker.health())) {
            warnings.add(new ReadinessIssue(
                    "WARNING",
                    "BROKER_HEALTH_DEGRADED",
                    "Broker health is " + broker.health(),
                    "Run test connection and refresh margin snapshot.",
                    "renew_broker_session",
                    null
            ));
        }
        return new BrokerHealth(
                broker.connected() ? "CONNECTED" : "DISCONNECTED",
                broker.tokenValid(),
                broker.health(),
                broker.lastSyncAt() != null ? broker.lastSyncAt().toString() : null,
                broker.marginSummary()
        );
    }

    private RuntimeHealth buildRuntimeHealth(List<StrategyRuntimeMetricsDto> runtime, List<ReadinessIssue> warnings, List<ReadinessIssue> blockers) {
        long running = runtime.stream().filter(r -> upper(r.runtimeState()).contains("RUN")).count();
        long stale = runtime.stream().filter(r -> "STALE".equalsIgnoreCase(upper(r.health()))).count();
        if (running == 0) {
            blockers.add(new ReadinessIssue(
                    "CRITICAL",
                    "RUNTIME_STOPPED",
                    "No intraday strategy runtime is running",
                    "Start or restart intraday strategy instances.",
                    "restart_runtime",
                    null
            ));
        }
        if (stale > 0) {
            warnings.add(new ReadinessIssue(
                    "WARNING",
                    "RUNTIME_STALE",
                    stale + " runtime(s) marked stale",
                    "Check feed freshness and strategy subscriptions.",
                    "restart_runtime",
                    null
            ));
        }
        return new RuntimeHealth(runtime.size(), running, stale);
    }

    private HistoricalCoverageAggregate aggregateHistorical(List<StrategyReadinessRow> strategies) {
        long ready = strategies.stream().filter(s -> "READY".equalsIgnoreCase(s.historical())).count();
        long stale = strategies.stream().filter(s -> "STALE".equalsIgnoreCase(s.historical())).count();
        long missing = strategies.stream().filter(s -> "NO_DATA".equalsIgnoreCase(s.historical()) || "NOT_BACKFILLED".equalsIgnoreCase(s.historical())).count();
        return new HistoricalCoverageAggregate((int) ready, (int) stale, (int) missing);
    }

    private MarketSessionInfo detectSession() {
        var now = Instant.now().atZone(IST);
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) {
            return new MarketSessionInfo("WEEKEND", now.toString(), "Market closed");
        }
        LocalTime t = now.toLocalTime();
        if (t.isBefore(LocalTime.of(9, 0))) {
            return new MarketSessionInfo("PRE_MARKET", now.toString(), "Pre-open readiness window");
        }
        if (!t.isAfter(LocalTime.of(15, 30))) {
            return new MarketSessionInfo("MARKET_OPEN", now.toString(), "Live market session");
        }
        return new MarketSessionInfo("POST_MARKET", now.toString(), "Post market analysis");
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (Exception ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static boolean asBool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    public record IntradayReadinessResponse(
            String overallStatus,
            String lastValidatedAt,
            FeedHealth feed,
            BrokerHealth broker,
            RuntimeHealth runtime,
            MarketSessionInfo session,
            List<StrategyReadinessRow> strategies,
            List<ReadinessIssue> blockers,
            List<ReadinessIssue> warnings,
            List<ReadinessIssue> info,
            Map<String, Integer> severityCounters,
            HistoricalCoverageAggregate historicalCoverage
    ) {
    }

    public record FeedHealth(
            String status,
            String severity,
            String connectionState,
            String websocketState,
            long tickLatencySeconds,
            long heartbeatAgeSeconds,
            long reconnectCount,
            long feedLagMs,
            long subscriptionCount,
            String lastTickAt,
            String lastHeartbeatAt,
            String detail
    ) {
    }

    public record BrokerHealth(
            String status,
            boolean tokenValid,
            String health,
            String lastSyncAt,
            String marginSummary
    ) {
    }

    public record RuntimeHealth(
            int totalStrategies,
            long runningStrategies,
            long staleStrategies
    ) {
    }

    public record MarketSessionInfo(
            String sessionState,
            String evaluatedAt,
            String detail
    ) {
    }

    public record StrategyReadinessRow(
            String strategy,
            String strategyKey,
            String runtime,
            String feed,
            String broker,
            String subscription,
            String historical,
            String lastSignalTime,
            String status,
            HistoricalCoverage historicalCoverage
    ) {
    }

    public record HistoricalCoverage(
            String symbol,
            String timeframe,
            String state,
            String detail,
            String coverageStart,
            String coverageEnd,
            String latestCandle,
            Long freshnessAgeSeconds
    ) {
    }

    public record HistoricalCoverageAggregate(
            int readyCount,
            int staleCount,
            int missingCount
    ) {
    }

    public record ReadinessIssue(
            String severity,
            String code,
            String title,
            String detail,
            String action,
            String strategyKey
    ) {
    }

    public record ReadinessActionResult(
            boolean ok,
            String message,
            Map<String, Object> payload,
            String nextStep
    ) {
    }

    public record IntradayReadinessActionRequest(String action, String strategyKey) {
    }

    private record SetupDef(String title, String strategyKey) {
    }
}
