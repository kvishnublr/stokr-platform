package com.stokr.oms.journal.repository;

import com.stokr.oms.journal.domain.ReplayCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReplayCheckpointRepository extends JpaRepository<ReplayCheckpoint, UUID> {

    Optional<ReplayCheckpoint> findByStreamTypeAndStreamKeyAndDeletedFalse(String streamType, String streamKey);
}
