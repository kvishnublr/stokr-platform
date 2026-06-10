package com.stokr.execution.orphan.service;

import com.stokr.execution.broker.service.BrokerPositionTruthService;
import com.stokr.execution.orphan.domain.BrokerPositionObservation;
import com.stokr.execution.orphan.repository.BrokerPositionObservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerPositionSnapshotService {

    private final BrokerPositionTruthService brokerPositionTruthService;
    private final BrokerPositionObservationRepository observationRepository;

    public void recordSnapshot(UUID userId) {
        try {
            var brokerPositions = brokerPositionTruthService.getBrokerPositions(userId);

            List<BrokerPositionObservation> observations = brokerPositions.stream()
                    .map(pos -> {
                        BrokerPositionObservation obs = new BrokerPositionObservation();
                        obs.setId(UUID.randomUUID());
                        obs.setCreatedAt(OffsetDateTime.now());
                        obs.setUpdatedAt(OffsetDateTime.now());
                        obs.setVersion(0L);
                        obs.setUserId(userId);
                        obs.setSymbol(pos.getSymbol());
                        obs.setBrokerQuantity(pos.getQuantity());
                        obs.setBrokerEntryPrice(pos.getEntryPrice());
                        obs.setBrokerEntryTime(pos.getEntryTime());
                        obs.setObservationTime(OffsetDateTime.now());
                        obs.setIsOrphaned(false);
                        return obs;
                    })
                    .collect(Collectors.toList());

            if (!observations.isEmpty()) {
                observationRepository.saveAll(observations);
                log.info("broker.positions.snapshot.recorded user_id={} count={}", userId, observations.size());
            }
        } catch (Exception e) {
            log.error("broker.positions.snapshot.failed user_id={}", userId, e);
        }
    }

    public List<BrokerPositionObservation> getLatestSnapshot(UUID userId) {
        return observationRepository.findByUserIdOrderByObservationTimeDesc(userId).stream()
                .collect(Collectors.groupingBy(BrokerPositionObservation::getSymbol))
                .values()
                .stream()
                .map(list -> list.stream()
                        .max(Comparator.comparing(BrokerPositionObservation::getObservationTime))
                        .orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<BrokerPositionObservation> getPositionHistory(UUID userId, String symbol,
                                                               OffsetDateTime from, OffsetDateTime to) {
        return observationRepository.findPositionHistory(userId, symbol, from, to);
    }
}
