package com.stokr.arbitrage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Periodically snapshots the Candidates (not arbitrage) discovery scans for Vertical/
 * Butterfly/Condor so there's an answerable "how many candidates showed up today/this
 * week" -- previously these were computed fresh on every page load and never persisted.
 * Count + top candidate per underlying per strategy, every 15 minutes during market hours --
 * not every candidate, to avoid tens of thousands of rows/day across 3 strategies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateSnapshotService {

    private final VerticalSpreadService verticalSpreadService;
    private final ButterflySpreadService butterflySpreadService;
    private final CondorSpreadService condorSpreadService;
    private final CandidateSnapshotRepository snapshotRepo;

    @Scheduled(fixedDelayString = "900000", initialDelay = 90000)
    public void snapshotAll() {
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 30))) return;

        try {
            snapshotOne("VERTICAL_SPREAD", verticalSpreadService.scanCandidates("ALL", 0.35));
        } catch (Exception e) {
            log.error("Candidate snapshot failed for Vertical: {}", e.getMessage(), e);
        }
        try {
            snapshotOne("BUTTERFLY_SPREAD", butterflySpreadService.scanCandidates("ALL", 0.35));
        } catch (Exception e) {
            log.error("Candidate snapshot failed for Butterfly: {}", e.getMessage(), e);
        }
        try {
            snapshotOne("CONDOR_SPREAD", condorSpreadService.scanCandidates("ALL", 0.35));
        } catch (Exception e) {
            log.error("Candidate snapshot failed for Condor: {}", e.getMessage(), e);
        }
    }

    private void snapshotOne(String strategyType, List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();

        Map<String, List<Map<String, Object>>> byUnderlying = candidates.stream()
            .filter(c -> c.get("underlying") != null)
            .collect(Collectors.groupingBy(c -> (String) c.get("underlying")));

        for (var entry : byUnderlying.entrySet()) {
            String underlying = entry.getKey();
            List<Map<String, Object>> list = entry.getValue();
            if (list.isEmpty()) continue;

            // Candidates already come sorted by POP descending from the scan services.
            Map<String, Object> top = list.get(0);
            double avgPop = list.stream()
                .mapToDouble(c -> c.get("pop") instanceof Number n ? n.doubleValue() : 0)
                .average().orElse(0);

            CandidateSnapshot snap = CandidateSnapshot.builder()
                .strategyType(strategyType)
                .underlying(underlying)
                .candidateCount(list.size())
                .avgPop(Math.round(avgPop * 10.0) / 10.0)
                .topOptionType((String) top.get("optionType"))
                .topStrikes((String) top.get("strikes"))
                .topPop(numOrNull(top.get("pop")))
                .topCostPerLot(numOrNull(top.get("costPerLot")))
                .topMaxLoss(numOrNull(top.get("maxLoss")))
                .topMaxProfit(numOrNull(top.get("maxProfit")))
                .topMarginEstimate(numOrNull(top.get("marginEstimate")))
                .snapshotTime(now)
                .build();
            snapshotRepo.save(snap);
        }

        log.debug("Snapshotted {} candidates across {} underlyings for {}", candidates.size(), byUnderlying.size(), strategyType);
    }

    private Double numOrNull(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
