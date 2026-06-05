package io.stokr.bootstrap.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "position_orphan_detection_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionOrphanDetectionLog {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @Column(name = "check_time")
    private LocalDateTime checkTime;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "position_id")
    private UUID positionId;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "broker_has_position")
    private Boolean brokerHasPosition;

    @Column(name = "oms_has_position")
    private Boolean omsHasPosition;

    @Column(name = "no_entry_signal_found")
    private Boolean noEntrySignalFound;

    @Column(name = "no_entry_order_found")
    private Boolean noEntryOrderFound;

    @Column(name = "owner_type_recorded")
    private String ownerTypeRecorded;

    @Column(name = "is_orphan")
    private Boolean isOrphan;

    @Column(name = "synthetic_exit_created")
    private Boolean syntheticExitCreated;

    @Column(name = "synthetic_exit_id")
    private UUID syntheticExitId;

    @Column(name = "ghost_removed")
    private Boolean ghostRemoved;
}
