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
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Manages the Eclipse Paho MQTT client lifecycle.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Connect to the broker on startup with LWT and auto-reconnect. If that first connect
 *       fails, retry it in the background with capped exponential backoff (#114): Paho's
 *       automatic reconnect only covers a connection that succeeded once.</li>
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

    /**
     * Paho TCP connect timeout. With MQTT 3.1.1 pinned an attempt makes one TCP connect. It does
     * not cover DNS resolution or the wait for CONNACK (Paho 1.2.5 has no CONNACK timeout); that
     * is what {@link ConnectRetryTiming#attemptTimeout()} bounds.
     */
    private static final int CONNECTION_TIMEOUT_SECONDS = 10;

    /**
     * Timing of the connect attempts after startup (package-private for tests).
     *
     * @param initialDelay   delay after the first failed attempt; doubles per further failure
     * @param maxDelay       cap of the delay between attempts
     * @param attemptTimeout bound of one attempt, including the wait for CONNACK
     * @param shutdownWait   how long shutdown waits for a running attempt before interrupting it
     */
    record ConnectRetryTiming(Duration initialDelay, Duration maxDelay,
                              Duration attemptTimeout, Duration shutdownWait) {

        /**
         * 1 s doubling up to 60 s; an attempt may take 30 s (DNS + 10 s TCP connect + CONNACK);
         * shutdown waits one TCP connect timeout plus 2 s, well inside the pod's 30 s grace period.
         */
        static final ConnectRetryTiming PRODUCTION = new ConnectRetryTiming(Duration.ofSeconds(1),
                Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS + 2));
    }

    private final MqttProperties props;
    private final MqttMessageHandler messageHandler;
    private final ConnectRetryTiming timing;

    /**
     * Runs the connect retries after a failed first connect. One daemon thread, created only
     * when the first retry is scheduled; delayed retries are dropped on {@code shutdown()}.
     */
    private final ScheduledThreadPoolExecutor connectRetryExecutor;

    private MqttClient client;
    private MqttConnectOptions connectOptions;
    private volatile boolean shuttingDown;

    /**
     * @param props          bound MQTT configuration
     * @param messageHandler handler for incoming messages; {@code @Lazy} to break any
     *                       potential circular dependency with beans that inject this service
     */
    @Autowired
    public MqttClientService(MqttProperties props,
                              @Lazy MqttMessageHandler messageHandler) {
        this(props, messageHandler, ConnectRetryTiming.PRODUCTION);
    }

    /** Test seam for the connect-retry timing. */
    MqttClientService(MqttProperties props, MqttMessageHandler messageHandler, ConnectRetryTiming timing) {
        this.props = props;
        this.messageHandler = messageHandler;
        this.timing = timing;
        this.connectRetryExecutor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "mqtt-connect-retry");
            thread.setDaemon(true);
            return thread;
        });
        this.connectRetryExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates the client and makes the first connect attempt, with LWT; subscriptions follow in
     * {@link #connectComplete(boolean, String)}. If the attempt fails, the startup is not blocked:
     * further attempts run in the background until one succeeds (see {@link #attemptConnect(int)}).
     */
    @PostConstruct
    public void connect() {
        try {
            client = new MqttClient(props.getBrokerUrl(), props.getClientId(), new MemoryPersistence());
            client.setCallback(this);

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setKeepAliveInterval(30);
            opts.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);
            // Pinned instead of Paho's default, which retries a failed 3.1.1 connect as 3.1 with a
            // second TCP connect and so doubles the time an attempt can take. Mosquitto 2 speaks 3.1.1.
            opts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
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

            connectOptions = opts;
        } catch (MqttException e) {
            // Creating the client failed (client persistence); retrying cannot fix that.
            log.error("Failed to create the MQTT client: {}", e.getMessage(), e);
            return;
        }

        log.info("Connecting to MQTT broker {} as client '{}'", props.getBrokerUrl(), props.getClientId());
        attemptConnect(1);
    }

    /**
     * One connect attempt on the single client. On failure the next attempt is scheduled with
     * {@link #retryDelay(int)}; the first success ends the chain, after which Paho's automatic
     * reconnect handles every later connection loss, so the two never run at the same time.
     * subscribeAll() and publishStatus() are called from connectComplete(), which fires for both
     * initial connect and reconnect — keeping a single code path.
     *
     * @param attempt 1-based number of this attempt
     */
    private void attemptConnect(int attempt) {
        if (shuttingDown) {
            return;
        }
        try {
            connectBounded();
            if (attempt > 1) {
                log.info("MQTT connect succeeded on attempt {}", attempt);
            }
        } catch (MqttException | RuntimeException e) {
            // RuntimeException too: on the retry thread it would otherwise end the retries
            // silently, because a scheduled task swallows what it throws.
            if (shuttingDown) {
                return;
            }
            Duration delay = retryDelay(attempt);
            if (attempt == 1) {
                // ERROR once per outage (a Sentry event); the retries below log at WARN.
                log.error("Failed to connect to MQTT broker: {}; retrying in {} ms",
                        e, delay.toMillis(), e);
            } else {
                // toString(): reason code and cause (e.g. "Connection refused") without a stack trace
                log.warn("MQTT connect attempt {} failed: {}; retrying in {} ms",
                        attempt, e.toString(), delay.toMillis());
            }
            try {
                connectRetryExecutor.schedule(() -> attemptConnect(attempt + 1),
                        delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException rejected) {
                // disconnect() shut the executor down in the meantime; nothing left to do.
                log.debug("MQTT connect retry not scheduled: shutting down");
            }
        }
    }

    /**
     * {@code client.connect} with the wait bounded by {@link ConnectRetryTiming#attemptTimeout()}.
     * The wait is restored afterwards, so publish and subscribe keep their behaviour.
     */
    private void connectBounded() throws MqttException {
        long previousTimeToWait = client.getTimeToWait();
        client.setTimeToWait(timing.attemptTimeout().toMillis());
        try {
            client.connect(connectOptions);
        } catch (MqttException e) {
            if (e.getReasonCode() == MqttException.REASON_CODE_CLIENT_TIMEOUT) {
                // No CONNACK in time. Paho is still connecting, and the next connect() would throw
                // CONNECT_IN_PROGRESS: reset the client. Without a cause this neither calls
                // connectionLost() nor starts Paho's own reconnect cycle.
                client.disconnectForcibly(0, 0, false);
            }
            throw e;
        } finally {
            client.setTimeToWait(previousTimeToWait);
        }
    }

    /**
     * Delay after the given number of failed attempts: the initial delay, doubled per further
     * failure, capped at the maximum (1 s, 2 s, 4 s ... 60 s in production).
     */
    Duration retryDelay(int failedAttempts) {
        Duration delay = timing.initialDelay();
        for (int i = 1; i < failedAttempts && delay.compareTo(timing.maxDelay()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(timing.maxDelay()) < 0 ? delay : timing.maxDelay();
    }

    /**
     * Stops pending connect retries, then publishes offline status and disconnects the MQTT
     * client cleanly. A connect attempt that is already running may finish first, so a connection
     * it opens is closed here as well. One that is still running after the shutdown wait is
     * interrupted, and the client is left to the JVM exit: Paho keeps connecting in the
     * background, so {@code close()} would fail, and a session it still opens ends with the
     * process (the broker then publishes the LWT).
     */
    @PreDestroy
    public void disconnect() {
        shuttingDown = true;
        boolean attemptStillRunning = !stopConnectRetries();
        if (client == null || attemptStillRunning) {
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

    /**
     * Drops pending retries and waits for a running attempt.
     *
     * @return {@code true} if no attempt is running any more
     */
    private boolean stopConnectRetries() {
        connectRetryExecutor.shutdown();
        try {
            if (connectRetryExecutor.awaitTermination(timing.shutdownWait().toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            log.warn("MQTT connect attempt still running after {} ms; interrupting it and "
                    + "skipping the MQTT disconnect", timing.shutdownWait().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connectRetryExecutor.shutdownNow();
        return false;
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
        if (shuttingDown) {
            // A connect that completed during shutdown: do not re-announce "online" after
            // disconnect() has published the offline status.
            log.info("Connected to MQTT broker {} during shutdown; skipping subscriptions", brokerUri);
            return;
        }
        if (reconnect) {
            log.info("Reconnected to MQTT broker {}", brokerUri);
        } else {
            log.info("Connected to MQTT broker {}", brokerUri);
        }
        // Subscribe and publish status for both initial connect and reconnect.
        // This is the single authoritative code path for post-connect setup.
        subscribeAll();
        // Checked again: shutdown may have started while subscribing (narrows, does not close,
        // the window in which "online" could follow the offline status).
        if (!shuttingDown) {
            publishStatus(ONLINE_PAYLOAD);
        }
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
