package ch.furchert.homelab.device.service;

/**
 * Immutable result of parsing a single MQTT message.
 *
 * <p>Fields unrelated to the detected {@link MessageType} are {@code null}.
 * Callers should always check {@code type} before reading type-specific fields.
 *
 * @param deviceName       device segment of the topic (e.g. "terra1"); may be null for
 *                         topics that do not start with "terra"
 * @param type             detected message type; never null
 * @param temperature      degrees Celsius — set only when type is {@link MessageType#SENSOR_DATA}
 * @param humidity         relative humidity % — set only when type is {@link MessageType#SENSOR_DATA}
 * @param mqttOnline       true = online, false = offline — set only when type is {@link MessageType#MQTT_STATUS}
 * @param lightState       "0" or "1" — set only when type is {@link MessageType#LIGHT_STATE}
 * @param nightLightState  "0" or "1" — set only when type is {@link MessageType#NIGHT_LIGHT_STATE}
 * @param rainState        "0" or "1" — set only when type is {@link MessageType#RAIN_STATE}
 */
public record ParsedMqttMessage(
        String deviceName,
        MessageType type,
        Double temperature,
        Double humidity,
        Boolean mqttOnline,
        String lightState,
        String nightLightState,
        String rainState
) {

    /** All recognised MQTT message types for terrarium devices. */
    public enum MessageType {
        /** Temperature + humidity payload from the SHT35 sensor. */
        SENSOR_DATA,
        /** MQTT connection state report from firmware. */
        MQTT_STATUS,
        /** Main light on/off state. */
        LIGHT_STATE,
        /** Night-light on/off state. */
        NIGHT_LIGHT_STATE,
        /** Rain/misting on/off state. */
        RAIN_STATE,
        /** Topic did not match any known pattern or the payload could not be parsed. */
        UNKNOWN
    }
}
