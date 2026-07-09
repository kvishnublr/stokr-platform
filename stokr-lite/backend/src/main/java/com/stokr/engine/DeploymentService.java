package com.stokr.engine;

import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerService;
import com.stokr.strategy.Strategy;
import com.stokr.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentService {

    private final DeploymentRepository repository;
    private final StrategyService strategyService;
    private final BrokerService brokerService;

    public List<Deployment> getUserDeployments(Long userId) {
        return repository.findByUserId(userId);
    }

    public List<Deployment> getActiveDeployments(Long userId) {
        return repository.findByUserIdAndStatus(userId, "ACTIVE");
    }

    public Deployment getDeployment(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Deployment not found"));
    }

    public List<Deployment> getAllActiveDeployments() {
        return repository.findByStatus("ACTIVE");
    }

    @Transactional
    public Deployment deploy(Long userId, Long strategyId, Long brokerAccountId,
                              String mode, BigDecimal capital) {
        // Validate strategy exists and is enabled
        Strategy strategy = strategyService.getStrategy(strategyId);
        if (!strategy.isEnabled()) {
            throw new IllegalStateException("Strategy is disabled: " + strategy.getName());
        }

        String deployMode = mode != null ? mode : "PAPER";

        // Validate broker account only for LIVE mode
        if ("LIVE".equalsIgnoreCase(deployMode)) {
            if (brokerAccountId == null) {
                throw new IllegalStateException("Broker account required for LIVE mode");
            }
            BrokerAccount broker = brokerService.getBrokerAccount(brokerAccountId, userId);
            if (!"ACTIVE".equals(broker.getStatus())) {
                throw new IllegalStateException("Broker account is not active");
            }
        }

        Deployment deployment = Deployment.builder()
                .userId(userId)
                .strategyId(strategyId)
                .brokerAccountId(brokerAccountId)
                .mode(deployMode)
                .capital(capital != null ? capital : new BigDecimal("100000"))
                .status("ACTIVE")
                .build();

        deployment = repository.save(deployment);
        log.info("Deployed strategy {} for user {} with mode {}", strategy.getName(), userId, deployMode);
        return deployment;
    }

    @Transactional
    public Deployment stopDeployment(Long id, Long userId) {
        Deployment deployment = getDeployment(id, userId);
        deployment.setStatus("STOPPED");
        log.info("Stopped deployment {} for user {}", id, userId);
        return repository.save(deployment);
    }

    @Transactional
    public Deployment updateStatus(Long id, Long userId, String newStatus) {
        Deployment deployment = getDeployment(id, userId);
        deployment.setStatus(newStatus);
        log.info("Updated deployment {} status to {} for user {}", id, newStatus, userId);
        return repository.save(deployment);
    }

    @Transactional
    public Deployment forceStopDeployment(Long id) {
        Deployment deployment = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Deployment not found"));
        deployment.setStatus("STOPPED");
        log.info("FORCE stopped deployment {} (admin)", id);
        return repository.save(deployment);
    }

    @Transactional
    public Deployment save(Deployment deployment) {
        return repository.save(deployment);
    }
}
