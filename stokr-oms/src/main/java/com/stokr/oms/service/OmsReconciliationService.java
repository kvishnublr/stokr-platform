package com.stokr.oms.service;

import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.util.OmsSymbolNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        Map<String, BigDecimal> execNetByNorm = new LinkedHashMap<>();
        for (OmsExecution e : executions) {
            OmsOrder order = e.getOrder();
            if (order == null || order.getSymbol() == null) {
                continue;
            }
            if (!countsTowardPortfolio(order)) {
                continue;
            }
            BigDecimal qty = e.getFilledQty() == null ? BigDecimal.ZERO : e.getFilledQty();
            BigDecimal signed = "BUY".equalsIgnoreCase(order.getSide()) ? qty : qty.negate();
            String norm = OmsSymbolNormalizer.normalize(order.getSymbol());
            execNetByNorm.merge(norm, signed, BigDecimal::add);
        }

        Map<String, BigDecimal> posQtyByNorm = new LinkedHashMap<>();
        for (PortfolioPosition p : positions) {
            if (p.getSymbol() == null) {
                continue;
            }
            BigDecimal qty = p.getQuantity() == null ? BigDecimal.ZERO : p.getQuantity();
            if (qty.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            posQtyByNorm.merge(OmsSymbolNormalizer.normalize(p.getSymbol()), qty, BigDecimal::add);
        }

        List<String> warnings = new ArrayList<>();
        Set<String> symbols = new LinkedHashSet<>();
        symbols.addAll(execNetByNorm.keySet());
        symbols.addAll(posQtyByNorm.keySet());
        for (String norm : symbols) {
            BigDecimal execNet = execNetByNorm.getOrDefault(norm, BigDecimal.ZERO);
            BigDecimal posQty = posQtyByNorm.getOrDefault(norm, BigDecimal.ZERO);
            if (execNet.compareTo(posQty) != 0) {
                warnings.add("SYMBOL_QTY_MISMATCH:" + OmsSymbolNormalizer.display(norm));
            }
        }

        long orphanExecutions = executions.stream().filter(e -> e.getOrder() == null).count();
        if (orphanExecutions > 0) {
            warnings.add("ORPHAN_EXECUTIONS");
        }

        BigDecimal netFromExecutions = execNetByNorm.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netFromPositions = posQtyByNorm.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

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

    private static boolean countsTowardPortfolio(OmsOrder order) {
        if (order.getBacktestRunId() != null) {
            return false;
        }
        return !Boolean.TRUE.equals(order.isTestTrade());
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
