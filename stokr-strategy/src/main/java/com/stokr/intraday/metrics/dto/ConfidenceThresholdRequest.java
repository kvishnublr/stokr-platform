package com.stokr.intraday.metrics.dto;

import lombok.*;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfidenceThresholdRequest {
    private UUID traderId;
    private Integer threshold;  // 60, 70, 80, or 90

    public void validate() {
        if (traderId == null) {
            throw new IllegalArgumentException("Trader ID cannot be null");
        }
        if (threshold == null || (threshold != 60 && threshold != 70 &&
            threshold != 80 && threshold != 90)) {
            throw new IllegalArgumentException(
                "Threshold must be 60, 70, 80, or 90. Got: " + threshold);
        }
    }
}
