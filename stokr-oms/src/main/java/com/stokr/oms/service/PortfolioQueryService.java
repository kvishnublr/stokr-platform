package com.stokr.oms.service;

import com.stokr.oms.domain.PortfolioDailySummary;
import com.stokr.oms.domain.PortfolioPnlSnapshot;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.dto.PortfolioDashboardDto;
import com.stokr.oms.dto.PortfolioEquityPointDto;
import com.stokr.oms.dto.PortfolioExposureDto;
import com.stokr.oms.dto.PortfolioOverviewDto;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.oms.repository.PortfolioDailySummaryRepository;
import com.stokr.oms.repository.PortfolioPnlSnapshotRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioQueryService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    private final PortfolioPositionRepository positionRepository;
    private final PortfolioPnlSnapshotRepository pnlSnapshotRepository;
    private final PortfolioDailySummaryRepository dailySummaryRepository;
    private final OmsTradeRepository tradeRepository;

    public PortfolioOverviewDto overview(UUID userId) {
        List<PortfolioPosition> positions = positionRepository.findByUserIdAndDeletedFalse(userId);
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        int open = 0;
        for (PortfolioPosition p : positions) {
            realized = realized.add(nullSafe(p.getRealizedPnl()));
            unrealized = unrealized.add(nullSafe(p.getUnrealizedPnl()));
            if (p.getQuantity() != null && p.getQuantity().compareTo(BigDecimal.ZERO) != 0) {
                open++;
            }
        }
        realized = realized.setScale(8, RoundingMode.HALF_UP);
        unrealized = unrealized.setScale(8, RoundingMode.HALF_UP);
        BigDecimal mtm = realized.add(unrealized).setScale(8, RoundingMode.HALF_UP);

        List<PortfolioPnlSnapshot> snaps = pnlSnapshotRepository.findTop200ByUserIdAndDeletedFalseOrderByAsOfDesc(userId);
        Instant latestSnap = snaps.isEmpty() ? null : snaps.get(0).getAsOf();
        LocalDate today = LocalDate.now(DEFAULT_ZONE);
        PortfolioDailySummary todayRow = dailySummaryRepository.findByUserIdAndBusinessDateAndDeletedFalse(userId, today).orElse(null);

        return new PortfolioOverviewDto(
                realized,
                unrealized,
                mtm,
                mtm,
                open,
                latestSnap,
                todayRow != null ? todayRow.getBusinessDate() : null,
                todayRow != null ? todayRow.getRealizedPnl() : BigDecimal.ZERO,
                todayRow != null ? todayRow.getUnrealizedPnl() : BigDecimal.ZERO,
                todayRow != null ? todayRow.getMtmPnl() : BigDecimal.ZERO
        );
    }

    public List<PortfolioEquityPointDto> equityCurve(UUID userId, int maxPoints) {
        List<PortfolioPnlSnapshot> snaps = pnlSnapshotRepository.findTop200ByUserIdAndDeletedFalseOrderByAsOfDesc(userId);
        List<PortfolioEquityPointDto> list = new ArrayList<>();
        int limit = Math.min(maxPoints, snaps.size());
        for (int i = 0; i < limit; i++) {
            PortfolioPnlSnapshot s = snaps.get(i);
            list.add(new PortfolioEquityPointDto(
                    s.getAsOf(),
                    s.getPnl(),
                    nullSafe(s.getRealizedPnl()),
                    nullSafe(s.getUnrealizedPnl())
            ));
        }
        list.sort(Comparator.comparing(PortfolioEquityPointDto::asOf));
        return list;
    }

    public PortfolioExposureDto exposure(UUID userId) {
        List<PortfolioPosition> positions = positionRepository.findByUserIdAndDeletedFalse(userId);
        List<PortfolioExposureDto.SymbolExposure> sym = new ArrayList<>();
        for (PortfolioPosition p : positions) {
            BigDecimal qty = nullSafe(p.getQuantity());
            BigDecimal px = p.getMtmPrice() != null ? p.getMtmPrice() : p.getAvgPrice();
            BigDecimal notional = px != null ? qty.abs().multiply(px) : qty.abs();
            sym.add(new PortfolioExposureDto.SymbolExposure(p.getSymbol(), qty, notional.setScale(8, RoundingMode.HALF_UP)));
        }
        List<PortfolioExposureDto.BrokerExposure> brokers = new ArrayList<>();
        for (Object[] row : tradeRepository.sumNotionalByBrokerVendor(userId)) {
            String bv = (String) row[0];
            BigDecimal n = row[1] instanceof BigDecimal b ? b : BigDecimal.ZERO;
            brokers.add(new PortfolioExposureDto.BrokerExposure(bv, n.setScale(8, RoundingMode.HALF_UP)));
        }
        return new PortfolioExposureDto(sym, brokers);
    }

    public PortfolioDashboardDto dashboard(UUID userId, int equityPoints) {
        PortfolioOverviewDto ov = overview(userId);
        List<PortfolioEquityPointDto> curve = equityCurve(userId, equityPoints);
        PortfolioExposureDto exp = exposure(userId);

        BigDecimal maxDd = computeMaxDrawdownPct(curve.stream().map(PortfolioEquityPointDto::cumulativePnl).toList());

        PortfolioDashboardDto.SymbolPnLBrief best = null;
        PortfolioDashboardDto.SymbolPnLBrief worst = null;
        for (PortfolioPosition p : positionRepository.findByUserIdAndDeletedFalse(userId)) {
            BigDecimal u = nullSafe(p.getUnrealizedPnl());
            if (best == null || u.compareTo(best.unrealizedPnl()) > 0) {
                best = new PortfolioDashboardDto.SymbolPnLBrief(p.getSymbol(), u);
            }
            if (worst == null || u.compareTo(worst.unrealizedPnl()) < 0) {
                worst = new PortfolioDashboardDto.SymbolPnLBrief(p.getSymbol(), u);
            }
        }

        return new PortfolioDashboardDto(ov, curve, exp, maxDd, best, worst);
    }

    private static BigDecimal computeMaxDrawdownPct(List<BigDecimal> cumulativeSeries) {
        if (cumulativeSeries.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal peak = cumulativeSeries.getFirst();
        BigDecimal maxDd = BigDecimal.ZERO;
        for (BigDecimal v : cumulativeSeries) {
            if (v.compareTo(peak) > 0) {
                peak = v;
            }
            if (peak.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal dd = peak.subtract(v).divide(peak.abs().max(BigDecimal.ONE), 8, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
        }
        return maxDd.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
