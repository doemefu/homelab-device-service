package ch.furchert.homelab.device.integration;

import ch.furchert.homelab.device.AbstractIntegrationTest;
import ch.furchert.homelab.device.dto.DeviceStateDto;
import ch.furchert.homelab.device.entity.Device;
import ch.furchert.homelab.device.service.InfluxWriterService;
import ch.furchert.homelab.device.service.WebSocketBroadcastService;
import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the STOMP-over-WebSocket broadcast path.
 *
 * <p>Boots the full application on a random port with the containers from
 * {@link AbstractIntegrationTest}, then connects a real {@link WebSocketStompClient}
 * to {@code ws://localhost:{port}/ws}. This exercises the parts that
 * {@code WebSocketBroadcastServiceTest} cannot: the STOMP handshake, the endpoint
 * registration in {@code WebSocketConfig}, the {@code /ws} security rule, the simple
 * broker's destination routing and the JSON wire format of {@link DeviceStateDto}.
 *
 * <p>{@link InfluxWriterService} is mocked so the test does not depend on a live
 * InfluxDB connection, exactly as in {@code MqttIntegrationTest}.
 *
 * <p>Not covered here: the {@code app.websocket.allowed-origins} restriction. A plain
 * JSR-356 client sends no {@code Origin} header and Spring's origin check accepts a
 * missing one, so tightening that property would not be caught by this suite.
 *
 * <p>Device-name convention: the PostgreSQL container is shared by every integration
 * test class in the JVM and no test resets it. {@code MqttIntegrationTest} owns
 * {@code terra1} for its database assertions; this class asserts only on the STOMP
 * payload, and its end-to-end test uses {@code terra2} with values no other test writes.
 * Both rows are seeded by {@code V1__create_devices.sql}, so concurrent contexts consuming
 * the same MQTT message only ever UPDATE an existing row -- they cannot race the
 * {@code devices.name} UNIQUE constraint.
 *
 * <p>Cross-class caveat: the end-to-end test publishes on {@code terra2/SHT35/data}, and
 * every cached context is subscribed to {@code terra2/#}. {@code InfluxWriterIntegrationTest}
 * asserts that no InfluxDB record exists for {@code device=terra2}, and unlike this class it
 * does not mock {@code InfluxWriterService}. That assertion is safe only because a context
 * consumes MQTT solely while it is alive, and its context is built when its own class starts
 * -- after this class has finished publishing. Reordering the classes so that
 * {@code InfluxWriterIntegrationTest} is live during this publish would break it; see the
 * follow-ups on PR #72.
 *
 * <h2>Why there are no sleeps</h2>
 * A STOMP {@code SUBSCRIBE} frame is written asynchronously, so a broadcast issued
 * immediately after {@link StompSession#subscribe} can be dropped by the broker before
 * the subscription is registered. Instead of sleeping, every subscription is probed:
 * a marker payload is broadcast to the destination until it comes back. The frame
 * handler filters marker payloads out, so a late marker can never pollute the queue
 * a test asserts on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Every @SpringBootTest class loads its own application context, each with its own
// MqttClientService. Paho disconnects an existing session when a second client connects
// with the same client id, so this context uses a dedicated one instead of the shared
// "device-service-test" from src/test/resources/application.yaml.
@TestPropertySource(properties = "app.mqtt.client-id=device-service-ws-it")
class WebSocketIntegrationTest extends AbstractIntegrationTest {

    /** Marker written into {@code light} so probe broadcasts can be told apart from real ones. */
    private static final String PROBE_MARKER = "__ws-it-probe__";

    private static final long AWAIT_SECONDS = 10;

    @MockitoBean
    InfluxWriterService influxWriterService;

    @Autowired
    WebSocketBroadcastService broadcastService;

    @LocalServerPort
    int port;

    private static WebSocketStompClient stompClient;

    private StompSession session;
    private MqttClient testPublisher;

    /**
     * First transport or payload-handling error reported by the STOMP session.
     * Without this, a serialisation mismatch would surface only as an empty queue.
     */
    private final AtomicReference<Throwable> sessionFailure = new AtomicReference<>();

    /**
     * One STOMP client for the whole class.
     *
     * <p>{@link StandardWebSocketClient} does not implement {@code Lifecycle}, so
     * {@link WebSocketStompClient#stop()} does not release the JSR-356 container that
     * Tomcat creates per instance. Building a client per test method would therefore
     * leak one {@code AsynchronousChannelGroup} and its threads per method.
     */
    @BeforeAll
    static void startStompClient() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        // No TaskScheduler is configured, so heartbeats must be off.
        stompClient.setDefaultHeartbeat(new long[] {0, 0});
    }

    @AfterAll
    static void stopStompClient() {
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    @BeforeEach
    void connectStompClient() throws Exception {
        StompSessionHandlerAdapter handler = new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, StompCommand command,
                                        StompHeaders headers, byte[] payload, Throwable exception) {
                sessionFailure.compareAndSet(null, exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFailure.compareAndSet(null, exception);
            }
        };

        session = stompClient
                .connectAsync("ws://localhost:" + port + "/ws", handler)
                .get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void disconnectAll() throws MqttException {
        // Snapshot before disconnecting, since a clean disconnect itself reports a transport
        // error. The assertion runs in the finally block so that a failure here can never skip
        // the cleanup below -- otherwise one bad test would leave a live session and a connected
        // publisher behind, and the next test's publisher would be kicked off the broker for
        // reusing the client id.
        Throwable failure = sessionFailure.get();
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            if (testPublisher != null) {
                if (testPublisher.isConnected()) {
                    testPublisher.disconnect();
                }
                testPublisher.close();
            }
        } finally {
            assertThat(failure)
                    .as("no STOMP transport or payload-handling error during the test")
                    .isNull();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Subscribes to {@code /topic/terrarium/{deviceName}} and returns a queue that
     * receives every non-probe frame delivered to that destination.
     *
     * <p>Blocks until a probe broadcast has made the round trip, which proves the
     * subscription is registered with the broker.
     */
    private BlockingQueue<DeviceStateDto> subscribeAndAwaitActive(String deviceName) {
        BlockingQueue<DeviceStateDto> received = new LinkedBlockingQueue<>();
        AtomicBoolean probeSeen = new AtomicBoolean(false);

        session.subscribe("/topic/terrarium/" + deviceName, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return DeviceStateDto.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                DeviceStateDto dto = (DeviceStateDto) payload;
                if (PROBE_MARKER.equals(dto.light())) {
                    probeSeen.set(true);
                } else {
                    received.add(dto);
                }
            }
        });

        Device probe = Device.builder().name(deviceName).light(PROBE_MARKER).build();
        Awaitility.await()
                .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> {
                    broadcastService.broadcastDeviceState(probe);
                    return probeSeen.get();
                });

        return received;
    }

    /**
     * Connects the short-lived publisher used by the end-to-end test.
     *
     * <p>Deliberately without {@code MqttCallbackExtended}, automatic reconnect or an LWT:
     * those are conventions for {@code MqttClientService}'s long-lived production connection.
     * This client exists for the duration of one test method and is closed in
     * {@code @AfterEach}; an LWT would additionally publish an unrelated message into the
     * shared broker when it disconnects. Same shape as {@code MqttIntegrationTest}.
     */
    private void connectTestPublisher() throws MqttException {
        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
        // Assigned before connect() so @AfterEach can still close the client if connect fails.
        testPublisher = new MqttClient(brokerUrl, "ws-it-test-publisher", new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        testPublisher.connect(opts);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * The STOMP handshake against {@code /ws} succeeds without any credentials.
     * {@code SecurityConfigTest} only asserts the request matcher; this proves the
     * real upgrade request is accepted by the running server.
     */
    @Test
    void stompEndpoint_acceptsUnauthenticatedConnection() {
        assertThat(session).as("STOMP session established without authentication").isNotNull();
        assertThat(session.isConnected()).as("session reports connected").isTrue();
    }

    /**
     * A {@code broadcastDeviceState} call is delivered to a client subscribed to that
     * device's destination, with every {@link DeviceStateDto} field intact after the
     * JSON round trip.
     */
    @Test
    void broadcastDeviceState_deliversDtoToSubscriber() throws Exception {
        BlockingQueue<DeviceStateDto> received = subscribeAndAwaitActive("terra1");

        LocalDateTime lastSeen = LocalDateTime.of(2026, 9, 4, 1, 2, 3);
        Device device = Device.builder()
                .id(42L)
                .name("terra1")
                .mqttOnline(true)
                .temperature(21.5)
                .humidity(58.25)
                .light("on")
                .nightLight("off")
                .rain("off")
                .lastSeen(lastSeen)
                .build();

        broadcastService.broadcastDeviceState(device);

        DeviceStateDto dto = received.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(dto).as("device state received over WebSocket").isNotNull();
        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.name()).isEqualTo("terra1");
        assertThat(dto.mqttOnline()).isTrue();
        assertThat(dto.temperature()).isEqualTo(21.5);
        assertThat(dto.humidity()).isEqualTo(58.25);
        assertThat(dto.light()).isEqualTo("on");
        assertThat(dto.nightLight()).isEqualTo("off");
        assertThat(dto.rain()).isEqualTo("off");
        assertThat(dto.lastSeen()).isEqualTo(lastSeen.toString());
    }

    /**
     * Each device gets its own destination: a terra1 broadcast must not reach a
     * terra2 subscriber and vice versa. Both messages are awaited before the
     * "nothing else arrived" assertion, so no timing assumption is involved.
     */
    @Test
    void broadcastDeviceState_isNotDeliveredToOtherDeviceDestination() throws Exception {
        BlockingQueue<DeviceStateDto> terra1 = subscribeAndAwaitActive("terra1");
        BlockingQueue<DeviceStateDto> terra2 = subscribeAndAwaitActive("terra2");

        broadcastService.broadcastDeviceState(
                Device.builder().name("terra1").temperature(11.1).build());
        broadcastService.broadcastDeviceState(
                Device.builder().name("terra2").temperature(22.2).build());

        DeviceStateDto first = terra1.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        DeviceStateDto second = terra2.poll(AWAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first).as("terra1 subscriber received the terra1 broadcast").isNotNull();
        assertThat(first.name()).isEqualTo("terra1");
        assertThat(first.temperature()).isEqualTo(11.1);

        assertThat(second).as("terra2 subscriber received the terra2 broadcast").isNotNull();
        assertThat(second.name()).isEqualTo("terra2");
        assertThat(second.temperature()).isEqualTo(22.2);

        // Both queues must stay empty for a whole settling window, not merely at the instant
        // after the expected frames arrived. WebSocketConfig does not set preservePublishOrder,
        // so brokerChannel and clientOutboundChannel are executor-backed and the two
        // destinations travel independent thread-pool hops: a mis-routed frame can still be in
        // flight when the correctly-routed one has already been delivered. A zero-width poll()
        // here would report "no cross-talk" for exactly the defect this test exists to catch.
        Awaitility.await()
                .alias("no cross-talk between the terra1 and terra2 destinations")
                .during(500, TimeUnit.MILLISECONDS)
                .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .until(() -> terra1.isEmpty() && terra2.isEmpty());
    }

    /**
     * End-to-end: a sensor message published to the Mosquitto container travels
     * through the MQTT client, the parser, {@code DeviceService} (which persists the
     * row in PostgreSQL) and out to the WebSocket subscriber as a
     * {@link DeviceStateDto}. This is the "works in a real environment" criterion
     * of issue #39.
     */
    @Test
    void mqttSensorMessage_reachesWebSocketSubscriber() throws Exception {
        BlockingQueue<DeviceStateDto> received = subscribeAndAwaitActive("terra2");

        connectTestPublisher();

        // Publish until the broadcast comes back, rather than publishing once and hoping the
        // application's own MQTT subscription is live. That client connects with
        // cleanSession=true and automatic reconnect, so while it sits in a reconnect backoff
        // the broker holds no session state for it and silently drops a QoS-1 publish -- the
        // broker still PUBACKs, so publish() returns normally and the message is simply lost.
        // Duplicate publishes are harmless: they carry the same values and land in a queue that
        // dies with this test's STOMP session.
        Awaitility.await()
                .alias("MQTT sensor message broadcast to the WebSocket subscriber")
                .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    MqttMessage message = new MqttMessage(
                            "{\"Temperature\": 27.7, \"Humidity\": 41.3}".getBytes(StandardCharsets.UTF_8));
                    message.setQos(1);
                    message.setRetained(false);
                    testPublisher.publish("terra2/SHT35/data", message);
                    return !received.isEmpty();
                });

        // DeviceService is @Transactional and broadcasts inside the transaction, so the
        // frame can arrive before the row is committed. Asserting on the STOMP payload
        // rather than on a repository read keeps this independent of commit timing.
        DeviceStateDto dto = received.poll();
        assertThat(dto).as("MQTT sensor message broadcast to the WebSocket subscriber").isNotNull();
        assertThat(dto.name()).isEqualTo("terra2");
        assertThat(dto.temperature()).isEqualTo(27.7);
        assertThat(dto.humidity()).isEqualTo(41.3);
        assertThat(dto.lastSeen()).as("lastSeen set by DeviceService").isNotNull();
    }
}
