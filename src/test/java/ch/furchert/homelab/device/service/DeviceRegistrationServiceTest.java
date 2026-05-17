package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.client.AuthServiceClient;
import ch.furchert.homelab.device.dto.AuthClientCreated;
import ch.furchert.homelab.device.dto.DeviceRegisteredResponse;
import ch.furchert.homelab.device.dto.RegisterDeviceRequest;
import ch.furchert.homelab.device.entity.Device;
import ch.furchert.homelab.device.exception.DeviceAlreadyExistsException;
import ch.furchert.homelab.device.exception.DeviceNotFoundException;
import ch.furchert.homelab.device.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DeviceRegistrationService} — focuses on the
 * compensation pattern and ordering guarantees (SPEC §4).
 */
@ExtendWith(MockitoExtension.class)
class DeviceRegistrationServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private AuthServiceClient authServiceClient;

    @InjectMocks
    private DeviceRegistrationService service;

    private static final AuthClientCreated CREATED = new AuthClientCreated(
            "terra3", "plaintext-secret", List.of("mqtt:pub", "mqtt:sub"), Instant.parse("2026-05-16T10:00:00Z"));

    private RegisterDeviceRequest req() {
        return new RegisterDeviceRequest("terra3", "terrarium", "Greenhouse 3");
    }

    @Test
    void register_happyPath_provisionsThenPersistsAndMaps() {
        when(deviceRepository.existsByName("terra3")).thenReturn(false);
        when(authServiceClient.createDeviceClient("terra3", "Greenhouse 3")).thenReturn(CREATED);
        when(deviceRepository.saveAndFlush(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        DeviceRegisteredResponse resp = service.register(req());

        assertThat(resp.name()).isEqualTo("terra3");
        assertThat(resp.clientId()).isEqualTo("terra3");
        assertThat(resp.clientSecret()).isEqualTo("plaintext-secret");
        assertThat(resp.scopes()).containsExactly("mqtt:pub", "mqtt:sub");
        assertThat(resp.mqttUsername()).isEqualTo("terra3");
        assertThat(resp.mqttTopicsAllowed()).containsExactly("terra3/#", "terraGeneral/#");

        // Ordering: auth client created before local persist.
        var inOrder = inOrder(authServiceClient, deviceRepository);
        inOrder.verify(authServiceClient).createDeviceClient("terra3", "Greenhouse 3");
        inOrder.verify(deviceRepository).saveAndFlush(any(Device.class));
    }

    @Test
    void register_persistsDeviceMarkedProvisioned() {
        when(deviceRepository.existsByName("terra3")).thenReturn(false);
        when(authServiceClient.createDeviceClient(any(), any())).thenReturn(CREATED);
        when(deviceRepository.saveAndFlush(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        service.register(req());

        verify(deviceRepository).saveAndFlush(argThat(d ->
                Boolean.TRUE.equals(d.getProvisioned())
                        && "terra3".equals(d.getName())
                        && "terrarium".equals(d.getType())));
    }

    @Test
    void register_duplicateLocally_throws409AndNeverCallsAuthService() {
        when(deviceRepository.existsByName("terra3")).thenReturn(true);

        assertThatThrownBy(() -> service.register(req()))
                .isInstanceOf(DeviceAlreadyExistsException.class);

        verifyNoInteractions(authServiceClient);
        verify(deviceRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_localSaveFails_compensatesAuthClientExactlyOnceAndRethrows() {
        when(deviceRepository.existsByName("terra3")).thenReturn(false);
        when(authServiceClient.createDeviceClient(any(), any())).thenReturn(CREATED);
        RuntimeException boom = new RuntimeException("DB down");
        when(deviceRepository.saveAndFlush(any(Device.class))).thenThrow(boom);

        assertThatThrownBy(() -> service.register(req())).isSameAs(boom);

        verify(authServiceClient, times(1)).deleteDeviceClient("terra3");
    }

    @Test
    void register_uniqueRace_compensatesAndThrows409() {
        when(deviceRepository.existsByName("terra3")).thenReturn(false);
        when(authServiceClient.createDeviceClient(any(), any())).thenReturn(CREATED);
        when(deviceRepository.saveAndFlush(any(Device.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> service.register(req()))
                .isInstanceOf(DeviceAlreadyExistsException.class);

        verify(authServiceClient, times(1)).deleteDeviceClient("terra3");
    }

    @Test
    void register_compensationAlsoFails_originalExceptionStillPropagates() {
        when(deviceRepository.existsByName("terra3")).thenReturn(false);
        when(authServiceClient.createDeviceClient(any(), any())).thenReturn(CREATED);
        RuntimeException boom = new RuntimeException("DB down");
        when(deviceRepository.saveAndFlush(any(Device.class))).thenThrow(boom);
        doThrow(new RuntimeException("auth-service down")).when(authServiceClient).deleteDeviceClient("terra3");

        assertThatThrownBy(() -> service.register(req())).isSameAs(boom);
    }

    @Test
    void delete_present_removesRowThenRevokesClient() {
        Device d = Device.builder().id(1L).name("terra3").build();
        when(deviceRepository.findByName("terra3")).thenReturn(Optional.of(d));

        service.delete("terra3");

        var inOrder = inOrder(deviceRepository, authServiceClient);
        inOrder.verify(deviceRepository).delete(d);
        inOrder.verify(authServiceClient).deleteDeviceClient("terra3");
    }

    @Test
    void delete_missing_throws404AndDoesNotCallAuthService() {
        when(deviceRepository.findByName("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("nope"))
                .isInstanceOf(DeviceNotFoundException.class);

        verifyNoInteractions(authServiceClient);
    }

    @Test
    void delete_authServiceFailure_doesNotThrow() {
        Device d = Device.builder().id(1L).name("terra3").build();
        when(deviceRepository.findByName("terra3")).thenReturn(Optional.of(d));
        doThrow(new RuntimeException("auth-service down")).when(authServiceClient).deleteDeviceClient("terra3");

        // Local row already deleted — must not propagate.
        service.delete("terra3");

        verify(deviceRepository).delete(d);
    }
}
