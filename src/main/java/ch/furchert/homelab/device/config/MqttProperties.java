package ch.furchert.homelab.device.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Typed configuration properties for the MQTT broker connection.
 * Bound from the {@code app.mqtt.*} namespace in application.yaml.
 */
@Data
@ConfigurationProperties("app.mqtt")
public class MqttProperties {

    /** Full broker URL, e.g. {@code tcp://mosquitto:1883}. */
    private String brokerUrl;

    /** Unique client identifier sent to the broker. */
    private String clientId;

    /** Broker username. */
    private String username;

    /** Broker password — never log this value. */
    private String password;

    /**
     * Topic filters to subscribe to on connect.
     * Spring auto-splits a comma-separated YAML string into a List.
     */
    private List<String> topics;

    /** Default QoS level for subscriptions and publications. */
    private int qos;

    /** Last-Will topic used to signal broker connectivity state. */
    private String willTopic;

    /** Last-Will payload (JSON). Published retained so late subscribers see it. */
    private String willPayload;
}
