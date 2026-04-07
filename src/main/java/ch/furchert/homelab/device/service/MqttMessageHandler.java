package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.service.ParsedMqttMessage.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Processes incoming MQTT messages dispatched by {@link MqttClientService}.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Parse the raw topic + payload into a typed {@link ParsedMqttMessage}.</li>
 *   <li>Update persistent device state via {@link DeviceService}.</li>
 *   <li>Forward sensor readings to InfluxDB via {@link InfluxWriterService}
 *       (only for {@link MessageType#SENSOR_DATA} messages).</li>
 * </ol>
 *
 * <p>{@link InfluxWriterService} is injected lazily to avoid a circular
 * dependency between service beans that share a common lifecycle.
 */
@Slf4j
@Service
public class MqttMessageHandler {

    private final MqttMessageParser parser;
    private final DeviceService deviceService;
    private final InfluxWriterService influxWriterService;

    @Autowired
    public MqttMessageHandler(MqttMessageParser parser,
                               DeviceService deviceService,
                               @Lazy InfluxWriterService influxWriterService) {
        this.parser = parser;
        this.deviceService = deviceService;
        this.influxWriterService = influxWriterService;
    }

    /**
     * Process a single MQTT message.
     *
     * @param topic   the topic the message arrived on, e.g. {@code terra1/SHT35/data}
     * @param payload the UTF-8 decoded message payload
     */
    public void handle(String topic, String payload) {
        ParsedMqttMessage msg = parser.parse(topic, payload);

        if (msg.type() == MessageType.UNKNOWN) {
            log.debug("Ignoring UNKNOWN message on topic '{}'", topic);
            return;
        }

        deviceService.updateDeviceState(msg);

        if (msg.type() == MessageType.SENSOR_DATA) {
            influxWriterService.write(msg);
        }
    }
}
