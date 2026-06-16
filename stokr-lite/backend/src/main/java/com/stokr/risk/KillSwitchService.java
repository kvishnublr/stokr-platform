package com.stokr.risk;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class KillSwitchService {

    private final KillSwitchRepository repository;

    public KillSwitchService(KillSwitchRepository repository) {
        this.repository = repository;
    }

    public boolean isActive() {
        return repository.findTopByOrderByIdAsc()
                .map(KillSwitchState::isActive)
                .orElse(false);
    }

    @Transactional
    public void activate(Long userId, String reason) {
        KillSwitchState state = repository.findTopByOrderByIdAsc()
                .orElse(new KillSwitchState());
        state.setActive(true);
        state.setActivatedBy(userId);
        state.setActivatedAt(Instant.now());
        state.setReason(reason);
        state.setDeactivatedAt(null);
        repository.save(state);
    }

    @Transactional
    public void deactivate() {
        repository.findTopByOrderByIdAsc().ifPresent(state -> {
            state.setActive(false);
            state.setDeactivatedAt(Instant.now());
            repository.save(state);
        });
    }

    public KillSwitchState getState() {
        return repository.findTopByOrderByIdAsc()
                .orElse(new KillSwitchState());
    }
}

// Entity
@Entity
@Table(name = "kill_switch_state")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
class KillSwitchState {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "activated_by")
    private Long activatedBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column
    private String reason;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;
}

// Repository
interface KillSwitchRepository extends JpaRepository<KillSwitchState, Long> {
    @Query("SELECT k FROM KillSwitchState k ORDER BY k.id ASC LIMIT 1")
    java.util.Optional<KillSwitchState> findTopByOrderByIdAsc();
}
