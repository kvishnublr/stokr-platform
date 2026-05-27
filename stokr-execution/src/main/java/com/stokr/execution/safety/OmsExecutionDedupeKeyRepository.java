package com.stokr.execution.safety;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OmsExecutionDedupeKeyRepository extends JpaRepository<OmsExecutionDedupeKey, Long> {

    @Query("""
            select d from OmsExecutionDedupeKey d
            where d.executionKey = :key and d.expiresAt > :now
            """)
    Optional<OmsExecutionDedupeKey> findActiveByKey(@Param("key") String key, @Param("now") Instant now);

    @Modifying
    @Query("delete from OmsExecutionDedupeKey d where d.expiresAt <= :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
