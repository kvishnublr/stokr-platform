package com.stokr.execution.safety;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "trading_kill_switch_events")
@Getter
@Setter
public class TradingKillSwitchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "trigger_source", nullable = false, length = 64)
    private String triggerSource;

    @Column(length = 512)
    private String reason;

    @Column(name = "flatten_requested", nullable = false)
    private boolean flattenRequested;

    @Column(length = 128)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
