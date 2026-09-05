package ch.furchert.homelab.device.integration;

import ch.furchert.homelab.device.AbstractIntegrationTest;
import ch.furchert.homelab.device.entity.Schedule;
import ch.furchert.homelab.device.repository.ScheduleRepository;
import ch.furchert.homelab.device.service.InfluxWriterService;
import ch.furchert.homelab.device.service.SchedulerService;
import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link SchedulerService} against real dependencies.
 *
 * <p>{@code SchedulerServiceTest} mocks the repository, the MQTT client and the
 * {@code ThreadPoolTaskScheduler}, so no cron ever fires and no row is ever read.
 * This test uses the real ones: schedules are rows in the PostgreSQL container,
 * the trigger runs on the real {@code ThreadPoolTaskScheduler} bean, and the
 * publish lands on the Mosquitto container where an independent Paho subscriber
 * picks it up.
 *
 * <h2>Cron choice per test</h2>
 * Execution tests use {@code * * * * * *} (once per second). Cancellation tests use
 * a daily cron that cannot fire during the test, so {@code future.cancel(false)}
 * always acts on a pending task and {@link ScheduledFuture#isCancelled()} is
 * deterministic — cancelling a cron task in the instant between one run finishing
 * and the next being scheduled would otherwise be a (very narrow) race.
 *
 * <h2>Why cancellation is not asserted on the MQTT topic</h2>
 * Every {@code @SpringBootTest} variant is a separate cached Spring context and all
 * of them stay alive for the rest of the JVM run, so several {@code SchedulerService}
 * instances share this one {@code schedules} table and re-poll it every 60 s. A row
 * this test deactivates may therefore still be running as a task inside another
 * context. "Nothing is published any more" is consequently not a safe assertion,
 * while "this context cancelled and forgot the task" is — so cancellation is checked
 * on {@code activeTasks} and {@link ScheduledFuture#isCancelled()}. Positive
 * assertions are unaffected: a duplicate publish cannot make them fail.
 *
 * <p>Rows are deleted and the scheduler reloaded in both {@code @BeforeEach} and
 * {@code @AfterEach}; without that, a once-per-second schedule would keep firing
 * from every cached context for the remainder of the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
// Own MQTT client id: a broker disconnects the older session when a second client
// reuses an id, and every integration context otherwise shares "device-service-test"
// from src/test/resources/application.yaml.
//
// The poll interval is pushed out of the way so that reloadSchedules() only ever runs
// from the test thread. SchedulerService's registration loop is a check-then-act on
// activeTasks (containsKey ... then put), so a background @Scheduled reload racing a
// manual one could register the same schedule twice and leave the loser's
// ScheduledFuture orphaned in the pool, firing for the rest of the JVM run.
@TestPropertySource(properties = {
        "app.mqtt.client-id=device-service-sched-it",
        "app.scheduler.poll-interval=3600000"
})
class SchedulerIntegrationTest extends AbstractIntegrationTest {

    /** Fires once per second — used where the test needs the task to actually run. */
    private static final String EVERY_SECOND = "* * * * * *";

    /** 03:00 daily — registers a task that cannot fire while the test runs. */
    private static final String NEVER_DURING_TEST = "0 0 3 * * *";

    private static final long AWAIT_SECONDS = 10;

    @MockitoBean
    InfluxWriterService influxWriterService;

    @Autowired
    SchedulerService schedulerService;

    @Autowired
    ScheduleRepository scheduleRepository;

    private MqttClient subscriber;

    @BeforeEach
    void resetSchedulesAndSubscribe() throws MqttException {
        scheduleRepository.deleteAll();
        schedulerService.reloadSchedules();

        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
        subscriber = new MqttClient(brokerUrl, "sched-it-test-subscriber", new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        subscriber.connect(opts);
    }

    @AfterEach
    void cleanUp() throws MqttException {
        scheduleRepository.deleteAll();
        schedulerService.reloadSchedules();

        if (subscriber != null) {
            if (subscriber.isConnected()) {
                subscriber.disconnect();
            }
            subscriber.close();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, ScheduledFuture<?>> activeTasks() {
        return (ConcurrentHashMap<Long, ScheduledFuture<?>>)
                ReflectionTestUtils.getField(schedulerService, "activeTasks");
    }

    private Schedule save(String field, String payload, String cron, boolean active) {
        return scheduleRepository.save(Schedule.builder()
                .deviceName("terra1")
                .field(field)
                .payload(payload)
                .cronExpression(cron)
                .active(active)
                .build());
    }

    /**
     * Subscribes to {@code terraGeneral/{field}/schedule}. Paho's {@code subscribe}
     * blocks until the broker acknowledges, so there is no subscribe/publish race.
     */
    private BlockingQueue<String> subscribeTo(String field) throws MqttException {
        BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
        subscriber.subscribe("terraGeneral/" + field + "/schedule", 1,
                (topic, message) -> payloads.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
        return payloads;
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * An active row in the {@code schedules} table is turned into a running cron task
     * that publishes its payload to {@code terraGeneral/{field}/schedule} on the real
     * broker.
     */
    @Test
    void activeSchedule_isRegisteredAndPublishesToMqtt() throws Exception {
        BlockingQueue<String> published = subscribeTo("itLight");
        Schedule schedule = save("itLight", "{\"LightState\": 1}", EVERY_SECOND, true);

        schedulerService.reloadSchedules();

        assertThat(activeTasks())
                .as("task registered for the active schedule")
                .containsKey(schedule.getId());

        assertThat(published.poll(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("scheduled payload published to terraGeneral/itLight/schedule")
                .isEqualTo("{\"LightState\": 1}");
    }

    /**
     * Setting {@code active = false} cancels that schedule's task on the next reload
     * and leaves every other schedule running.
     */
    @Test
    void deactivatedSchedule_isCancelledAndOthersKeepRunning() {
        Schedule target = save("itTarget", "{\"LightState\": 0}", NEVER_DURING_TEST, true);
        Schedule control = save("itControl", "{\"RainState\": 1}", NEVER_DURING_TEST, true);

        schedulerService.reloadSchedules();

        ScheduledFuture<?> targetFuture = activeTasks().get(target.getId());
        ScheduledFuture<?> controlFuture = activeTasks().get(control.getId());
        // Cast to Object: assertThat(Future) and assertThat(T) are ambiguous for ScheduledFuture.
        assertThat((Object) targetFuture).as("target task registered").isNotNull();
        assertThat((Object) controlFuture).as("control task registered").isNotNull();

        target.setActive(false);
        scheduleRepository.save(target);
        schedulerService.reloadSchedules();

        assertThat(targetFuture.isCancelled())
                .as("deactivated schedule's task was cancelled")
                .isTrue();
        assertThat(activeTasks())
                .as("deactivated schedule is no longer tracked")
                .doesNotContainKey(target.getId());
        assertThat(activeTasks())
                .as("unrelated schedule keeps running")
                .containsKey(control.getId());
        assertThat(controlFuture.isCancelled())
                .as("unrelated schedule's task was not cancelled")
                .isFalse();
    }

    /**
     * Deleting the row cancels the task on the next reload — the removal branch of
     * {@code reloadSchedules()}, which is distinct from the deactivation branch only
     * in how the schedule disappears from {@code findByActiveTrue()}.
     */
    @Test
    void deletedSchedule_isCancelled() {
        Schedule schedule = save("itDeleted", "{\"LightState\": 1}", NEVER_DURING_TEST, true);
        schedulerService.reloadSchedules();

        ScheduledFuture<?> future = activeTasks().get(schedule.getId());
        assertThat((Object) future).as("task registered before deletion").isNotNull();

        scheduleRepository.delete(schedule);
        schedulerService.reloadSchedules();

        assertThat(future.isCancelled()).as("deleted schedule's task was cancelled").isTrue();
        assertThat(activeTasks())
                .as("deleted schedule is no longer tracked")
                .doesNotContainKey(schedule.getId());
    }

    /**
     * Changing the payload changes the schedule's fingerprint
     * ({@code cronExpression|field|payload}), so the running task is replaced rather
     * than left publishing the old payload.
     *
     * <p>This is the one test that cancels a task registered with {@link #EVERY_SECOND}.
     * Spring's {@code ReschedulingRunnable.cancel(false)} is a no-op if it lands while the
     * task is mid-publish, and the runnable then re-schedules itself even though
     * {@code SchedulerService} has already dropped it from {@code activeTasks} — so in a
     * narrow window (a few milliseconds of publishing per one-second period) this can leave
     * an orphaned publisher behind for the remainder of the JVM run. The assertions below
     * are unaffected, and the orphan is harmless: nothing subscribes to
     * {@code terraGeneral/itChanged/schedule}, and {@code MqttMessageParser} rejects
     * {@code terraGeneral} as {@code UNKNOWN} before any write. Cancelling a *pending* task
     * (as every other test here does, via {@link #NEVER_DURING_TEST}) has no such window.
     */
    @Test
    void changedPayload_reRegistersTaskAndPublishesNewPayload() throws Exception {
        BlockingQueue<String> published = subscribeTo("itChanged");
        Schedule schedule = save("itChanged", "{\"LightState\": 0}", EVERY_SECOND, true);

        schedulerService.reloadSchedules();
        ScheduledFuture<?> firstFuture = activeTasks().get(schedule.getId());
        assertThat((Object) firstFuture).as("task registered for the original payload").isNotNull();

        schedule.setPayload("{\"LightState\": 1}");
        scheduleRepository.save(schedule);
        schedulerService.reloadSchedules();

        assertThat((Object) activeTasks().get(schedule.getId()))
                .as("task replaced after the fingerprint changed")
                .isNotNull()
                .isNotSameAs(firstFuture);

        // The old task may publish once more before it is cancelled, so skip past any
        // stale payload rather than asserting on the very next message.
        Awaitility.await()
                .alias("updated payload published on terraGeneral/itChanged/schedule")
                .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> "{\"LightState\": 1}".equals(published.poll(200, TimeUnit.MILLISECONDS)));
    }

    /**
     * An unparsable cron expression is logged and skipped; it must not abort the
     * reload or prevent the remaining schedules from being registered.
     */
    @Test
    void invalidCronExpression_isSkippedAndValidSchedulesStillRun() throws Exception {
        BlockingQueue<String> published = subscribeTo("itValid");
        Schedule invalid = save("itInvalid", "{\"LightState\": 1}", "not-a-cron", true);
        Schedule valid = save("itValid", "{\"RainState\": 1}", EVERY_SECOND, true);

        schedulerService.reloadSchedules();

        assertThat(activeTasks())
                .as("no task registered for the invalid cron expression")
                .doesNotContainKey(invalid.getId());
        assertThat(activeTasks())
                .as("the valid schedule is still registered")
                .containsKey(valid.getId());
        assertThat(published.poll(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("the valid schedule still fires despite the invalid one")
                .isEqualTo("{\"RainState\": 1}");
    }
}
