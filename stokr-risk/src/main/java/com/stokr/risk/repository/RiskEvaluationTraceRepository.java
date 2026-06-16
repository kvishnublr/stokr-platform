package com.stokr.risk.repository;

import com.stokr.risk.domain.RiskEvaluationTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RiskEvaluationTraceRepository extends JpaRepository<RiskEvaluationTrace, UUID> {
}
