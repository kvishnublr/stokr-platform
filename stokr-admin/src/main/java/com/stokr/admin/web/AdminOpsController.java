package com.stokr.admin.web;

import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@RestController
@RequestMapping("/api/admin/ops")
@RequiredArgsConstructor
public class AdminOpsController {

    private final RabbitAdmin rabbitAdmin;
    private final AuthUserRepository authUserRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final ObjectProvider<SimpUserRegistry> simpUserRegistry;

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registeredUsers", authUserRepository.countByDeletedFalse());
        body.put("runningStrategies", strategyInstanceRepository.countByRuntimeStateAndDeletedFalse("RUNNING"));
        body.put("websocketUsersApprox", websocketUsers());
        Map<String, Object> queues = new LinkedHashMap<>();
        queues.put(PipelineQueues.STRATEGY_SIGNAL, queueProps(PipelineQueues.STRATEGY_SIGNAL));
        queues.put(PipelineQueues.OMS_ORDER, queueProps(PipelineQueues.OMS_ORDER));
        queues.put(PipelineQueues.EXECUTION, queueProps(PipelineQueues.EXECUTION));
        body.put("rabbitQueues", queues);
        return ApiResponse.ok(body, CorrelationIdHolder.get());
    }

    private int websocketUsers() {
        SimpUserRegistry reg = simpUserRegistry.getIfAvailable();
        if (reg == null) {
            return -1;
        }
        try {
            return reg.getUsers().size();
        } catch (Exception ex) {
            return -1;
        }
    }

    private Map<String, Object> queueProps(String queue) {
        Properties p = rabbitAdmin.getQueueProperties(queue);
        Map<String, Object> map = new LinkedHashMap<>();
        if (p != null) {
            for (String name : p.stringPropertyNames()) {
                map.put(name, p.getProperty(name));
            }
        }
        return map;
    }
}
