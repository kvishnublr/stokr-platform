package com.stokr.intraday.metrics.dto;

import lombok.*;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignalCountByThreshold {
    private Long threshold60;
    private Long threshold70;
    private Long threshold80;
    private Long threshold90;
    private Instant timestamp;

    public long getTotalSignals() {
        return (threshold60 != null ? threshold60 : 0) +
               (threshold70 != null ? threshold70 : 0) +
               (threshold80 != null ? threshold80 : 0) +
               (threshold90 != null ? threshold90 : 0);
    }

    @Override
    public String toString() {
        return String.format("Signals - 60: %d, 70: %d, 80: %d, 90: %d (Total: %d)",
            threshold60 != null ? threshold60 : 0,
            threshold70 != null ? threshold70 : 0,
            threshold80 != null ? threshold80 : 0,
            threshold90 != null ? threshold90 : 0,
            getTotalSignals());
    }
}
