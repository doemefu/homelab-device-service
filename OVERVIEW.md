# homelab-device-service — Overview

**Real-time IoT device management service** for the doemefu homelab ecosystem — MQTT subscriber, device state persistence, InfluxDB writer, scheduled commands, and WebSocket broadcast.

**Port:** 8081 · **Package:** `ch.furchert.homelab.device` · **Database:** PostgreSQL (`homelabdb`)

---

## Context & Purpose

device-service is the long-running, **stateful** core of the homelab IoT stack.
It maintains persistent MQTT connections and an in-process scheduler, turning
raw terrarium sensor traffic into queryable state and time-series data, and
exposing live updates to the frontend.

**Responsibilities:**

- MQTT subscriber (all `terra#/#` topics) — receives sensor data, status, and state changes
- Device state persistence in PostgreSQL (`devices` table)
- InfluxDB writer (sensor data on every MQTT message)
- Scheduled MQTT commands (light, nightlight, rain automation via cron)
- WebSocket broadcast (STOMP, live device state to frontend)
- Device control REST endpoint (manual toggle — publishes MQTT command)

**Does NOT:** handle user authentication or user CRUD (→ auth-service), or
serve historical InfluxDB queries for charts (→ data-service).

### Architecture Position

This is 1 of 3 microservices in the homelab IoT stack.

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

- **auth-service** — issues JWTs, exposes the JWKS endpoint
- **device-service** (this repo) — validates JWTs via JWKS, manages real-time
  device state, writes to InfluxDB, publishes/subscribes MQTT
- **data-service** — serves historical InfluxDB queries to consuming services

**Key design:** long-running and stateful. Restarting briefly disconnects from
MQTT but reconnects automatically (Eclipse Paho auto-reconnect + Last Will and
Testament). In-process scheduled tasks resume on startup.

---

## Features

| Category | Feature | Description |
|----------|---------|-------------|
| **Ingest** | MQTT subscriber | Subscribes to `terra1/#`, `terra2/#`, `terraGeneral/#` (QoS 1) |
| | InfluxDB writer | Writes sensor measurements on every inbound message |
| | Device state | Upserts current state into the `devices` table |
| **Control** | REST control endpoint | `POST /devices/{id}/control` publishes a manual MQTT command |
| | Scheduler | Cron-driven light / nightlight / rain automation |
| **Realtime** | STOMP WebSocket | Broadcasts full device state on every change |
| **Security** | JWT resource server | Validates bearer tokens against auth-service JWKS |
| | Swagger SSO | Browser Swagger access via auth-service OIDC login |

---

## Accessible URLs & APIs

### REST Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/devices` | JWT | List all devices with current state |
| GET | `/devices/{id}` | JWT | Single device state |
| POST | `/devices/{id}/control` | JWT | Send control command (publishes to MQTT) |
| GET | `/actuator/health` | None | K8s liveness/readiness |
| GET | `/actuator/info` | None | Service info |
| GET | `/api-docs` | OIDC login | OpenAPI JSON spec |
| GET | `/swagger-ui.html` | OIDC login | Swagger UI |

Unauthenticated browser requests to Swagger endpoints are redirected to
auth-service login and returned to Swagger after sign-in.

### WebSocket

STOMP endpoint at `/ws` (no authentication — read-only broadcast). Subscribe
to `/topic/terrarium/{deviceName}`; each message is the full current device
state after a change.

### MQTT Topic Summary

| Direction | Topic pattern | Purpose |
|-----------|---------------|---------|
| Subscribe | `terra{n}/SHT35/data`, `terra{n}/mqtt/status`, `terra{n}/{light\|nightLight\|rain}` | Sensor data, device status, state changes |
| Publish | `terra{n}/{field}/man` | Manual control via REST |
| Publish | `terraGeneral/{field}/schedule` | Scheduled task (cron) |
| Publish | `javaBackend/mqtt/status` | Service connect/disconnect (LWT) |

Full payloads and frequencies: see [INTERFACES.md](./INTERFACES.md).

---

## Technology Stack

| Component | Notes |
|-----------|-------|
| Java 25 / Spring Boot | REST, scheduling, security (OAuth2 resource server) |
| Eclipse Paho MQTT | Persistent broker connection, auto-reconnect, LWT |
| PostgreSQL + Flyway | `devices` / `schedules` tables; `flyway_schema_history_device` |
| InfluxDB | Time-series sink for sensor data |
| STOMP / WebSocket | Live state broadcast to frontend |
| Testcontainers | PostgreSQL + InfluxDB + Mosquitto integration tests |

---

## Service Contracts

- **Inbound auth:** validates JWTs issued by auth-service using the JWKS at
  `JWKS_URI` (default `http://localhost:8080/oauth2/jwks`). No tokens issued here.
- **Outbound:** publishes MQTT commands to Mosquitto; writes points to InfluxDB.
- **Frontend:** STOMP broadcast contract on `/topic/terrarium/{deviceName}`.

Detailed request/response shapes, MQTT payloads, and validation rules live in
[INTERFACES.md](./INTERFACES.md).

---

## Related Repositories

| Repo | Description |
|------|-------------|
| [homelab](https://github.com/doemefu/homelab) | Infrastructure-as-Code — Ansible, K3s cluster, platform services (PostgreSQL, InfluxDB, Mosquitto) |
| [homelab-auth-service](https://github.com/doemefu/homelab-auth-service) | JWT authentication — user CRUD, token issuance, JWKS endpoint |
| homelab-data-service | Historical data queries (InfluxDB) for consuming services (not yet created) |

Full architecture docs (migration plan, current/target architecture,
cross-service contracts): [homelab/docs/](https://github.com/doemefu/homelab/tree/main/docs)
