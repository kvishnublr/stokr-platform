package com.stokr.risk.service;

import com.stokr.risk.domain.RiskEvent;
import com.stokr.risk.repository.RiskEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RiskEventRecorder {

    private final RiskEventRepository riskEventRepository;

    @Transactional
    public void record(UUID userId, UUID orderId, String ruleCode, String result, String message) {
        RiskEvent ev = new RiskEvent();
        ev.setUserId(userId);
        ev.setOrderId(orderId);
        ev.setRuleCode(ruleCode);
        ev.setResult(result);
        ev.setMessage(message);
        riskEventRepository.save(ev);
    }
}
