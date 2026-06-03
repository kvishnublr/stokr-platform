package com.stokr.bootstrap.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalFailureClassifierTest {

    private PlatformRecoveryProperties properties;
    private OperationalFailureClassifier classifier;

    @BeforeEach
    void setUp() {
        properties = new PlatformRecoveryProperties();
        classifier = new OperationalFailureClassifier(properties);
    }

    @Test
    void classifiesDbUnreachableFirstWhenBothStorageDown() {
        OperationalRecoveryContext ctx = baseContext()
                .withDatabase(Map.of("status", "DISCONNECTED", "error", "timeout"))
                .withRedis(Map.of("status", "DISCONNECTED", "error", "timeout"))
                .build();
        assertEquals(OperationalFailureSignature.DB_UNREACHABLE, classifier.classify(ctx));
        assertTrue(classifier.isBroadOutage(ctx));
    }

    @Test
    void classifiesBadAuthWhenOAuthRequired() {
        OperationalRecoveryContext ctx = baseContext()
                .withRequiresOAuth(true)
                .build();
        assertEquals(OperationalFailureSignature.BAD_AUTH, classifier.classify(ctx));
        assertTrue(classifier.isHealthy(ctx));
    }

    @Test
    void classifiesBrokerFeedDown() {
        OperationalRecoveryContext ctx = baseContext()
                .withBrokerFeed(Map.of("operationalLivePath", false, "reconnecting", false))
                .withFeedHealth(Map.of("level", "WARN"))
                .build();
        assertEquals(OperationalFailureSignature.BROKER_FEED_DOWN, classifier.classify(ctx));
    }

    @Test
    void classifiesKillSwitchWhenAutoDisarmSource() {
        OperationalRecoveryContext ctx = baseContext()
                .withKillSwitch(true, Map.of("lastEventSource", "BROKER_DISCONNECT"))
                .build();
        assertEquals(OperationalFailureSignature.KILL_SWITCH_ACTIVE, classifier.classify(ctx));
    }

    @Test
    void ignoresKillSwitchForMarketClose() {
        OperationalRecoveryContext ctx = baseContext()
                .withKillSwitch(true, Map.of("lastEventSource", "MARKET_CLOSE"))
                .withActuatorHealthy(false)
                .build();
        assertEquals(OperationalFailureSignature.UNKNOWN, classifier.classify(ctx));
    }

    @Test
    void classifiesScannerStalledWhenPipelineInactive() {
        OperationalRecoveryContext ctx = baseContext()
                .withActiveBindings(2)
                .withExecutionPipeline(Map.of("executionPipelineActive", false))
                .withScanner(Map.of("lastPollCompletedAt", Instant.now().toString()))
                .build();
        assertEquals(OperationalFailureSignature.SCANNER_STALLED, classifier.classify(ctx));
    }

    @Test
    void healthyWhenAllChecksPass() {
        OperationalRecoveryContext ctx = baseContext().build();
        assertTrue(classifier.isHealthy(ctx));
    }

    private static ContextBuilder baseContext() {
        return new ContextBuilder()
                .withActuatorHealthy(true)
                .withRedis(Map.of("status", "CONNECTED", "pingMs", 2))
                .withDatabase(Map.of("status", "CONNECTED", "pingMs", 3))
                .withBrokerFeed(Map.of("operationalLivePath", true, "reconnecting", false))
                .withFeedHealth(Map.of("level", "OK"))
                .withKillSwitch(false, Map.of())
                .withExecutionPipeline(Map.of("executionPipelineActive", true))
                .withScanner(Map.of(
                        "lastPollCompletedAt", Instant.now().toString(),
                        "lastPollWasSkipped", false))
                .withActiveBindings(1);
    }

    private static final class ContextBuilder {
        private boolean actuatorHealthy = true;
        private Map<String, Object> brokerFeed = Map.of();
        private Map<String, Object> feedHealth = Map.of();
        private Map<String, Object> redis = Map.of();
        private Map<String, Object> database = Map.of();
        private boolean killSwitch;
        private Map<String, Object> killSwitchDetail = Map.of();
        private Map<String, Object> executionPipeline = Map.of();
        private Map<String, Object> scanner = Map.of();
        private int activeBindings;
        private boolean requiresOAuth;

        ContextBuilder withActuatorHealthy(boolean v) {
            this.actuatorHealthy = v;
            return this;
        }

        ContextBuilder withBrokerFeed(Map<String, Object> v) {
            this.brokerFeed = v;
            return this;
        }

        ContextBuilder withFeedHealth(Map<String, Object> v) {
            this.feedHealth = v;
            return this;
        }

        ContextBuilder withRedis(Map<String, Object> v) {
            this.redis = v;
            return this;
        }

        ContextBuilder withDatabase(Map<String, Object> v) {
            this.database = v;
            return this;
        }

        ContextBuilder withKillSwitch(boolean active, Map<String, Object> detail) {
            this.killSwitch = active;
            this.killSwitchDetail = detail;
            return this;
        }

        ContextBuilder withExecutionPipeline(Map<String, Object> v) {
            this.executionPipeline = v;
            return this;
        }

        ContextBuilder withScanner(Map<String, Object> v) {
            this.scanner = v;
            return this;
        }

        ContextBuilder withActiveBindings(int n) {
            this.activeBindings = n;
            return this;
        }

        ContextBuilder withRequiresOAuth(boolean v) {
            this.requiresOAuth = v;
            return this;
        }

        OperationalRecoveryContext build() {
            return new OperationalRecoveryContext(
                    Instant.now(),
                    actuatorHealthy,
                    Map.of("status", actuatorHealthy ? "UP" : "DOWN"),
                    brokerFeed,
                    feedHealth,
                    redis,
                    database,
                    killSwitch,
                    killSwitchDetail,
                    executionPipeline,
                    scanner,
                    activeBindings,
                    requiresOAuth,
                    false,
                    List.of(),
                    List.of()
            );
        }
    }
}
