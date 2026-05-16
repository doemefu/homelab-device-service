# homelab-device-service — Development Guide

This document provides instructions, reminders, and reference material for
developers and agents working on this repository. For a high-level picture of
what the service does, read [OVERVIEW.md](../OVERVIEW.md) first; for the
interface contracts, see [INTERFACES.md](../INTERFACES.md).

---

## Prerequisites

- **Java 25**
- **Docker** (required for Testcontainers integration tests)
- **kubectl** access to the K3s cluster (for port-forwarding platform services)

---

## Project Structure

```
homelab-device-service/
├── README.md                            # Landing page — doc index, quick start
├── OVERVIEW.md                          # What the service is, features, API/MQTT summary
├── INTERFACES.md                        # REST, WebSocket/STOMP, MQTT contract, JWT validation
├── OPERATIONS.md                        # Operator runbooks, troubleshooting
├── DEPLOYMENT.md                        # K8s deployment, secrets, ingress
├── CONTRIBUTING.md                      # Local dev setup, testing, PR process
├── CHANGELOG.md                         # Version history
│
├── docs/                                # Supplementary documentation
│   ├── INDEX.md                         # Documentation index
│   ├── DEVELOPMENT.md (this file)
│   ├── PLAN.md                          # Implementation plan
│   └── SPEC-device-registration.md      # Spec — device registration / OAuth2 provisioning
│
├── src/main/java/ch/furchert/homelab/device/
│   ├── config/                          # MQTT, security, scheduler, WebSocket config
│   ├── controller/                      # REST endpoints
│   ├── dto/                             # Request/response records
│   ├── entity/                          # JPA entities (devices, schedules)
│   ├── exception/                       # Exception handling
│   ├── repository/                      # Spring Data repositories
│   └── service/                         # MQTT ingest, InfluxDB writer, scheduler, broadcast
│
├── src/main/resources/
│   └── db/migration/                    # Flyway migrations
│       ├── V1__create_devices.sql
│       └── V2__create_schedules.sql
│
├── src/test/java/ch/furchert/homelab/device/
│   ├── service/                         # Unit tests
│   ├── controller/                      # MockMvc slice tests
│   └── integration/                     # Testcontainers integration tests
│
├── k8s/                                 # Kubernetes manifests
├── .github/workflows/                   # GitHub Actions
└── pom.xml                              # Maven configuration
```

---

## Local Development Setup

### 1. Port-forward cluster services

device-service depends on three platform services plus auth-service:

```bash
kubectl port-forward -n apps svc/postgres 5432:5432    # PostgreSQL
kubectl port-forward -n apps svc/mosquitto 1883:1883   # Mosquitto
kubectl port-forward -n apps svc/influxdb 8086:8086    # InfluxDB
```

auth-service must also be reachable at `localhost:8080` for JWKS validation,
and at `https://auth.furchert.ch` for Swagger SSO login.

### 2. Set environment variables

```bash
export DB_USERNAME=homelab
export DB_PASSWORD=homelab
export MQTT_PASSWORD=<mqtt-password>
export INFLUX_TOKEN=<influx-token>
export DEVICE_SERVICE_CLIENT_SECRET=<oidc-client-secret>
```

### 3. Run

```bash
./mvnw spring-boot:run
```

The service connects to database `homelabdb` on `localhost:5432`, Mosquitto on
`localhost:1883`, and InfluxDB on `localhost:8086` (defaults in
`application.yaml`).

---

## Configuration Reference

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
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `JWKS_URI` | `http://localhost:8080/oauth2/jwks` |
| `spring.security.oauth2.client.registration.device-service.client-secret` | `DEVICE_SERVICE_CLIENT_SECRET` | — (required for Swagger SSO) |
| `app.scheduler.poll-interval` | — | `60000` ms (1 min) |
| `spring.flyway.table` | — | `flyway_schema_history_device` |

---

## Running Tests

```bash
./mvnw test          # Unit tests only (no Docker needed)
./mvnw test -Dtest=ClassName   # Single test class
./mvnw verify        # Full suite including Testcontainers integration tests
```

Integration tests use Testcontainers — Docker must be running. Containers
used: **PostgreSQL**, **InfluxDB**, and a generic **Mosquitto** container with
anonymous auth.

---

## Useful Commands

```bash
./mvnw clean package              # Build
./mvnw clean package -DskipTests  # Build, skip tests
docker build -t device-service .  # Build Docker image locally

# Connect to local PostgreSQL (after port-forward)
psql -h localhost -U homelab -d homelabdb
\dt                               # List tables
SELECT * FROM devices;            # Inspect device state
SELECT * FROM flyway_schema_history_device;   # Flyway history
```

---

## Developer Reminders

- This is a **stateful** service — a restart drops the MQTT link briefly;
  Paho auto-reconnects and the LWT updates `javaBackend/mqtt/status`.
- Flyway only — no `ddl-auto=update`/`create`. Add a new `V{n}__*.sql`
  migration for schema changes.
- Never log secrets, tokens, MQTT passwords, or InfluxDB tokens.
- All comments, commit messages, and documentation in **English**.
- Keep diffs minimal — no drive-by refactors.
- See `CONTRIBUTING.md` for the PR process and `docs/PLAN.md` for the
  implementation roadmap.
