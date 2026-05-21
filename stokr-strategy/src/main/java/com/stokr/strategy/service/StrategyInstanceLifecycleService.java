package com.stokr.strategy.service;

import com.stokr.common.events.realtime.RealtimeBridgeEvents;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.ForbiddenException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.common.trading.LiveStrategyGate;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.dto.UpdateStrategyInstanceRequest;
import com.stokr.strategy.dto.UserStrategyInstanceDto;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StrategyInstanceLifecycleService {

    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_STOPPED = "STOPPED";
    private static final String STATE_PAUSED = "PAUSED";

    private final StrategyInstanceRepository instanceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final StrategyHeartbeatService heartbeatService;
    private final ObjectProvider<LiveStrategyGate> liveStrategyGate;
    private final StrategyUniverseResolverService universeResolverService;

    @Transactional(readOnly = true)
    public Page<UserStrategyInstanceDto> pageForUser(UUID userId, Pageable pageable) {
        return instanceRepository.findPageByUserIdAndDeletedFalse(userId, pageable).map(this::toDto);
    }

    @Transactional
    public UserStrategyInstanceDto start(UUID userId, UUID instanceId) {
        StrategyInstance si = loadOwned(userId, instanceId);
        if (!si.isEnabled()) {
            throw new BadRequestException("Enable subscription before starting this strategy");
        }
        refreshSymbolFromBinding(si);
        if (si.getExecutionMode() != null && "LIVE".equalsIgnoreCase(si.getExecutionMode())) {
            liveStrategyGate.ifAvailable(g -> g.assertLiveRuntimeAllowed(userId, si.getDefinition().getStrategyKey()));
        }
        si.setRuntimeState(STATE_RUNNING);
        si.setStartedAt(Instant.now());
        si.setStoppedAt(null);
        StrategyInstance saved = instanceRepository.save(si);
        heartbeatService.touch(saved.getId());
        eventPublisher.publishEvent(new RealtimeBridgeEvents.StrategyRuntime(userId, saved.getId(), saved.getRuntimeState(), Instant.now()));
        return toDto(saved);
    }

    @Transactional
    public UserStrategyInstanceDto stop(UUID userId, UUID instanceId) {
        StrategyInstance si = loadOwned(userId, instanceId);
        si.setRuntimeState(STATE_STOPPED);
        si.setStoppedAt(Instant.now());
        StrategyInstance saved = instanceRepository.save(si);
        heartbeatService.touch(saved.getId());
        eventPublisher.publishEvent(new RealtimeBridgeEvents.StrategyRuntime(userId, saved.getId(), saved.getRuntimeState(), Instant.now()));
        return toDto(saved);
    }

    @Transactional
    public UserStrategyInstanceDto pause(UUID userId, UUID instanceId) {
        StrategyInstance si = loadOwned(userId, instanceId);
        si.setRuntimeState(STATE_PAUSED);
        StrategyInstance saved = instanceRepository.save(si);
        heartbeatService.touch(saved.getId());
        eventPublisher.publishEvent(new RealtimeBridgeEvents.StrategyRuntime(userId, saved.getId(), saved.getRuntimeState(), Instant.now()));
        return toDto(saved);
    }

    /**
     * Internal start used by the auto-heal scheduler — bypasses trader eligibility gate.
     * Safe because the platform ARM state is the operator-level approval.
     */
    @Transactional
    public void startInternal(UUID instanceId) {
        StrategyInstance si = instanceRepository.findById(instanceId)
                .filter(i -> i.isEnabled() && !i.isDeleted())
                .orElse(null);
        if (si == null) return;
        try { refreshSymbolFromBinding(si); } catch (Exception ignored) {}
        si.setRuntimeState(STATE_RUNNING);
        si.setOrchestrationState("MANAGED");
        si.setStartedAt(Instant.now());
        si.setStoppedAt(null);
        StrategyInstance saved = instanceRepository.save(si);
        heartbeatService.touch(saved.getId());
        eventPublisher.publishEvent(new RealtimeBridgeEvents.StrategyRuntime(
                si.getUserId(), saved.getId(), saved.getRuntimeState(), Instant.now()));
    }

    @Transactional
    public UserStrategyInstanceDto patch(UUID userId, UUID instanceId, UpdateStrategyInstanceRequest req) {
        StrategyInstance si = loadOwned(userId, instanceId);
        if (!si.getDefinition().isEnabled() || !si.getDefinition().isVisibleToUsers()) {
            throw new ForbiddenException("Strategy is no longer available");
        }
        if (req.symbol() != null && !req.symbol().isBlank()) {
            si.setSymbol(req.symbol().trim());
        }
        if (req.executionMode() != null && !req.executionMode().isBlank()) {
            si.setExecutionMode(normalizeExecutionMode(req.executionMode()));
        }
        if (req.allocationAmount() != null) {
            si.setAllocationAmount(req.allocationAmount());
        }
        if (req.riskMultiplier() != null) {
            si.setRiskMultiplier(req.riskMultiplier());
        }
        if (req.maxDailyLoss() != null) {
            si.setMaxDailyLoss(req.maxDailyLoss());
        }
        return toDto(instanceRepository.save(si));
    }

    private StrategyInstance loadOwned(UUID userId, UUID instanceId) {
        return instanceRepository.findByIdAndUserIdAndDeletedFalse(instanceId, userId)
                .orElseThrow(() -> new NotFoundException("Strategy instance not found"));
    }

    private static String normalizeExecutionMode(String raw) {
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (!u.equals("SIMULATED") && !u.equals("LIVE") && !u.equals("PAPER")) {
            throw new BadRequestException("executionMode must be SIMULATED, LIVE, or PAPER");
        }
        return u;
    }

    private void refreshSymbolFromBinding(StrategyInstance si) {
        String strategyKey = si.getDefinition().getStrategyKey();
        String bound = universeResolverService.resolveBindingsForStrategy(strategyKey).stream()
                .findFirst()
                .map(b -> universeResolverService.resolveSymbolsForGroup(b.getUniverseGroup().getId()))
                .filter(list -> list != null && !list.isEmpty())
                .map(list -> list.getFirst())
                .orElseThrow(() -> new ForbiddenException(
                        "No active runtime binding/symbols configured for strategy: " + strategyKey));
        if (!bound.equalsIgnoreCase(si.getSymbol())) {
            si.setSymbol(bound);
        }
    }

    private UserStrategyInstanceDto toDto(StrategyInstance si) {
        String display = si.getDefinition().getDisplayName();
        if (display == null || display.isBlank()) {
            display = si.getDefinition().getStrategyKey().replace('_', ' ').toLowerCase(Locale.ROOT);
        }
        BigDecimal rm = si.getRiskMultiplier();
        if (rm == null) {
            rm = BigDecimal.ONE;
        }
        return new UserStrategyInstanceDto(
                si.getId(),
                si.getDefinition().getId(),
                si.getDefinition().getStrategyKey(),
                display,
                si.getSymbol(),
                si.isEnabled(),
                si.getExecutionMode(),
                si.getRuntimeState(),
                si.getAllocationAmount(),
                rm,
                si.getMaxDailyLoss(),
                si.getStartedAt(),
                si.getStoppedAt()
        );
    }
}
