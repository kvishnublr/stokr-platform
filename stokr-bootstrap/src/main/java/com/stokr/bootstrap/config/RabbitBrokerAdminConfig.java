package com.stokr.bootstrap.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot may expose only {@code AmqpAdmin}; some components need a concrete
 * {@link RabbitAdmin} (e.g. queue property introspection).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RabbitAdmin.class)
public class RabbitBrokerAdminConfig {

    @Bean
    @ConditionalOnMissingBean(RabbitAdmin.class)
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }
}
