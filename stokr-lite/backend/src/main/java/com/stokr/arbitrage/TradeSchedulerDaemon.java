package com.stokr.arbitrage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeSchedulerDaemon {

    private final ScheduledTradeRepository repo;
    private final OptionArbAutoExecService autoExecService;
    private final MultiTenantExecutionRouter executionRouter;
    private final OptionArbHistoryService historyService;

    @Scheduled(fixedDelay = 5000)
    public void executeScheduledTrades() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledTrade> pending = repo.findByStatusAndScheduledTimeLessThanEqual("PENDING", now);
        
        for (ScheduledTrade st : pending) {
            log.info("Executing scheduled trade: {} for {}", st.getId(), st.getUnderlying());
            
            try {
                // Convert to Opportunity to reuse existing execution logic
                OptionArbOpportunity opp = OptionArbOpportunity.builder()
                    .scanTime(LocalDateTime.now())
                    .underlying(st.getUnderlying())
                    .type(st.getStrategyType())
                    .strike(st.getStrike())
                    .action(st.getAction())
                    .legs(st.getAction() + " " + st.getUnderlying() + " " + st.getStrike())
                    .strategyType(st.getStrategyType())
                    .description("Scheduled Trade for " + st.getUnderlying())
                    .status("RUNNING")
                    .build();
                opp.setLegList(st.getLegList());
                
                opp = historyService.getRepository().save(opp);
                
                opp.setUserId(st.getUserId());
                autoExecService.manualExecuteLive(opp, st.getLots(), st.getBroker(), st.getUserId());
                
                st.setStatus("EXECUTED");
                st.setExecutedAt(LocalDateTime.now());
                repo.save(st);
                
            } catch (Exception e) {
                log.error("Failed to execute scheduled trade {}: {}", st.getId(), e.getMessage());
                st.setStatus("FAILED");
                repo.save(st);
            }
        }
    }
}
