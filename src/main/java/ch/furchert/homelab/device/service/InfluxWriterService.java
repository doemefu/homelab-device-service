package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.config.InfluxDbProperties;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Writes sensor measurements to InfluxDB.
 *
 * <p>Only messages of type {@link ParsedMqttMessage.MessageType#SENSOR_DATA} are
 * persisted; all other types are silently ignored. Write failures are caught,
 * logged at ERROR level, and not rethrown — the MQTT processing pipeline must
 * not be interrupted by a transient storage error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfluxWriterService {

    private final WriteApiBlocking writeApi;
    private final InfluxDbProperties props;

    /**
     * Writes the sensor readings contained in {@code msg} to InfluxDB.
     *
     * <p>The method is a no-op for any {@link ParsedMqttMessage.MessageType} other than
     * {@code SENSOR_DATA}.
     *
     * @param msg the parsed MQTT message; must not be null
     */
    public void write(ParsedMqttMessage msg) {
        if (msg.type() != ParsedMqttMessage.MessageType.SENSOR_DATA) {
            return;
        }

        Point point = Point.measurement("terrarium")
                .addTag("device", msg.deviceName())
                .addField("temperature", msg.temperature())
                .addField("humidity", msg.humidity())
                .time(Instant.now(), WritePrecision.MS);

        try {
            writeApi.writePoint(props.getBucket(), props.getOrg(), point);
            log.debug("Wrote sensor data to InfluxDB for device '{}'", msg.deviceName());
        } catch (Exception e) {
            log.error("Failed to write sensor data to InfluxDB for device '{}': {}",
                    msg.deviceName(), e.getMessage(), e);
        }
    }
}
