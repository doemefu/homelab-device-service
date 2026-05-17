package ch.furchert.homelab.device.client;

import ch.furchert.homelab.device.dto.AuthClientCreated;

/**
 * Service-to-service client for the auth-service device-client admin API
 * ({@code /api/v1/clients}, see auth-service {@code INTERFACES.md} §8).
 *
 * <p>Calls are authenticated automatically via the OAuth2
 * {@code client_credentials} grant of the {@code device-service-admin}
 * registration; token acquisition, expiry refresh and 401-driven re-fetch are
 * handled transparently by Spring's {@code OAuth2ClientHttpRequestInterceptor}.
 */
public interface AuthServiceClient {

    /**
     * Provisions a new OAuth2 device client.
     *
     * @param clientId    the device id (== device name)
     * @param description optional description (may be null/blank)
     * @return the created client incl. the one-time plaintext secret
     * @throws ch.furchert.homelab.device.exception.DeviceAlreadyExistsException
     *         if auth-service reports a 409 conflict
     */
    AuthClientCreated createDeviceClient(String clientId, String description);

    /**
     * Revokes an OAuth2 device client. Idempotent — auth-service returns 204
     * even if the client does not exist.
     *
     * @param clientId the device id to revoke
     */
    void deleteDeviceClient(String clientId);
}
