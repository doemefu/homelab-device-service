package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.service.ParsedMqttMessage.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MqttMessageHandler}.
 *
 * <p>Verifies the parse → dispatch routing:
 * <ul>
 *   <li>SENSOR_DATA → {@link DeviceService#updateDeviceState} + {@link InfluxWriterService#write}</li>
 *   <li>All other recognised types → {@link DeviceService#updateDeviceState} only</li>
 *   <li>UNKNOWN → neither service called</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MqttMessageHandlerTest {

    @Mock
    private MqttMessageParser parser;

    @Mock
    private DeviceService deviceService;

    @Mock
    private InfluxWriterService influxWriterService;

    private MqttMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MqttMessageHandler(parser, deviceService, influxWriterService);
    }

    // -------------------------------------------------------------------------
    // SENSOR_DATA — both DeviceService and InfluxWriterService called
    // -------------------------------------------------------------------------

    @Test
    void handle_sensorData_updatesDeviceAndWritesToInflux() {
        ParsedMqttMessage msg = sensorDataMsg("terra1", 22.5, 65.0);
        when(parser.parse("terra1/SHT35/data", "{}")).thenReturn(msg);

        handler.handle("terra1/SHT35/data", "{}");

        verify(deviceService).updateDeviceState(msg);
        verify(influxWriterService).write(msg);
    }

    // -------------------------------------------------------------------------
    // MQTT_STATUS — DeviceService only, Influx NOT called
    // -------------------------------------------------------------------------

    @Test
    void handle_mqttStatus_updatesDeviceButDoesNotWriteToInflux() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1", MessageType.MQTT_STATUS, null, null, true, null, null, null);
        when(parser.parse("terra1/mqtt/status", "{}")).thenReturn(msg);

        handler.handle("terra1/mqtt/status", "{}");

        verify(deviceService).updateDeviceState(msg);
        verifyNoInteractions(influxWriterService);
    }

    // -------------------------------------------------------------------------
    // LIGHT_STATE — DeviceService only, Influx NOT called
    // -------------------------------------------------------------------------

    @Test
    void handle_lightState_updatesDeviceButDoesNotWriteToInflux() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1", MessageType.LIGHT_STATE, null, null, null, "1", null, null);
        when(parser.parse(any(), any())).thenReturn(msg);

        handler.handle("terra1/light", "{\"LightState\":1}");

        verify(deviceService).updateDeviceState(msg);
        verifyNoInteractions(influxWriterService);
    }

    // -------------------------------------------------------------------------
    // NIGHT_LIGHT_STATE — DeviceService only, Influx NOT called
    // -------------------------------------------------------------------------

    @Test
    void handle_nightLightState_updatesDeviceButDoesNotWriteToInflux() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1", MessageType.NIGHT_LIGHT_STATE, null, null, null, null, "0", null);
        when(parser.parse(any(), any())).thenReturn(msg);

        handler.handle("terra1/nightLight", "{\"NightLightState\":0}");

        verify(deviceService).updateDeviceState(msg);
        verifyNoInteractions(influxWriterService);
    }

    // -------------------------------------------------------------------------
    // RAIN_STATE — DeviceService only, Influx NOT called
    // -------------------------------------------------------------------------

    @Test
    void handle_rainState_updatesDeviceButDoesNotWriteToInflux() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra2", MessageType.RAIN_STATE, null, null, null, null, null, "1");
        when(parser.parse(any(), any())).thenReturn(msg);

        handler.handle("terra2/rain", "{\"RainState\":1}");

        verify(deviceService).updateDeviceState(msg);
        verifyNoInteractions(influxWriterService);
    }

    // -------------------------------------------------------------------------
    // UNKNOWN — neither service called
    // -------------------------------------------------------------------------

    @Test
    void handle_unknownMessage_neitherServiceIsCalled() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                null, MessageType.UNKNOWN, null, null, null, null, null, null);
        when(parser.parse(any(), any())).thenReturn(msg);

        handler.handle("unknown/topic", "{}");

        verifyNoInteractions(deviceService);
        verifyNoInteractions(influxWriterService);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static ParsedMqttMessage sensorDataMsg(String device, double temp, double humidity) {
        return new ParsedMqttMessage(
                device, MessageType.SENSOR_DATA, temp, humidity, null, null, null, null);
    }
}
