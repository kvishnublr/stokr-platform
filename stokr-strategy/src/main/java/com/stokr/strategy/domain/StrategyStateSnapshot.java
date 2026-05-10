package com.stokr.strategy.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "strategy_state_snapshots")
public class StrategyStateSnapshot extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private StrategyInstance instance;

    @Column(name = "sequence_num", nullable = false)
    private long sequenceNum;

    @Column(name = "state_json", nullable = false, columnDefinition = "text")
    private String stateJson;

    @Column(name = "indicator_json", columnDefinition = "text")
    private String indicatorJson;

    @Column(name = "replay_checkpoint", length = 128)
    private String replayCheckpoint;
}
