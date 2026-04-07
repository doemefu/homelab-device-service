package ch.furchert.homelab.device.repository;

import ch.furchert.homelab.device.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link Device} entities.
 * <p>
 * Provides standard CRUD operations plus a lookup by device name,
 * which is the primary key used in MQTT topic routing.
 */
public interface DeviceRepository extends JpaRepository<Device, Long> {

    /**
     * Finds a device by its unique name (e.g. "terra1").
     *
     * @param name the device name as published in MQTT topics
     * @return the matching device, or empty if not found
     */
    Optional<Device> findByName(String name);
}
