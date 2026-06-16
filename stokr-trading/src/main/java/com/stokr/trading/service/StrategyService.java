package com.stokr.trading.service;

import com.stokr.trading.domain.*;
import com.stokr.trading.dto.TradingDto.*;
import com.stokr.trading.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyService {

    private final StrategyRepository strategyRepository;
    private final StrategyInstanceRepository instanceRepository;
    private final SignalRepository signalRepository;
    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;

    // ===================== STRATEGIES =====================

    @Transactional(readOnly = true)
    public List<StrategyDto> getStrategiesByOrganization(UUID organizationId) {
        return strategyRepository.findByOrganizationIdAndDeletedFalse(organizationId)
                .stream()
                .map(this::toStrategyDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public StrategyDto getStrategy(UUID strategyId) {
        Strategy strategy = strategyRepository.findByIdAndDeletedFalse(strategyId)
                .orElseThrow(() -> new IllegalArgumentException("Strategy not found"));
        return toStrategyDto(strategy);
    }

    @Transactional
    public StrategyDto createStrategy(UUID organizationId, UUID creatorId, CreateStrategyRequest request) {
        Strategy strategy = Strategy.builder()
                .organizationId(organizationId)
                .creatorId(creatorId)
                .name(request.getName())
                .description(request.getDescription())
                .code(request.getCode())
                .parameters(toJson(request.getParameters()))
                .tags(toJson(request.getTags()))
                .isActive(true)
                .isPublic(false)
                .build();

        Strategy saved = strategyRepository.save(strategy);
        log.info("Strategy created: {} by user: {}", saved.getName(), creatorId);

        return toStrategyDto(saved);
    }

    @Transactional
    public StrategyDto updateStrategy(UUID strategyId, UpdateStrategyRequest request) {
        Strategy strategy = strategyRepository.findByIdAndDeletedFalse(strategyId)
                .orElseThrow(() -> new IllegalArgumentException("Strategy not found"));

        if (request.getName() != null) strategy.setName(request.getName());
        if (request.getDescription() != null) strategy.setDescription(request.getDescription());
        if (request.getCode() != null) strategy.setCode(request.getCode());
        if (request.getParameters() != null) strategy.setParameters(toJson(request.getParameters()));
        if (request.getIsActive() != null) strategy.setIsActive(request.getIsActive());

        Strategy saved = strategyRepository.save(strategy);
        return toStrategyDto(saved);
    }

    @Transactional
    public void deleteStrategy(UUID strategyId) {
        Strategy strategy = strategyRepository.findByIdAndDeletedFalse(strategyId)
                .orElseThrow(() -> new IllegalArgumentException("Strategy not found"));
        strategy.setDeleted(true);
        strategyRepository.save(strategy);
    }

    // ===================== STRATEGY INSTANCES =====================

    @Transactional(readOnly = true)
    public List<InstanceDto> getInstancesByUser(UUID userId) {
        return instanceRepository.findByUserIdAndDeletedFalse(userId)
                .stream()
                .map(this::toInstanceDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InstanceDto getInstance(UUID instanceId) {
        StrategyInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found"));
        return toInstanceDto(instance);
    }

    @Transactional
    public InstanceDto createInstance(UUID strategyId, UUID userId, UUID organizationId, CreateInstanceRequest request) {
        Strategy strategy = strategyRepository.findByIdAndDeletedFalse(strategyId)
                .orElseThrow(() -> new IllegalArgumentException("Strategy not found"));

        StrategyInstance instance = StrategyInstance.builder()
                .strategyId(strategyId)
                .userId(userId)
                .organizationId(organizationId)
                .brokerAccountId(request.getBrokerAccountId())
                .name(request.getName() != null ? request.getName() : strategy.getName())
                .symbol(request.getSymbol().toUpperCase())
                .enabled(true)
                .executionMode(request.getExecutionMode() != null ? request.getExecutionMode() : "PAPER")
                .allocation(request.getAllocation() != null ? request.getAllocation() : BigDecimal.valueOf(10000))
                .maxPositionSize(request.getMaxPositionSize() != null ? request.getMaxPositionSize() : BigDecimal.valueOf(1000))
                .riskMultiplier(request.getRiskMultiplier() != null ? request.getRiskMultiplier() : BigDecimal.ONE)
                .maxDailyLoss(request.getMaxDailyLoss() != null ? request.getMaxDailyLoss() : BigDecimal.valueOf(500))
                .status("STOPPED")
                .build();

        StrategyInstance saved = instanceRepository.save(instance);
        log.info("Instance created: {} for user: {}", saved.getName(), userId);

        return toInstanceDto(saved);
    }

    @Transactional
    public InstanceDto updateInstance(UUID instanceId, UUID userId, UpdateInstanceRequest request) {
        StrategyInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found"));

        if (!instance.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to update this instance");
        }

        if (request.getName() != null) instance.setName(request.getName());
        if (request.getSymbol() != null) instance.setSymbol(request.getSymbol().toUpperCase());
        if (request.getEnabled() != null) instance.setEnabled(request.getEnabled());
        if (request.getExecutionMode() != null) instance.setExecutionMode(request.getExecutionMode());
        if (request.getAllocation() != null) instance.setAllocation(request.getAllocation());
        if (request.getMaxPositionSize() != null) instance.setMaxPositionSize(request.getMaxPositionSize());
        if (request.getRiskMultiplier() != null) instance.setRiskMultiplier(request.getRiskMultiplier());
        if (request.getMaxDailyLoss() != null) instance.setMaxDailyLoss(request.getMaxDailyLoss());

        StrategyInstance saved = instanceRepository.save(instance);
        return toInstanceDto(saved);
    }

    @Transactional
    public InstanceDto startInstance(UUID instanceId, UUID userId) {
        StrategyInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found"));

        if (!instance.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to start this instance");
        }

        instance.setStatus("RUNNING");
        instance.setStartedAt(Instant.now());
        instance.setStoppedAt(null);

        StrategyInstance saved = instanceRepository.save(instance);
        log.info("Instance started: {} by user: {}", saved.getName(), userId);

        return toInstanceDto(saved);
    }

    @Transactional
    public InstanceDto stopInstance(UUID instanceId, UUID userId) {
        StrategyInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found"));

        if (!instance.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to stop this instance");
        }

        instance.setStatus("STOPPED");
        instance.setStoppedAt(Instant.now());

        StrategyInstance saved = instanceRepository.save(instance);
        log.info("Instance stopped: {} by user: {}", saved.getName(), userId);

        return toInstanceDto(saved);
    }

    @Transactional
    public InstanceDto pauseInstance(UUID instanceId, UUID userId) {
        StrategyInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found"));

        if (!instance.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to pause this instance");
        }

        instance.setStatus("PAUSED");

        StrategyInstance saved = instanceRepository.save(instance);
        return toInstanceDto(saved);
    }

    @Transactional
    public void deleteInstance(UUID instanceId, UUID userId) {
        StrategyInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found"));

        if (!instance.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to delete this instance");
        }

        instance.setDeleted(true);
        instanceRepository.save(instance);
    }

    // ===================== SIGNALS =====================

    @Transactional(readOnly = true)
    public List<SignalDto> getSignalsByInstance(UUID instanceId) {
        return signalRepository.findByInstanceIdAndDeletedFalseOrderByCreatedAtDesc(instanceId)
                .stream()
                .map(this::toSignalDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public SignalDto createSignal(UUID instanceId, CreateSignalRequest request) {
        StrategyInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found"));

        Signal signal = Signal.builder()
                .instanceId(instanceId)
                .symbol(request.getSymbol().toUpperCase())
                .signalType(request.getSignalType())
                .side(request.getSide())
                .confidence(request.getConfidence())
                .entryPrice(request.getEntryPrice())
                .targetPrice(request.getTargetPrice())
                .stopLoss(request.getStopLoss())
                .quantity(request.getQuantity())
                .status("PENDING")
                .build();

        Signal saved = signalRepository.save(signal);

        instance.setLastSignalAt(Instant.now());
        instanceRepository.save(instance);

        log.info("Signal created: {} {} {} for instance: {}", 
                saved.getSide(), saved.getSignalType(), saved.getSymbol(), instanceId);

        return toSignalDto(saved);
    }

    // ===================== HELPER METHODS =====================

    private StrategyDto toStrategyDto(Strategy s) {
        long instanceCount = instanceRepository.countByUserId(s.getId());
        return StrategyDto.builder()
                .id(s.getId())
                .organizationId(s.getOrganizationId())
                .creatorId(s.getCreatorId())
                .name(s.getName())
                .description(s.getDescription())
                .isActive(s.getIsActive())
                .isPublic(s.getIsPublic())
                .instanceCount((int) instanceCount)
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private InstanceDto toInstanceDto(StrategyInstance si) {
        long pendingSignals = signalRepository.countByInstanceIdAndStatus(si.getId(), "PENDING");
        List<Position> positions = positionRepository.findByUserIdAndStatusAndDeletedFalse(si.getUserId(), "OPEN");
        BigDecimal totalPnl = positions.stream()
                .map(p -> p.getPnl() != null ? p.getPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return InstanceDto.builder()
                .id(si.getId())
                .strategyId(si.getStrategyId())
                .userId(si.getUserId())
                .brokerAccountId(si.getBrokerAccountId())
                .name(si.getName())
                .symbol(si.getSymbol())
                .enabled(si.getEnabled())
                .executionMode(si.getExecutionMode())
                .allocation(si.getAllocation())
                .maxPositionSize(si.getMaxPositionSize())
                .riskMultiplier(si.getRiskMultiplier())
                .maxDailyLoss(si.getMaxDailyLoss())
                .status(si.getStatus())
                .startedAt(si.getStartedAt())
                .stoppedAt(si.getStoppedAt())
                .lastSignalAt(si.getLastSignalAt())
                .pendingSignals((int) pendingSignals)
                .openPositions(positions.size())
                .totalPnl(totalPnl)
                .createdAt(si.getCreatedAt())
                .build();
    }

    private SignalDto toSignalDto(Signal s) {
        return SignalDto.builder()
                .id(s.getId())
                .instanceId(s.getInstanceId())
                .symbol(s.getSymbol())
                .signalType(s.getSignalType())
                .side(s.getSide())
                .confidence(s.getConfidence())
                .entryPrice(s.getEntryPrice())
                .targetPrice(s.getTargetPrice())
                .stopLoss(s.getStopLoss())
                .quantity(s.getQuantity())
                .status(s.getStatus())
                .createdAt(s.getCreatedAt())
                .executedAt(s.getExecutedAt())
                .build();
    }

    private String toJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
