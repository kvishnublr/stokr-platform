package com.stokr.bootstrap.portfolio;

import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.dto.PortfolioExposureDto;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.oms.repository.PortfolioDailySummaryRepository;
import com.stokr.oms.repository.PortfolioPnlSnapshotRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.service.PortfolioQueryService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * When Zerodha is connected, symbol exposure uses broker net quantity (source of truth)
 * instead of OMS {@code portfolio_positions}, which can drift after partial fills or
 * external broker exits.
 */
@Service
@Primary
public class BrokerAwarePortfolioQueryService extends PortfolioQueryService {

    private final BrokerPositionTruthService brokerPositionTruthService;
    private final PortfolioPositionRepository positionRepository;

    public BrokerAwarePortfolioQueryService(
            PortfolioPositionRepository positionRepository,
            PortfolioPnlSnapshotRepository pnlSnapshotRepository,
            PortfolioDailySummaryRepository dailySummaryRepository,
            OmsTradeRepository tradeRepository,
            BrokerPositionTruthService brokerPositionTruthService
    ) {
        super(positionRepository, pnlSnapshotRepository, dailySummaryRepository, tradeRepository);
        this.brokerPositionTruthService = brokerPositionTruthService;
        this.positionRepository = positionRepository;
    }

    @Override
    public PortfolioExposureDto exposure(UUID userId) {
        BrokerPositionTruthSnapshot snap = brokerPositionTruthService.syncUser(userId);
        if (!snap.brokerConnected() || snap.lastSyncAt() == null || snap.positions().isEmpty()) {
            return super.exposure(userId);
        }
        return exposureFromBrokerTruth(userId, snap);
    }

    private PortfolioExposureDto exposureFromBrokerTruth(UUID userId, BrokerPositionTruthSnapshot snap) {
        Map<String, PortfolioPosition> omsByNorm = indexOmsPositions(userId);
        List<PortfolioExposureDto.SymbolExposure> sym = new ArrayList<>();
        Set<String> brokerOpenNorm = new LinkedHashSet<>();

        for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow row : snap.positions()) {
            BigDecimal brokerQty = nullSafe(row.brokerQty());
            if (brokerQty.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            String norm = BrokerPositionTruthService.normalizeSymbol(row.symbol());
            brokerOpenNorm.add(norm);
            PortfolioPosition oms = omsByNorm.get(norm);
            BigDecimal omsQty = oms != null ? nullSafe(oms.getQuantity()) : BigDecimal.ZERO;
            sym.add(buildSymbolExposure(row.symbol(), brokerQty, omsQty, oms, row.brokerAvgPrice()));
        }

        for (Map.Entry<String, PortfolioPosition> entry : omsByNorm.entrySet()) {
            if (brokerOpenNorm.contains(entry.getKey())) {
                continue;
            }
            PortfolioPosition p = entry.getValue();
            BigDecimal omsQty = nullSafe(p.getQuantity());
            if (omsQty.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            sym.add(buildSymbolExposure(
                    p.getSymbol(),
                    BigDecimal.ZERO,
                    omsQty,
                    p,
                    p.getMtmPrice() != null ? p.getMtmPrice() : p.getAvgPrice()
            ));
        }

        sym.sort((a, b) -> b.exposureNotional().abs().compareTo(a.exposureNotional().abs()));
        List<PortfolioExposureDto.BrokerExposure> brokers = super.exposure(userId).byBrokerNotional();
        return new PortfolioExposureDto(sym, brokers);
    }

    private Map<String, PortfolioPosition> indexOmsPositions(UUID userId) {
        Map<String, PortfolioPosition> out = new LinkedHashMap<>();
        for (PortfolioPosition p : positionRepository.findByUserIdAndDeletedFalse(userId)) {
            out.put(BrokerPositionTruthService.normalizeSymbol(p.getSymbol()), p);
        }
        return out;
    }

    private static PortfolioExposureDto.SymbolExposure buildSymbolExposure(
            String symbol,
            BigDecimal displayQty,
            BigDecimal omsQty,
            PortfolioPosition oms,
            BigDecimal brokerAvgPrice
    ) {
        BigDecimal px = brokerAvgPrice;
        if (px == null && oms != null) {
            px = oms.getMtmPrice() != null ? oms.getMtmPrice() : oms.getAvgPrice();
        }
        BigDecimal notional = px != null
                ? displayQty.abs().multiply(px)
                : displayQty.abs();
        String parity = displayQty.compareTo(omsQty) == 0 ? "SYNCED" : "MISMATCH";
        String source = displayQty.compareTo(BigDecimal.ZERO) != 0 ? "BROKER" : "OMS";
        return new PortfolioExposureDto.SymbolExposure(
                displaySymbol(symbol),
                displayQty,
                notional.setScale(8, RoundingMode.HALF_UP),
                omsQty,
                source,
                parity
        );
    }

    private static String displaySymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        String t = symbol.trim();
        int idx = t.indexOf(':');
        return idx >= 0 ? t.substring(idx + 1) : t;
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
