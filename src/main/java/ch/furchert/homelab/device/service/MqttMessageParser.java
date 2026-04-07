package ch.furchert.homelab.device.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Parses raw MQTT topic + payload pairs into typed {@link ParsedMqttMessage} instances.
 *
 * <p>Recognised topic patterns (N = any digit suffix, e.g. "terra1", "terra2"):
 * <ul>
 *   <li>{@code terraN/SHT35/data}   → {@link ParsedMqttMessage.MessageType#SENSOR_DATA}</li>
 *   <li>{@code terraN/mqtt/status}  → {@link ParsedMqttMessage.MessageType#MQTT_STATUS}</li>
 *   <li>{@code terraN/light}        → {@link ParsedMqttMessage.MessageType#LIGHT_STATE}</li>
 *   <li>{@code terraN/nightLight}   → {@link ParsedMqttMessage.MessageType#NIGHT_LIGHT_STATE}</li>
 *   <li>{@code terraN/rain}         → {@link ParsedMqttMessage.MessageType#RAIN_STATE}</li>
 * </ul>
 *
 * <p>Any topic that does not match, or any payload that cannot be parsed, results in a
 * {@link ParsedMqttMessage.MessageType#UNKNOWN} message. Malformed payloads are logged at
 * WARN level and never propagated as exceptions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttMessageParser {

    private final ObjectMapper objectMapper;

    /**
     * Parse a single MQTT message.
     *
     * @param topic   the full topic string, e.g. {@code terra1/SHT35/data}
     * @param payload the UTF-8 decoded payload, e.g. {@code {"Temperature":22.5,"Humidity":65.0}}
     * @return a {@link ParsedMqttMessage} whose {@code type} is never null;
     *         all fields irrelevant to the detected type are null
     */
    public ParsedMqttMessage parse(String topic, String payload) {
        if (topic == null || topic.isBlank()) {
            return unknown(null);
        }

        String[] parts = topic.split("/");

        if (!parts[0].startsWith("terra")) {
            return unknown(null);
        }

        String deviceName = parts[0];

        // Only actual device names (terra1, terra2, …) — not terraGeneral or other prefixes
        if (!deviceName.matches("terra\\d+")) {
            return unknown(null);
        }

        try {
            // terra{n}/SHT35/data — exactly 3 segments
            if (parts.length == 3
                    && parts[1].equals("SHT35")
                    && parts[2].equals("data")) {
                return parseSensorData(deviceName, payload);
            }

            // terra{n}/mqtt/status — exactly 3 segments
            if (parts.length == 3
                    && parts[1].equals("mqtt")
                    && parts[2].equals("status")) {
                return parseMqttStatus(deviceName, payload);
            }

            // terra{n}/light — exactly 2 segments
            if (parts.length == 2 && parts[1].equals("light")) {
                return parseLightState(deviceName, payload);
            }

            // terra{n}/nightLight — exactly 2 segments
            if (parts.length == 2 && parts[1].equals("nightLight")) {
                return parseNightLightState(deviceName, payload);
            }

            // terra{n}/rain — exactly 2 segments
            if (parts.length == 2 && parts[1].equals("rain")) {
                return parseRainState(deviceName, payload);
            }

        } catch (Exception e) {
            log.warn("Failed to parse MQTT message on topic '{}': {}", topic, e.getMessage());
            return unknown(deviceName);
        }

        return unknown(deviceName);
    }

    // --- private helpers ---

    private ParsedMqttMessage parseSensorData(String deviceName, String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        if (!node.has("Temperature") || !node.has("Humidity")) {
            log.warn("Sensor data payload missing required fields for device '{}'", deviceName);
            return unknown(deviceName);
        }
        double temperature = node.get("Temperature").asDouble();
        double humidity = node.get("Humidity").asDouble();
        return new ParsedMqttMessage(
                deviceName,
                ParsedMqttMessage.MessageType.SENSOR_DATA,
                temperature,
                humidity,
                null,
                null,
                null,
                null
        );
    }

    private ParsedMqttMessage parseMqttStatus(String deviceName, String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        if (!node.has("MqttState")) {
            log.warn("MQTT status payload missing 'MqttState' for device '{}'", deviceName);
            return unknown(deviceName);
        }
        boolean online = node.get("MqttState").asInt() == 1;
        return new ParsedMqttMessage(
                deviceName,
                ParsedMqttMessage.MessageType.MQTT_STATUS,
                null,
                null,
                online,
                null,
                null,
                null
        );
    }

    private ParsedMqttMessage parseLightState(String deviceName, String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        if (!node.has("LightState")) {
            log.warn("Light state payload missing 'LightState' for device '{}'", deviceName);
            return unknown(deviceName);
        }
        String state = String.valueOf(node.get("LightState").asInt());
        return new ParsedMqttMessage(
                deviceName,
                ParsedMqttMessage.MessageType.LIGHT_STATE,
                null,
                null,
                null,
                state,
                null,
                null
        );
    }

    private ParsedMqttMessage parseNightLightState(String deviceName, String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        if (!node.has("NightLightState")) {
            log.warn("Night light payload missing 'NightLightState' for device '{}'", deviceName);
            return unknown(deviceName);
        }
        String state = String.valueOf(node.get("NightLightState").asInt());
        return new ParsedMqttMessage(
                deviceName,
                ParsedMqttMessage.MessageType.NIGHT_LIGHT_STATE,
                null,
                null,
                null,
                null,
                state,
                null
        );
    }

    private ParsedMqttMessage parseRainState(String deviceName, String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        if (!node.has("RainState")) {
            log.warn("Rain state payload missing 'RainState' for device '{}'", deviceName);
            return unknown(deviceName);
        }
        String state = String.valueOf(node.get("RainState").asInt());
        return new ParsedMqttMessage(
                deviceName,
                ParsedMqttMessage.MessageType.RAIN_STATE,
                null,
                null,
                null,
                null,
                null,
                state
        );
    }

    private static ParsedMqttMessage unknown(String deviceName) {
        return new ParsedMqttMessage(deviceName, ParsedMqttMessage.MessageType.UNKNOWN,
                null, null, null, null, null, null);
    }
}
