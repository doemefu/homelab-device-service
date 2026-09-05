# Changelog

## [Unreleased]

### Added
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
- Integration tests: WebSocket/STOMP — real client connection against a running server, per-device destination routing, and the MQTT-to-WebSocket broadcast chain (#39)
- Integration tests: scheduler — cron tasks driven from real `schedules` rows against the real `ThreadPoolTaskScheduler` and Mosquitto, covering registration, cancellation on deactivate and delete, payload-change re-registration, and invalid-cron skip (#40)
- OpenAPI / Swagger UI documentation

### Security
- Pin embedded Tomcat to 11.0.25, overriding the 11.0.24 managed by the Spring Boot 4.1.1 BOM — resolves three critical advisories (GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5) in `tomcat-embed-core` (#74).

### Fixed
- k8s: raise CPU limit to 1 CPU and startupProbe budget to 300 s — pods scheduled on the 4-core node exceeded the 150 s startup budget under the 500m quota and crash-looped for days (#60).
