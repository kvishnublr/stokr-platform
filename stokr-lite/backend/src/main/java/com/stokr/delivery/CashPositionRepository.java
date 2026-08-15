package com.stokr.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CashPositionRepository extends JpaRepository<CashPosition, Long> {

    @Query("SELECT c FROM CashPosition c WHERE c.status = 'OPEN' ORDER BY c.enteredAt DESC")
    List<CashPosition> findAllOpen();

    @Query("SELECT c FROM CashPosition c WHERE c.status IN ('CLOSED', 'EXITED') ORDER BY c.exitedAt DESC")
    List<CashPosition> findAllClosed();

    @Query("SELECT COUNT(c) FROM CashPosition c WHERE c.status = 'OPEN'")
    long countAllOpen();

    List<CashPosition> findAllByOrderByEnteredAtDesc();
}
