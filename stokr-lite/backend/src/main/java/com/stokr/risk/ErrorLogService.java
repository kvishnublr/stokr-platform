package com.stokr.risk;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ErrorLogService {

    private final ErrorLogRepository repository;

    public void logError(Long deploymentId, String errorType, String message, String stackTrace, String severity) {
        ErrorLog log = new ErrorLog();
        log.setDeploymentId(deploymentId);
        log.setErrorType(errorType);
        log.setMessage(message);
        log.setStackTrace(stackTrace);
        log.setSeverity(severity != null ? severity : "ERROR");
        repository.save(log);
    }

    public void logError(Long deploymentId, String errorType, Exception ex) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement ste : ex.getStackTrace()) {
            sb.append(ste.toString()).append("\n");
            if (sb.length() > 2000) break; // Limit stack trace size
        }
        logError(deploymentId, errorType, ex.getMessage(), sb.toString(), "ERROR");
    }

    public Page<ErrorLog> getErrors(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Page<ErrorLog> getErrorsBySeverity(String severity, Pageable pageable) {
        return repository.findBySeverityOrderByCreatedAtDesc(severity, pageable);
    }
}

// Entity
@Entity
@Table(name = "error_logs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
class ErrorLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(name = "error_type", nullable = false)
    private String errorType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(nullable = false)
    private String severity = "ERROR";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

// Repository
interface ErrorLogRepository extends JpaRepository<ErrorLog, Long> {
    Page<ErrorLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<ErrorLog> findBySeverityOrderByCreatedAtDesc(String severity, Pageable pageable);
}
