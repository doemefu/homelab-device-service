package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.config.MqttProperties;
import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MqttClientService}.
 *
 * <p>Uses {@link MockedConstruction} to intercept {@code new MqttClient(...)} and avoid
 * any real broker connection while still verifying the full service logic.
 */
@ExtendWith(MockitoExtension.class)
class MqttClientServiceTest {

    /** Short delays keep the connect-retry tests fast; production: {@code ConnectRetryTiming.PRODUCTION}. */
    private static final MqttClientService.ConnectRetryTiming TIMING = new MqttClientService.ConnectRetryTiming(
            Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofSeconds(30), Duration.ofMillis(200));

    /** Long enough for several retries at the 50 ms maximum delay if any were still scheduled. */
    private static final long QUIET_PERIOD_MS = 300;

    @Mock
    private MqttProperties props;

    @Mock
    private MqttMessageHandler messageHandler;

    private MqttClientService service;

    @BeforeEach
    void setUp() {
        // Lenient: only tests that call connect() consume these stubs.
        lenient().when(props.getBrokerUrl()).thenReturn("tcp://localhost:1883");
        lenient().when(props.getClientId()).thenReturn("test-client");
        lenient().when(props.getTopics()).thenReturn(List.of("terra1/#", "terra2/#"));
        lenient().when(props.getQos()).thenReturn(1);
        lenient().when(props.getWillTopic()).thenReturn("javaBackend/mqtt/status");
        lenient().when(props.getWillPayload()).thenReturn("{\"MqttState\": 0}");

        service = new MqttClientService(props, messageHandler, TIMING);
    }

    @AfterEach
    void tearDown() {
        // Stops any connect retry a test left scheduled, so no retry thread outlives the test.
        service.disconnect();
    }

    // -------------------------------------------------------------------------
    // connect()
    // -------------------------------------------------------------------------

    @Test
    void connect_createsClientAndConnectsWithAutoReconnectAndLwt() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class)) {
            service.connect();

            assertThat(mocked.constructed()).hasSize(1);
            MqttClient client = mocked.constructed().get(0);

            verify(client).setCallback(service);

            ArgumentCaptor<MqttConnectOptions> optsCaptor =
                    ArgumentCaptor.forClass(MqttConnectOptions.class);
            verify(client).connect(optsCaptor.capture());

            MqttConnectOptions opts = optsCaptor.getValue();
            assertThat(opts.isAutomaticReconnect()).isTrue();
            assertThat(opts.isCleanSession()).isTrue();
            assertThat(opts.getWillDestination()).isEqualTo("javaBackend/mqtt/status");
        }
    }

    @Test
    void connect_withCredentials_setsUsernameAndPasswordOnOptions() throws Exception {
        when(props.getUsername()).thenReturn("mqttuser");
        when(props.getPassword()).thenReturn("secret");

        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class)) {
            service.connect();

            MqttClient client = mocked.constructed().get(0);
            ArgumentCaptor<MqttConnectOptions> optsCaptor =
                    ArgumentCaptor.forClass(MqttConnectOptions.class);
            verify(client).connect(optsCaptor.capture());

            MqttConnectOptions opts = optsCaptor.getValue();
            assertThat(opts.getUserName()).isEqualTo("mqttuser");
            assertThat(new String(opts.getPassword())).isEqualTo("secret");
        }
    }

    @Test
    void connect_brokerConnectException_doesNotPropagate() {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> doThrow(new MqttException(MqttException.REASON_CODE_CONNECTION_LOST))
                        .when(mock).connect(any()))) {
            assertThatCode(service::connect).doesNotThrowAnyException();
            assertThat(mocked.constructed()).hasSize(1);
        }
    }

    // -------------------------------------------------------------------------
    // connect() — retry after a failed first attempt (#114)
    // -------------------------------------------------------------------------

    @Test
    void connect_pinsMqtt311_soAnAttemptMakesOneTcpConnect() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class)) {
            service.connect();

            ArgumentCaptor<MqttConnectOptions> optsCaptor =
                    ArgumentCaptor.forClass(MqttConnectOptions.class);
            verify(mocked.constructed().get(0)).connect(optsCaptor.capture());
            // The Paho default (MQTT_VERSION_DEFAULT) falls back to 3.1 with a second TCP connect.
            assertThat(optsCaptor.getValue().getMqttVersion()).isEqualTo(MqttConnectOptions.MQTT_VERSION_3_1_1);
        }
    }

    @Test
    void connect_boundsEachAttemptAndRestoresTheWaitAfterwards() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> when(mock.getTimeToWait()).thenReturn(-1L))) {
            service.connect();

            // Paho bounds only the TCP connect; the wait for CONNACK is bounded by timeToWait.
            MqttClient client = mocked.constructed().get(0);
            InOrder inOrder = inOrder(client);
            inOrder.verify(client).setTimeToWait(30_000L);
            inOrder.verify(client).connect(any());
            inOrder.verify(client).setTimeToWait(-1L);
        }
    }

    @Test
    void connect_attemptTimesOutWithoutConnack_resetsClientBeforeNextAttempt() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT))
                        .doNothing()
                        .when(mock).connect(any()))) {
            service.connect();

            // After a timed-out wait Paho is still connecting, and the next connect() would throw
            // CONNECT_IN_PROGRESS. A forced disconnect without a cause resets it to disconnected
            // and does not start Paho's own reconnect cycle.
            MqttClient client = mocked.constructed().get(0);
            verify(client, timeout(5_000).times(2)).connect(any());
            InOrder inOrder = inOrder(client);
            inOrder.verify(client).connect(any());
            inOrder.verify(client).disconnectForcibly(0, 0, false);
            inOrder.verify(client).connect(any());
            service.disconnect();
        }
    }

    @Test
    void connect_firstAttemptFails_retriesOnSameClientUntilConnected() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> doThrow(new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR))
                        .doThrow(new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR))
                        .doNothing()
                        .when(mock).connect(any()))) {
            service.connect();

            // Construction mocking only sees the test thread, so a client built on the retry thread
            // would be real and uncounted. Three attempts on the first mock, all with the same
            // options, are what prove that the retries reuse the one client.
            MqttClient client = mocked.constructed().get(0);
            ArgumentCaptor<MqttConnectOptions> optsCaptor =
                    ArgumentCaptor.forClass(MqttConnectOptions.class);
            verify(client, timeout(5_000).times(3)).connect(optsCaptor.capture());
            assertThat(optsCaptor.getAllValues()).allSatisfy(
                    opts -> assertThat(opts).isSameAs(optsCaptor.getAllValues().get(0)));

            // The first success ends the retries; Paho's automatic reconnect takes over from here.
            verify(client, after(QUIET_PERIOD_MS).times(3)).connect(any());
            service.disconnect();
        }
    }

    @Test
    void connect_retryThrowsUnexpectedRuntimeException_keepsRetrying() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> doThrow(new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR))
                        .doThrow(new IllegalStateException("unexpected"))
                        .doNothing()
                        .when(mock).connect(any()))) {
            service.connect();

            // An exception other than MqttException on the retry thread must not end the retries
            // silently (a scheduled task swallows it).
            verify(mocked.constructed().get(0), timeout(5_000).times(3)).connect(any());
            service.disconnect();
        }
    }

    @Test
    void connect_firstAttemptSucceeds_schedulesNoRetry() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class)) {
            service.connect();

            verify(mocked.constructed().get(0), after(QUIET_PERIOD_MS).times(1)).connect(any());
        }
    }

    @Test
    void disconnect_whileRetrying_stopsFurtherAttemptsAndClosesClient() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> doAnswer(inv -> {
                    attempts.incrementAndGet();
                    throw new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR);
                }).when(mock).connect(any()))) {
            service.connect();
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> attempts.get() >= 3);

            service.disconnect();
            int attemptsAtShutdown = attempts.get();

            MqttClient client = mocked.constructed().get(0);
            verify(client, after(QUIET_PERIOD_MS).times(attemptsAtShutdown)).connect(any());
            verify(client).close();
        }
    }

    @Test
    void disconnect_attemptStillRunningAfterShutdownWait_interruptsItAndSkipsClose() throws Exception {
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> doThrow(new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR))
                        .doAnswer(inv -> {
                            // A broker that accepts TCP but never answers: Paho's connect waits until
                            // interrupted and then throws an MqttException wrapping the interrupt.
                            secondAttemptStarted.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException e) {
                                throw new MqttException(e);
                            }
                            return null;
                        })
                        .when(mock).connect(any()))) {
            service.connect();
            assertThat(secondAttemptStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatCode(service::disconnect).doesNotThrowAnyException();

            // close() would throw CONNECT_IN_PROGRESS while Paho's connect is still running.
            MqttClient client = mocked.constructed().get(0);
            verify(client, never()).close();
            verify(client, after(QUIET_PERIOD_MS).times(2)).connect(any());
        }
    }

    @Test
    void retryDelay_doublesFromInitialDelayAndCapsAtMaxDelay() {
        MqttClientService withDefaults = new MqttClientService(props, messageHandler,
                MqttClientService.ConnectRetryTiming.PRODUCTION);

        assertThat(List.of(1, 2, 3, 4, 5, 6, 7, 8).stream().map(withDefaults::retryDelay).toList())
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                        Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(32),
                        Duration.ofSeconds(60), Duration.ofSeconds(60));
        assertThat(withDefaults.retryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(60));
    }

    // -------------------------------------------------------------------------
    // connectComplete() — called by broker after (re-)connect
    // -------------------------------------------------------------------------

    @Test
    void connectComplete_subscribesToAllConfiguredTopics() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> when(mock.isConnected()).thenReturn(true))) {
            service.connect();
            service.connectComplete(false, "tcp://localhost:1883");

            MqttClient client = mocked.constructed().get(0);
            verify(client).subscribe("terra1/#", 1);
            verify(client).subscribe("terra2/#", 1);
        }
    }

    @Test
    void connectComplete_publishesOnlineStatusRetained() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> when(mock.isConnected()).thenReturn(true))) {
            service.connect();
            service.connectComplete(false, "tcp://localhost:1883");

            MqttClient client = mocked.constructed().get(0);
            ArgumentCaptor<MqttMessage> msgCaptor = ArgumentCaptor.forClass(MqttMessage.class);
            verify(client, atLeastOnce()).publish(eq("javaBackend/mqtt/status"), msgCaptor.capture());

            boolean hasOnlinePayload = msgCaptor.getAllValues().stream()
                    .anyMatch(m -> "{\"MqttState\": 1}".equals(
                            new String(m.getPayload(), StandardCharsets.UTF_8)));
            assertThat(hasOnlinePayload).isTrue();
        }
    }

    @Test
    void connectComplete_onReconnect_alsoSubscribesAndPublishes() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> when(mock.isConnected()).thenReturn(true))) {
            service.connect();
            service.connectComplete(true, "tcp://localhost:1883"); // reconnect = true

            MqttClient client = mocked.constructed().get(0);
            verify(client).subscribe("terra1/#", 1);
            verify(client).subscribe("terra2/#", 1);
        }
    }

    @Test
    void connectComplete_afterShutdownStarted_doesNotSubscribeOrPublish() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class)) {
            service.connect();
            service.disconnect();
            // A connect that completes while the service shuts down must not re-announce "online"
            // after disconnect() published the offline status.
            service.connectComplete(false, "tcp://localhost:1883");

            MqttClient client = mocked.constructed().get(0);
            verify(client, never()).subscribe(any(String.class), anyInt());
            verify(client, never()).publish(any(String.class), any(MqttMessage.class));
        }
    }

    // -------------------------------------------------------------------------
    // messageArrived()
    // -------------------------------------------------------------------------

    @Test
    void messageArrived_delegatesToMessageHandler() {
        MqttMessage mqttMsg = new MqttMessage("payload".getBytes(StandardCharsets.UTF_8));
        service.messageArrived("terra1/SHT35/data", mqttMsg);
        verify(messageHandler).handle("terra1/SHT35/data", "payload");
    }

    @Test
    void messageArrived_handlerThrowsRuntimeException_doesNotPropagate() {
        doThrow(new RuntimeException("boom")).when(messageHandler).handle(any(), any());
        MqttMessage mqttMsg = new MqttMessage("{}".getBytes(StandardCharsets.UTF_8));
        assertThatCode(() -> service.messageArrived("terra1/SHT35/data", mqttMsg))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // disconnect()
    // -------------------------------------------------------------------------

    @Test
    void disconnect_publishesOfflineStatusAndClosesConnection() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> when(mock.isConnected()).thenReturn(true))) {
            service.connect();
            service.disconnect();

            MqttClient client = mocked.constructed().get(0);
            ArgumentCaptor<MqttMessage> msgCaptor = ArgumentCaptor.forClass(MqttMessage.class);
            verify(client, atLeastOnce()).publish(eq("javaBackend/mqtt/status"), msgCaptor.capture());

            boolean hasOfflinePayload = msgCaptor.getAllValues().stream()
                    .anyMatch(m -> "{\"MqttState\": 0}".equals(
                            new String(m.getPayload(), StandardCharsets.UTF_8)));
            assertThat(hasOfflinePayload).isTrue();

            verify(client).disconnect();
            verify(client).close();
        }
    }

    @Test
    void disconnect_whenClientNeverInitialized_doesNotThrow() {
        // connect() never called — client field is null
        assertThatCode(service::disconnect).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // publish()
    // -------------------------------------------------------------------------

    @Test
    void publish_whenConnected_sendsMessageWithCorrectQosAndRetained() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> when(mock.isConnected()).thenReturn(true))) {
            service.connect();
            service.publish("test/topic", "hello", 1, false);

            MqttClient client = mocked.constructed().get(0);
            ArgumentCaptor<MqttMessage> msgCaptor = ArgumentCaptor.forClass(MqttMessage.class);
            verify(client, atLeastOnce()).publish(eq("test/topic"), msgCaptor.capture());

            MqttMessage sent = msgCaptor.getAllValues().stream()
                    .filter(m -> "hello".equals(new String(m.getPayload(), StandardCharsets.UTF_8)))
                    .findFirst()
                    .orElseThrow();
            assertThat(sent.getQos()).isEqualTo(1);
            assertThat(sent.isRetained()).isFalse();
        }
    }

    @Test
    void publish_whenClientDisconnected_doesNotCallClientPublish() throws Exception {
        try (MockedConstruction<MqttClient> mocked = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> when(mock.isConnected()).thenReturn(false))) {
            service.connect();
            service.publish("test/topic", "hello", 1, false);

            MqttClient client = mocked.constructed().get(0);
            verify(client, never()).publish(any(String.class), any(MqttMessage.class));
        }
    }

    @Test
    void publish_whenClientNull_doesNotThrow() {
        // connect() never called — client is null
        assertThatCode(() -> service.publish("test/topic", "hello", 1, false))
                .doesNotThrowAnyException();
    }
}
