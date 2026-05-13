package com.stokr.bootstrap.env;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.util.StringUtils;

/**
 * Copies {@code SPRING_MAIL_HOST} into {@code spring.mail.host} when the latter is unset or blank.
 * <p>
 * Imported {@code .env} files register literal keys (no relaxed binding), so {@code SPRING_MAIL_HOST}
 * would not populate {@code spring.mail.host} for {@code JavaMailSender} auto-configuration. OS
 * environment variables already map {@code SPRING_MAIL_HOST} to {@code spring.mail.host} without this
 * bridge.
 */
public class SpringMailHostBridgeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "stokrSpringMailHostBridge";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(PROPERTY_SOURCE_NAME);
        String bridged = environment.getProperty("SPRING_MAIL_HOST");
        String host = environment.getProperty("spring.mail.host");
        if (!StringUtils.hasText(host) && StringUtils.hasText(bridged)) {
            sources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of("spring.mail.host", bridged.trim())));
        }
    }

    /**
     * Run after {@code spring.config.import} (e.g. optional {@code .env}) so {@code SPRING_MAIL_HOST} from file is visible.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
