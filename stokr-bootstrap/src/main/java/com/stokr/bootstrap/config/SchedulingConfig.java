package com.stokr.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executor;

/**
 * Default Spring scheduler uses a single thread; long equity backfill jobs were
 * blocking CDS currency backfill and other housekeeping tasks.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler stokrTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(6);
        scheduler.setThreadNamePrefix("stokr-scheduled-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(stokrTaskScheduler());
    }
}
