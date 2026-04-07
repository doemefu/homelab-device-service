package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.config.MqttProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Manages the Eclipse Paho MQTT client lifecycle.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Connect to the broker on startup with LWT and auto-reconnect.</li>
 *   <li>Subscribe to configured topics (QoS 1).</li>
 *   <li>Publish an online-status message on (re-)connect.</li>
 *   <li>Delegate incoming messages to {@link MqttMessageHandler}.</li>
 *   <li>Publish offline-status and disconnect cleanly on shutdown.</li>
 *   <li>Expose {@link #publish(String, String, int, boolean)} for other services.</li>
 * </ul>
 */
@Slf4j
@Service
public class MqttClientService implements MqttCallbackExtended {

    private static final String ONLINE_PAYLOAD = "{\"MqttState\": 1}";
    private static final String OFFLINE_PAYLOAD = "{\"MqttState\": 0}";

    private final MqttProperties props;
    private final MqttMessageHandler messageHandler;

    private MqttClient client;

    /**
     * @param props          bound MQTT configuration
     * @param messageHandler handler for incoming messages; {@code @Lazy} to break any
     *                       potential circular dependency with beans that inject this service
     */
    @Autowired
    public MqttClientService(MqttProperties props,
                              @Lazy MqttMessageHandler messageHandler) {
        this.props = props;
        this.messageHandler = messageHandler;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Connects to the broker, sets up LWT, subscribes to all configured topics. */
    @PostConstruct
    public void connect() {
        try {
            client = new MqttClient(props.getBrokerUrl(), props.getClientId(), new MemoryPersistence());
            client.setCallback(this);

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setKeepAliveInterval(30);
            opts.setConnectionTimeout(10);
            if (props.getUsername() != null && !props.getUsername().isBlank()) {
                opts.setUserName(props.getUsername());
            }
            if (props.getPassword() != null && !props.getPassword().isBlank()) {
                opts.setPassword(props.getPassword().toCharArray());
            }

            // Last-Will: published by the broker if the connection drops unexpectedly
            String willPayload = props.getWillPayload() != null
                    ? props.getWillPayload()
                    : OFFLINE_PAYLOAD;
            opts.setWill(
                    props.getWillTopic(),
                    willPayload.getBytes(StandardCharsets.UTF_8),
                    props.getQos(),
                    true
            );

            log.info("Connecting to MQTT broker {} as client '{}'", props.getBrokerUrl(), props.getClientId());
            client.connect(opts);
            // subscribeAll() and publishStatus() are called from connectComplete(),
            // which fires for both initial connect and reconnect — keeping a single code path.

        } catch (MqttException e) {
            log.error("Failed to connect to MQTT broker: {}", e.getMessage(), e);
        }
    }

    /** Publishes offline status and disconnects the MQTT client cleanly. */
    @PreDestroy
    public void disconnect() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                publishStatus(OFFLINE_PAYLOAD);
                client.disconnect();
                log.info("Disconnected from MQTT broker");
            }
            client.close();
        } catch (MqttException e) {
            log.warn("Error during MQTT disconnect: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // MqttCallbackExtended
    // -------------------------------------------------------------------------

    /**
     * Called after a successful (re-)connect.
     * Re-subscribes to all topics and re-publishes the online status so that
     * retained messages on the broker reflect the current state.
     */
    @Override
    public void connectComplete(boolean reconnect, String brokerUri) {
        if (reconnect) {
            log.info("Reconnected to MQTT broker {}", brokerUri);
        } else {
            log.info("Connected to MQTT broker {}", brokerUri);
        }
        // Subscribe and publish status for both initial connect and reconnect.
        // This is the single authoritative code path for post-connect setup.
        subscribeAll();
        publishStatus(ONLINE_PAYLOAD);
    }

    /** Delegates an incoming message to {@link MqttMessageHandler}. */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.debug("MQTT message received on topic '{}': {}", topic, payload);
        try {
            messageHandler.handle(topic, payload);
        } catch (Exception e) {
            log.error("Error handling MQTT message on topic '{}': {}", topic, e.getMessage(), e);
        }
    }

    /** Logs a warning when the connection is lost; auto-reconnect handles recovery. */
    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause.getMessage());
    }

    /** No-op — QoS 0/1 delivery confirmation not required here. */
    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // intentionally empty
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Publishes a message to the given topic.
     *
     * @param topic    MQTT topic string
     * @param payload  message body (UTF-8 string)
     * @param qos      Quality of Service level (0, 1, or 2)
     * @param retained whether the broker should retain the message
     */
    public void publish(String topic, String payload, int qos, boolean retained) {
        if (client == null || !client.isConnected()) {
            log.warn("Cannot publish to '{}': MQTT client not connected", topic);
            return;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retained);
            client.publish(topic, message);
            log.debug("Published to '{}': {}", topic, payload);
        } catch (MqttException e) {
            log.error("Failed to publish to '{}': {}", topic, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Subscribes to every topic listed in {@link MqttProperties#getTopics()}. */
    private void subscribeAll() {
        for (String topic : props.getTopics()) {
            try {
                client.subscribe(topic, props.getQos());
                log.info("Subscribed to MQTT topic '{}' (QoS {})", topic, props.getQos());
            } catch (MqttException e) {
                log.error("Failed to subscribe to topic '{}': {}", topic, e.getMessage(), e);
            }
        }
    }

    /**
     * Publishes a status payload to the will-topic retained so that other
     * clients always see the latest broker-connectivity state.
     *
     * @param statusPayload JSON payload, e.g. {@code {"MqttState": 1}}
     */
    private void publishStatus(String statusPayload) {
        publish(props.getWillTopic(), statusPayload, props.getQos(), true);
    }
}
