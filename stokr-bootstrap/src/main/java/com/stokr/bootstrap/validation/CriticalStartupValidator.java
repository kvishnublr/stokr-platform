package com.stokr.bootstrap.validation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Slf4j
@Component
@RequiredArgsConstructor
public class CriticalStartupValidator implements ApplicationRunner {

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("🔍 Starting critical system validation...");
        try {
            validateDatabaseConnectivity();
            log.info("✅ All critical systems are operational");
        } catch (Exception e) {
            log.error("❌ CRITICAL: Startup validation failed - application will not be ready for traffic", e);
            throw e;
        }
    }

    private void validateDatabaseConnectivity() throws Exception {
        log.info("  Checking database connectivity...");
        try (Connection conn = dataSource.getConnection()) {
            if (!conn.isValid(5)) {
                throw new IllegalStateException("Database connection test failed - connection.isValid() returned false");
            }
            log.info("    ✅ Database: OK");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot connect to database. Check DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD", e);
        }
    }
}
