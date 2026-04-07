# Changelog

## [Unreleased]

### Added
- Initial implementation of homelab-device-service
- MQTT client (Eclipse Paho 1.2.5) with auto-reconnect and LWT
- MQTT message parser for all topic patterns (sensor data, device status, light/nightlight/rain state)
- Device state persistence in PostgreSQL (`devices` table via Flyway V1)
- InfluxDB writer for sensor measurements (write-only, measurement `terrarium`)
- Scheduler service reading from `schedules` table (owned by data-service), registering CronTriggers
- WebSocket broadcast (STOMP at `/ws`, topics `/topic/terrarium/{deviceName}`)
- Device control REST endpoint (`POST /devices/{id}/control` -> MQTT publish)
- Device list REST endpoints (`GET /devices`, `GET /devices/{id}`)
- OAuth2 Resource Server JWT validation via auth-service JWKS endpoint
- Spring Boot 4.0.5 / Java 25 / Spring Security 7
- Unit tests: MqttMessageParser, DeviceService, InfluxWriterService, SchedulerService, DeviceController
- Integration tests: MQTT (Mosquitto container), InfluxDB writer, full flow
- OpenAPI / Swagger UI documentation
