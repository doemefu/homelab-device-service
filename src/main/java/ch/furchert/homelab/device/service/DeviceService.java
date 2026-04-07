package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.entity.Device;
import ch.furchert.homelab.device.repository.DeviceRepository;
import ch.furchert.homelab.device.service.ParsedMqttMessage.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Business logic for device state management.
 *
 * <p>Handles CRUD operations on {@link Device} entities and updates state
 * whenever a parsed MQTT message arrives. After every save the updated device
 * state is broadcast over WebSocket.
 *
 * <p>{@link WebSocketBroadcastService} is injected lazily to avoid a circular
 * dependency with beans that may in turn depend on this service.
 */
@Slf4j
@Service
@Transactional
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final WebSocketBroadcastService webSocketBroadcastService;

    @Autowired
    public DeviceService(DeviceRepository deviceRepository,
                         @Lazy WebSocketBroadcastService webSocketBroadcastService) {
        this.deviceRepository = deviceRepository;
        this.webSocketBroadcastService = webSocketBroadcastService;
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Returns all known devices.
     *
     * @return list of all devices; never null
     */
    @Transactional(readOnly = true)
    public List<Device> getAllDevices() {
        return deviceRepository.findAll();
    }

    /**
     * Looks up a single device by its database ID.
     *
     * @param id the primary key
     * @return the device, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<Device> getDevice(Long id) {
        return deviceRepository.findById(id);
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Applies the state contained in {@code msg} to the matching device row.
     *
     * <p>If no row exists for the device name yet, a new one is created with
     * sensible defaults. Messages of type {@link MessageType#UNKNOWN} are
     * silently ignored and do not result in a database write.
     *
     * @param msg the parsed MQTT message; must not be null
     */
    public void updateDeviceState(ParsedMqttMessage msg) {
        if (msg.type() == MessageType.UNKNOWN) {
            log.debug("Ignoring UNKNOWN message for device '{}'", msg.deviceName());
            return;
        }

        Device device = deviceRepository.findByName(msg.deviceName())
                .orElseGet(() -> {
                    log.info("Creating new device entry for '{}'", msg.deviceName());
                    return Device.builder()
                            .name(msg.deviceName())
                            .build();
                });

        switch (msg.type()) {
            case SENSOR_DATA -> {
                device.setTemperature(msg.temperature());
                device.setHumidity(msg.humidity());
                device.setLastSeen(LocalDateTime.now());
            }
            case MQTT_STATUS -> {
                device.setMqttOnline(msg.mqttOnline());
                device.setLastSeen(LocalDateTime.now());
            }
            case LIGHT_STATE -> {
                device.setLight(msg.lightState());
                device.setLastSeen(LocalDateTime.now());
            }
            case NIGHT_LIGHT_STATE -> {
                device.setNightLight(msg.nightLightState());
                device.setLastSeen(LocalDateTime.now());
            }
            case RAIN_STATE -> {
                device.setRain(msg.rainState());
                device.setLastSeen(LocalDateTime.now());
            }
            default -> {
                // Unreachable: UNKNOWN is handled above, all other enum values are covered.
                log.warn("Unhandled message type '{}' for device '{}'", msg.type(), msg.deviceName());
                return;
            }
        }

        Device saved = deviceRepository.save(device);
        webSocketBroadcastService.broadcastDeviceState(saved);
    }
}
