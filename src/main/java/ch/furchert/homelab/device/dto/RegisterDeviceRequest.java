package ch.furchert.homelab.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /devices}.
 *
 * <p>{@code name} is the canonical device id: it becomes the OAuth2
 * {@code clientId}, the MQTT username, and the MQTT topic prefix
 * ({@code name=terra3} → {@code terra3/#}). The pattern matches the
 * auth-service {@code clientId} constraint ({@code [a-z0-9-]{3,32}}).
 *
 * @param name        canonical device id
 * @param type        optional device type, e.g. "terrarium"
 * @param description optional free-text description
 */
public record RegisterDeviceRequest(
        @NotBlank
        @Pattern(regexp = "[a-z0-9-]{3,32}",
                message = "name must match [a-z0-9-]{3,32}")
        String name,

        @Size(max = 50)
        String type,

        @Size(max = 200)
        String description
) {}
