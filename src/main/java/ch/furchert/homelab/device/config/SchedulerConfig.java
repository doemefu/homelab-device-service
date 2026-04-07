package ch.furchert.homelab.device.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Configures the in-process task scheduler used by {@link ch.furchert.homelab.device.service.SchedulerService}
 * to fire periodic MQTT messages based on cron expressions stored in the database.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    /**
     * Task scheduler for MQTT cron tasks.
     *
     * <p>Pool size of 5 allows a small number of concurrent schedule firings while
     * keeping thread overhead minimal. The thread name prefix aids debugging.
     *
     * @return configured {@link ThreadPoolTaskScheduler} bean
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("mqtt-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
