package ch.furchert.homelab.device.integration;

import ch.furchert.homelab.device.AbstractIntegrationTest;
import ch.furchert.homelab.device.entity.Device;
import ch.furchert.homelab.device.repository.DeviceRepository;
import ch.furchert.homelab.device.service.InfluxWriterService;
import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the MQTT-to-PostgreSQL pipeline.
 *
 * <p>Starts a real PostgreSQL container and a real Mosquitto broker via
 * {@link AbstractIntegrationTest}. The {@link InfluxWriterService} is mocked
 * so that no InfluxDB connection is required for this test.
 *
 * <p>The test publishes a sensor message to {@code terra1/SHT35/data} using an
 * independent Paho client, then polls {@link DeviceRepository} via Awaitility
 * until the device row reflects the expected values or the 5-second deadline
 * is reached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MqttIntegrationTest extends AbstractIntegrationTest {

    /**
     * Mock the InfluxDB write service so that this test does not depend on a
     * live InfluxDB connection. The MQTT→DB path under test is independent of
     * the InfluxDB write.
     */
    @MockitoBean
    InfluxWriterService influxWriterService;

    @Autowired
    DeviceRepository deviceRepository;

    private MqttClient testClient;

    @BeforeEach
    void setUpMqttClient() throws MqttException {
        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
        testClient = new MqttClient(brokerUrl, "it-test-publisher", new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        testClient.connect(opts);
    }

    @AfterEach
    void tearDownMqttClient() throws MqttException {
        if (testClient != null && testClient.isConnected()) {
            testClient.disconnect();
        }
        if (testClient != null) {
            testClient.close();
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * Publishes a SENSOR_DATA message to {@code terra1/SHT35/data} and verifies
     * that the device row in PostgreSQL is updated with the correct temperature
     * and humidity values within 5 seconds.
     */
    @Test
    void sensorMessage_publishedToMqtt_updatesDeviceInPostgres() throws Exception {
        String topic = "terra1/SHT35/data";
        String payload = "{\"Temperature\": 23.5, \"Humidity\": 60.0}";

        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);
        message.setRetained(false);
        testClient.publish(topic, message);

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Optional<Device> deviceOpt = deviceRepository.findByName("terra1");
                    assertThat(deviceOpt).isPresent();
                    Device device = deviceOpt.get();
                    assertThat(device.getTemperature())
                            .as("temperature stored after SENSOR_DATA message")
                            .isEqualTo(23.5);
                    assertThat(device.getHumidity())
                            .as("humidity stored after SENSOR_DATA message")
                            .isEqualTo(60.0);
                    assertThat(device.getLastSeen())
                            .as("lastSeen updated after SENSOR_DATA message")
                            .isNotNull();
                });
    }

    /**
     * Publishes an MQTT_STATUS online message to {@code terra1/mqtt/status} and
     * verifies that the device row reflects {@code mqttOnline=true} within 5 seconds.
     */
    @Test
    void mqttStatusMessage_publishedToMqtt_setsMqttOnlineInPostgres() throws Exception {
        String topic = "terra1/mqtt/status";
        String payload = "{\"MqttState\": 1}";

        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);
        message.setRetained(false);
        testClient.publish(topic, message);

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Optional<Device> deviceOpt = deviceRepository.findByName("terra1");
                    assertThat(deviceOpt).isPresent();
                    assertThat(deviceOpt.get().getMqttOnline())
                            .as("mqttOnline set to true after MQTT_STATUS online message")
                            .isTrue();
                });
    }
}
