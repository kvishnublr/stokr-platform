package com.stokr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Execution Service entry point for the V3 split runtime.
 *
 * The module still depends on shared OMS/risk/strategy domain packages while
 * the migration is in progress, so it scans the platform packages and relies
 * on service-level feature flags to keep strategy publishing disabled here.
 */
@SpringBootApplication(scanBasePackages = "com.stokr")
@EnableScheduling
public class ExecutionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExecutionServiceApplication.class, args);
    }
}
