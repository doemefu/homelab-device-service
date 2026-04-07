package ch.furchert.homelab.device.dto;

/**
 * WebSocket broadcast payload for a device state update.
 *
 * <p>{@code lastSeen} is serialised as an ISO-8601 string so that the JPA
 * entity ({@code LocalDateTime}) is never sent directly over the wire.
 */
public record DeviceStateDto(
        Long id,
        String name,
        Boolean mqttOnline,
        Double temperature,
        Double humidity,
        String light,
        String nightLight,
        String rain,
        String lastSeen
) {}
