package com.stokr.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {
    List<Deployment> findByUserId(Long userId);
    List<Deployment> findByUserIdAndStatus(Long userId, String status);
    List<Deployment> findByStatus(String status);
    java.util.Optional<Deployment> findByIdAndUserId(Long id, Long userId);
}
