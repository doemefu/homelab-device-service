package ch.furchert.homelab.device.dto;

import ch.furchert.homelab.device.validation.ValidCron;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /schedules} and {@code PUT /schedules/{id}} (full replace).
 *
 * @param deviceName     an existing device name; a grouping label only, execution publishes to
 *                       {@code terraGeneral/{field}/schedule} for all devices
 * @param field          the actuator field: light, nightLight or rain
 * @param state          the state to set when the schedule fires: 0 or 1
 * @param cronExpression Spring cron expression, evaluated in the service's time zone (UTC)
 * @param active         whether the schedule runs; required so that a PUT never re-enables silently
 */
public record ScheduleRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9-]{3,32}$", message = "must match [a-z0-9-]{3,32}") String deviceName,
        @NotBlank @Pattern(regexp = "^(light|nightLight|rain)$", message = "field must be one of: light, nightLight, rain") String field,
        @NotNull @Min(0) @Max(1) Integer state,
        @NotBlank @Size(max = 100) @ValidCron String cronExpression,
        @NotNull Boolean active
) {}
