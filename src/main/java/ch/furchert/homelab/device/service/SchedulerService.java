package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.entity.Schedule;
import ch.furchert.homelab.device.repository.ScheduleRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

/**
 * Manages dynamic MQTT publish tasks driven by cron schedules stored in the database.
 *
 * <p>On startup and periodically (every {@code app.scheduler.poll-interval} ms) this service:
 * <ol>
 *   <li>Fetches all active schedules from the database.</li>
 *   <li>Cancels tasks whose schedule has been removed or deactivated.</li>
 *   <li>Registers new {@link CronTrigger} tasks for schedules not yet running.</li>
 * </ol>
 *
 * <p>Each task publishes a message to {@code terraGeneral/{field}/schedule} at the time
 * defined by the schedule's cron expression.
 */
@Slf4j
@Service
public class SchedulerService {

    private final ScheduleRepository scheduleRepository;
    private final MqttClientService mqttClientService;
    private final ThreadPoolTaskScheduler taskScheduler;

    /** Tracks the running {@link ScheduledFuture} for each active schedule by its database ID. */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    public SchedulerService(ScheduleRepository scheduleRepository,
                            MqttClientService mqttClientService,
                            ThreadPoolTaskScheduler taskScheduler) {
        this.scheduleRepository = scheduleRepository;
        this.mqttClientService = mqttClientService;
        this.taskScheduler = taskScheduler;
    }

    /**
     * Loads schedules from the database on application startup.
     *
     * <p>Called automatically by Spring after all dependencies are injected, before
     * the first poll-interval fires.
     */
    @PostConstruct
    public void init() {
        reloadSchedules();
    }

    /**
     * Reconciles in-process tasks against the active schedules in the database.
     *
     * <p>Runs every {@code app.scheduler.poll-interval} milliseconds (fixed delay).
     * The first execution after startup is triggered by {@link #init()}.
     */
    @Scheduled(fixedDelayString = "${app.scheduler.poll-interval}")
    public void reloadSchedules() {
        List<Schedule> current = scheduleRepository.findByActiveTrue();
        Map<Long, Schedule> currentMap = current.stream()
                .collect(Collectors.toMap(Schedule::getId, s -> s));

        // Cancel tasks that are no longer in the active set
        activeTasks.keySet().removeIf(id -> {
            if (!currentMap.containsKey(id)) {
                ScheduledFuture<?> future = activeTasks.get(id);
                if (future != null) {
                    future.cancel(false);
                }
                log.debug("Cancelled scheduler task for schedule id={}", id);
                return true;
            }
            return false;
        });

        // Register tasks for newly added schedules
        for (Schedule schedule : currentMap.values()) {
            if (activeTasks.containsKey(schedule.getId())) {
                // Task already running — nothing to do
                continue;
            }

            String topic = "terraGeneral/" + schedule.getField() + "/schedule";
            String payload = schedule.getPayload();

            Runnable task = () -> mqttClientService.publish(topic, payload, 1, false);

            try {
                CronTrigger trigger = new CronTrigger(schedule.getCronExpression());
                ScheduledFuture<?> future = taskScheduler.schedule(task, trigger);
                if (future != null) {
                    activeTasks.put(schedule.getId(), future);
                    log.debug("Registered scheduler task for schedule id={} cron='{}' topic='{}'",
                            schedule.getId(), schedule.getCronExpression(), topic);
                } else {
                    log.warn("Scheduler rejected task for schedule id={}", schedule.getId());
                }
            } catch (IllegalArgumentException e) {
                log.error("Invalid cron expression '{}' for schedule id={}: {}",
                        schedule.getCronExpression(), schedule.getId(), e.getMessage());
            }
        }

        log.info("Schedules reloaded: {} active tasks", activeTasks.size());
    }
}
