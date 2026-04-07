package ch.furchert.homelab.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /devices/{id}/control.
 *
 * @param field the device field to control, e.g. "light", "rain", "nightLight"
 * @param state the desired state: 0 (off) or 1 (on)
 */
public record ControlCommandDto(
        @NotBlank String field,
        @NotNull Integer state
) {}
