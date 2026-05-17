package ch.furchert.homelab.device.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /devices}.
 *
 * <p>{@code clientSecret} is the auth-service-issued plaintext secret, returned
 * <strong>exactly once</strong>. It is never persisted by device-service and
 * must never be logged (see {@code GlobalExceptionHandler} and the SPEC Risks).
 *
 * @param name             canonical device id
 * @param type             device type (may be null)
 * @param clientId         OAuth2 client id (== {@code name})
 * @param clientSecret     one-time plaintext secret to flash onto the device
 * @param scopes           scopes granted to the device client
 * @param createdAt        auth-service client creation timestamp
 * @param mqttUsername     MQTT username (== {@code name})
 * @param mqttTopicsAllowed topic patterns the device may use
 */
public record DeviceRegisteredResponse(
        String name,
        String type,
        String clientId,
        String clientSecret,
        List<String> scopes,
        Instant createdAt,
        String mqttUsername,
        List<String> mqttTopicsAllowed
) {}
