package com.stokr.execution.orphan.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class EvidenceScore {
    private UUID orphanId;
    private Integer score;
    private String suportingEvidence;
    private Map<String, Integer> evidenceBreakdown;
}
