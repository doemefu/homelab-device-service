# Device Service — Design Spec

## Purpose

Real-time IoT device management service. Subscribes to MQTT, persists device state, writes sensor data to InfluxDB, owns and executes scheduled commands, broadcasts live state via WebSocket, and exposes REST APIs for device control and schedule management.

This is the IoT-facing backend. It handles everything between the MQTT broker and the frontend's real-time needs.

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 25 |
| Spring Boot | 4.0.5 |
| Eclipse Paho MQTT | 1.2.5 |
| influxdb-client-java | 7.2.0 |
| springdoc-openapi | 2.7.0 |
| Testcontainers BOM | 1.20.4 |
| Base image | eclipse-temurin:25-jre-alpine |

## Architecture Context

One of 3 microservices in the homelab IoT ecosystem:
- **auth-service** — JWT issuance, JWKS endpoint
- **device-service** (this) — MQTT, device state, InfluxDB, schedule execution, WebSocket
- **data-service** — InfluxDB queries for historical data, schedule CRUD (owns `schedules` table)

Device-service validates JWTs via auth-service's JWKS endpoint (cached, fetched once at startup).

## Database

**PostgreSQL** — `devices` table owned by this service; `schedules` table owned by data-service (read-only access here):

### `devices` table (Flyway V1 — owned by device-service)
```sql
CREATE TABLE devices (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(50) NOT NULL UNIQUE,
    mqtt_online     BOOLEAN DEFAULT FALSE,
    temperature     DOUBLE PRECISION,
    humidity        DOUBLE PRECISION,
    light           VARCHAR(10),
    night_light     VARCHAR(10),
    rain            VARCHAR(10),
    last_seen       TIMESTAMP
);

INSERT INTO devices (name) VALUES ('terra1'), ('terra2');
```

### `schedules` table (owned by data-service — read-only access)

> **Note:** This table is created and migrated by `homelab-data-service`. Device-service only reads active schedules to execute cron-triggered MQTT publishes. No Flyway migration for this table exists in this service.

**Flyway config:** `spring.flyway.table=flyway_schema_history_device` (separate from other services sharing the same PostgreSQL instance).

**JPA:** `spring.jpa.hibernate.ddl-auto=validate`

## REST API

### Device endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/devices` | JWT | List all devices with current state |
| GET | `/devices/{id}` | JWT | Single device state |
| POST | `/devices/{id}/control` | JWT | Send control command → MQTT publish |

### Schedule endpoints

> **Note:** Schedule CRUD endpoints are provided by `homelab-data-service`, which owns the `schedules` table. Device-service only reads active schedules internally for cron-triggered MQTT publishing — no REST endpoints for schedules are exposed here.

### WebSocket

| Protocol | Path | Auth | Description |
|----------|------|------|-------------|
| STOMP | `/ws` | None | Subscribe to `/topic/terrarium/{deviceName}` for live state |

## MQTT

### Subscribe (from ESP32 devices)

| Topic | Payload | Frequency |
|-------|---------|-----------|
| `terra{n}/SHT35/data` | `{"Temperature": 22.5, "Humidity": 65.0}` | 30s |
| `terra{n}/mqtt/status` | `{"MqttState": 1}` | On connect (retained) |
| `terra{n}/light` | `{"LightState": 1}` | On state change |
| `terra{n}/nightLight` | `{"NightLightState": 1}` | On state change |
| `terra{n}/rain` | `{"RainState": 1}` | On state change |

### Publish (from this service)

| Topic | Payload | Trigger |
|-------|---------|---------|
| `terra{n}/{field}/man` | `{"{Field}State": 0\|1}` | Manual control via REST |
| `terraGeneral/{field}/schedule` | `{"{Field}State": 0\|1}` | Scheduled task execution |
| `javaBackend/mqtt/status` | `{"MqttState": 0\|1}` | Service connect/disconnect |

### Client config
- Eclipse Paho `MqttClient` (synchronous)
- Auto-reconnect, clean session, keepalive 30s, connect timeout 10s
- LWT: `javaBackend/mqtt/status` → `{"MqttState": 0}`, QoS 1, retained
- On connect: subscribe all topics, publish `{"MqttState": 1}`
- `MqttCallbackExtended` for reconnect handling
- `@PostConstruct` connect, `@PreDestroy` disconnect

## InfluxDB

- Write-only (sensor measurements)
- `WriteApiBlocking` from `InfluxDBClient`
- On sensor MQTT message: write `Point` with measurement `terrarium`, tag `device`, fields (temperature, humidity, etc.)
- `WritePrecision.MS`
- Config: url, token, org, bucket via `app.influxdb.*` properties

## Scheduler

- `ThreadPoolTaskScheduler` bean
- On startup + on CRUD changes: load active schedules, compare with running tasks, cancel removed/changed, register new
- Also polls periodically (`app.scheduler.poll-interval`) as fallback
- Each task: publish MQTT message to configured topic with configured payload
- Track tasks in `ConcurrentHashMap<Long, ScheduledFuture<?>>`
- On schedule CRUD (create/update/delete): trigger immediate reload

## WebSocket

- STOMP endpoint at `/ws`
- Simple broker prefix `/topic`
- Broadcast to `/topic/terrarium/{deviceName}` on every device state change
- No authentication required (read-only broadcast)

## Security

- OAuth2 Resource Server with JWKS from auth-service
- JWT required for all REST endpoints
- ADMIN role required for schedule write operations (POST/PUT/DELETE)
- WebSocket unauthenticated (read-only)

## Package Structure

```
ch.furchert.homelab.device/
├── config/          SecurityConfig, MqttProperties, InfluxDbConfig, WebSocketConfig, SchedulerConfig
├── controller/      DeviceController, ScheduleController
├── dto/             DeviceDto, ControlCommandDto, ScheduleDto, ScheduleCreateDto
├── entity/          Device, Schedule
├── exception/       GlobalExceptionHandler, ResourceNotFoundException
├── repository/      DeviceRepository, ScheduleRepository
└── service/         DeviceService, MqttClientService, MqttMessageParser, MqttMessageHandler,
                     InfluxWriterService, WebSocketBroadcastService, SchedulerService
```

## Testing

### Unit tests
- `MqttMessageParserTest` — all topic/payload combos + malformed payloads
- `DeviceServiceTest` — state update logic, device lookup
- `InfluxWriterServiceTest` — Point construction verification
- `SchedulerServiceTest` — task registration, cancellation, reload logic
- `DeviceControllerTest` — MockMvc, REST endpoints, validation
- `ScheduleControllerTest` — MockMvc, CRUD endpoints, role-based access

### Integration tests
- `MqttIntegrationTest` — Mosquitto container, publish message, verify DB state
- `InfluxWriterIntegrationTest` — write via service, query back from InfluxDB container
- `WebSocketIntegrationTest` — STOMP client, trigger state change, verify broadcast
- `SchedulerIntegrationTest` — insert schedule with imminent cron, verify MQTT publish

### Testcontainers
- PostgreSQL: `PostgreSQLContainer`
- InfluxDB: `InfluxDBContainer` (from `testcontainers:influxdb2`)
- Mosquitto: `GenericContainer("eclipse-mosquitto:2")` with anonymous auth config (`mosquitto-test.conf`)

## Architecture Spec Update

> **Decision:** The `schedules` table remains owned by data-service. Device-service reads active schedules (read-only) to execute cron-triggered MQTT publishes. No schedule CRUD endpoints exist in this service.

## What User Needs To Provide

Before implementation can start, the user must generate the Spring Boot scaffold:

1. Go to **start.spring.io** with these settings:
   - Project: Maven | Language: Java | Spring Boot: 4.0.5
   - Group: `ch.furchert.homelab` | Artifact: `device-service`
   - Package name: `ch.furchert.homelab.device`
   - Packaging: Jar | Java: 25

2. Select these dependencies in the UI:
   - Spring Web
   - Spring Security
   - Spring Data JPA
   - PostgreSQL Driver
   - Flyway Migration
   - Validation
   - Lombok
   - Spring Boot Actuator
   - OAuth2 Resource Server
   - WebSocket

3. Download, extract into this repo root, verify `./mvnw clean package` compiles

4. Then we add manually to `pom.xml`: Eclipse Paho, influxdb-client-java, springdoc-openapi, Testcontainers, spring-boot-webmvc-test (test), spring-security-test (test)
