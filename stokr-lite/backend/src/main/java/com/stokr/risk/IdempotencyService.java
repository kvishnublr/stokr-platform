package com.stokr.risk;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * Check if an order with this key was already placed.
     * Returns true if this is a NEW request (not duplicate).
     */
    @Transactional
    public boolean tryAcquire(String deploymentId, String symbol, String side) {
        String key = hash(deploymentId + ":" + symbol + ":" + side);

        if (repository.existsByKeyHash(key)) {
            return false; // Duplicate
        }

        IdempotencyKey entry = new IdempotencyKey();
        entry.setKeyHash(key);
        entry.setExpiresAt(Instant.now().plusSeconds(3600)); // 1 hour TTL
        repository.save(entry);
        return true;
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
    boolean existsByKeyHash(String keyHash);
    Optional<IdempotencyKey> findByKeyHash(String keyHash);
}
