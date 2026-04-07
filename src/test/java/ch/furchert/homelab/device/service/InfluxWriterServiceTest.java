package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.config.InfluxDbProperties;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import com.influxdb.exceptions.InfluxException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InfluxWriterService}.
 *
 * <p>All dependencies are mocked; no real InfluxDB instance required.
 */
@ExtendWith(MockitoExtension.class)
class InfluxWriterServiceTest {

    @Mock
    private WriteApiBlocking writeApi;

    private InfluxDbProperties props;
    private InfluxWriterService service;

    @BeforeEach
    void setUp() {
        props = new InfluxDbProperties();
        props.setUrl("http://localhost:8086");
        props.setToken("test-token");
        props.setOrg("test-org");
        props.setBucket("test-bucket");

        service = new InfluxWriterService(writeApi, props);
    }

    @Test
    void write_sensorData_callsWritePoint() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1",
                ParsedMqttMessage.MessageType.SENSOR_DATA,
                22.5,
                65.0,
                null, null, null, null
        );

        service.write(msg);

        ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
        verify(writeApi, times(1))
                .writePoint(eq("test-bucket"), eq("test-org"), pointCaptor.capture());

        String lineProtocol = pointCaptor.getValue().toLineProtocol();
        assertThat(lineProtocol).contains("terrarium");
        assertThat(lineProtocol).contains("device=terra1");
        assertThat(lineProtocol).contains("temperature=22.5");
        assertThat(lineProtocol).contains("humidity=65.0");
    }

    @Test
    void write_mqttStatus_isIgnored() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1",
                ParsedMqttMessage.MessageType.MQTT_STATUS,
                null, null, true, null, null, null
        );

        service.write(msg);

        verifyNoInteractions(writeApi);
    }

    @Test
    void write_lightState_isIgnored() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1",
                ParsedMqttMessage.MessageType.LIGHT_STATE,
                null, null, null, "1", null, null
        );

        service.write(msg);

        verifyNoInteractions(writeApi);
    }

    @Test
    void write_nightLightState_isIgnored() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1",
                ParsedMqttMessage.MessageType.NIGHT_LIGHT_STATE,
                null, null, null, null, "0", null
        );

        service.write(msg);

        verifyNoInteractions(writeApi);
    }

    @Test
    void write_rainState_isIgnored() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1",
                ParsedMqttMessage.MessageType.RAIN_STATE,
                null, null, null, null, null, "1"
        );

        service.write(msg);

        verifyNoInteractions(writeApi);
    }

    @Test
    void write_unknown_isIgnored() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                null,
                ParsedMqttMessage.MessageType.UNKNOWN,
                null, null, null, null, null, null
        );

        service.write(msg);

        verifyNoInteractions(writeApi);
    }

    @Test
    void write_influxException_isCaughtAndNotRethrown() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra2",
                ParsedMqttMessage.MessageType.SENSOR_DATA,
                19.0,
                55.0,
                null, null, null, null
        );

        doThrow(new InfluxException("connection refused"))
                .when(writeApi).writePoint(any(), any(), any());

        // Must not propagate the exception to the caller
        assertThatNoException().isThrownBy(() -> service.write(msg));
    }
}
