package com.stokr.execution.safety;

import com.stokr.risk.service.KillSwitchService;
import com.stokr.strategy.service.StrategyEmergencyStopService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingKillSwitchService {

    public enum TriggerSource {
        ADMIN_API,
        CONFIG_FLAG,
        RISK_BREACH,
        BROKER_DISCONNECT,
        MARKET_CLOSE
    }

    private final KillSwitchService killSwitchService;
    private final TradingKillSwitchEventRepository eventRepository;
    private final StrategyEmergencyStopService strategyEmergencyStopService;

    @Value("${stokr.oms.kill-switch.config-enabled:false}")
    private boolean configEnabled;

    @Value("${stokr.oms.kill-switch.flatten-on-activate:false}")
    private boolean flattenOnActivateDefault;

    @PostConstruct
    void initFromConfig() {
        if (configEnabled && !killSwitchService.isEnabled()) {
            activate(TriggerSource.CONFIG_FLAG, "Config flag stokr.oms.kill-switch.config-enabled=true", false, "system");
        }
    }

    public boolean isActive() {
        return killSwitchService.isEnabled() || configEnabled;
    }

    public boolean forcesPaperMode() {
        return isActive();
    }

    @Transactional
    public Map<String, Object> activate(TriggerSource source, String reason, boolean flatten, String actor) {
        killSwitchService.setEnabled(true);
        persistEvent(true, source, reason, flatten, actor);
        log.error("oms.kill_switch.ACTIVATED source={} reason={} flatten={} actor={}",
                source, reason, flatten, actor);

        int stopped = 0;
        if (flatten || flattenOnActivateDefault) {
            stopped = strategyEmergencyStopService.stopAllRunning();
            log.warn("oms.kill_switch.flatten stoppedInstances={}", stopped);
        }

        return statusMap(stopped);
    }

    @Transactional
    public Map<String, Object> deactivate(TriggerSource source, String reason, String actor) {
        configEnabled = false;
        killSwitchService.setEnabled(false);
        persistEvent(false, source, reason, false, actor);
        log.info("oms.kill_switch.DEACTIVATED source={} reason={} actor={}", source, reason, actor);
        return statusMap(0);
    }

    @Transactional
    public Map<String, Object> activateOnRiskBreach(String reason) {
        boolean flatten = flattenOnActivateDefault;
        return activate(TriggerSource.RISK_BREACH, reason, flatten, "risk-engine");
    }

    public Map<String, Object> statusMap(int stoppedInstances) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", isActive());
        m.put("forcesPaperMode", forcesPaperMode());
        m.put("configFlagEnabled", configEnabled);
        m.put("redisKillSwitch", killSwitchService.isEnabled());
        m.put("flattenOnActivateDefault", flattenOnActivateDefault);
        if (stoppedInstances > 0) {
            m.put("stoppedStrategyInstances", stoppedInstances);
        }
        eventRepository.findFirstByOrderByCreatedAtDesc().ifPresent(ev -> {
            m.put("lastEventSource", ev.getTriggerSource());
            m.put("lastEventReason", ev.getReason());
            m.put("lastEventAt", ev.getCreatedAt());
        });
        return Map.copyOf(m);
    }

    private void persistEvent(boolean active, TriggerSource source, String reason, boolean flatten, String actor) {
        TradingKillSwitchEvent row = new TradingKillSwitchEvent();
        row.setActive(active);
        row.setTriggerSource(source.name());
        row.setReason(truncate(reason, 512));
        row.setFlattenRequested(flatten || (active && flattenOnActivateDefault));
        row.setActor(actor);
        row.setCreatedAt(Instant.now());
        eventRepository.save(row);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
