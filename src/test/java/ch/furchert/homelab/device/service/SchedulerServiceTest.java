package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.entity.Schedule;
import ch.furchert.homelab.device.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SchedulerService}.
 *
 * <p>All dependencies are mocked. The internal {@code activeTasks} map is accessed
 * via {@link ReflectionTestUtils} to verify registration and cancellation without
 * requiring a real {@link ThreadPoolTaskScheduler} thread pool.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private MqttClientService mqttClientService;

    @Mock
    private ThreadPoolTaskScheduler taskScheduler;

    private SchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        schedulerService = new SchedulerService(scheduleRepository, mqttClientService, taskScheduler);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, ScheduledFuture<?>> activeTasks() {
        return (ConcurrentHashMap<Long, ScheduledFuture<?>>) ReflectionTestUtils.getField(
                schedulerService, "activeTasks");
    }

    private Schedule buildSchedule(Long id, String field, String cron) {
        return Schedule.builder()
                .id(id)
                .field(field)
                .payload("{\"" + capitalize(field) + "State\": 1}")
                .cronExpression(cron)
                .active(true)
                .build();
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -------------------------------------------------------------------------
    // New schedule → task registered
    // -------------------------------------------------------------------------

    @Test
    void reloadSchedules_newSchedule_registersTaskInActiveTasks() {
        Schedule schedule = buildSchedule(1L, "light", "0 0 8 * * *");
        when(scheduleRepository.findByActiveTrue()).thenReturn(List.of(schedule));

        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> mockFuture = mock(ScheduledFuture.class);
        // doReturn avoids wildcard capture mismatch that when/thenReturn causes
        doReturn(mockFuture)
                .when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));

        schedulerService.reloadSchedules();

        assertThat(activeTasks()).containsKey(1L);
        assertThat((Object) activeTasks().get(1L)).isSameAs(mockFuture);
        verify(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    // -------------------------------------------------------------------------
    // Removed schedule → task cancelled
    // -------------------------------------------------------------------------

    @Test
    void reloadSchedules_removedSchedule_cancelsPreviousTask() {
        // Pre-populate activeTasks with an existing task for id=1
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> existingFuture = mock(ScheduledFuture.class);
        activeTasks().put(1L, existingFuture);

        // No schedules active any more
        when(scheduleRepository.findByActiveTrue()).thenReturn(List.of());

        schedulerService.reloadSchedules();

        // The future must have been cancelled with interrupt=false
        verify(existingFuture).cancel(false);
        assertThat(activeTasks()).doesNotContainKey(1L);
    }

    // -------------------------------------------------------------------------
    // Invalid cron → skipped, no exception
    // -------------------------------------------------------------------------

    @Test
    void reloadSchedules_invalidCron_skipsEntryWithoutException() {
        Schedule badSchedule = buildSchedule(2L, "rain", "NOT_A_CRON");
        when(scheduleRepository.findByActiveTrue()).thenReturn(List.of(badSchedule));

        // new CronTrigger("NOT_A_CRON") throws IllegalArgumentException at construction time
        // before taskScheduler.schedule() is ever called. The service must catch it silently.
        assertThatNoException().isThrownBy(() -> schedulerService.reloadSchedules());

        // Task must NOT have been added to activeTasks
        assertThat(activeTasks()).doesNotContainKey(2L);
        // The scheduler was never asked to run anything
        verifyNoInteractions(taskScheduler);
    }

    // -------------------------------------------------------------------------
    // Idempotency — already running task is not re-registered
    // -------------------------------------------------------------------------

    @Test
    void reloadSchedules_alreadyRunningTask_isNotReRegistered() {
        Schedule schedule = buildSchedule(1L, "light", "0 0 8 * * *");
        when(scheduleRepository.findByActiveTrue()).thenReturn(List.of(schedule));

        @SuppressWarnings("unchecked")
        ScheduledFuture<?> existingFuture = mock(ScheduledFuture.class);
        activeTasks().put(1L, existingFuture);

        schedulerService.reloadSchedules();

        // scheduler.schedule must NOT be called again for an already-tracked task
        verifyNoInteractions(taskScheduler);
        // The existing future must remain unchanged
        assertThat((Object) activeTasks().get(1L)).isSameAs(existingFuture);
    }
}
