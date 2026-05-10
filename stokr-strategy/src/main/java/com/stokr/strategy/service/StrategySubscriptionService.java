package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.dto.SubscriptionToggleResult;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.common.exception.ForbiddenException;
import com.stokr.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StrategySubscriptionService {

    private final StrategyDefinitionRepository definitionRepository;
    private final StrategyInstanceRepository instanceRepository;

    @Value("${stokr.strategy.subscription-default-symbol:NIFTY_FUT}")
    private String defaultSymbol;

    @Transactional
    public SubscriptionToggleResult toggle(UUID userId, UUID definitionId) {
        StrategyDefinition def = definitionRepository.findByIdAndDeletedFalse(definitionId)
                .orElseThrow(() -> new NotFoundException("Strategy not found"));
        if (!def.isEnabled() || !def.isVisibleToUsers()) {
            throw new ForbiddenException("Strategy is not available for subscription");
        }

        Optional<StrategyInstance> existing =
                instanceRepository.findByUserIdAndDefinition_IdAndDeletedFalse(userId, definitionId);
        if (existing.isPresent()) {
            StrategyInstance si = existing.get();
            si.setEnabled(!si.isEnabled());
            instanceRepository.save(si);
            return new SubscriptionToggleResult(true, si.isEnabled());
        }

        StrategyInstance created = new StrategyInstance();
        created.setDefinition(def);
        created.setUserId(userId);
        created.setSymbol(defaultSymbol);
        created.setEnabled(true);
        created.setExecutionMode("SIMULATED");
        created.setRuntimeState("STOPPED");
        created.setRiskMultiplier(BigDecimal.ONE);
        instanceRepository.save(created);
        return new SubscriptionToggleResult(true, true);
    }
}
