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

    /**
     * Fingerprint of each registered schedule: {@code cronExpression|payload}.
     * Used to detect when an existing schedule's definition changes so that the
     * old task is cancelled and a new one is registered with the updated values.
     */
    private final ConcurrentHashMap<Long, String> taskFingerprints = new ConcurrentHashMap<>();

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

        // Cancel tasks that are no longer in the active set or whose definition changed
        activeTasks.keySet().removeIf(id -> {
            Schedule existing = currentMap.get(id);
            boolean removed = existing == null;
            boolean changed = !removed
                    && !fingerprint(existing).equals(taskFingerprints.getOrDefault(id, ""));
            if (removed || changed) {
                ScheduledFuture<?> future = activeTasks.get(id);
                if (future != null) {
                    future.cancel(false);
                }
                taskFingerprints.remove(id);
                log.debug("Cancelled scheduler task for schedule id={} ({})",
                        id, removed ? "removed" : "definition changed");
                return true;
            }
            return false;
        });

        // Register tasks for new schedules (or re-register after a definition change)
        for (Schedule schedule : currentMap.values()) {
            if (activeTasks.containsKey(schedule.getId())) {
                // Task already running with same definition — nothing to do
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
                    taskFingerprints.put(schedule.getId(), fingerprint(schedule));
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

    /**
     * Returns a fingerprint string that captures the schedule fields that define
     * what the task does and when. A change in any of these fields requires the
     * old task to be cancelled and a new one registered.
     */
    private static String fingerprint(Schedule schedule) {
        return schedule.getCronExpression() + "|" + schedule.getField() + "|" + schedule.getPayload();
    }
}
