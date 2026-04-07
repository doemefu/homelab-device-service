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
./mvnw spring-boot:run
```

The service connects to database `homelabdb` on `localhost:5432`, Mosquitto on `localhost:1883`, and InfluxDB on `localhost:8086` (defaults in `application.yaml`).

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
| `src/test/.../integration/` | Integration tests (Testcontainers) | Docker |

`AbstractIntegrationTest` provides shared containers (PostgreSQL, Mosquitto, InfluxDB) for all integration tests via `@DynamicPropertySource`.

---

## Spring Boot 4.0 / Spring Security 7 Notes

- **Jackson 3** uses group ID `tools.jackson` (not `com.fasterxml.jackson`) — affects MQTT JSON message parsing
- **`@WebMvcTest`** support moved to the `spring-boot-webmvc-test` artifact
- **`@AutoConfigureMockMvc`** must be added explicitly for MockMvc in `@SpringBootTest`
- **Timestamps** — the `Device` entity uses `java.time.LocalDateTime` (mapped to PostgreSQL `TIMESTAMP`)

---

## Database Migrations

All schema changes go through Flyway. Migration files are in `src/main/resources/db/migration/` and follow `V{n}__{description}.sql` (two underscores).

Current migrations:
- **V1** — Initial schema: `devices` table + seed data (terra1, terra2)

New migrations should follow `V2__description.sql`.

Never edit or delete an existing migration file — Flyway checksums will fail on next startup.

The Flyway history table is named `flyway_schema_history_device` to avoid conflicts when multiple services share the same PostgreSQL instance.

**Important:** This service does NOT create or migrate the `schedules` table — data-service owns it.

---

## CI/CD

GitHub Actions workflow: `.github/workflows/build.yml`

**On every push and PR to `main`:**
- `test` job: runs `./mvnw verify` (unit tests + Testcontainers integration tests; Docker must be available on the runner)

**On push to `main` only (after tests pass):**
- `build-and-push` job: builds a multi-arch Docker image (`linux/amd64` + `linux/arm64`) via `docker/build-push-action` and pushes to GHCR (`ghcr.io/doemefu/homelab-device-service`)
- Image is tagged with the Git SHA (short) and `latest`
- Build cache is stored in GitHub Actions Cache for faster rebuilds

After a successful CI run, update `k8s/deployment.yml` with the new image tag before deploying to the cluster.

---

## Pull Request Process

- All changes go through a PR to `main`
- CI must be green (all tests pass) before merging
- Keep diffs minimal — no drive-by refactors or style-only changes
- New features require unit tests; database-touching features require integration tests
- All comments and documentation must be in English
