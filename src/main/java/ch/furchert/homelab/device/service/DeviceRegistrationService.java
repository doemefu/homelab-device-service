package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.client.AuthServiceClient;
import ch.furchert.homelab.device.dto.AuthClientCreated;
import ch.furchert.homelab.device.dto.DeviceRegisteredResponse;
import ch.furchert.homelab.device.dto.RegisterDeviceRequest;
import ch.furchert.homelab.device.entity.Device;
import ch.furchert.homelab.device.exception.DeviceAlreadyExistsException;
import ch.furchert.homelab.device.exception.DeviceNotFoundException;
import ch.furchert.homelab.device.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestrates device registration across auth-service and the local DB.
 *
 * <p>Order matters (SPEC §4): the OAuth2 client is provisioned in auth-service
 * <strong>first</strong> (it can fail for many reasons — duplicate, network,
 * auth), then the device row is written locally. If the local write fails the
 * auth-service client is compensated (deleted). {@code @Transactional} only
 * protects the local write; auth-service is the source of truth for "client
 * exists".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRegistrationService {

    private final DeviceRepository deviceRepository;
    private final AuthServiceClient authServiceClient;

    /**
     * Registers a device: provision auth-service client → persist row, with
     * compensation on local failure.
     *
     * @param req validated registration request
     * @return the device metadata plus the one-time client secret
     * @throws DeviceAlreadyExistsException if the name is already taken
     */
    @Transactional
    public DeviceRegisteredResponse register(RegisterDeviceRequest req) {
        String name = req.name();
        if (deviceRepository.existsByName(name)) {
            throw new DeviceAlreadyExistsException(name);
        }

        // 1. Provision the OAuth2 client FIRST (external source of truth).
        AuthClientCreated created = authServiceClient.createDeviceClient(name, req.description());

        // 2. Persist locally. saveAndFlush forces the INSERT now so a
        //    constraint violation surfaces inside this try (not at tx commit,
        //    which is after the method returns and too late to compensate).
        try {
            Device device = Device.builder()
                    .name(name)
                    .type(req.type())
                    .description(req.description())
                    .provisioned(true)
                    .build();
            deviceRepository.saveAndFlush(device);
        } catch (RuntimeException e) {
            log.error("Local persist failed after auth-service client creation "
                    + "for device '{}' — compensating by deleting the auth client", name, e);
            compensate(name);
            if (e instanceof DataIntegrityViolationException) {
                // Lost a race against a concurrent registration of the same name.
                throw new DeviceAlreadyExistsException(name);
            }
            throw e;
        }

        return new DeviceRegisteredResponse(
                name,
                req.type(),
                created.clientId(),
                created.clientSecret(),
                created.scopes(),
                created.createdAt(),
                name,
                List.of(name + "/#", "terraGeneral/#"));
    }

    /**
     * Removes a device locally then revokes its auth-service client. If the
     * auth-service call fails the local row is already gone — log/alert and do
     * not block (an admin can clean up auth-service later).
     *
     * @param name the device name
     * @throws DeviceNotFoundException if no such device exists
     */
    @Transactional
    public void delete(String name) {
        Device device = deviceRepository.findByName(name)
                .orElseThrow(() -> new DeviceNotFoundException(name));
        deviceRepository.delete(device);
        try {
            authServiceClient.deleteDeviceClient(name);
        } catch (RuntimeException e) {
            log.error("Local device '{}' deleted but auth-service client revocation "
                    + "failed — manual cleanup required (clientId={})", name, name, e);
        }
    }

    private void compensate(String name) {
        try {
            authServiceClient.deleteDeviceClient(name);
        } catch (RuntimeException ce) {
            // Inconsistent state: auth client exists, no local row, compensation
            // failed. Logged at ERROR (Sentry-visible) with both ids per SPEC Risks.
            log.error("COMPENSATION FAILED — orphaned auth-service client "
                    + "(clientId={}, deviceName={}); manual reconciliation required",
                    name, name, ce);
        }
    }
}
