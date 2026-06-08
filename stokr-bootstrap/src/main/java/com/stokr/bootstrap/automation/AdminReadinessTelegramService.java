package com.stokr.bootstrap.automation;

import com.stokr.bootstrap.recovery.OperationalFailureClassifier;
import com.stokr.bootstrap.recovery.OperationalRecoveryContext;
import com.stokr.user.config.TelegramBotProperties;
import com.stokr.user.telegram.TelegramDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Sends concise admin Telegram for live-trading readiness (OK or action required). */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminReadinessTelegramService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TelegramBotProperties telegramBotProperties;
    private final TelegramDeliveryService telegramDeliveryService;
    private final OperationalFailureClassifier classifier;

    private final ConcurrentHashMap<String, String> lastSentByPhaseDay = new ConcurrentHashMap<>();

    public void notifyReadiness(String phase, OperationalRecoveryContext ctx, List<String> blockers) {
        if (!telegramBotProperties.isOperatorReadinessAlertsEnabled()) {
            return;
        }
        if (phase == null || ctx == null) {
            return;
        }
        boolean healthy = classifier.isHealthy(ctx);
        boolean oauthRequired = ctx.requiresUserOAuth();
        boolean actionRequired = oauthRequired || !blockers.isEmpty() || !healthy;

        String day = LocalDate.now(IST).toString();
        String signature = day + "|" + phase + "|" + (actionRequired ? "ACTION" : "OK");
        if (signature.equals(lastSentByPhaseDay.get(phase))) {
            return;
        }

        String html;
        if (!actionRequired) {
            html = """
                    ✅ <b>Live trading ready</b> (%s)

                    All checks passed for market open. Feed, pipeline, and risk are OK.""".formatted(phase);
        } else {
            StringBuilder details = new StringBuilder();
            if (oauthRequired) {
                details.append("• Zerodha OAuth required\n");
            }
            for (String blocker : blockers) {
                if (blocker != null && !blocker.isBlank()) {
                    details.append("• ").append(escapeHtml(blocker)).append("\n");
                }
            }
            if (details.isEmpty()) {
                details.append("• Platform health check failed\n");
            }
            html = """
                    ⚠️ <b>Action required</b> (%s)

                    %s""".formatted(phase, details.toString().trim());
        }

        if (telegramDeliveryService.sendOperatorHtml("LIVE_READINESS_" + phase.toUpperCase(), html)) {
            lastSentByPhaseDay.put(phase, signature);
            log.info("telegram.readiness.sent phase={} actionRequired={}", phase, actionRequired);
        }
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
