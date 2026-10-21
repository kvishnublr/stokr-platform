package com.stokr.arbitrage;

import com.stokr.broker.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class CondorAutoRollService {

    private final LivePositionRepository positionRepo;
    private final OptionArbAutoExecService autoExecService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );


    private final Map<Long, LocalDateTime> breachTracker = new HashMap<>();
    /** Positions already reported as not rollable, so the log says it once instead of every minute. */
    private final Set<Long> rollRefusedLogged = new HashSet<>();

    @Scheduled(fixedDelayString = "60000", initialDelay = 50000)
    public synchronized void monitorAndRollCondors() {
        Map<String, Object> settings = autoExecService.getSettings();

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (!com.stokr.marketdata.MarketCalendar.isTradingDayToday() || nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        List<LivePosition> condors = positionRepo.findAllOpen().stream()
            .filter(p -> "CONDOR_SPREAD".equals(p.getStrategyType()) || "IRON_CONDOR".equals(p.getStrategyType()))
            .filter(p -> p.getLegs() != null && p.getLegs().size() == 4)
            .toList();
            
        if (condors.isEmpty()) return;

        for (LivePosition pos : condors) {
            try {
                String uKey = pos.getUnderlying() != null ? pos.getUnderlying().toLowerCase() : "";
                if (!Boolean.TRUE.equals(settings.get(uKey + "AutoRollEnabled"))) continue;

                List<Map<String, Object>> legs = pos.getLegs();
                List<Integer> strikes = new ArrayList<>();
                for (Map<String, Object> l : legs) {
                    if (l.get("strike") instanceof Number n) strikes.add(n.intValue());
                }
                Collections.sort(strikes);
                
                if (strikes.size() != 4) continue;
                
                int k1 = strikes.get(0);
                int k2 = strikes.get(1);
                int k3 = strikes.get(2);
                int k4 = strikes.get(3);
                
                String spotKey = SPOT_KEYS.getOrDefault(pos.getUnderlying(), "NSE:" + pos.getUnderlying() + " 50");
                String futKey = FuturesKeyResolver.resolveFuturesKey(pos.getUnderlying(), spotPriceFetcher, spotKey);
                double[] sf = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (sf != null && sf.length > 0) ? sf[0] : 0;
                
                if (spot <= 0) continue;

                boolean putTested = spot <= k2;
                boolean callTested = spot >= k3;
                
                // Expiry Rollover Trigger: DTE < 1
                long dte = pos.getExpiryDate() != null ? java.time.Duration.between(LocalDate.now().atStartOfDay(), pos.getExpiryDate().atStartOfDay()).toDays() : 999;
                if (dte < 1) {
                    roll(pos, "EXPIRY_ROLL", pos.getUnderlying() + " DTE < 1. Auto-rolling forward.");
                    continue;
                }

                if (putTested || callTested) {
                    breachTracker.putIfAbsent(pos.getId(), LocalDateTime.now());
                    long minutesBreached = java.time.Duration.between(breachTracker.get(pos.getId()), LocalDateTime.now()).toMinutes();
                    
                    int requiredMinutes = ((Number) settings.getOrDefault(uKey + "AutoRollBreachMinutes", 5)).intValue();
                    if (minutesBreached >= requiredMinutes) {
                        log.info("Condor Auto-Adjuster Triggered: {} spot at {} breached for {} min. K2={}, K3={}", pos.getUnderlying(), spot, minutesBreached, k2, k3);
                        roll(pos, "TRIGGERED", pos.getUnderlying() + " Condor tested at " + spot + " for " + minutesBreached + "m. Squaring off and shifting to ATM.");
                        breachTracker.remove(pos.getId());
                    }
                } else {
                    breachTracker.remove(pos.getId());
                }
            } catch (Exception e) {
                log.error("Condor Auto-Adjust monitor failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Rolls via autoExecService and logs what really happened. rollPosition() refuses multi-leg
     * spreads, which used to be logged as a successful "squaring off and shifting" every few
     * minutes while the position stayed put; the refusal is now reported once per position.
     */
    private void roll(LivePosition pos, String status, String message) {
        Map<String, Object> result = autoExecService.rollPosition(pos.getId());
        Object error = result != null ? result.get("error") : "no result";
        if (error == null) {
            autoExecService.addLog("CONDOR_ADJUST", status, message);
        } else if (rollRefusedLogged.add(pos.getId())) {
            autoExecService.addLog("CONDOR_ADJUST", "NOT_ROLLED", pos.getUnderlying() + " #" + pos.getId()
                + " not rolled (" + error + "); SL / time exit still apply");
        }
    }
}
