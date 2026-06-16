package com.stokr.broker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrokerAccountRepository extends JpaRepository<BrokerAccount, Long> {

    List<BrokerAccount> findByUserId(Long userId);

    Optional<BrokerAccount> findByIdAndUserId(Long id, Long userId);

    List<BrokerAccount> findByStatus(String status);

    List<BrokerAccount> findByBrokerNameAndStatus(String brokerName, String status);
}
