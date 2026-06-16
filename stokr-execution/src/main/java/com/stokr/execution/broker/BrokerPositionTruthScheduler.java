package com.stokr.execution.broker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class BrokerPositionTruthScheduler {

    private final BrokerPositionTruthService brokerPositionTruthService;

    @Scheduled(fixedDelayString = "${stokr.broker-truth.poll-ms:5000}")
    public void pollBrokerTruth() {
        for (UUID userId : brokerPositionTruthService.usersNeedingSync()) {
            try {
                brokerPositionTruthService.syncUser(userId);
            } catch (Exception ex) {
                log.debug("broker.truth.poll_failed user={} {}", userId, ex.getMessage());
            }
        }
    }
}
