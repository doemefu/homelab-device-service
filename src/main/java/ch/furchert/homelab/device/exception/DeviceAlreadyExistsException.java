package ch.furchert.homelab.device.exception;

/**
 * Thrown when registering a device whose name is already taken — either in the
 * local {@code devices} table or reported as a conflict (409) by auth-service.
 * Mapped to HTTP 409 by {@code GlobalExceptionHandler}.
 */
public class DeviceAlreadyExistsException extends RuntimeException {
    public DeviceAlreadyExistsException(String name) {
        super("Device already exists: " + name);
    }
}
