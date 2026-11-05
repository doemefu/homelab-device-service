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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Startup connect retry (#114) against real sockets: a Mosquitto that starts only after the
 * service, and a TCP server that accepts but never answers (no CONNACK).
 *
 * <p>No Spring context on purpose: every cached integration context stays subscribed to the
 * shared broker of {@code AbstractIntegrationTest} for the rest of the JVM run, and this test
 * needs a broker that does not exist yet when {@link MqttClientService#connect()} runs. The
 * broker is a separate container bound to a fixed host port, so the service can be pointed at
 * the address before anything listens there. The port is probed free right before the bind; if
 * something takes it in between, the container start fails loudly rather than flaking.
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
                new MqttClientService.ConnectRetryTiming(Duration.ofMillis(200), Duration.ofSeconds(1),
                        Duration.ofSeconds(30), Duration.ofSeconds(12)));

        // Nothing listens on the port yet: startup must neither throw nor wait for the broker.
        long started = System.nanoTime();
        service.connect();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("connect() returns without waiting for the broker")
                .isLessThan(Duration.ofSeconds(5));

        try (GenericContainer<?> broker = new GenericContainer<>("eclipse-mosquitto:2")
                .withExposedPorts(1883)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mosquitto-test.conf"),
                        "/mosquitto/config/mosquitto.conf")
                // The log line, not the port: Docker can accept on a published port before
                // Mosquitto listens inside the container.
                .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1))) {
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

    @Test
    @Timeout(60)
    void brokerAcceptsTcpButNeverSendsConnack_attemptTimesOutAndRetries() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        List<Socket> open = new CopyOnWriteArrayList<>();
        try (ServerSocket silentBroker = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                while (!silentBroker.isClosed()) {
                    try {
                        open.add(silentBroker.accept()); // read nothing, answer nothing
                        accepted.incrementAndGet();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "silent-broker");
            acceptor.setDaemon(true);
            acceptor.start();

            service = new MqttClientService(
                    properties("tcp://127.0.0.1:" + silentBroker.getLocalPort()), messageHandler,
                    new MqttClientService.ConnectRetryTiming(Duration.ofMillis(100), Duration.ofMillis(200),
                            Duration.ofMillis(500), Duration.ofSeconds(2)));

            long started = System.nanoTime();
            service.connect();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("the first attempt gives up after its timeout instead of waiting for CONNACK")
                    .isLessThan(Duration.ofSeconds(5));

            // Every new TCP connection is a new attempt: the timed-out one did not block the retries.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (accepted.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(accepted.get()).as("connect attempts reaching the silent broker").isGreaterThanOrEqualTo(3);
        } finally {
            for (Socket socket : open) {
                socket.close();
            }
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
