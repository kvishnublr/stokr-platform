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

    @Scheduled(fixedDelayString = "60000", initialDelay = 50000)
    public synchronized void monitorAndRollCondors() {
        Map<String, Object> settings = autoExecService.getSettings();

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

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
                    log.info("Condor Auto-Rollover Triggered: {} expiring today. Rolling forward.", pos.getUnderlying());
                    autoExecService.addLog("CONDOR_ADJUST", "EXPIRY_ROLL", pos.getUnderlying() + " DTE < 1. Auto-rolling forward.");
                    autoExecService.rollPosition(pos.getId());
                    continue;
                }

                if (putTested || callTested) {
                    breachTracker.putIfAbsent(pos.getId(), LocalDateTime.now());
                    long minutesBreached = java.time.Duration.between(breachTracker.get(pos.getId()), LocalDateTime.now()).toMinutes();
                    
                    int requiredMinutes = ((Number) settings.getOrDefault(uKey + "AutoRollBreachMinutes", 5)).intValue();
                    if (minutesBreached >= requiredMinutes) {
                        log.info("Condor Auto-Adjuster Triggered: {} spot at {} breached for {} min. K2={}, K3={}", pos.getUnderlying(), spot, minutesBreached, k2, k3);
                        autoExecService.addLog("CONDOR_ADJUST", "TRIGGERED", pos.getUnderlying() + " Condor tested at " + spot + " for " + minutesBreached + "m. Squaring off and shifting to ATM.");
                        autoExecService.rollPosition(pos.getId()); // In autoExecService, this squares off and opens new ATM
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
}
