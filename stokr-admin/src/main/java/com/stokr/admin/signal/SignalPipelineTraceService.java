package com.stokr.admin.signal;

import com.stokr.admin.signal.SignalPipelineTraceDto.PipelineStageDto;
import com.stokr.admin.signal.SignalPipelineTraceDto.UserTraceDto;
import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.OmsExecutionEvent;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsExecutionEventRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.pipeline.SignalPipelineAudit;
import com.stokr.strategy.pipeline.SignalPipelineAuditRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalPipelineTraceService {

    private static final String STAGE_PASSED = "PASSED";
    private static final String STAGE_FAILED = "FAILED";
    private static final String STAGE_SKIPPED = "SKIPPED";
    private static final String STAGE_PENDING = "PENDING";

    private final StrategySignalRepository signalRepo;
    private final SignalPipelineAuditRepository auditRepo;
    private final OmsOrderRepository orderRepo;
    private final OmsExecutionEventRepository executionEventRepo;
    private final AuthUserRepository authUserRepo;

    @Transactional(readOnly = true)
    public SignalPipelineTraceDto buildTrace(UUID signalId) {
        StrategySignalEntity signal = signalRepo.findById(signalId).orElse(null);
        if (signal == null) {
            return null;
        }

        // 1. Build application-level pipeline stages
        List<SignalPipelineAudit> audits = auditRepo.findBySignalIdOrderByCreatedAtAsc(signalId);
        List<PipelineStageDto> appStages = buildApplicationStages(signal, audits);

        // 2. Build per-user traces from orders linked to this signal
        List<OmsOrder> orders = orderRepo.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(signalId);
        List<UserTraceDto> users = buildUserTraces(signal, orders);

        // 3. Determine overall status
        String overallStatus = resolveOverallStatus(users, audits);

        return new SignalPipelineTraceDto(
            signal.getId(),
            signal.getSymbol(),
            signal.getStrategyName(),
            signal.getSignalType() != null ? signal.getSignalType().name() : "UNKNOWN",
            signal.getPipeline(),
            signal.getOutcomeStatus(),
            signal.getCreatedAt(),
            overallStatus,
            appStages,
            users
        );
    }

    // ????????? Application-Level Stages ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    private List<PipelineStageDto> buildApplicationStages(StrategySignalEntity signal, List<SignalPipelineAudit> audits) {
        List<PipelineStageDto> stages = new ArrayList<>();
        int idx = 0;

        // Stage 1: SIGNAL_GENERATED ??? always passed if signal exists
        stages.add(new PipelineStageDto(
            "SIGNAL_GENERATED", STAGE_PASSED, "Signal Generated",
            signal.getCreatedAt(), null, null,
            detailsOf(
                "confidence", signal.getConfidenceScore(),
                "signalType", signal.getSignalType() != null ? signal.getSignalType().name() : null,
                "provenance", signal.getSignalSource() != null ? signal.getSignalSource().name() : null
            ),
            idx++
        ));

        // Stages 2-6: map from pipeline audits (SESSION_CHECK, QUALITY_GATE, DEDUP, DAILY_CAP)
        Map<String, SignalPipelineAudit> auditByStage = audits.stream()
            .collect(Collectors.toMap(
                SignalPipelineAudit::getPipelineStage,
                a -> a,
                (a, b) -> b
            ));

        addAppStageFromAudit(stages, auditByStage, "SESSION_CHECK", "Session Check", idx++);
        addAppStageFromAudit(stages, auditByStage, "QUALITY_CHECK", "Quality Gate", idx++);
        addAppStageFromAudit(stages, auditByStage, "QUALITY_GATE", "Quality Gate", idx++);
        addAppStageFromAudit(stages, auditByStage, "DEDUP", "Deduplication", idx++);
        addAppStageFromAudit(stages, auditByStage, "DAILY_CAP", "Daily Cap", idx++);

        // Stage 7: PERSISTED ??? always true if signal exists
        stages.add(new PipelineStageDto(
            "PERSISTED", STAGE_PASSED, "Persisted to Database",
            signal.getCreatedAt(), null, null,
            Map.of("signalId", signal.getId().toString()),
            idx++
        ));

        // Stage 8: DISPATCHED ??? check OMS_ELIGIBLE audit or order existence
        boolean dispatched = hasPassedStage(auditByStage, "OMS_ELIGIBLE")
            || hasPassedStage(auditByStage, "DISPATCHED");
        if (dispatched) {
            SignalPipelineAudit dispatchAudit = auditByStage.get("OMS_ELIGIBLE");
            if (dispatchAudit == null) dispatchAudit = auditByStage.get("DISPATCHED");
            Instant ts = dispatchAudit != null ? dispatchAudit.getCreatedAt() : signal.getCreatedAt();
            stages.add(new PipelineStageDto(
                "DISPATCHED", STAGE_PASSED, "Dispatched to OMS",
                ts, null, null, Map.of(), idx++
            ));
        } else {
            stages.add(new PipelineStageDto(
                "DISPATCHED", STAGE_PENDING, "Dispatched to OMS",
                null, null, null, Map.of(), idx++
            ));
        }

        // Re-sort by orderIndex
        stages.sort(Comparator.comparingInt(PipelineStageDto::orderIndex));
        return stages;
    }

    private void addAppStageFromAudit(List<PipelineStageDto> stages,
                                       Map<String, SignalPipelineAudit> auditByStage,
                                       String stageKey, String label, int orderIdx) {
        SignalPipelineAudit audit = auditByStage.get(stageKey);
        if (audit != null) {
            boolean blocked = "BLOCKED".equalsIgnoreCase(audit.getExecutionStatus())
                || "QUALITY_REJECTED".equalsIgnoreCase(audit.getExecutionStatus());
            stages.add(new PipelineStageDto(
                audit.getPipelineStage(),
                blocked ? STAGE_FAILED : STAGE_PASSED,
                label,
                audit.getCreatedAt(),
                audit.getRejectionCode(),
                audit.getRejectionMessage(),
                buildAuditDetails(audit),
                orderIdx
            ));
        } else {
            stages.add(new PipelineStageDto(
                stageKey, STAGE_PASSED + "_NO_DATA", label + " (no data ??? assumed passed)",
                null, null, null, Map.of(), orderIdx
            ));
        }
    }

    private boolean hasPassedStage(Map<String, SignalPipelineAudit> auditByStage, String stageKey) {
        SignalPipelineAudit a = auditByStage.get(stageKey);
        return a != null && !"BLOCKED".equalsIgnoreCase(a.getExecutionStatus())
            && !"QUALITY_REJECTED".equalsIgnoreCase(a.getExecutionStatus());
    }

    // ????????? Per-User Traces ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    private List<UserTraceDto> buildUserTraces(StrategySignalEntity signal, List<OmsOrder> orders) {
        if (orders.isEmpty()) {
            // If signal has a userId but no orders yet, show a pending user trace
            if (signal.getUserId() != null) {
                AuthUser user = authUserRepo.findById(signal.getUserId()).orElse(null);
                if (user != null) {
                    return List.of(buildPendingUserTrace(signal, user));
                }
            }
            return List.of();
        }

        Map<UUID, List<OmsOrder>> ordersByUser = orders.stream()
            .collect(Collectors.groupingBy(OmsOrder::getUserId, LinkedHashMap::new, Collectors.toList()));

        List<UserTraceDto> userTraces = new ArrayList<>();
        for (Map.Entry<UUID, List<OmsOrder>> entry : ordersByUser.entrySet()) {
            UUID userId = entry.getKey();
            List<OmsOrder> userOrders = entry.getValue();
            AuthUser user = authUserRepo.findById(userId).orElse(null);
            String username = user != null ? user.getUsername() : userId.toString().substring(0, 8);
            String displayName = user != null && user.getDisplayName() != null ? user.getDisplayName() : username;

            // Gather all execution events for this user's orders
            List<PipelineStageDto> userStages = new ArrayList<>();
            String finalStatus = "PENDING";
            String lastStage = null;
            String lastRejectionCode = null;
            String lastRejectionMessage = null;
            String brokerExtId = null;
            UUID brokerOrderId = null;
            int idx = 0;

            // Check for user-level pipeline audits
            List<SignalPipelineAudit> userAudits = auditRepo.findBySignalIdAndUserIdOrderByCreatedAtAsc(signal.getId(), userId);
            Map<String, SignalPipelineAudit> userAuditByStage = userAudits.stream()
                .collect(Collectors.toMap(
                    SignalPipelineAudit::getPipelineStage,
                    a -> a,
                    (a, b) -> b
                ));

            // OMS safety gate from user-level audit
            addUserStageFromAudit(userStages, userAuditByStage, "OMS_ELIGIBLE", "OMS Safety Gate", idx++);
            addUserStageFromAudit(userStages, userAuditByStage, "LIVE_ELIGIBILITY", "Live Trading Eligibility", idx++);

            // For each order, get execution events
            OmsOrder primaryOrder = userOrders.get(0);
            List<OmsExecutionEvent> events = executionEventRepo.findByOrder_IdAndDeletedFalseOrderByStreamSequenceAsc(primaryOrder.getId());
            Map<ExecutionEventType, OmsExecutionEvent> eventsByType = events.stream()
                .collect(Collectors.toMap(
                    OmsExecutionEvent::getEventType,
                    e -> e,
                    (a, b) -> b
                ));

            // Map execution events to pipeline stages
            for (ExecutionEventType eventType : ExecutionEventType.values()) {
                OmsExecutionEvent event = eventsByType.get(eventType);
                if (event == null) continue;

                boolean isFailed = eventType == ExecutionEventType.EXECUTION_REJECTED
                    || eventType == ExecutionEventType.EXECUTION_DLQ;
                boolean isRetry = eventType == ExecutionEventType.EXECUTION_RETRY_SCHEDULED;
                String stageStatus = isFailed ? STAGE_FAILED : STAGE_PASSED;

                String stageName = eventType.name();
                String stageLabel = formatEventTypeLabel(eventType);
                String rejectCode = null;
                String rejectMsg = null;
                if (isFailed) {
                    rejectCode = eventType.name();
                    rejectMsg = extractRejectionMessage(event.getEventPayloadJson());
                    finalStatus = "REJECTED";
                    lastRejectionCode = rejectCode;
                    lastRejectionMessage = rejectMsg;
                }

                Map<String, Object> details = parsePayload(event.getEventPayloadJson());
                userStages.add(new PipelineStageDto(
                    stageName, stageStatus, stageLabel,
                    event.getCreatedAt(), rejectCode, rejectMsg, details, idx++
                ));
                lastStage = stageName;
            }

            // If we have events, determine final status
            if (!events.isEmpty()) {
                OmsExecutionEvent lastEvent = events.get(events.size() - 1);
                if (lastEvent.getEventType() == ExecutionEventType.ORDER_FILLED
                    || lastEvent.getEventType() == ExecutionEventType.PARTIAL_FILL) {
                    finalStatus = "FILLED";
                }
                brokerExtId = primaryOrder.getBrokerExternalOrderId();
                brokerOrderId = primaryOrder.getBrokerOrderId();
            }

            // If no events but has order, it's in CREATED/VALIDATED/RISK_CHECK state
            if (events.isEmpty() && primaryOrder != null) {
                finalStatus = primaryOrder.getState() != null ? primaryOrder.getState().name() : "PENDING";
                lastStage = "ORDER_CREATED";
                userStages.add(new PipelineStageDto(
                    "ORDER_CREATED", STAGE_PASSED, "Order Created",
                    primaryOrder.getCreatedAt(), null, null,
                    detailsOf(
                        "orderId", primaryOrder.getId().toString(),
                        "state", primaryOrder.getState() != null ? primaryOrder.getState().name() : null,
                        "quantity", primaryOrder.getQuantity(),
                        "side", primaryOrder.getSide()
                    ),
                    idx++
                ));
            }

            userTraces.add(new UserTraceDto(
                userId, username, displayName,
                finalStatus, lastStage, lastRejectionCode, lastRejectionMessage,
                brokerExtId, brokerOrderId, userStages
            ));
        }

        return userTraces;
    }

    private UserTraceDto buildPendingUserTrace(StrategySignalEntity signal, AuthUser user) {
        return new UserTraceDto(
            user.getId(), user.getUsername(),
            user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
            "PENDING", null, null, null, null, null, List.of()
        );
    }

    private void addUserStageFromAudit(List<PipelineStageDto> stages,
                                        Map<String, SignalPipelineAudit> auditByStage,
                                        String stageKey, String label, int orderIdx) {
        SignalPipelineAudit audit = auditByStage.get(stageKey);
        if (audit != null) {
            boolean blocked = "BLOCKED".equalsIgnoreCase(audit.getExecutionStatus());
            stages.add(new PipelineStageDto(
                audit.getPipelineStage(),
                blocked ? STAGE_FAILED : STAGE_PASSED,
                label,
                audit.getCreatedAt(),
                audit.getRejectionCode(),
                audit.getRejectionMessage(),
                buildAuditDetails(audit),
                orderIdx
            ));
        }
    }

    // ????????? Helpers ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    private String resolveOverallStatus(List<UserTraceDto> users, List<SignalPipelineAudit> audits) {
        // Check if application pipeline blocked at any stage
        for (SignalPipelineAudit a : audits) {
            if ("BLOCKED".equalsIgnoreCase(a.getExecutionStatus())
                || "QUALITY_REJECTED".equalsIgnoreCase(a.getExecutionStatus())) {
                return "APPLICATION_BLOCKED";
            }
        }
        if (users.isEmpty()) {
            return "NO_USERS";
        }
        boolean anyFilled = users.stream().anyMatch(u -> "FILLED".equals(u.finalStatus()));
        boolean anyRejected = users.stream().anyMatch(u -> "REJECTED".equals(u.finalStatus()));
        boolean anyPending = users.stream().anyMatch(u -> "PENDING".equals(u.finalStatus()));
        if (anyFilled && !anyRejected && !anyPending) return "ALL_FILLED";
        if (anyFilled) return "PARTIAL_FILL";
        if (anyRejected && !anyPending) return "ALL_REJECTED";
        if (anyPending) return "PENDING";
        return "UNKNOWN";
    }

    private Map<String, Object> buildAuditDetails(SignalPipelineAudit audit) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (audit.getRequestedMode() != null) details.put("requestedMode", audit.getRequestedMode());
        if (audit.getEffectiveMode() != null) details.put("effectiveMode", audit.getEffectiveMode());
        if (audit.getConfidenceScore() != null) details.put("confidenceScore", audit.getConfidenceScore());
        if (audit.getQualityGate() != null) details.put("qualityGate", audit.getQualityGate());
        if (audit.getRiskGate() != null) details.put("riskGate", audit.getRiskGate());
        if (audit.getCooldownSecRemaining() != null) details.put("cooldownSecRemaining", audit.getCooldownSecRemaining());
        if (audit.getUserId() != null) details.put("userId", audit.getUserId().toString());
        return details;
    }

    private static Map<String, Object> detailsOf(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (keyValues == null) {
            return details;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                details.put(String.valueOf(keyValues[i]), value);
            }
        }
        return details;
    }

    private String extractRejectionMessage(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.readValue(payloadJson, java.util.Map.class);
            Object msg = map.get("rejectionMessage");
            if (msg != null) return msg.toString();
            Object reason = map.get("reason");
            if (reason != null) return reason.toString();
            Object error = map.get("error");
            if (error != null) return error.toString();
        } catch (Exception e) {
            // ignore parse errors
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Map.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(payloadJson, java.util.Map.class);
        } catch (Exception e) {
            return Map.of("rawPayload", payloadJson.substring(0, Math.min(200, payloadJson.length())));
        }
    }

    private String formatEventTypeLabel(ExecutionEventType type) {
        return switch (type) {
            case SIGNAL_GENERATED -> "Signal Generated";
            case RISK_CHECK_PASSED -> "Risk Check Passed";
            case ORDER_REQUESTED -> "Order Requested";
            case ORDER_ACCEPTED -> "Order Accepted";
            case EXECUTION_DISPATCHED -> "Execution Dispatched";
            case BROKER_SUBMITTED -> "Broker Submitted";
            case EXCHANGE_ACK -> "Exchange Acknowledged";
            case PARTIAL_FILL -> "Partial Fill";
            case ORDER_FILLED -> "Order Filled";
            case STOP_TRIGGERED -> "Stop Triggered";
            case POSITION_CLOSED -> "Position Closed";
            case EXECUTION_REJECTED -> "Execution Rejected";
            case EXECUTION_RETRY_SCHEDULED -> "Retry Scheduled";
            case EXECUTION_DLQ -> "Sent to DLQ";
        };
    }
}
