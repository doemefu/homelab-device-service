package ch.furchert.homelab.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link MqttMessageParser}.
 *
 * <p>Uses a real {@link ObjectMapper} — no Spring context required.
 */
class MqttMessageParserTest {

    private MqttMessageParser parser;

    @BeforeEach
    void setUp() {
        parser = new MqttMessageParser(new ObjectMapper());
    }

    // -------------------------------------------------------------------------
    // SENSOR_DATA — terra{n}/SHT35/data
    // -------------------------------------------------------------------------

    @Test
    void parse_sensorData_returnsCorrectTypeAndValues() {
        ParsedMqttMessage result = parser.parse(
                "terra1/SHT35/data",
                "{\"Temperature\":22.5,\"Humidity\":65.0}"
        );

        assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.SENSOR_DATA);
        assertThat(result.deviceName()).isEqualTo("terra1");
        assertThat(result.temperature()).isEqualTo(22.5);
        assertThat(result.humidity()).isEqualTo(65.0);
        assertThat(result.mqttOnline()).isNull();
        assertThat(result.lightState()).isNull();
        assertThat(result.nightLightState()).isNull();
        assertThat(result.rainState()).isNull();
    }

    // -------------------------------------------------------------------------
    // MQTT_STATUS — terra{n}/mqtt/status
    // -------------------------------------------------------------------------

    @Test
    void parse_mqttStatus_online_returnsCorrectTypeAndValue() {
        ParsedMqttMessage result = parser.parse(
                "terra2/mqtt/status",
                "{\"MqttState\":1}"
        );

        assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.MQTT_STATUS);
        assertThat(result.deviceName()).isEqualTo("terra2");
        assertThat(result.mqttOnline()).isTrue();
        assertThat(result.temperature()).isNull();
        assertThat(result.humidity()).isNull();
    }

    @Test
    void parse_mqttStatus_offline_returnsOnlineFalse() {
        ParsedMqttMessage result = parser.parse(
                "terra1/mqtt/status",
                "{\"MqttState\":0}"
        );

        assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.MQTT_STATUS);
        assertThat(result.mqttOnline()).isFalse();
    }

    // -------------------------------------------------------------------------
    // LIGHT_STATE — terra{n}/light
    // -------------------------------------------------------------------------

    @Test
    void parse_lightState_returnsCorrectTypeAndValue() {
        ParsedMqttMessage result = parser.parse(
                "terra1/light",
                "{\"LightState\":1}"
        );

        assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.LIGHT_STATE);
        assertThat(result.deviceName()).isEqualTo("terra1");
        assertThat(result.lightState()).isEqualTo("1");
        assertThat(result.nightLightState()).isNull();
        assertThat(result.rainState()).isNull();
    }

    // -------------------------------------------------------------------------
    // NIGHT_LIGHT_STATE — terra{n}/nightLight
    // -------------------------------------------------------------------------

    @Test
    void parse_nightLightState_returnsCorrectTypeAndValue() {
        ParsedMqttMessage result = parser.parse(
                "terra1/nightLight",
                "{\"NightLightState\":0}"
        );

        assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.NIGHT_LIGHT_STATE);
        assertThat(result.deviceName()).isEqualTo("terra1");
        assertThat(result.nightLightState()).isEqualTo("0");
        assertThat(result.lightState()).isNull();
        assertThat(result.rainState()).isNull();
    }

    // -------------------------------------------------------------------------
    // RAIN_STATE — terra{n}/rain
    // -------------------------------------------------------------------------

    @Test
    void parse_rainState_returnsCorrectTypeAndValue() {
        ParsedMqttMessage result = parser.parse(
                "terra2/rain",
                "{\"RainState\":1}"
        );

        assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.RAIN_STATE);
        assertThat(result.deviceName()).isEqualTo("terra2");
        assertThat(result.rainState()).isEqualTo("1");
        assertThat(result.lightState()).isNull();
        assertThat(result.nightLightState()).isNull();
    }

    // -------------------------------------------------------------------------
    // UNKNOWN — unrecognised topic
    // -------------------------------------------------------------------------

    @Test
    void parse_unknownTopic_returnsUnknown() {
        ParsedMqttMessage result = parser.parse("someOtherTopic/foo/bar", "{\"value\":1}");

        assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.UNKNOWN);
    }

    @Test
    void parse_terraTopicWithUnmatchedPath_returnsUnknown() {
        ParsedMqttMessage result = parser.parse("terra1/unknownField", "{\"value\":1}");

        assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.UNKNOWN);
    }

    // -------------------------------------------------------------------------
    // Malformed / null / empty payload
    // -------------------------------------------------------------------------

    @Test
    void parse_malformedJson_returnsUnknownWithoutException() {
        assertThatNoException().isThrownBy(() -> {
            ParsedMqttMessage result = parser.parse("terra1/SHT35/data", "not-json{{");
            assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.UNKNOWN);
        });
    }

    @Test
    void parse_nullPayload_returnsUnknownWithoutException() {
        assertThatNoException().isThrownBy(() -> {
            ParsedMqttMessage result = parser.parse("terra1/SHT35/data", null);
            assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.UNKNOWN);
        });
    }

    @Test
    void parse_emptyPayload_returnsUnknownWithoutException() {
        assertThatNoException().isThrownBy(() -> {
            ParsedMqttMessage result = parser.parse("terra1/SHT35/data", "");
            assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.UNKNOWN);
        });
    }

    @Test
    void parse_nullTopic_returnsUnknownWithoutException() {
        assertThatNoException().isThrownBy(() -> {
            ParsedMqttMessage result = parser.parse(null, "{\"Temperature\":22.5}");
            assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.UNKNOWN);
        });
    }

    @Test
    void parse_emptyTopic_returnsUnknownWithoutException() {
        assertThatNoException().isThrownBy(() -> {
            ParsedMqttMessage result = parser.parse("", "{\"Temperature\":22.5}");
            assertThat(result.type()).isEqualTo(ParsedMqttMessage.MessageType.UNKNOWN);
        });
    }
}
