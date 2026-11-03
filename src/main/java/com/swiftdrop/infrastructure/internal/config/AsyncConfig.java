package com.swiftdrop.infrastructure.internal.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Map;

/**
 * Turns on {@code @Async} (e.g. account emails are sent in the background). Spring Boot's
 * {@code applicationTaskExecutor} runs the work and picks up the {@link TaskDecorator} below, which copies the log
 * MDC (the request's correlation id) into the background thread.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    TaskDecorator mdcTaskDecorator() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(context);
                }
                try {
                    task.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }
}
