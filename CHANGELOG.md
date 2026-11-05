# Changelog

## [Unreleased]

### Added
- Schedules REST API (#41): `GET /schedules[?device=]`, `GET /schedules/{id}` (any valid token), `POST`, `PUT` (full replace) and `DELETE /schedules/{id}` (role `ADMIN`). Writes re-register the scheduler tasks immediately; cron is validated (must fire in the future) and runs in UTC; `deviceName` must name an existing device and is a label only (schedules publish to `terraGeneral/{field}/schedule`).
- Device registration: `POST /devices` (ADMIN) provisions an auth-service
  OAuth2 `client_credentials` client then persists the device (compensating
  on local failure) and returns the one-time `clientSecret`;
  `DELETE /devices/{name}` (ADMIN) removes both. New Flyway `V3` adds
  `type`, `description`, `created_at`, `provisioned` to `devices`. Adds a
  `device-service-admin` `client_credentials` OAuth2 registration; calls to
  the auth-service admin API time out (connect 5 s, read 10 s), and an
  unknown creation outcome (timeout, reset) is compensated as well (#62).
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
- Security regression test: access tokens with header `typ: at+jwt` get 401 on `GET /devices` and `POST /devices/{id}/control`, verified against the auto-configured JWT decoder with a test-served JWKS (gate G5 of doemefu/homelab#168, #88)
- OpenAPI / Swagger UI documentation

### Security
- The one-time device `clientSecret` is redacted from the `toString()` of the DTOs that carry it (#62).
- JWT validation (#81, interim): the `iss` claim must be `https://auth.furchert.ch` (`JWT_ISSUER`), and ID tokens (claim `azp`) are rejected as bearer tokens on REST and STOMP `CONNECT`. The auth-service `role` claim is mapped to `ROLE_*` next to `SCOPE_*`; `POST /devices/{id}/control` and every other non-GET request under `/devices/**` need role `ADMIN` (403 otherwise), reads stay open to any valid access token. Audience and token-type checks follow doemefu/homelab-auth-service#101.
- Spring Boot 4.1.1 → 4.1.2: Jackson 2.21.7 / 3.1.7 resolve GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89, GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54, GHSA-q4xh-88c3-wmh7, GHSA-gx83-3vf8-gh7j and GHSA-wjgm-6hv5-3cvf in `jackson-core` / `jackson-databind`; the BOM now manages Tomcat 11.0.27, so the 11.0.25 override is removed; PostgreSQL JDBC 42.7.13 → 42.7.14 (#102).
- WebSocket/STOMP hardening (#84): STOMP `CONNECT` requires `Authorization: Bearer <jwt>`, validated by the resource-server JWT decoder (kill switch `app.websocket.require-auth`, default `true`); browser origins limited to `app.websocket.allowed-origins` (default `https://furchert.ch` instead of `*`); client `SEND` frames are accepted only for `/app/**`, so clients can no longer publish into `/topic/**`; `SUBSCRIBE` is limited to `/topic/terrarium/**`; client-sent server frames (`MESSAGE` etc.) are rejected.
- Deployment hardening (#85): `runAsNonRoot` (uid 100 / gid 101), `RuntimeDefault` seccomp, no privilege escalation, all capabilities dropped, read-only root filesystem with an `emptyDir` at `/tmp`.
- emSign ECC Root CA – G3, emSign Root CA – C1 and emSign ECC Root CA – C3 (eMudhra compromise) are not trusted: a temporary build step deleted them from the JRE cacerts (#94, #95) until Temurin 25.0.5+8, whose cacerts no longer contain them; the step is removed (doemefu/homelab#212).
- Temurin base images pinned by digest (#96, #97).
- Pin embedded Tomcat to 11.0.25, overriding the 11.0.24 managed by the Spring Boot 4.1.1 BOM — resolves three critical advisories (GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5) in `tomcat-embed-core` (#74).

### Fixed
- MQTT: a failed first connect at startup is retried in the background (1 s, doubling, at most every 60 s) until the broker is reachable. Before, a pod that started while the broker was down ran without MQTT until it was restarted, because Paho's automatic reconnect only covers a connection that succeeded once. The client now always connects with MQTT 3.1.1 and no longer falls back to MQTT 3.1 after a failed attempt (#114).
- Malformed JSON bodies and non-numeric path ids return `400` instead of `500` on all endpoints (#41).
- k8s: rollouts no longer start the new pod next to the running old one (`maxSurge: 0`, `maxUnavailable: 1`; the old pod starts terminating first). Both pods used the MQTT client id `device-service`, so the broker dropped one session after the other and the logs showed `MQTT connection lost` / `Reconnected` every ~2.6 s until the old pod exited, with two schedulers running meanwhile. A rollout now costs the startup time as downtime, typically about 45 to 95 s (#67).
- k8s: raise CPU limit to 1 CPU and startupProbe budget to 300 s — pods scheduled on the 4-core node exceeded the 150 s startup budget under the 500m quota and crash-looped for days (#60).
