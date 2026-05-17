# CONTRIBUTING.md — homelab-device-service

---

## Prerequisites

- Java 25 (Temurin distribution recommended — matches CI)
- Docker Desktop or Docker Engine running locally (required for Testcontainers integration tests)
- kubectl configured to reach the homelab K3s cluster (for port-forwarding infrastructure services during local dev)
- No separate Maven installation needed — use the included `./mvnw` wrapper

---

## Local Development Setup

### 1. Port-forward infrastructure services from the cluster

```bash
kubectl port-forward -n apps svc/postgres 5432:5432 &
kubectl port-forward -n apps svc/mosquitto 1883:1883 &
kubectl port-forward -n apps svc/influxdb2 8086:8086 &
```

### 2. Set environment variables and run

```bash
export DB_USERNAME=homelab
export DB_PASSWORD=homelab
export MQTT_PASSWORD=<mosquitto-backend-password>
export INFLUX_TOKEN=<influxdb-admin-token>
export DEVICE_SERVICE_CLIENT_SECRET=<oidc-client-secret>
# Only needed to exercise POST/DELETE /devices locally — the default points
# at the cluster-internal DNS name, unreachable from a laptop:
export AUTH_SERVICE_BASE_URL=http://localhost:8080
./mvnw spring-boot:run
```

The service connects to database `homelabdb` on `localhost:5432`, Mosquitto on `localhost:1883`, and InfluxDB on `localhost:8086` (defaults in `application.yaml`).
Auth-service must be reachable for JWT JWKS validation (`http://localhost:8080/oauth2/jwks`) and for Swagger SSO login (`https://auth.furchert.ch`). The device-registration endpoints (`POST`/`DELETE /devices`) additionally call the auth-service **admin API** (`/api/v1/clients`) server-to-server via `AUTH_SERVICE_BASE_URL`, so auth-service must also be reachable as a resource API to test registration locally (port-forward: `kubectl port-forward -n apps svc/auth-service 8080:8080`).

---

## Running Tests

```bash
# Unit + controller tests — no Docker required
./mvnw test

# Full suite including Testcontainers integration tests — Docker must be running
./mvnw verify

# Single test class
./mvnw test -Dtest=MqttMessageParserTest
```

---

## Test Structure

| Location | Type | Dependencies |
|----------|------|-------------|
| `src/test/.../service/` | Unit tests (Mockito) | None |
| `src/test/.../controller/` | MockMvc slice tests (`@WebMvcTest`) | None |
| `src/test/.../client/` | WireMock HTTP + OAuth2 tests (`@WireMockTest`) — `wiremock-spring-boot:4.2.1` | None (no Docker) |
| `src/test/.../integration/` | Integration tests (Testcontainers) | Docker |

`AbstractIntegrationTest` provides shared containers (PostgreSQL, Mosquitto, InfluxDB) for all integration tests via `@DynamicPropertySource`.

---

## Spring Boot 4.0 / Spring Security 7 Notes

- **Jackson 3** uses group ID `tools.jackson` (not `com.fasterxml.jackson`) — affects MQTT JSON message parsing
- **`@WebMvcTest`** support moved to the `spring-boot-webmvc-test` artifact
- **`@AutoConfigureMockMvc`** must be added explicitly for MockMvc in `@SpringBootTest`
- **Timestamps** — `Device.lastSeen` is `java.time.LocalDateTime` (PostgreSQL `TIMESTAMP`); `Device.createdAt` is `java.time.Instant` (PostgreSQL `TIMESTAMP WITH TIME ZONE`, defaulted in-entity so the MQTT auto-create path satisfies NOT NULL)

---

## Database Migrations

All schema changes go through Flyway. Migration files are in `src/main/resources/db/migration/` and follow `V{n}__{description}.sql` (two underscores).

Current migrations:
- **V1** — Initial schema: `devices` table + seed data (terra1, terra2)
- **V2** — `schedules` table
- **V3** — device-registration metadata on `devices` (`type`, `description`, `created_at`, `provisioned`)

New migrations should follow `V4__description.sql`.

Never edit or delete an existing migration file — Flyway checksums will fail on next startup.

The Flyway history table is named `flyway_schema_history_device` to avoid conflicts when multiple services share the same PostgreSQL instance.

This service owns both the `devices` and `schedules` tables. Migrations live in `src/main/resources/db/migration/`.

---

## CI/CD

GitHub Actions workflow: `.github/workflows/build.yml`

**On every push and PR to `main`:**
- `test` job: runs `./mvnw verify` (unit tests + Testcontainers integration tests; Docker must be available on the runner)

**On push to `main` only (after tests pass):**
- `build-and-push` job: builds a multi-arch Docker image (`linux/amd64` + `linux/arm64`) via `docker/build-push-action` and pushes to GHCR (`ghcr.io/doemefu/homelab-device-service`)
- Two tags are pushed: `<git-sha>` (content-addressable, for debugging) and `main-YYYYMMDDTHHmmss` (timestamp tag used by Flux CD)
- The `latest` tag is not pushed — Flux CD uses the sortable timestamp tag to select the newest image
- Build cache is stored in GitHub Actions Cache for faster rebuilds

**No manual image tag update is needed.** Flux CD automatically detects the new `main-*` tag on GHCR, commits the updated tag to `k8s/deployment.yaml`, and rolls out the new pod.

---

## Pull Request Process

- All changes go through a PR to `main`
- CI must be green (all tests pass) before merging
- Keep diffs minimal — no drive-by refactors or style-only changes
- New features require unit tests; database-touching features require integration tests
- All comments and documentation must be in English
