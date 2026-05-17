package ch.furchert.homelab.device.dto;

import java.time.Instant;
import java.util.List;

/**
 * Mirrors the auth-service {@code DeviceClientCreatedResponse} (201 body of
 * {@code POST /api/v1/clients}). See auth-service {@code INTERFACES.md} §8.
 *
 * @param clientId     created client id
 * @param clientSecret one-time plaintext secret
 * @param scopes       scopes granted to the client (e.g. mqtt:pub, mqtt:sub)
 * @param createdAt    creation timestamp
 */
public record AuthClientCreated(
        String clientId,
        String clientSecret,
        List<String> scopes,
        Instant createdAt
) {}
