package com.stokr.execution.orphan.service;

import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.orphan.domain.BrokerPositionObservation;
import com.stokr.execution.orphan.repository.BrokerPositionObservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerPositionSnapshotService {

    private final BrokerPositionTruthService brokerPositionTruthService;
    private final BrokerPositionObservationRepository observationRepository;

    public void recordSnapshot(UUID userId) {
        try {
            log.debug("broker.positions.snapshot.recording user_id={}", userId);
            // Placeholder: Implementation uses BrokerPositionTruthService.snapshot() to fetch current positions
            // and persist to broker_position_observations table
        } catch (Exception e) {
            log.error("broker.positions.snapshot.failed user_id={}", userId, e);
        }
    }

    public List<BrokerPositionObservation> getLatestSnapshot(UUID userId) {
        try {
            // Placeholder: Returns latest snapshot for each symbol by observation_time DESC
            return observationRepository.findByUserIdOrderByObservationTimeDesc(userId).isEmpty() ?
                    Collections.emptyList() : observationRepository.findByUserIdOrderByObservationTimeDesc(userId);
        } catch (Exception e) {
            log.error("broker.positions.latest.failed user_id={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<BrokerPositionObservation> getPositionHistory(UUID userId, String symbol,
                                                               Instant from, Instant to) {
        try {
            return observationRepository.findPositionHistory(userId, symbol, from, to);
        } catch (Exception e) {
            log.error("broker.positions.history.failed user_id={} symbol={}", userId, symbol, e);
            return Collections.emptyList();
        }
    }
}
