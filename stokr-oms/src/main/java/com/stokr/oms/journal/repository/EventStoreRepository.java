package com.stokr.oms.journal.repository;

import com.stokr.oms.journal.domain.EventStoreEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventStoreRepository extends JpaRepository<EventStoreEntry, UUID> {

    List<EventStoreEntry> findByStreamTypeAndStreamKeyOrderBySequenceNumAsc(String streamType, String streamKey);

    @Query("""
            select e from EventStoreEntry e
            where e.streamType = :st and e.streamKey = :sk and e.sequenceNum <= :maxSeq
            order by e.sequenceNum asc
            """)
    List<EventStoreEntry> findStreamPrefix(
            @Param("st") String streamType,
            @Param("sk") String streamKey,
            @Param("maxSeq") long maxSeqInclusive
    );

    Optional<EventStoreEntry> findTopByStreamTypeAndStreamKeyOrderBySequenceNumDesc(String streamType, String streamKey);

    @Query("select e from EventStoreEntry e where e.backtestRunId = :runId order by e.sequenceNum asc")
    List<EventStoreEntry> findByBacktestRunOrdered(@Param("runId") UUID runId);

    @Query("select e from EventStoreEntry e where e.userId = :userId order by e.createdAt asc")
    List<EventStoreEntry> findByUserOrdered(@Param("userId") UUID userId);

    @Query("select e from EventStoreEntry e where e.strategyKey = :sk order by e.createdAt asc")
    List<EventStoreEntry> findByStrategyKeyOrdered(@Param("sk") String strategyKey);
}
