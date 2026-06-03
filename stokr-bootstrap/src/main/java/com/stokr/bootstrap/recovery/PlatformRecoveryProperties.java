package com.stokr.bootstrap.recovery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "stokr.platform.recovery")
public class PlatformRecoveryProperties {

    /** Master switch for ranked recovery orchestrator. */
    private boolean enabled = true;

    /** Scheduler interval between recovery cycles. */
    private long intervalMs = 45_000;

    /** Minimum wait after an action before recheck. */
    private long recheckWaitMs = 15_000;

    /** Maximum wait after an action before recheck. */
    private long recheckWaitMaxMs = 30_000;

    /** Redis/in-memory key suffix for this JVM instance. */
    private String serviceKey = "platform-api";

    /** Max attempts of the same signature+action before escalation. */
    private int maxAttemptsPerSignature = 3;

    /** Cooldown before repeating the same recovery action. */
    private long actionCooldownSec = 60;

    /** Broker websocket reconnect cooldown. */
    private long reconnectCooldownSec = 30;

    /** Signal pipeline activation cooldown. */
    private long pipelineCooldownSec = 300;

    /** Optional webhook for human escalation payloads (JSON POST). */
    private String webhookUrl = "";

    /** Unhealthy cycles before container restart flag / human alert. */
    private int escalateAfterAttempts = 5;

    /** Redis key written when container restart is requested (external autoheal may watch). */
    private String containerRestartFlagKey = "stokr:platform:recovery:restart-requested";

    /** Kill-switch trigger sources safe to auto-disarm (comma list in YAML). */
    private List<String> killSwitchAutoDisarmSources = new ArrayList<>(List.of(
            "BROKER_DISCONNECT",
            "RISK_BREACH"
    ));

    /** Scanner poll age threshold during market hours (seconds). */
    private long scannerStaleSeconds = 120;

    /** Vendor code for broker feed recovery. */
    private String brokerVendor = "ZERODHA";
}
