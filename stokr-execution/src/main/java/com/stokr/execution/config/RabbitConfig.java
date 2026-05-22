package com.stokr.execution.config;

import com.stokr.common.pipeline.PipelineQueues;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.stokr.common.pipeline.messages");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public Queue strategySignalQueue() {
        return QueueBuilder.durable(PipelineQueues.STRATEGY_SIGNAL).build();
    }

    @Bean
    public Queue strategySignalDlq() {
        return QueueBuilder.durable(PipelineQueues.STRATEGY_SIGNAL_DLQ).build();
    }

    @Bean
    public Queue omsOrderQueue() {
        // Dead-letter to OMS_ORDER_DLQ so failed/rejected messages never requeue and block the main queue.
        return QueueBuilder.durable(PipelineQueues.OMS_ORDER)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", PipelineQueues.OMS_ORDER_DLQ)
                .build();
    }

    @Bean
    public Queue omsOrderDlq() {
        return QueueBuilder.durable(PipelineQueues.OMS_ORDER_DLQ).build();
    }

    @Bean
    public Queue executionQueue() {
        return QueueBuilder.durable(PipelineQueues.EXECUTION).build();
    }

    @Bean
    public Queue executionDlq() {
        return QueueBuilder.durable(PipelineQueues.EXECUTION_DLQ).build();
    }
}
