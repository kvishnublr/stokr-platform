package com.stokr.oms.journal.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "event_stream_counters")
public class EventStreamCounter extends BaseEntity {

    @Column(name = "stream_type", nullable = false, length = 32)
    private String streamType;

    @Column(name = "stream_key", nullable = false, length = 256)
    private String streamKey;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;
}
