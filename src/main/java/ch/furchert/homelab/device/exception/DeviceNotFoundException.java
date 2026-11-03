package ch.furchert.homelab.device.exception;

/**
 * Thrown when an operation targets a device name that does not exist.
 * Mapped to HTTP 404 by {@code GlobalExceptionHandler}.
 */
public class DeviceNotFoundException extends RuntimeException {
    public DeviceNotFoundException(String name) {
        super("Device not found: " + name);
    }
}
