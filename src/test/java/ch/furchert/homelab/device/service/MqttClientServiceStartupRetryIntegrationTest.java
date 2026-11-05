package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Broker unavailable when the service starts, available later (#114), against a real Mosquitto.
 *
 * <p>No Spring context on purpose: every cached integration context stays subscribed to the
 * shared broker of {@code AbstractIntegrationTest} for the rest of the JVM run, and this test
 * needs a broker that does not exist yet when {@link MqttClientService#connect()} runs. The
 * broker is a separate container bound to a fixed host port, so the service can be pointed at
 * the address before anything listens there.
 */
@Testcontainers(disabledWithoutDocker = true)
class MqttClientServiceStartupRetryIntegrationTest {

    private static final String STATUS_TOPIC = "javaBackend/mqtt/status";
    private static final String ONLINE = "{\"MqttState\": 1}";
    private static final String OFFLINE = "{\"MqttState\": 0}";

    private final MqttMessageHandler messageHandler = mock(MqttMessageHandler.class);

    private MqttClientService service;
    private MqttClient observer;

    @AfterEach
    void tearDown() throws MqttException {
        if (service != null) {
            service.disconnect();
        }
        if (observer != null) {
            if (observer.isConnected()) {
                observer.disconnect();
            }
            observer.close();
        }
    }

    @Test
    @Timeout(120)
    void brokerUnavailableAtStartup_connectsAndSubscribesOnceBrokerIsUp() throws Exception {
        int port = freeLocalPort();
        String brokerUrl = "tcp://" + DockerClientFactory.instance().dockerHostIpAddress() + ":" + port;
        service = new MqttClientService(properties(brokerUrl), messageHandler,
                Duration.ofMillis(200), Duration.ofSeconds(1));

        // Nothing listens on the port yet: startup must neither throw nor wait for the broker.
        service.connect();

        try (GenericContainer<?> broker = new GenericContainer<>("eclipse-mosquitto:2")
                .withExposedPorts(1883)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mosquitto-test.conf"),
                        "/mosquitto/config/mosquitto.conf")
                .waitingFor(Wait.forListeningPort())) {
            broker.setPortBindings(List.of(port + ":1883"));
            broker.start();

            BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
            observer = new MqttClient(brokerUrl, "retry-it-observer", new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setConnectionTimeout(5);
            observer.connect(opts);
            observer.subscribe(STATUS_TOPIC, 1, (topic, message) ->
                    statuses.add(new String(message.getPayload(), StandardCharsets.UTF_8)));

            // connectComplete() subscribes first and then publishes the retained online status,
            // so seeing it means the retried connect succeeded and the subscriptions exist.
            assertThat(awaitPayload(statuses, ONLINE, 30))
                    .as("service publishes its online status after the broker came up")
                    .isTrue();

            MqttMessage sensor = new MqttMessage("{\"Temperature\": 21.0}".getBytes(StandardCharsets.UTF_8));
            sensor.setQos(1);
            observer.publish("terra1/SHT35/data", sensor);
            verify(messageHandler, timeout(10_000)).handle("terra1/SHT35/data", "{\"Temperature\": 21.0}");

            service.disconnect();
            assertThat(awaitPayload(statuses, OFFLINE, 10))
                    .as("service publishes its offline status on shutdown")
                    .isTrue();
        }
    }

    private static MqttProperties properties(String brokerUrl) {
        MqttProperties props = new MqttProperties();
        props.setBrokerUrl(brokerUrl);
        props.setClientId("device-service-retry-it");
        props.setTopics(List.of("terra1/#", "terra2/#", "terraGeneral/#"));
        props.setQos(1);
        props.setWillTopic(STATUS_TOPIC);
        props.setWillPayload(OFFLINE);
        return props;
    }

    private static boolean awaitPayload(BlockingQueue<String> queue, String expected, long seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            String payload = queue.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (expected.equals(payload)) {
                return true;
            }
        }
        return false;
    }

    private static int freeLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
