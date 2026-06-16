package com.stokr.core.repository;

import com.stokr.core.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
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

    boolean existsByIdAndDeletedFalse(UUID id);
}
