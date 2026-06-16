package com.stokr.oms;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByDeploymentId(Long deploymentId);
    Page<Order> findByDeploymentId(Long deploymentId, Pageable pageable);
    List<Order> findByDeploymentIdAndStatus(Long deploymentId, String status);
    List<Order> findByStatus(String status);
    Optional<Order> findByBrokerOrderId(String brokerOrderId);
}
