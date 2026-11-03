package ch.furchert.homelab.device.integration;

import ch.furchert.homelab.device.AbstractIntegrationTest;
import ch.furchert.homelab.device.dto.ScheduleDto;
import ch.furchert.homelab.device.dto.ScheduleRequest;
import ch.furchert.homelab.device.entity.Device;
import ch.furchert.homelab.device.repository.DeviceRepository;
import ch.furchert.homelab.device.repository.ScheduleRepository;
import ch.furchert.homelab.device.service.InfluxWriterService;
import ch.furchert.homelab.device.service.ScheduleService;
import ch.furchert.homelab.device.service.SchedulerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schedules API writes (#41) take effect in the real scheduler immediately: the poll interval is
 * pushed out of the way and the test never calls {@code reloadSchedules()} itself, so only the
 * write path can register or cancel tasks. Real PostgreSQL and Mosquitto via Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "app.mqtt.client-id=device-service-schedule-api-it",
        "app.scheduler.poll-interval=3600000"
})
class ScheduleApiIntegrationTest extends AbstractIntegrationTest {

    /** 03:00 daily — registers a task that cannot fire while the test runs. */
    private static final String NEVER_DURING_TEST = "0 0 3 * * *";

    @MockitoBean
    InfluxWriterService influxWriterService;

    @Autowired
    ScheduleService scheduleService;

    @Autowired
    SchedulerService schedulerService;

    @Autowired
    ScheduleRepository scheduleRepository;

    @Autowired
    DeviceRepository deviceRepository;

    @BeforeEach
    void setUp() {
        scheduleRepository.deleteAll();
        schedulerService.reloadSchedules();
        if (!deviceRepository.existsByName("terra1")) {
            deviceRepository.save(Device.builder().name("terra1").build());
        }
    }

    @AfterEach
    void cleanUp() {
        scheduleRepository.deleteAll();
        schedulerService.reloadSchedules();
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, ScheduledFuture<?>> activeTasks() {
        return (ConcurrentHashMap<Long, ScheduledFuture<?>>)
                ReflectionTestUtils.getField(schedulerService, "activeTasks");
    }

    private static ScheduleRequest request(String cron, boolean active) {
        return new ScheduleRequest("terra1", "light", 1, cron, active);
    }

    @Test
    void create_registersTheTaskImmediately() {
        ScheduleDto created = scheduleService.create(request(NEVER_DURING_TEST, true));

        assertThat(activeTasks()).containsKey(created.id());
        assertThat(created.payload()).isEqualTo("{\"LightState\":1}");
        assertThat(created.nextRun()).isNotNull();
    }

    @Test
    void update_withNewCron_replacesTheTask() {
        ScheduleDto created = scheduleService.create(request(NEVER_DURING_TEST, true));
        ScheduledFuture<?> before = activeTasks().get(created.id());

        scheduleService.update(created.id(), request("0 0 4 * * *", true));

        ScheduledFuture<?> after = activeTasks().get(created.id());
        assertThat((Object) after).isNotNull().isNotSameAs(before);
        assertThat(before.isCancelled()).isTrue();
    }

    @Test
    void update_toInactive_cancelsTheTask() {
        ScheduleDto created = scheduleService.create(request(NEVER_DURING_TEST, true));
        ScheduledFuture<?> before = activeTasks().get(created.id());

        scheduleService.update(created.id(), request(NEVER_DURING_TEST, false));

        assertThat(activeTasks()).doesNotContainKey(created.id());
        assertThat(before.isCancelled()).isTrue();
    }

    @Test
    void delete_cancelsTheTask() {
        ScheduleDto created = scheduleService.create(request(NEVER_DURING_TEST, true));
        ScheduledFuture<?> before = activeTasks().get(created.id());

        scheduleService.delete(created.id());

        assertThat(activeTasks()).doesNotContainKey(created.id());
        assertThat(before.isCancelled()).isTrue();
        assertThat(scheduleRepository.findById(created.id())).isEmpty();
    }
}
