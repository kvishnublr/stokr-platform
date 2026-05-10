package com.stokr.bootstrap;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.stokr")
@EnableJpaRepositories(basePackages = "com.stokr")
@EntityScan(basePackages = "com.stokr")
@EnableRabbit
@EnableScheduling
@EnableAsync
public class StokrApplication {

    public static void main(String[] args) {
        SpringApplication.run(StokrApplication.class, args);
    }
}
