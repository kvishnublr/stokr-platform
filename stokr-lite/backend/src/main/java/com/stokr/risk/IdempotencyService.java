package com.stokr.risk;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Check if a non-expired order with this key was already placed.
     * Returns true if this is a NEW request (not duplicate).
     */
    @Transactional
    public boolean tryAcquire(String deploymentId, String symbol, String side) {
        String key = hash(deploymentId + ":" + symbol + ":" + side);

        // Only block if a non-expired key exists (expired = previous day's trade, allow re-entry)
        if (repository.existsByKeyHashAndExpiresAtAfter(key, Instant.now())) {
            return false; // Duplicate
        }

        // Delete any stale expired key for this hash before inserting fresh one
        repository.deleteExpiredByKeyHash(key);

        IdempotencyKey entry = new IdempotencyKey();
        entry.setKeyHash(key);
        entry.setExpiresAt(Instant.now().plusSeconds(3600)); // 1 hour TTL
        repository.save(entry);
        return true;
    }

    /** Purge expired keys every 30 minutes to keep the table clean. */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    @Transactional
    public void purgeExpiredKeys() {
        int deleted = repository.deleteExpired(Instant.now());
        if (deleted > 0) {
            org.slf4j.LoggerFactory.getLogger(IdempotencyService.class)
                .info("Purged {} expired idempotency keys", deleted);
        }
    }

    @Transactional
    public void linkOrder(String keyHash, Long orderId) {
        repository.findByKeyHash(keyHash).ifPresent(key -> {
            key.setOrderId(orderId);
            repository.save(key);
        });
    }

    private String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}

@Entity
@Table(name = "idempotency_keys")
@Getter @Setter
@NoArgsConstructor
class IdempotencyKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}

interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    boolean existsByKeyHashAndExpiresAtAfter(String keyHash, Instant now);
    Optional<IdempotencyKey> findByKeyHash(String keyHash);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :now")
    int deleteExpired(Instant now);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.keyHash = :hash AND k.expiresAt < :#{T(java.time.Instant).now()}")
    void deleteExpiredByKeyHash(String hash);
}
