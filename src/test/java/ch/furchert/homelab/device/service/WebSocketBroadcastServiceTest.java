package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.dto.DeviceStateDto;
import ch.furchert.homelab.device.entity.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WebSocketBroadcastService}.
 *
 * <p>Verifies the STOMP destination pattern and the complete field-to-DTO mapping
 * performed before sending to connected WebSocket clients.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketBroadcastServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private WebSocketBroadcastService service;

    @BeforeEach
    void setUp() {
        service = new WebSocketBroadcastService(messagingTemplate);
    }

    // -------------------------------------------------------------------------
    // Destination topic
    // -------------------------------------------------------------------------

    @Test
    void broadcastDeviceState_sendsToTopicTerrariumDeviceName() {
        Device device = Device.builder().id(1L).name("terra1").build();

        service.broadcastDeviceState(device);

        verify(messagingTemplate).convertAndSend(
                eq("/topic/terrarium/terra1"), any(DeviceStateDto.class));
    }

    @Test
    void broadcastDeviceState_topicContainsDeviceName() {
        Device device = Device.builder().id(2L).name("terra2").build();

        service.broadcastDeviceState(device);

        verify(messagingTemplate).convertAndSend(
                argThat((String dest) -> dest.endsWith("/terra2")),
                any(DeviceStateDto.class));
    }

    // -------------------------------------------------------------------------
    // DTO field mapping
    // -------------------------------------------------------------------------

    @Test
    void broadcastDeviceState_mapsAllFieldsToDto() {
        LocalDateTime lastSeen = LocalDateTime.of(2026, 4, 14, 12, 0, 0);
        Device device = Device.builder()
                .id(1L)
                .name("terra1")
                .mqttOnline(true)
                .temperature(22.5)
                .humidity(65.0)
                .light("1")
                .nightLight("0")
                .rain("1")
                .lastSeen(lastSeen)
                .build();

        service.broadcastDeviceState(device);

        ArgumentCaptor<DeviceStateDto> captor = ArgumentCaptor.forClass(DeviceStateDto.class);
        verify(messagingTemplate).convertAndSend(any(String.class), captor.capture());

        DeviceStateDto dto = captor.getValue();
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.name()).isEqualTo("terra1");
        assertThat(dto.mqttOnline()).isTrue();
        assertThat(dto.temperature()).isEqualTo(22.5);
        assertThat(dto.humidity()).isEqualTo(65.0);
        assertThat(dto.light()).isEqualTo("1");
        assertThat(dto.nightLight()).isEqualTo("0");
        assertThat(dto.rain()).isEqualTo("1");
        assertThat(dto.lastSeen()).isEqualTo(lastSeen.toString());
    }

    @Test
    void broadcastDeviceState_withNullLastSeen_dtoLastSeenIsNull() {
        Device device = Device.builder().id(1L).name("terra1").mqttOnline(false).build();

        service.broadcastDeviceState(device);

        ArgumentCaptor<DeviceStateDto> captor = ArgumentCaptor.forClass(DeviceStateDto.class);
        verify(messagingTemplate).convertAndSend(any(String.class), captor.capture());
        assertThat(captor.getValue().lastSeen()).isNull();
    }

    @Test
    void broadcastDeviceState_offlineDevice_dtoMqttOnlineFalse() {
        Device device = Device.builder().id(1L).name("terra1").mqttOnline(false).build();

        service.broadcastDeviceState(device);

        ArgumentCaptor<DeviceStateDto> captor = ArgumentCaptor.forClass(DeviceStateDto.class);
        verify(messagingTemplate).convertAndSend(any(String.class), captor.capture());
        assertThat(captor.getValue().mqttOnline()).isFalse();
    }
}
