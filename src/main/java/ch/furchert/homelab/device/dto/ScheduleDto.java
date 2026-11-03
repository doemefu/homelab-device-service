package ch.furchert.homelab.device.dto;

import java.time.OffsetDateTime;

/**
 * Response body for the schedules API.
 *
 * @param id             schedule id
 * @param deviceName     grouping label (see {@link ScheduleRequest})
 * @param field          actuator field
 * @param state          0 or 1 when {@code payload} has the standard {@code {"<Field>State": n}}
 *                       shape, otherwise {@code null}
 * @param payload        the MQTT payload published when the schedule fires
 * @param cronExpression Spring cron expression (service time zone, UTC)
 * @param active         whether the schedule runs ({@code false} for a NULL column)
 * @param nextRun        next fire time with offset, or {@code null} if inactive or not parseable
 */
public record ScheduleDto(
        Long id,
        String deviceName,
        String field,
        Integer state,
        String payload,
        String cronExpression,
        boolean active,
        OffsetDateTime nextRun
) {}
