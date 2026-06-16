package com.stokr.oms.journal.repository;

import com.stokr.oms.journal.domain.EventStreamCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EventStreamCounterRepository extends JpaRepository<EventStreamCounter, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EventStreamCounter c where c.streamType = :st and c.streamKey = :sk and c.deleted = false")
    Optional<EventStreamCounter> lockByStream(@Param("st") String streamType, @Param("sk") String streamKey);
}
