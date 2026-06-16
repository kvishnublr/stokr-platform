package com.stokr.core.repository;

import com.stokr.core.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndDeletedFalse(String email);

    Optional<User> findByIdAndDeletedFalse(UUID id);

    boolean existsByEmail(String email);

    List<User> findByOrganizationIdAndDeletedFalse(UUID organizationId);

    @Query("SELECT COUNT(u) FROM User u WHERE u.organizationId = :orgId AND u.deleted = false")
    int countByOrganizationId(UUID organizationId);

    List<User> findByRoleAndDeletedFalse(String role);

    List<User> findByStatusAndDeletedFalse(String status);
}
