# Changelog

## [Unreleased]

### Added
- Device registration: `POST /devices` (ADMIN) provisions an auth-service
  OAuth2 `client_credentials` client then persists the device (compensating
  on local failure) and returns the one-time `clientSecret`;
  `DELETE /devices/{name}` (ADMIN) removes both. New Flyway `V3` adds
  `type`, `description`, `created_at`, `provisioned` to `devices`. Adds a
  `device-service-admin` `client_credentials` OAuth2 registration and a
  `role` → `ROLE_*` JWT authorities mapping.
- Initial implementation of homelab-device-service
- MQTT client (Eclipse Paho 1.2.5) with auto-reconnect and LWT
- MQTT message parser for all topic patterns (sensor data, device status, light/nightlight/rain state)
- Device state persistence in PostgreSQL (`devices` table via Flyway V1)
- InfluxDB writer for sensor measurements (write-only, measurement `terrarium`)
- Scheduler service with `schedules` table (Flyway V2), registering CronTriggers
- WebSocket broadcast (STOMP at `/ws`, topics `/topic/terrarium/{deviceName}`)
- Device control REST endpoint (`POST /devices/{id}/control` -> MQTT publish)
- Device list REST endpoints (`GET /devices`, `GET /devices/{id}`)
- OAuth2 Resource Server JWT validation via auth-service JWKS endpoint
- Spring Boot 4.0.5 / Java 25 / Spring Security 7
- Unit tests: MqttMessageParser, DeviceService, InfluxWriterService, SchedulerService, DeviceController
- Integration tests: MQTT (Mosquitto container), InfluxDB writer, full flow
- OpenAPI / Swagger UI documentation
