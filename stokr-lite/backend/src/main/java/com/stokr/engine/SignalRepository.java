package com.stokr.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SignalRepository extends JpaRepository<SignalEntity, Long> {

    List<SignalEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<SignalEntity> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    List<SignalEntity> findByDeploymentIdOrderByCreatedAtDesc(Long deploymentId);

    List<SignalEntity> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    List<SignalEntity> findTop50ByUserIdOrUserIdIsNullOrderByCreatedAtDesc(Long userId);

    List<SignalEntity> findTop50ByOrderByCreatedAtDesc();

    long countByUserIdAndCreatedAtAfter(Long userId, Instant since);

    long countByUserIdAndStatus(Long userId, String status);

    long countByUserId(Long userId);

    long countByCreatedAtAfter(Instant since);

    long countByStatus(String status);

    long countAllBy();

    java.util.Optional<SignalEntity> findFirstByDeploymentIdAndSymbolAndStatusOrderByCreatedAtDesc(
        Long deploymentId, String symbol, String status);

    java.util.List<SignalEntity> findByUserIdAndExitTimeAfter(Long userId, java.time.Instant since);
}
