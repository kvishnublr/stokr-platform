package com.stokr.auth.repository;

import com.stokr.auth.domain.AuthRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AuthRoleRepository extends JpaRepository<AuthRole, UUID> {

    Optional<AuthRole> findByNameAndDeletedFalse(String name);
}
