package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.entity.Device;
import ch.furchert.homelab.device.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DeviceService}.
 *
 * <p>All dependencies are mocked; no Spring context or database required.
 */
@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private WebSocketBroadcastService webSocketBroadcastService;

    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        deviceService = new DeviceService(deviceRepository, webSocketBroadcastService);
    }

    // -------------------------------------------------------------------------
    // updateDeviceState — SENSOR_DATA
    // -------------------------------------------------------------------------

    @Test
    void updateDeviceState_sensorData_updatesFieldsAndBroadcasts() {
        Device existing = Device.builder().name("terra1").build();
        when(deviceRepository.findByName("terra1")).thenReturn(Optional.of(existing));
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1",
                ParsedMqttMessage.MessageType.SENSOR_DATA,
                23.5, 70.0,
                null, null, null, null
        );

        deviceService.updateDeviceState(msg);

        ArgumentCaptor<Device> savedCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(savedCaptor.capture());
        Device saved = savedCaptor.getValue();

        assertThat(saved.getName()).isEqualTo("terra1");
        assertThat(saved.getTemperature()).isEqualTo(23.5);
        assertThat(saved.getHumidity()).isEqualTo(70.0);
        assertThat(saved.getLastSeen()).isNotNull();

        verify(webSocketBroadcastService).broadcastDeviceState(saved);
    }

    // -------------------------------------------------------------------------
    // updateDeviceState — MQTT_STATUS
    // -------------------------------------------------------------------------

    @Test
    void updateDeviceState_mqttStatus_online_setsMqttOnlineAndSaves() {
        Device existing = Device.builder().name("terra1").mqttOnline(false).build();
        when(deviceRepository.findByName("terra1")).thenReturn(Optional.of(existing));
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1",
                ParsedMqttMessage.MessageType.MQTT_STATUS,
                null, null,
                true, null, null, null
        );

        deviceService.updateDeviceState(msg);

        ArgumentCaptor<Device> savedCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getMqttOnline()).isTrue();

        verify(webSocketBroadcastService).broadcastDeviceState(any());
    }

    // -------------------------------------------------------------------------
    // updateDeviceState — UNKNOWN
    // -------------------------------------------------------------------------

    @Test
    void updateDeviceState_unknown_doesNotSave() {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1",
                ParsedMqttMessage.MessageType.UNKNOWN,
                null, null, null, null, null, null
        );

        deviceService.updateDeviceState(msg);

        verifyNoInteractions(deviceRepository);
        verifyNoInteractions(webSocketBroadcastService);
    }

    // -------------------------------------------------------------------------
    // updateDeviceState — unknown device name creates new device
    // -------------------------------------------------------------------------

    @Test
    void updateDeviceState_unknownDeviceName_createsNewDevice() {
        when(deviceRepository.findByName("terraNew")).thenReturn(Optional.empty());
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terraNew",
                ParsedMqttMessage.MessageType.SENSOR_DATA,
                19.0, 55.0,
                null, null, null, null
        );

        deviceService.updateDeviceState(msg);

        ArgumentCaptor<Device> savedCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(savedCaptor.capture());

        assertThat(savedCaptor.getValue().getName()).isEqualTo("terraNew");
        assertThat(savedCaptor.getValue().getTemperature()).isEqualTo(19.0);
    }

    // -------------------------------------------------------------------------
    // getAllDevices
    // -------------------------------------------------------------------------

    @Test
    void getAllDevices_returnsListFromRepository() {
        List<Device> devices = List.of(
                Device.builder().name("terra1").build(),
                Device.builder().name("terra2").build()
        );
        when(deviceRepository.findAll()).thenReturn(devices);

        List<Device> result = deviceService.getAllDevices();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("terra1");
        assertThat(result.get(1).getName()).isEqualTo("terra2");
        verify(deviceRepository).findAll();
    }

    // -------------------------------------------------------------------------
    // getDevice
    // -------------------------------------------------------------------------

    @Test
    void getDevice_found_returnsOptionalWithDevice() {
        Device device = Device.builder().name("terra1").build();
        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));

        Optional<Device> result = deviceService.getDevice(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("terra1");
    }

    @Test
    void getDevice_notFound_returnsEmptyOptional() {
        when(deviceRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<Device> result = deviceService.getDevice(99L);

        assertThat(result).isEmpty();
    }
}
