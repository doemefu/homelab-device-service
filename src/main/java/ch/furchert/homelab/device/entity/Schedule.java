package ch.furchert.homelab.device.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Read-only JPA entity mapped to the {@code schedules} table.
 * <p>
 * This table is owned and migrated by the homelab-data-service. This service
 * only reads from it to drive the in-process scheduler. No Flyway migration
 * for this table exists here and none should ever be added.
 */
@Entity
@Table(name = "schedules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Name of the target device, e.g. "terra1".
     * Matches {@link Device#getName()}.
     */
    @Column(name = "device_name")
    private String deviceName;

    /**
     * The actuator field to control, e.g. "light", "rain", "nightLight".
     * Used to construct the MQTT publish topic.
     */
    private String field;

    /**
     * JSON payload to publish to MQTT, e.g. {@code {"LightState": 1}}.
     */
    private String payload;

    /**
     * Standard cron expression (6-part Spring format) defining when to fire,
     * e.g. {@code "0 0 8 * * *"} for 08:00 every day.
     */
    @Column(name = "cron_expression")
    private String cronExpression;

    /** When {@code false} the scheduler ignores this entry. */
    private Boolean active;
}
