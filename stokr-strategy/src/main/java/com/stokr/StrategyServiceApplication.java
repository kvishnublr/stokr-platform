package com.stokr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Strategy Service - Standalone microservice for trading signal generation.
 *
 * Architecture:
 * - Runs independently from core trading system
 * - Scans market data every 30 seconds
 * - Publishes signals to RabbitMQ
 * - Can be deployed and scaled independently
 *
 * Port: 8081
 * Message Queue: RabbitMQ (rabbitmq:5672)
 * Market Data: REST call to market-data-service:8080
 */
@SpringBootApplication
@EnableScheduling
public class StrategyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(StrategyServiceApplication.class, args);
    }
}
