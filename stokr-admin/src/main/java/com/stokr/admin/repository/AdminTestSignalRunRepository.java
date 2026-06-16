package com.stokr.admin.repository;

import com.stokr.admin.domain.AdminTestSignalRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AdminTestSignalRunRepository extends JpaRepository<AdminTestSignalRun, UUID> {

    Page<AdminTestSignalRun> findAllByDeletedFalseOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select r from AdminTestSignalRun r
            where r.deleted = false
              and r.autoSquareOffDueAt is not null
              and r.autoSquareOffDueAt <= :now
              and (r.squareOffStatus is null or r.squareOffStatus in ('PENDING', 'FAILED'))
            order by r.autoSquareOffDueAt asc
            """)
    List<AdminTestSignalRun> findSquareOffDue(Instant now, Pageable pageable);
}
