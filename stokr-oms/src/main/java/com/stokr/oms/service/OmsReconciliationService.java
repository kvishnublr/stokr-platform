package com.stokr.oms.service;

import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OmsReconciliationService {

    private final OmsOrderRepository orderRepository;
    private final OmsExecutionRepository executionRepository;
    private final PortfolioPositionRepository positionRepository;

    @Transactional(readOnly = true)
    public ReconciliationReport reconcileUser(UUID userId) {
        List<OmsExecution> executions = executionRepository.findAllForUserOrdered(userId);
        List<PortfolioPosition> positions = positionRepository.findByUserIdAndDeletedFalse(userId);

        BigDecimal netFromExecutions = BigDecimal.ZERO;
        for (OmsExecution e : executions) {
            if ("BUY".equalsIgnoreCase(e.getOrder().getSide())) {
                netFromExecutions = netFromExecutions.add(e.getFilledQty());
            } else {
                netFromExecutions = netFromExecutions.subtract(e.getFilledQty());
            }
        }

        BigDecimal netFromPositions = BigDecimal.ZERO;
        for (PortfolioPosition p : positions) {
            netFromPositions = netFromPositions.add(p.getQuantity());
        }

        List<String> warnings = new ArrayList<>();
        if (netFromExecutions.compareTo(netFromPositions) != 0) {
            warnings.add("NET_QTY_MISMATCH");
        }

        long orphanExecutions = executions.stream().filter(e -> e.getOrder() == null).count();
        if (orphanExecutions > 0) {
            warnings.add("ORPHAN_EXECUTIONS");
        }

        return new ReconciliationReport(
                userId,
                orderRepository.countByUserIdAndDeletedFalse(userId),
                executions.size(),
                positions.size(),
                netFromExecutions,
                netFromPositions,
                orphanExecutions,
                warnings
        );
    }

    public record ReconciliationReport(
            UUID userId,
            long orderCount,
            long executionCount,
            long positionCount,
            BigDecimal netQtyFromExecutions,
            BigDecimal netQtyFromPositions,
            long orphanExecutionCount,
            List<String> warnings
    ) {
    }
}
