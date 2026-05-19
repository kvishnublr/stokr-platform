package com.stokr.bootstrap.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Default async executor used by @Async methods.
 * Sized for 2000-stock live feed: peak ~2000 ticks/sec at market open.
 * CallerRunsPolicy: if queue fills, the WebSocket thread processes the tick
 * inline rather than dropping it — provides natural back-pressure.
 */
@Configuration
public class AsyncExecutionConfig {

    @Bean(name = "taskExecutor")
    @Primary
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("stokr-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
