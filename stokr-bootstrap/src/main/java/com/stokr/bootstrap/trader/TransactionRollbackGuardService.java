package com.stokr.bootstrap.trader;

import com.stokr.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionRollbackGuardService {

    private final TraderTerminalControlService traderTerminalControlService;

    /**
     * Wraps TraderTerminalControlService.execute() in a separate transaction (REQUIRES_NEW)
     * to prevent exceptions from marking the outer transaction for rollback.
     *
     * When an exception occurs inside a @Transactional method:
     * 1. Spring marks the transaction for rollback (even if caught)
     * 2. On exit, Spring tries to commit but detects rollback-only status
     * 3. Throws UnexpectedRollbackException
     *
     * PROPAGATION_REQUIRES_NEW creates a new independent transaction for execute(),
     * isolating any exceptions from affecting the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> executeWithGuard(UUID userId, String token) {
        try {
            return traderTerminalControlService.execute(userId, token);
        } catch (BadRequestException ex) {
            log.warn("trader.terminal_control.execute_failed userId={} err={}", userId, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("trader.terminal_control.execute_unexpected userId={} err={}", userId, ex.getMessage(), ex);
            throw new BadRequestException("Exit operation failed: " + ex.getMessage());
        }
    }
}
