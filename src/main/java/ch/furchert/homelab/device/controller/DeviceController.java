package ch.furchert.homelab.device.controller;

import ch.furchert.homelab.device.dto.ControlCommandDto;
import ch.furchert.homelab.device.dto.DeviceStateDto;
import ch.furchert.homelab.device.entity.Device;
import ch.furchert.homelab.device.service.DeviceService;
import ch.furchert.homelab.device.service.MqttClientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST controller exposing device state and control endpoints.
 *
 * <p>All endpoints require a valid JWT (enforced by Spring Security).
 */
@Slf4j
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final MqttClientService mqttClientService;

    /**
     * Returns the current state of every known device.
     *
     * @return 200 with list of device states; empty list when no devices exist
     */
    @GetMapping
    public ResponseEntity<List<DeviceStateDto>> getAllDevices() {
        List<DeviceStateDto> dtos = deviceService.getAllDevices()
                .stream()
                .map(DeviceController::toDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Returns the current state of a single device.
     *
     * @param id the device primary key
     * @return 200 with device state, or 404 when not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<DeviceStateDto> getDevice(@PathVariable Long id) {
        Optional<Device> device = deviceService.getDevice(id);
        if (device.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDto(device.get()));
    }

    /**
     * Sends a control command for the specified device via MQTT.
     *
     * <p>The MQTT topic is {@code <deviceName>/<field>/man}, e.g.
     * {@code terra1/light/man}. The payload is
     * {@code {"<Field>State": <state>}}, e.g. {@code {"LightState": 1}}.
     *
     * @param id  the device primary key
     * @param cmd the control command (field name + desired state)
     * @return 200 on success, 404 when device not found
     */
    @PostMapping("/{id}/control")
    public ResponseEntity<Void> controlDevice(@PathVariable Long id,
                                              @Valid @RequestBody ControlCommandDto cmd) {
        Optional<Device> device = deviceService.getDevice(id);
        if (device.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String deviceName = device.get().getName();
        String topic = deviceName + "/" + cmd.field() + "/man";
        String payload = "{\"" + capitalize(cmd.field()) + "State\": " + cmd.state() + "}";

        log.info("Control command: topic='{}' payload='{}'", topic, payload);
        mqttClientService.publish(topic, payload, 1, false);

        return ResponseEntity.ok().build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a {@link Device} entity to a {@link DeviceStateDto}.
     *
     * <p>Note: intentionally duplicated from {@code WebSocketBroadcastService}
     * per project conventions — DRY refactoring is out of scope here.
     */
    private static DeviceStateDto toDto(Device device) {
        return new DeviceStateDto(
                device.getId(),
                device.getName(),
                device.getMqttOnline(),
                device.getTemperature(),
                device.getHumidity(),
                device.getLight(),
                device.getNightLight(),
                device.getRain(),
                device.getLastSeen() != null ? device.getLastSeen().toString() : null
        );
    }

    /**
     * Capitalizes the first character of the given string.
     * Example: "light" → "Light", "nightLight" → "NightLight".
     *
     * @param s input string; must not be null or empty (enforced by @NotBlank)
     * @return string with first character uppercased
     */
    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
