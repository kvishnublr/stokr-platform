package com.stokr.admin.service;

import com.stokr.admin.domain.AdminTestSignalRun;
import com.stokr.admin.repository.AdminTestSignalRunRepository;
import com.stokr.execution.dto.CreateOrderRequest;
import com.stokr.execution.service.OrderPlacementService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.PortfolioPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTestSignalLabSquareOffScheduler {

    private final AdminTestSignalRunRepository runRepository;
    private final PortfolioPositionRepository portfolioPositionRepository;
    private final OrderPlacementService orderPlacementService;

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
        PortfolioPosition position = portfolioPositionRepository
                .findByUserIdAndSymbolAndDeletedFalse(run.getTraderUserId(), run.getSymbol())
                .orElse(null);
        if (position == null || position.getQuantity() == null || position.getQuantity().signum() == 0) {
            run.setSquareOffStatus("NO_POSITION");
            run.setSquareOffCompletedAt(Instant.now());
            runRepository.save(run);
            return;
        }
        String side = position.getQuantity().signum() > 0 ? "SELL" : "BUY";
        BigDecimal qty = position.getQuantity().abs();
        ExecutionMode mode = "LIVE".equalsIgnoreCase(run.getExecutionMode()) ? ExecutionMode.LIVE : ExecutionMode.PAPER;
        var request = new CreateOrderRequest(
                run.getSymbol(),
                side,
                "MARKET",
                qty,
                null,
                mode,
                mode == ExecutionMode.LIVE ? "ZERODHA" : "SIM",
                run.getStrategyKey(),
                "test-squareoff:" + run.getId() + ":" + UUID.randomUUID(),
                run.getSignalId(),
                Instant.now(),
                null,
                "1m",
                true,
                "EXIT_SAFE",
                true,
                run.getId()
        );
        var order = orderPlacementService.place(run.getTraderUserId(), request);
        run.setSquareOffOrderId(order.getId());
        run.setSquareOffStatus("COMPLETED");
        run.setSquareOffCompletedAt(Instant.now());
        runRepository.save(run);
        log.info("test.squareoff.completed runId={} orderId={}", run.getId(), order.getId());
    }
}
