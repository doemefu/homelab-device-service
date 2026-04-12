# homelab-device-service

Real-time IoT device management service for the doemefu homelab ecosystem -- MQTT subscriber, device state persistence, InfluxDB writer, scheduled commands, and WebSocket broadcast.

**Port:** 8081 | **Package:** `ch.furchert.homelab.device` | **Database:** PostgreSQL | **External deps:** MQTT (Mosquitto), InfluxDB, auth-service (JWKS)

---

## Responsibilities

- MQTT subscriber (all `terra#/#` topics) -- receives sensor data, status, and state changes
- Device state persistence in PostgreSQL (`devices` table)
- InfluxDB writer (sensor data on every MQTT message)
- Scheduled MQTT commands (light, nightlight, rain automation via cron)
- WebSocket broadcast (STOMP, live device state to frontend)
- Device control REST endpoint (manual toggle -- publishes MQTT command)

**Does NOT:** handle user authentication or user CRUD (auth-service), or query historical InfluxDB data for charts (data-service).

---

## API Reference

### REST Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/devices` | JWT | List all devices with current state |
| GET | `/devices/{id}` | JWT | Single device state |
| POST | `/devices/{id}/control` | JWT | Send control command (publishes to MQTT) |
| GET | `/actuator/health` | None | K8s liveness/readiness |
| GET | `/actuator/info` | None | Service info |
| GET | `/api-docs` | None | OpenAPI JSON spec |
| GET | `/swagger-ui.html` | None | Swagger UI |

### WebSocket

STOMP endpoint at `/ws` (no authentication required -- read-only broadcast).

Subscribe to live device state updates:

```
/topic/terrarium/{deviceName}
```

Each message contains the full current state of the device after a change.

---

## MQTT Topic Reference

### Subscribe (device -> broker, consumed by device-service)

| Topic | Payload | Frequency |
|-------|---------|-----------|
| `terra{n}/SHT35/data` | `{"Temperature": 22.5, "Humidity": 65.0}` | Every 30s |
| `terra{n}/mqtt/status` | `{"MqttState": 1}` | On connect (retained) |
| `terra{n}/light` | `{"LightState": 1}` | On state change |
| `terra{n}/nightLight` | `{"NightLightState": 1}` | On state change |
| `terra{n}/rain` | `{"RainState": 1}` | On state change |

### Publish (device-service -> broker)

| Topic | Payload | Trigger |
|-------|---------|---------|
| `terra{n}/{field}/man` | `{"{Field}State": 0\|1}` | Manual control via REST |
| `terraGeneral/{field}/schedule` | `{"{Field}State": 0\|1}` | Scheduled task (cron) |
| `javaBackend/mqtt/status` | `{"MqttState": 0\|1}` | Service connect/disconnect (LWT) |

---

## Local Development

### Prerequisites

- Java 25
- Docker (for integration tests)
- kubectl access to the K3s cluster

### 1. Port-forward cluster services

```bash
kubectl port-forward -n apps svc/postgres 5432:5432
kubectl port-forward -n apps svc/mosquitto 1883:1883
kubectl port-forward -n apps svc/influxdb 8086:8086
```

### 2. Set environment variables

```bash
export DB_USERNAME=homelab
export DB_PASSWORD=homelab
export MQTT_PASSWORD=<mqtt-password>
export INFLUX_TOKEN=<influx-token>
```

### 3. Run

```bash
./mvnw spring-boot:run
```

> The service connects to database `homelabdb` on `localhost:5432`, Mosquitto on `localhost:1883`, and InfluxDB on `localhost:8086` (defaults in `application.yaml`). Auth-service must be reachable at `localhost:8080` for JWKS validation.

---

## Configuration

| Property | Env override | Default |
|----------|-------------|---------|
| `spring.datasource.username` | `DB_USERNAME` | `homelab` |
| `spring.datasource.password` | `DB_PASSWORD` | `homelab` |
| `app.mqtt.broker-url` | — | `tcp://localhost:1883` |
| `app.mqtt.client-id` | — | `device-service` |
| `app.mqtt.username` | `MQTT_USERNAME` | `backend` |
| `app.mqtt.password` | `MQTT_PASSWORD` | — (required) |
| `app.mqtt.topics` | — | `terra1/#,terra2/#,terraGeneral/#` |
| `app.mqtt.qos` | — | `1` |
| `app.influxdb.url` | — | `http://localhost:8086` |
| `app.influxdb.token` | `INFLUX_TOKEN` | — (required) |
| `app.influxdb.org` | — | `homelab` |
| `app.influxdb.bucket` | — | `iot-bucket` |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | — | `http://localhost:8080/auth/jwks` |
| `app.scheduler.poll-interval` | — | `60000` ms (1 min) |
| `spring.flyway.table` | — | `flyway_schema_history_device` |

---

## Testing

```bash
./mvnw test          # Unit tests only (no Docker needed)
./mvnw verify        # Full suite including integration tests (Docker required)
```

Integration tests use Testcontainers -- Docker must be running. Containers used: PostgreSQL, InfluxDB, and a generic Mosquitto container with anonymous auth.

---

## Architecture

This service is 1 of 3 microservices in the homelab IoT stack.

```
                          ┌─────────────────┐
                          │  auth-service    │
                          │  (port 8080)     │
                          └───────┬─────────┘
                          JWKS    │    JWKS
                       ┌──────────┴──────────┐
                       v                     v
              ┌─────────────────┐   ┌─────────────────┐
  MQTT <----> │ device-service  │   │  data-service    │
  InfluxDB <--│ (port 8081)     │   │  (port 8082)     │
              │ owns: devices,  │   │                  │
              │   schedules     │   │                  │
              └─────────────────┘   └──────────────────┘
```

- **auth-service** -- issues JWTs, exposes JWKS endpoint
- **device-service** (this repo) -- validates JWTs via JWKS, manages real-time device state, writes to InfluxDB, publishes/subscribes MQTT
- **data-service** -- serves historical InfluxDB queries to consuming services

**Key design:** This is a long-running, stateful service. It maintains persistent MQTT connections and in-process scheduled tasks. Restarting briefly disconnects from MQTT but reconnects automatically (Eclipse Paho auto-reconnect + LWT).

---

## K8s Deployment

Manifests are in `k8s/`:
- `k8s/deployment.yaml` — Deployment + ClusterIP Service in namespace `apps`; image tag managed by Flux CD (do not edit the tag manually)
- `k8s/kustomization.yaml` — Kustomize base consumed by Flux

Required Secret `device-service-secrets` must exist in namespace `apps` before the first deploy (see `DEPLOYMENT.md`).

**Deployments are automated via Flux CD.** Push to `main` — CI builds a new `main-YYYYMMDDTHHMMSS` image, Flux detects it within 5 min, commits the updated tag to this repo, and the cluster rolls out the new pod automatically. No manual `kubectl` steps needed.

---

## CI/CD

GitHub Actions workflow at `.github/workflows/build.yml`:

- **test** job: runs `./mvnw verify` on every push and PR to `main`
- **build-and-push** job: builds a multi-arch image (`linux/amd64` + `linux/arm64`) and pushes to `ghcr.io/doemefu/homelab-device-service` — runs only on push to `main` after tests pass

Two image tags are pushed per build:
- `<git-sha>` — content-addressable, retained for debugging
- `main-YYYYMMDDTHHMMSS` — timestamp tag used by Flux CD for automatic deployment

The `latest` tag is not pushed. Flux CD selects the newest `main-*` tag via `ImagePolicy` and updates `k8s/deployment.yaml` automatically.

---

## Related Repositories

| Repo | Description |
|------|-------------|
| [homelab](https://github.com/doemefu/homelab) | Infrastructure-as-Code -- Ansible, K3s cluster, platform services (PostgreSQL, InfluxDB, Mosquitto) |
| [homelab-auth-service](https://github.com/doemefu/homelab-auth-service) | JWT authentication -- user CRUD, token issuance, JWKS endpoint |
| homelab-data-service | Historical data queries (InfluxDB) for consuming services (not yet created) |

Full architecture docs (migration plan, current/target architecture, cross-service contracts): [homelab/docs/](https://github.com/doemefu/homelab/tree/main/docs)
