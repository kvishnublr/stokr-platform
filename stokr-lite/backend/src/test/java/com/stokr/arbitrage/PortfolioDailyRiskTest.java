package com.stokr.arbitrage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortfolioDailyRiskTest {

    private LivePositionRepository positionRepo;
    private AutoExecSettingRepository settingRepo;

    @BeforeEach
    void setUp() {
        positionRepo = mock(LivePositionRepository.class);
        settingRepo = mock(AutoExecSettingRepository.class);

        AutoExecSetting loss = new AutoExecSetting();
        loss.setSettingKey("globalMaxDailyLoss");
        loss.setSettingValue("5000.0");
        when(settingRepo.findBySettingKey("globalMaxDailyLoss")).thenReturn(Optional.of(loss));

        AutoExecSetting profit = new AutoExecSetting();
        profit.setSettingKey("globalMaxDailyProfit");
        profit.setSettingValue("8000.0");
        when(settingRepo.findBySettingKey("globalMaxDailyProfit")).thenReturn(Optional.of(profit));
    }

    @Test
    void normalPnlAllowsTrading() {
        LivePosition closed = new LivePosition();
        closed.setStatus("CLOSED");
        closed.setBroker("PAPER");
        closed.setCurrentPnl(BigDecimal.valueOf(1200.0));
        closed.setExitedAt(java.time.LocalDate.now(ZoneId.of("Asia/Kolkata")).atTime(10, 30));

        when(positionRepo.findAllClosed()).thenReturn(List.of(closed));
        when(positionRepo.findAllOpen()).thenReturn(List.of());

        PortfolioRiskCalculator.PortfolioRiskStatus status = 
                PortfolioRiskCalculator.evaluateDailyRisk(positionRepo, settingRepo, null, "PAPER");

        assertFalse(status.halted);
        assertNull(status.haltReason);
        assertEquals(1200.0, status.todayRealizedPnl);
        assertEquals(1200.0, status.todayNetPnl);
    }

    @Test
    void dailyLossLimitBreachHaltsTrading() {
        LivePosition closed1 = new LivePosition();
        closed1.setStatus("CLOSED");
        closed1.setBroker("PAPER");
        closed1.setCurrentPnl(BigDecimal.valueOf(-3500.0));
        closed1.setExitedAt(java.time.LocalDate.now(ZoneId.of("Asia/Kolkata")).atTime(9, 45));

        LivePosition closed2 = new LivePosition();
        closed2.setStatus("CLOSED");
        closed2.setBroker("PAPER");
        closed2.setCurrentPnl(BigDecimal.valueOf(-2000.0));
        closed2.setExitedAt(java.time.LocalDate.now(ZoneId.of("Asia/Kolkata")).atTime(10, 15));

        when(positionRepo.findAllClosed()).thenReturn(List.of(closed1, closed2));
        when(positionRepo.findAllOpen()).thenReturn(List.of());

        PortfolioRiskCalculator.PortfolioRiskStatus status = 
                PortfolioRiskCalculator.evaluateDailyRisk(positionRepo, settingRepo, null, "PAPER");

        assertTrue(status.halted);
        assertNotNull(status.haltReason);
        assertTrue(status.haltReason.contains("Daily loss limit"));
        assertEquals(-5500.0, status.todayNetPnl);
    }

    @Test
    void dailyProfitTargetBreachHaltsTradingToLockInGains() {
        LivePosition closed = new LivePosition();
        closed.setStatus("CLOSED");
        closed.setBroker("PAPER");
        closed.setCurrentPnl(BigDecimal.valueOf(6000.0));
        closed.setExitedAt(java.time.LocalDate.now(ZoneId.of("Asia/Kolkata")).atTime(11, 0));

        LivePosition open = new LivePosition();
        open.setStatus("OPEN");
        open.setBroker("PAPER");
        open.setCurrentPnl(BigDecimal.valueOf(2500.0));

        when(positionRepo.findAllClosed()).thenReturn(List.of(closed));
        when(positionRepo.findAllOpen()).thenReturn(List.of(open));

        PortfolioRiskCalculator.PortfolioRiskStatus status = 
                PortfolioRiskCalculator.evaluateDailyRisk(positionRepo, settingRepo, null, "PAPER");

        assertTrue(status.halted);
        assertNotNull(status.haltReason);
        assertTrue(status.haltReason.contains("Daily profit target"));
        assertEquals(8500.0, status.todayNetPnl);
    }

    @Test
    void disabledProfitTargetDoesNotHaltOnLargeGains() {
        AutoExecSetting zeroProfit = new AutoExecSetting();
        zeroProfit.setSettingKey("globalMaxDailyProfit");
        zeroProfit.setSettingValue("0");
        when(settingRepo.findBySettingKey("globalMaxDailyProfit")).thenReturn(Optional.of(zeroProfit));

        LivePosition closed = new LivePosition();
        closed.setStatus("CLOSED");
        closed.setBroker("PAPER");
        closed.setCurrentPnl(BigDecimal.valueOf(25000.0));
        closed.setExitedAt(java.time.LocalDate.now(ZoneId.of("Asia/Kolkata")).atTime(12, 0));

        when(positionRepo.findAllClosed()).thenReturn(List.of(closed));
        when(positionRepo.findAllOpen()).thenReturn(List.of());

        PortfolioRiskCalculator.PortfolioRiskStatus status = 
                PortfolioRiskCalculator.evaluateDailyRisk(positionRepo, settingRepo, null, "PAPER");

        assertFalse(status.halted);
        assertEquals(25000.0, status.todayNetPnl);
    }
}
