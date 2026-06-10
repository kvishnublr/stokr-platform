package com.stokr.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.support.converter.ClassPathxmlApplicationContext;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for RabbitMQ message conversion.
 * Ensures messages are serialized/deserialized as JSON using Jackson.
 */
@Configuration
public class MessageConverterConfig {

    @Bean
    public MessageConverter jackson2MessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setJsonObjectMapper(objectMapper);
        return converter;
    }
}
