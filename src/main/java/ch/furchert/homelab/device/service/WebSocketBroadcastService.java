package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.dto.DeviceStateDto;
import ch.furchert.homelab.device.entity.Device;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Broadcasts device state updates to connected WebSocket clients via STOMP.
 *
 * <p>Publishes a {@link DeviceStateDto} to {@code /topic/terrarium/{deviceName}}
 * whenever a device's state changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Sends the current state of {@code device} to all subscribed WebSocket clients.
     *
     * @param device the device whose state changed; must not be null
     */
    public void broadcastDeviceState(Device device) {
        DeviceStateDto dto = new DeviceStateDto(
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

        messagingTemplate.convertAndSend("/topic/terrarium/" + device.getName(), dto);
        log.debug("Broadcast device state for {}", device.getName());
    }
}
