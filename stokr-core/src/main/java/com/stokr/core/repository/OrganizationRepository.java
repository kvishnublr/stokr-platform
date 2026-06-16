package com.stokr.core.repository;

import com.stokr.core.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    Optional<Organization> findByIdAndDeletedFalse(UUID id);

    boolean existsBySlug(String slug);

    List<Organization> findByDeletedFalse();

    @Query("SELECT COUNT(u) FROM User u WHERE u.organizationId = :orgId AND u.deleted = false")
    int countActiveUsers(UUID orgId);

    @Query("SELECT COUNT(s) FROM Strategy s WHERE s.organizationId = :orgId AND s.deleted = false")
    int countStrategies(UUID orgId);
}
