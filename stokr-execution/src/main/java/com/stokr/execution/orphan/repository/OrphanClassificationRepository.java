package com.stokr.execution.orphan.repository;

import com.stokr.execution.orphan.domain.OrphanClassification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrphanClassificationRepository extends JpaRepository<OrphanClassification, UUID> {

    List<OrphanClassification> findByOrphanId(UUID orphanId);

    Optional<OrphanClassification> findFirstByOrphanIdOrderByCreatedAtDesc(UUID orphanId);

    @Query("SELECT c FROM OrphanClassification c WHERE c.status = 'PENDING_REVIEW' ORDER BY c.evidenceScore DESC")
    List<OrphanClassification> findPendingClassifications();

    @Query("SELECT c FROM OrphanClassification c WHERE c.status = 'DO_NOT_TOUCH' ORDER BY c.createdAt DESC")
    List<OrphanClassification> findDoNotTouchClassifications();

    List<OrphanClassification> findByClassificationType(String classificationType);

    @Query("SELECT c FROM OrphanClassification c WHERE c.evidenceScore >= ?1 AND c.confidenceLevel = ?2")
    List<OrphanClassification> findHighConfidenceClassifications(Integer scoreThreshold, String confidenceLevel);
}
