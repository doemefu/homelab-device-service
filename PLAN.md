# homelab-device-service — Implementation Plan

## Context

The device-service is the **third service to build** (M2 in milestones, but built last due to dependencies). It's the most complex service: MQTT subscriber, device state persistence, InfluxDB writer, scheduled commands, and WebSocket broadcast. It's a long-running, stateful service.

**Repo:** `github.com/doemefu/homelab-device-service`
**Port:** 8081
**Database:** PostgreSQL — `devices` table (owns it), reads `schedules` table (data-service owns it)
**InfluxDB:** Write-only (sensor measurements)
**MQTT:** Eclipse Paho — subscribe to device topics, publish commands
**WebSocket:** STOMP at `/ws`, broadcast device state changes
**Package:** `ch.furchert.homelab.device`
**Depends on:** auth-service (JWKS), data-service (owns `schedules` table)

---

## Pinned Versions

| Component | Version |
|-----------|---------|
| Java | 25 |
| Spring Boot | 4.0.5 |
| Eclipse Paho MQTT | 1.2.5 |
| influxdb-client-java | 7.2.0 |
| Testcontainers BOM | 1.20.4 |
| springdoc-openapi | 2.7.0 |
| Base image | `eclipse-temurin:25-jre-alpine` |

---

## Spring Boot 4.0 Migration Notes

- **Flyway:** Use `spring-boot-starter-flyway` (selected in start.spring.io), no separate `flyway-database-postgresql`
- **Jackson 3:** New group ID `tools.jackson` — affects MQTT JSON message parsing
- **Testing:** Add `@AutoConfigureMockMvc` explicitly for MockMvc tests
- **WebSocket:** Spring Boot 4.0.5 fixed a WebSocket+Jackson startup bug (`#49749`) — use 4.0.5+
- **Spring Security 7.0:** Review security filter chain API changes

---

## Prerequisites (user action)

1. Create GitHub repo `doemefu/homelab-device-service`
2. Generate Spring Boot project at **start.spring.io** with settings below
3. Auth-service deployed (JWKS). Data-service deployed (`schedules` table exists in DB)

### start.spring.io Settings

- Project: Maven | Language: Java | Spring Boot: **4.0.5**
- Group: `ch.furchert.homelab` | Artifact: `device-service`
- Package name: `ch.furchert.homelab.device`
- Packaging: Jar | Java: 25

### start.spring.io Dependencies (select in UI)

- Spring Web
- Spring Security
- Spring Data JPA
- PostgreSQL Driver
- Flyway Migration
- Validation
- Lombok
- Spring Boot Actuator
- **OAuth2 Resource Server**
- **WebSocket**

### Additional Maven Dependencies (add manually to pom.xml)

```xml
<!-- Eclipse Paho MQTT -->
<dependency>
    <groupId>org.eclipse.paho</groupId>
    <artifactId>org.eclipse.paho.client.mqttv3</artifactId>
    <version>1.2.5</version>
</dependency>

<!-- InfluxDB client (write-only) -->
<dependency>
    <groupId>com.influxdb</groupId>
    <artifactId>influxdb-client-java</artifactId>
    <version>7.2.0</version>
</dependency>

<!-- springdoc-openapi -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.7.0</version>
</dependency>

<!-- Testcontainers BOM (in <dependencyManagement>) -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.20.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>

<!-- Testcontainers modules (test scope) -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>influxdb2</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

**Mosquitto Testcontainer:** No official module. Use `GenericContainer("eclipse-mosquitto:2")` with anonymous auth config.

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/devices` | JWT | List all devices with current state |
| GET | `/devices/{id}` | JWT | Single device state |
| POST | `/devices/{id}/control` | JWT | Send control command (-> MQTT publish) |
| WS | `/ws` | Optional | STOMP WebSocket, subscribe to `/topic/terrarium/{deviceId}` |

---

## Database Schema (Flyway V1)

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

-- Seed known devices
INSERT INTO devices (name) VALUES ('terra1'), ('terra2');
```

**Important:** This service does NOT create the `schedules` table. Data-service owns it. The `Schedule` entity here is read-only (no Flyway migration).

---

## Implementation Order

| Step | Component | Files | Rationale |
|------|-----------|-------|-----------|
| 1 | Flyway V1 migration | `db/migration/V1__create_devices.sql` | Schema first |
| 2 | Device entity + repository | `entity/Device.java`, `repository/DeviceRepository.java` | Foundation |
| 3 | Schedule entity (read-only) | `entity/Schedule.java`, `repository/ScheduleRepository.java` | Scheduler reads this, no migration |
| 4 | SecurityConfig (OAuth2 Resource Server) | `config/SecurityConfig.java` | JWKS validation, same pattern as data-service |
| 5 | MQTT config + client | `config/MqttProperties.java`, `service/MqttClientService.java` | Core capability, test early |
| 6 | MQTT message parser | `service/MqttMessageParser.java` | Parse JSON payloads by topic pattern |
| 7 | DeviceService | `service/DeviceService.java` | Update device state in DB on MQTT message |
| 8 | MqttMessageHandler | `service/MqttMessageHandler.java` | Wires MQTT callback -> parser -> DeviceService |
| 9 | InfluxDB writer | `config/InfluxDbConfig.java`, `service/InfluxWriterService.java` | Write sensor data per MQTT message |
| 10 | WebSocket config + broadcast | `config/WebSocketConfig.java`, `service/WebSocketBroadcastService.java` | STOMP setup, broadcast state changes |
| 11 | DeviceController (REST) | `controller/DeviceController.java` | GET devices, POST control |
| 12 | MQTT publish (control) | Extend `MqttClientService` | `terra{n}/{field}/man` on control |
| 13 | SchedulerService | `service/SchedulerService.java` | Read schedules, register CronTriggers, publish MQTT |
| 14 | Unit tests | `*Test.java` | Parser, services, controllers |
| 15 | Integration tests | `*IntegrationTest.java` | Testcontainers: Mosquitto + PostgreSQL + InfluxDB |
| 16 | Dockerfile | `Dockerfile` | Multi-stage, JRE-alpine, non-root |
| 17 | K8s manifest | `k8s/deployment.yml` | Deployment + Service |
| 18 | GitHub Actions | `.github/workflows/build.yml` | CI/CD multi-arch |
| 19 | README.md | `README.md` | MQTT topics, API, scheduling, config |

---

## Key Architectural Decisions

### MQTT Client
- Eclipse Paho `MqttClient` (synchronous)
- `MqttConnectOptions`: `setAutomaticReconnect(true)`, `setCleanSession(true)`, `setKeepAliveInterval(30)`, `setConnectionTimeout(10)`
- **LWT:** topic `javaBackend/mqtt/status`, payload `{"MqttState": 0}`, QoS 1, retained
- On connect: subscribe to all topics, publish `{"MqttState": 1}` to `javaBackend/mqtt/status`
- Implement `MqttCallbackExtended` (handles reconnect notification)
- `@PostConstruct` for initial connect, `@PreDestroy` for clean disconnect
- Config via `@ConfigurationProperties("app.mqtt")`

### MQTT Message Parser
- Single class, pattern-match on topic segments:
  - `terra{n}/SHT35/data` -> sensor data (temperature, humidity)
  - `terra{n}/mqtt/status` -> online/offline
  - `terra{n}/light` -> light state
  - `terra{n}/nightLight` -> nightlight state
  - `terra{n}/rain` -> rain state
- Returns `ParsedMqttMessage` record with device name, message type enum, parsed fields
- Uses Jackson `ObjectMapper` for JSON parsing (Jackson 3 in Spring Boot 4.0)

### InfluxDB Writer
- `WriteApiBlocking` from `InfluxDBClient`
- On each sensor MQTT message: write `Point` with measurement `terrarium`, tag `device`, fields (temperature, humidity, etc.)
- `WritePrecision.MS`

### Scheduler Service
- `ThreadPoolTaskScheduler` bean (configured in `@Configuration`)
- On startup + periodically (`@Scheduled(fixedDelayString = "${app.scheduler.poll-interval}")`):
  1. Query all active schedules from `schedules` table
  2. Compare with currently registered tasks (by schedule ID + hash of cron/topic/payload)
  3. Cancel removed/changed tasks, register new ones
- Each task: publish MQTT message to configured topic with configured payload
- Store `ScheduledFuture<?>` in `ConcurrentHashMap<Long, ScheduledFuture<?>>`

### WebSocket
- STOMP config: endpoint `/ws`, simple broker prefix `/topic`
- Broadcast to `/topic/terrarium/{deviceName}` on every device state change
- Auth optional for WebSocket (read-only broadcast)

### Schedule Entity (read-only)
- JPA entity mapped to `schedules` table, but **no Flyway migration** here
- Data-service owns the table and its schema evolution

### Flyway
- `spring.flyway.table=flyway_schema_history_device` (separate from other services)

---

## Configuration

```properties
spring.application.name=device-service
server.port=8081

# PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/homelabdb
spring.datasource.username=${DB_USERNAME:homelab}
spring.datasource.password=${DB_PASSWORD:homelab}
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

# Flyway
spring.flyway.enabled=true
spring.flyway.table=flyway_schema_history_device

# MQTT
app.mqtt.broker-url=tcp://localhost:1883
app.mqtt.client-id=device-service
app.mqtt.username=${MQTT_USERNAME:backend}
app.mqtt.password=${MQTT_PASSWORD}
app.mqtt.topics=terra1/#,terra2/#,terraGeneral/#
app.mqtt.qos=1
app.mqtt.will-topic=javaBackend/mqtt/status
app.mqtt.will-payload={"MqttState": 0}

# InfluxDB
app.influxdb.url=http://localhost:8086
app.influxdb.token=${INFLUX_TOKEN}
app.influxdb.org=homelab
app.influxdb.bucket=iot-bucket

# OAuth2 Resource Server
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8080/auth/jwks
# Prod: http://auth-service.apps.svc.cluster.local:8080/auth/jwks

# Scheduler
app.scheduler.poll-interval=60000

# Actuator
management.endpoints.web.exposure.include=health,info

# OpenAPI
springdoc.api-docs.path=/api-docs
springdoc.swagger-ui.path=/swagger-ui.html
```

---

## MQTT Topic Reference

### Device -> Broker (publish)

| Topic | Payload | Interval |
|-------|---------|----------|
| `terra{n}/SHT35/data` | `{"Temperature": 22.5, "Humidity": 65.0}` | 30s |
| `terra{n}/mqtt/status` | `{"MqttState": 1}` | On connect (retained) |
| `terra{n}/light` | `{"LightState": 1}` | On state change |
| `terra{n}/nightLight` | `{"NightLightState": 1}` | On state change |
| `terra{n}/rain` | `{"RainState": 1}` | On state change |

### device-service -> Broker (publish)

| Topic | Payload | Trigger |
|-------|---------|---------|
| `terra{n}/{field}/man` | `{"{Field}State": 0\|1}` | Manual control |
| `terraGeneral/{field}/schedule` | `{"{Field}State": 0\|1}` | Scheduled task |
| `javaBackend/mqtt/status` | `{"MqttState": 0\|1}` | Connect/disconnect |

---

## Tests

### Unit Tests
| Test | Scope |
|------|-------|
| `MqttMessageParserTest` | All topic/payload combos + malformed payloads |
| `DeviceServiceTest` | State update logic, upsert, device lookup |
| `InfluxWriterServiceTest` | Verify Point construction (measurement, tags, fields) |
| `SchedulerServiceTest` | Task registration, cancellation, reload logic |
| `DeviceControllerTest` | MockMvc (`@AutoConfigureMockMvc`): REST endpoints, validation |

### Integration Tests
| Test | Scope |
|------|-------|
| `MqttIntegrationTest` | Connect to Mosquitto container, publish msg, verify DB state |
| `InfluxWriterIntegrationTest` | Write via service, query back from InfluxDB container |
| `WebSocketIntegrationTest` | `WebSocketStompClient`, trigger state change, verify broadcast |
| `SchedulerIntegrationTest` | Insert schedule with imminent cron, verify MQTT publish |

**Mosquitto Testcontainer:**
```java
@Container
static GenericContainer<?> mosquitto = new GenericContainer<>(
    DockerImageName.parse("eclipse-mosquitto:2"))
    .withExposedPorts(1883)
    .withCopyFileToContainer(
        MountableFile.forClasspathResource("mosquitto-test.conf"),
        "/mosquitto-no-auth.conf")
    .withCommand("mosquitto -c /mosquitto-no-auth.conf");
```

`src/test/resources/mosquitto-test.conf`:
```
listener 1883
allow_anonymous true
```

---

## Verification

```bash
./mvnw verify
# Deploy, then:
kubectl logs -n apps -l app=device-service    # "MQTT connected", receiving messages
kubectl port-forward -n apps svc/device-service 8081:8081
curl http://localhost:8081/devices -H "Authorization: Bearer <jwt>"
# Verify: device state updates on MQTT, InfluxDB points, WebSocket broadcasts
```
