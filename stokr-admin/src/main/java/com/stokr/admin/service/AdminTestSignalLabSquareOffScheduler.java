package com.stokr.admin.service;

import com.stokr.admin.domain.AdminTestSignalRun;
import com.stokr.admin.repository.AdminTestSignalRunRepository;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.util.OmsSymbolNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTestSignalLabSquareOffScheduler {

    private final AdminTestSignalRunRepository runRepository;
    private final PortfolioPositionRepository portfolioPositionRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final AdminTestSignalLabSquareOffService squareOffService;

    @Scheduled(fixedDelayString = "${stokr.admin.test-lab.squareoff-ms:30000}")
    @Transactional
    public void squareOffDueRuns() {
        List<AdminTestSignalRun> due = runRepository.findSquareOffDue(Instant.now(), PageRequest.of(0, 30));
        for (AdminTestSignalRun run : due) {
            try {
                process(run);
            } catch (Exception ex) {
                run.setSquareOffStatus("FAILED");
                runRepository.save(run);
                log.warn("test.squareoff.failed runId={}", run.getId(), ex);
            }
        }
    }

    private void process(AdminTestSignalRun run) {
        if (run.getSquareOffStatus() != null && run.getSquareOffStatus().equalsIgnoreCase("COMPLETED")) {
            return;
        }
        OmsOrder entry = run.getOrderId() != null
                ? omsOrderRepository.findById(run.getOrderId()).filter(o -> !o.isDeleted()).orElse(null)
                : null;
        if (entry != null) {
            squareOffService.squareOffImmediately(run, entry, false);
            return;
        }
        PortfolioPosition position = findPosition(run);
        if (position == null || position.getQuantity() == null || position.getQuantity().signum() == 0) {
            run.setSquareOffStatus("NO_POSITION");
            run.setSquareOffCompletedAt(Instant.now());
            runRepository.save(run);
            return;
        }
        log.warn("test.squareoff.scheduler_no_entry_order runId={}", run.getId());
    }

    private PortfolioPosition findPosition(AdminTestSignalRun run) {
        String normalized = OmsSymbolNormalizer.normalize(run.getSymbol());
        return portfolioPositionRepository.findByUserIdAndDeletedFalse(run.getTraderUserId()).stream()
                .filter(p -> normalized.equals(OmsSymbolNormalizer.normalize(p.getSymbol())))
                .findFirst()
                .orElse(null);
    }
}
