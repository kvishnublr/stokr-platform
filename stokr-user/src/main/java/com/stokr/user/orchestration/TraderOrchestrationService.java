package com.stokr.user.orchestration;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.service.StrategyInstanceLifecycleService;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Auto-starts MANAGED strategy instances after prerequisites (onboarding, broker) are satisfied.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TraderOrchestrationService {

    private final AuthUserRepository authUserRepository;
    private final BrokerAccountRepository brokerAccountRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final StrategyInstanceLifecycleService strategyInstanceLifecycleService;

    @Transactional
    public void reconcile(UUID userId) {
        AuthUser user = authUserRepository.findById(userId).orElse(null);
        if (user == null || !user.isOnboardingComplete()) {
            return;
        }
        if (brokerAccountRepository
                .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA")
                .isEmpty()) {
            return;
        }
        List<StrategyInstance> instances = strategyInstanceRepository.findAllForUserWithDefinition(userId);
        for (StrategyInstance si : instances) {
            if (!si.isEnabled()) {
                continue;
            }
            if (!"MANAGED".equalsIgnoreCase(si.getOrchestrationState())) {
                continue;
            }
            if (!"STOPPED".equalsIgnoreCase(si.getRuntimeState())) {
                continue;
            }
            if (!"LIVE".equalsIgnoreCase(si.getExecutionMode())) {
                continue;
            }
            try {
                strategyInstanceLifecycleService.start(userId, si.getId());
                log.info("orchestration.auto_started instanceId={} userId={}", si.getId(), userId);
            } catch (Exception ex) {
                log.warn("orchestration.start_skipped instanceId={} userId={} reason={}",
                        si.getId(), userId, ex.getMessage());
            }
        }
    }
}
