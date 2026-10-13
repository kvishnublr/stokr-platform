package com.stokr.arbitrage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sentinel")
@RequiredArgsConstructor
@Slf4j
public class TradeSupervisorController {

    private final TradeSupervisorService supervisorService;
    private final OptionArbOpportunityRepository oppRepo;

    @GetMapping("/status")
    public ResponseEntity<TradeSupervisorService.SentinelStatus> getStatus() {
        return ResponseEntity.ok(supervisorService.getSentinelStatus());
    }

    @PostMapping("/reconcile")
    public ResponseEntity<TradeSupervisorService.ReconciliationReport> reconcile(
            @RequestParam(defaultValue = "true") boolean autoHeal) {
        log.info("Sentinel: Manual reconciliation triggered with autoHeal={}", autoHeal);
        return ResponseEntity.ok(supervisorService.reconcileBrokerPositions(autoHeal));
    }

    @PostMapping("/certify-eod")
    public ResponseEntity<Map<String, Object>> certifyEod() {
        log.info("Sentinel: Manual EOD flat-book certification triggered");
        return ResponseEntity.ok(supervisorService.certifyEodFlatBook());
    }

    @PostMapping("/pre-check")
    public ResponseEntity<TradeSupervisorService.GatekeeperResult> preCheck(
            @RequestBody Map<String, Object> body) {
        Long oppId = body.get("opportunityId") instanceof Number n ? n.longValue() : null;
        int lots = body.get("lots") instanceof Number n ? n.intValue() : 1;
        String broker = (String) body.getOrDefault("broker", "PAPER");
        boolean isPaper = "PAPER".equalsIgnoreCase(broker);

        OptionArbOpportunity opp = null;
        if (oppId != null) {
            opp = oppRepo.findById(oppId).orElse(null);
        }
        if (opp == null) {
            // Build temporary opportunity from request payload
            opp = new OptionArbOpportunity();
            opp.setUnderlying((String) body.get("underlying"));
            opp.setStrike(body.get("strike") instanceof Number n ? n.intValue() : null);
            opp.setStrategyType((String) body.get("strategyType"));
            opp.setAction((String) body.get("action"));
            if (body.get("expiryDate") instanceof String s) {
                try { opp.setExpiryDate(java.time.LocalDate.parse(s)); } catch (Exception ignored) {}
            }
        }

        return ResponseEntity.ok(supervisorService.validatePreOrder(opp, lots, broker, isPaper));
    }
}
