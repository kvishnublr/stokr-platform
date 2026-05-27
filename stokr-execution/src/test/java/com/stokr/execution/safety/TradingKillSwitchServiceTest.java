package com.stokr.execution.safety;

import com.stokr.risk.service.KillSwitchService;
import com.stokr.strategy.service.StrategyEmergencyStopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingKillSwitchServiceTest {

    @Mock
    private KillSwitchService killSwitchService;
    @Mock
    private TradingKillSwitchEventRepository eventRepository;
    @Mock
    private StrategyEmergencyStopService strategyEmergencyStopService;

    private TradingKillSwitchService service;

    @BeforeEach
    void setUp() {
        service = new TradingKillSwitchService(killSwitchService, eventRepository, strategyEmergencyStopService);
        ReflectionTestUtils.setField(service, "configEnabled", false);
        ReflectionTestUtils.setField(service, "flattenOnActivateDefault", false);
    }

    @Test
    void activateEngagesKillSwitchAndLogsEvent() {
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var status = service.activate(TradingKillSwitchService.TriggerSource.ADMIN_API, "test", false, "admin");

        verify(killSwitchService).setEnabled(true);
        verify(eventRepository).save(any(TradingKillSwitchEvent.class));
        assertTrue((Boolean) status.get("active"));
        assertTrue((Boolean) status.get("forcesPaperMode"));
    }

    @Test
    void deactivateClearsKillSwitch() {
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(TradingKillSwitchService.TriggerSource.ADMIN_API, "clear", "admin");

        verify(killSwitchService).setEnabled(false);
    }
}
