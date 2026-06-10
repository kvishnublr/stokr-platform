package com.stokr.common.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ Configuration for microservices communication.
 *
 * Architecture:
 * - Strategy Service publishes signals to trading.signals queue
 * - Execution Service consumes from trading.signals and publishes to trading.orders
 * - Core Trading System consumes from trading.orders and publishes exits to trading.exits
 * - Audit Logger consumes from trading.audit queue
 */
@Configuration
public class RabbitMQConfig {

    // Queue Names
    public static final String QUEUE_SIGNALS = "trading.signals";
    public static final String QUEUE_SIGNALS_DLQ = "trading.signals.dlq";
    public static final String QUEUE_ORDERS = "trading.orders";
    public static final String QUEUE_ORDERS_DLQ = "trading.orders.dlq";
    public static final String QUEUE_EXITS = "trading.exits";
    public static final String QUEUE_EXITS_DLQ = "trading.exits.dlq";
    public static final String QUEUE_AUDIT = "trading.audit";

    // Exchange Names
    public static final String EXCHANGE_TRADING = "trading.events";
    public static final String EXCHANGE_DLX = "trading.dlx";

    // Routing Keys
    public static final String ROUTING_KEY_SIGNAL = "signal.generated";
    public static final String ROUTING_KEY_ORDER = "order.executed";
    public static final String ROUTING_KEY_EXIT = "exit.signal";
    public static final String ROUTING_KEY_AUDIT = "audit.*";

    // ============ MAIN EXCHANGES ============

    @Bean
    public TopicExchange tradingExchange() {
        return new TopicExchange(EXCHANGE_TRADING, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(EXCHANGE_DLX, true, false);
    }

    // ============ SIGNAL QUEUE (Strategy → Execution) ============

    @Bean
    public Queue signalQueue() {
        return QueueBuilder.durable(QUEUE_SIGNALS)
                .deadLetterExchange(EXCHANGE_DLX)
                .deadLetterRoutingKey(QUEUE_SIGNALS_DLQ)
                .ttl(300000) // 5 minutes TTL (signals expire)
                .maxLength(10000) // Max 10k pending signals
                .build();
    }

    @Bean
    public Binding signalBinding(Queue signalQueue, TopicExchange tradingExchange) {
        return BindingBuilder.bind(signalQueue)
                .to(tradingExchange)
                .with(ROUTING_KEY_SIGNAL);
    }

    @Bean
    public Queue signalDLQ() {
        return QueueBuilder.durable(QUEUE_SIGNALS_DLQ)
                .ttl(604800000) // 7 days retention for DLQ
                .build();
    }

    @Bean
    public Binding signalDLQBinding(Queue signalDLQ, DirectExchange dlxExchange) {
        return BindingBuilder.bind(signalDLQ)
                .to(dlxExchange)
                .with(QUEUE_SIGNALS_DLQ);
    }

    // ============ ORDER QUEUE (Execution → Core Trading) ============

    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(QUEUE_ORDERS)
                .deadLetterExchange(EXCHANGE_DLX)
                .deadLetterRoutingKey(QUEUE_ORDERS_DLQ)
                .maxLength(50000) // Max 50k pending orders
                .build();
    }

    @Bean
    public Binding orderBinding(Queue orderQueue, TopicExchange tradingExchange) {
        return BindingBuilder.bind(orderQueue)
                .to(tradingExchange)
                .with(ROUTING_KEY_ORDER);
    }

    @Bean
    public Queue orderDLQ() {
        return QueueBuilder.durable(QUEUE_ORDERS_DLQ)
                .ttl(604800000) // 7 days retention
                .build();
    }

    @Bean
    public Binding orderDLQBinding(Queue orderDLQ, DirectExchange dlxExchange) {
        return BindingBuilder.bind(orderDLQ)
                .to(dlxExchange)
                .with(QUEUE_ORDERS_DLQ);
    }

    // ============ EXIT QUEUE (Core Trading → Execution) ============

    @Bean
    public Queue exitQueue() {
        return QueueBuilder.durable(QUEUE_EXITS)
                .deadLetterExchange(EXCHANGE_DLX)
                .deadLetterRoutingKey(QUEUE_EXITS_DLQ)
                .ttl(60000) // 1 minute TTL (exit signals expire quickly)
                .maxLength(5000) // Max 5k pending exits
                .build();
    }

    @Bean
    public Binding exitBinding(Queue exitQueue, TopicExchange tradingExchange) {
        return BindingBuilder.bind(exitQueue)
                .to(tradingExchange)
                .with(ROUTING_KEY_EXIT);
    }

    @Bean
    public Queue exitDLQ() {
        return QueueBuilder.durable(QUEUE_EXITS_DLQ)
                .ttl(604800000) // 7 days retention
                .build();
    }

    @Bean
    public Binding exitDLQBinding(Queue exitDLQ, DirectExchange dlxExchange) {
        return BindingBuilder.bind(exitDLQ)
                .to(dlxExchange)
                .with(QUEUE_EXITS_DLQ);
    }

    // ============ AUDIT QUEUE (All → Audit Logger) ============

    @Bean
    public Queue auditQueue() {
        return QueueBuilder.durable(QUEUE_AUDIT)
                .maxLength(100000) // Max 100k audit logs queued
                .build();
    }

    @Bean
    public Binding auditBinding(Queue auditQueue, TopicExchange tradingExchange) {
        return BindingBuilder.bind(auditQueue)
                .to(tradingExchange)
                .with(ROUTING_KEY_AUDIT);
    }
}
