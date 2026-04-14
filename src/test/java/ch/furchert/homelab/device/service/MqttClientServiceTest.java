package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
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

        service = new MqttClientService(props, messageHandler);
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
        try (var _ = Mockito.mockConstruction(MqttClient.class,
                (mock, ctx) -> doThrow(new MqttException(MqttException.REASON_CODE_CONNECTION_LOST))
                        .when(mock).connect(any()))) {
            assertThatCode(service::connect).doesNotThrowAnyException();
        }
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
