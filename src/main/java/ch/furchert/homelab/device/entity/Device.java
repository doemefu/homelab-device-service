package ch.furchert.homelab.device.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * JPA entity for the {@code devices} table.
 * <p>
 * This table is owned by homelab-device-service. The schema is managed
 * exclusively via Flyway (V1__create_devices.sql). Do not use ddl-auto
 * create or update.
 */
@Entity
@Table(name = "devices")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique device name, e.g. "terra1" or "terra2". */
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /** Whether the device is currently connected to the MQTT broker. */
    @Builder.Default
    @Column(name = "mqtt_online")
    private Boolean mqttOnline = false;

    /** Latest temperature reading in degrees Celsius. */
    private Double temperature;

    /** Latest relative humidity reading in percent. */
    private Double humidity;

    /** Latest main-light state, e.g. "on" or "off". */
    @Column(length = 10)
    private String light;

    /** Latest night-light state, e.g. "on" or "off". */
    @Column(name = "night_light", length = 10)
    private String nightLight;

    /** Latest rain/misting state, e.g. "on" or "off". */
    @Column(length = 10)
    private String rain;

    /** Timestamp of the last MQTT message received from this device. */
    @Column(name = "last_seen")
    private LocalDateTime lastSeen;
}
