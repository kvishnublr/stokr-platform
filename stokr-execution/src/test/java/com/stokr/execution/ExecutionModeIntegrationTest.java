package com.stokr.execution;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.execution.simulation.ExecutionSimulator;
import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify:
 * 1. Signal generation works
 * 2. PAPER trades execute correctly
 * 3. SIMULATION trades execute correctly
 * 4. LIVE trades are properly gated
 * 5. Each mode doesn't cross-contaminate
 */
@SpringBootTest(
        classes = ExecutionModeIntegrationTest.TestBootConfig.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:stokr_execution_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=never"
        }
)
@Transactional
@DisplayName("Execution Mode Integration Tests")
@Disabled("Requires full stokr-bootstrap runtime (Postgres/Flyway/security/bootstrap beans); not runnable as isolated module test")
public class ExecutionModeIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.stokr")
    @EntityScan(basePackages = "com.stokr")
    @EnableJpaRepositories(basePackages = "com.stokr")
    static class TestBootConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        FieldCipher fieldCipher() {
            return new FieldCipher() {
                @Override
                public String encrypt(String plaintext) {
                    return plaintext;
                }

                @Override
                public String decrypt(String ciphertext) {
                    return ciphertext;
                }
            };
        }
    }

    @Autowired
    private OmsOrderRepository orderRepository;

    @Autowired
    private OrderLifecycleService orderLifecycleService;

    @Autowired
    private ExecutionSimulator executionSimulator;

    @Autowired
    private StrategySignalRepository signalRepository;

    private UUID testUserId = UUID.randomUUID();

    @Test
    @DisplayName("PAPER mode: Order should execute with simulated fills")
    public void testPaperModeExecution() {
        // Create signal
        StrategySignalEntity signal = createTestSignal("NIFTY_50", "BUY");
        signal = signalRepository.save(signal);

        // Create PAPER order
        OmsOrder order = createTestOrder(testUserId, "NIFTY_50", ExecutionMode.PAPER, signal.getId());
        order = orderRepository.save(order);

        // Verify order state
        assertEquals(OrderState.PENDING_SUBMISSION, order.getState());
        assertEquals(ExecutionMode.PAPER, order.getExecutionMode());

        // Execute
        ExecutionDispatchMessage msg = dispatchMessageFor(order, "ZERODHA");
        executionSimulator.process(msg);

        // Verify execution
        OmsOrder executed = orderLifecycleService.getRequired(order.getId());
        assertEquals(OrderState.FILLED, executed.getState());
        assertEquals(ExecutionMode.PAPER, executed.getExecutionMode());
    }

    @Test
    @DisplayName("SIMULATION mode: Order should execute with simulated fills")
    public void testSimulationModeExecution() {
        // Create signal
        StrategySignalEntity signal = createTestSignal("BANKNIFTY", "SELL");
        signal = signalRepository.save(signal);

        // Create SIMULATION order
        OmsOrder order = createTestOrder(testUserId, "BANKNIFTY", ExecutionMode.SIMULATED, signal.getId());
        order = orderRepository.save(order);

        // Verify execution mode
        assertEquals(ExecutionMode.SIMULATED, order.getExecutionMode());

        // Execute
        ExecutionDispatchMessage msg = dispatchMessageFor(order, "SIM");
        executionSimulator.process(msg);

        // Verify execution
        OmsOrder executed = orderLifecycleService.getRequired(order.getId());
        assertEquals(OrderState.FILLED, executed.getState());
        assertEquals(ExecutionMode.SIMULATED, executed.getExecutionMode());
    }

    @Test
    @DisplayName("LIVE mode: Order should be blocked if trader not eligible")
    public void testLiveModeBlocksIneligibleTrader() {
        // Create signal
        StrategySignalEntity signal = createTestSignal("FINNIFTY", "BUY");
        signal = signalRepository.save(signal);

        // Create LIVE order
        OmsOrder order = createTestOrder(testUserId, "FINNIFTY", ExecutionMode.LIVE, signal.getId());
        order = orderRepository.save(order);

        // Verify execution mode is LIVE
        assertEquals(ExecutionMode.LIVE, order.getExecutionMode());

        // Execute - should be rejected due to eligibility gate
        ExecutionDispatchMessage msg = dispatchMessageFor(order, "ZERODHA");
        executionSimulator.process(msg);

        // Verify rejection (not eligible or no broker credentials)
        OmsOrder result = orderLifecycleService.getRequired(order.getId());
        assertTrue(result.getState() == OrderState.REJECTED || result.getState() == OrderState.FAILED,
                "LIVE order should be rejected/failed for test user without credentials");
    }

    @Test
    @DisplayName("Cross-mode isolation: PAPER and LIVE orders don't mix")
    public void testExecutionModeIsolation() {
        // Create signals
        StrategySignalEntity signal1 = createTestSignal("RELIANCE", "BUY");
        signal1 = signalRepository.save(signal1);

        StrategySignalEntity signal2 = createTestSignal("TCS", "SELL");
        signal2 = signalRepository.save(signal2);

        // Create PAPER order
        OmsOrder paperOrder = createTestOrder(testUserId, "RELIANCE", ExecutionMode.PAPER, signal1.getId());
        paperOrder = orderRepository.save(paperOrder);

        // Create LIVE order
        OmsOrder liveOrder = createTestOrder(testUserId, "TCS", ExecutionMode.LIVE, signal2.getId());
        liveOrder = orderRepository.save(liveOrder);

        // Execute both
        executionSimulator.process(dispatchMessageFor(paperOrder, "SIM"));
        executionSimulator.process(dispatchMessageFor(liveOrder, "ZERODHA"));

        // Verify they're processed separately
        OmsOrder paper = orderLifecycleService.getRequired(paperOrder.getId());
        OmsOrder live = orderLifecycleService.getRequired(liveOrder.getId());

        assertEquals(ExecutionMode.PAPER, paper.getExecutionMode());
        assertEquals(ExecutionMode.LIVE, live.getExecutionMode());
        assertEquals(OrderState.FILLED, paper.getState());
        // LIVE should be rejected/failed
        assertTrue(live.getState() == OrderState.REJECTED || live.getState() == OrderState.FAILED);
    }

    @Test
    @DisplayName("Multiple signals: Each generates correct execution mode orders")
    public void testMultipleSignalsWithDifferentModes() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        // Signal 1 → PAPER order
        StrategySignalEntity sig1 = createTestSignal("INFY", "BUY");
        sig1 = signalRepository.save(sig1);
        OmsOrder order1 = createTestOrder(user1, "INFY", ExecutionMode.PAPER, sig1.getId());
        order1 = orderRepository.save(order1);

        // Signal 2 → SIMULATION order
        StrategySignalEntity sig2 = createTestSignal("SBIN", "SELL");
        sig2 = signalRepository.save(sig2);
        OmsOrder order2 = createTestOrder(user2, "SBIN", ExecutionMode.SIMULATED, sig2.getId());
        order2 = orderRepository.save(order2);

        // Execute both
        executionSimulator.process(dispatchMessageFor(order1, "SIM"));
        executionSimulator.process(dispatchMessageFor(order2, "SIM"));

        // Verify both executed in their respective modes
        OmsOrder exec1 = orderLifecycleService.getRequired(order1.getId());
        OmsOrder exec2 = orderLifecycleService.getRequired(order2.getId());

        assertEquals(ExecutionMode.PAPER, exec1.getExecutionMode());
        assertEquals(OrderState.FILLED, exec1.getState());

        assertEquals(ExecutionMode.SIMULATED, exec2.getExecutionMode());
        assertEquals(OrderState.FILLED, exec2.getState());

        // Verify no cross-contamination (mode/state remain isolated)
        assertNotEquals(exec1.getId(), exec2.getId());
    }

    // Helper methods

    private StrategySignalEntity createTestSignal(String symbol, String side) {
        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setUserId(testUserId);
        signal.setStrategyName("TEST_STRATEGY");
        signal.setStrategyVersion("1.0.0");
        signal.setSymbol(symbol);
        signal.setSignalType(SignalType.valueOf(side));
        signal.setConfidenceScore(BigDecimal.valueOf(0.8));
        signal.setEntryReferencePrice(BigDecimal.valueOf(100.0));
        signal.setAtrValue(BigDecimal.valueOf(2.5));
        signal.setTestTrade(true);
        return signal;
    }

    private OmsOrder createTestOrder(UUID userId, String symbol, ExecutionMode mode, UUID signalId) {
        OmsOrder order = new OmsOrder();
        order.setUserId(userId);
        order.setSymbol(symbol);
        order.setExecutionMode(mode);
        order.setSignalId(signalId);
        order.setState(OrderState.PENDING_SUBMISSION);
        order.setSide("BUY");
        order.setOrderType("MARKET");
        order.setQuantity(BigDecimal.ONE);
        order.setLimitPrice(BigDecimal.valueOf(100.0));
        order.setStrategyKey("TEST_STRATEGY");
        order.setCreatedAt(Instant.now());
        return order;
    }

    private ExecutionDispatchMessage dispatchMessageFor(OmsOrder order, String brokerVendor) {
        return new ExecutionDispatchMessage(
                order.getId(),
                order.getUserId(),
                order.getSignalId(),
                brokerVendor,
                0,
                order.getBacktestRunId(),
                order.getExecutionMode().name()
        );
    }
}
