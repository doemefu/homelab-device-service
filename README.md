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
| POST | `/devices/{id}/control` | JWT, role `ADMIN` (403 otherwise) | Send control command (publishes to MQTT) |
| POST | `/devices` | JWT, role `ADMIN` | Register a device and provision its OAuth2 client (see below) → `201` |
| DELETE | `/devices/{name}` | JWT, role `ADMIN` | Delete a device and revoke its OAuth2 client → `204` |
| GET/POST/PUT/DELETE | `/schedules`, `/schedules/{id}` | JWT; writes role `ADMIN` | Schedules CRUD (see below) |
| GET | `/actuator/health` | None | K8s liveness/readiness |
| GET | `/actuator/info` | None | Service info |
| GET | `/api-docs` | OIDC login | OpenAPI JSON spec |
| GET | `/swagger-ui.html` | OIDC login | Swagger UI |

JWT means an auth-service access token: valid signature (JWKS), `iss` = `https://auth.furchert.ch`, not expired, no `typ: at+jwt`, and no ID token (tokens with an `azp` claim are rejected). Reads accept any such token; every other method under `/devices/**` needs the `role` claim `ADMIN`, which auth-service puts only into tokens of signed-in users (client_credentials tokens carry no role). Audience (`aud`) is not checked yet (doemefu/homelab-auth-service#101, #81).

### Device registration (`POST /devices`)

Admin-only. Provisions an OAuth2 `client_credentials` client in auth-service
(`POST /api/v1/clients`, auth-service `INTERFACES.md` §8) **first**, then
persists the device row; a local failure compensates by deleting the
auth-service client.

Request body:

```json
{ "name": "terra3", "type": "terrarium", "description": "Greenhouse 3" }
```

`name` is canonical: `[a-z0-9-]{3,32}`, equals the OAuth2 `clientId`, the MQTT
username, and the MQTT topic prefix. Response `201`:

```json
{
  "name": "terra3",
  "type": "terrarium",
  "clientId": "terra3",
  "clientSecret": "<one-time-plaintext>",
  "scopes": ["mqtt:pub", "mqtt:sub"],
  "createdAt": "2026-05-16T10:00:00Z",
  "mqttUsername": "terra3",
  "mqttTopicsAllowed": ["terra3/#", "terraGeneral/#"]
}
```

`createdAt` is the **auth-service client** creation timestamp (from the admin
API), distinct from the device row's own DB `created_at` column added by
Flyway V3. `clientSecret` is returned **exactly once** (never persisted by
device-service, never logged). Flash it onto the device. `DELETE
/devices/{name}` deletes the row and revokes the auth-service client
(idempotent; `404` if the device is unknown).

Scope today (provisioning only): the broker does not yet authenticate devices against
auth-service (doemefu/homelab#122), and device-service subscribes only to `terra1/#`,
`terra2/#` and `terraGeneral/#`, so `mqttUsername` and `mqttTopicsAllowed` describe the
target state. Devices that already exist as auto-created rows (from their first MQTT message)
get `409`. A device's own `client_credentials` token carries no role: it can read
`GET /devices/**` and subscribe via STOMP, but not write (no audience or scope gate on reads
until doemefu/homelab-auth-service#101). Identifiers: `GET /devices/{id}` and control use the
numeric row id; `DELETE` uses the canonical `name`.

Unauthenticated browser requests to Swagger endpoints are automatically redirected to auth-service login and returned to Swagger after successful sign-in.

### Schedules (`/schedules`)

Cron-driven MQTT commands (#41, ADR 0001: device-service owns the `schedules` table and executes it). Reads need
any valid access token (as for `/devices`); writes need the `role` claim `ADMIN`, i.e. a signed-in admin's access
token (a server-side proxy such as furchert-ch must forward the user's token, not a client_credentials token).

| Method | Path | Auth | Result |
|--------|------|------|--------|
| GET | `/schedules[?device=<name>]` | JWT | `200` list of schedules, ordered by id, unpaginated (small table) |
| GET | `/schedules/{id}` | JWT | `200` schedule, `404` unknown id |
| POST | `/schedules` | JWT, role `ADMIN` | `201` schedule + `Location: /schedules/{id}` |
| PUT | `/schedules/{id}` | JWT, role `ADMIN` | Full replace → `200` schedule, `404` unknown id |
| DELETE | `/schedules/{id}` | JWT, role `ADMIN` | `204`, `404` unknown id |

Request body (POST and PUT, all fields required):

```json
{ "deviceName": "terra1", "field": "light", "state": 1, "cronExpression": "0 0 8 * * *", "active": true }
```

- `deviceName`: an existing device (`[a-z0-9-]{3,32}`, otherwise `400`). It is a **grouping label only**: a schedule
  publishes to `terraGeneral/{field}/schedule`, which every terrarium receives. Deleting a device does not delete its
  schedules.
- `field`: `light`, `nightLight` or `rain`; `state`: `0` or `1`. The stored MQTT payload is built like manual control:
  `{"LightState":1}`.
- `cronExpression`: Spring cron, 6 fields (seconds first) or a macro such as `@daily`, at most 100 characters, and it
  must fire at least once in the future (`0 0 0 30 2 *` is rejected). Evaluated in the service's time zone, which is
  **UTC** (container default): `0 0 8 * * *` fires at 09:00 or 10:00 Zurich time.
- `active`: required, so that a PUT never re-enables a disabled schedule by omission.

Response body:

```json
{ "id": 5, "deviceName": "terra1", "field": "light", "state": 1, "payload": "{\"LightState\":1}",
  "cronExpression": "0 0 8 * * *", "active": true, "nextRun": "2026-11-04T08:00:00Z" }
```

`state` is `null` when a stored payload does not have the standard `{"<Field>State": n}` shape; `nextRun` is `null`
for an inactive schedule. Writes take effect in the scheduler immediately; the periodic reload
(`app.scheduler.poll-interval`) only covers out-of-band database edits.

Errors: validation failures and unknown devices return `400 {"error": "Validation failed", "details": ["<field>: <message>"]}`;
a malformed body or a non-numeric id returns `400 {"error": "..."}`; unknown ids return `404 {"error": "Schedule not found: <id>"}`.

### WebSocket

STOMP endpoint at `/ws` (read-only broadcast).

- **Authentication:** the STOMP `CONNECT` frame must carry the native header `Authorization: Bearer <access token>` (the same JWTs the REST API accepts). The WebSocket upgrade itself needs no token, because browsers cannot send one. Kill switch: `app.websocket.require-auth=false` (`APP_WEBSOCKET_REQUIRE_AUTH`).
- **Origins:** browser handshakes are accepted only from `app.websocket.allowed-origins` (default `https://furchert.ch`; comma-separated patterns, `APP_WEBSOCKET_ALLOWED_ORIGINS`). Clients that send no `Origin` header (non-browser) are not affected.
- **Token lifetime (residual risk):** the JWT is checked only at `CONNECT`. An open session is not closed when the token's `exp` passes, so a client keeps receiving the read-only broadcast until it disconnects. Accepted because the stream is read-only and clients cannot publish; per-frame or expiry-driven re-validation would be a follow-up.
- **Client SUBSCRIBE:** only destinations under `/topic/terrarium/` are accepted, in both switch positions; any other `SUBSCRIBE` closes the session with a STOMP `ERROR`.
- **Client SEND:** only application destinations (`/app/**`) are accepted; a `SEND` to `/topic/**` or any other destination closes the session with a STOMP `ERROR`. Server-to-client frames sent by a client (`MESSAGE`, `CONNECTED`, `RECEIPT`, `ERROR`) are rejected the same way.

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
kubectl port-forward -n apps svc/postgresql 5432:5432
kubectl port-forward -n apps svc/mosquitto 1883:1883
kubectl port-forward -n apps svc/influxdb2 8086:80
kubectl port-forward -n apps svc/auth-service 8080:8080   # JWKS; admin API for device registration
```

### 2. Set environment variables

```bash
export DB_USERNAME=homelab
export DB_PASSWORD=homelab
export MQTT_PASSWORD=<mqtt-password>
export INFLUX_TOKEN=<influx-token>
export DEVICE_SERVICE_CLIENT_SECRET=<oidc-client-secret>
# Only to test POST/DELETE /devices locally (default is a cluster DNS name):
export AUTH_SERVICE_BASE_URL=http://localhost:8080
```

### 3. Run

```bash
./mvnw spring-boot:run
```

> The service connects to database `homelabdb` on `localhost:5432`, Mosquitto on `localhost:1883`, and InfluxDB on `localhost:8086` (defaults in `application.yaml`). Auth-service must be reachable at `localhost:8080` for JWKS validation and at `https://auth.furchert.ch` for Swagger SSO login.

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
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `JWKS_URI` | `http://localhost:8080/oauth2/jwks` |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `JWT_ISSUER` | `https://auth.furchert.ch` |
| `spring.security.oauth2.client.registration.device-service.client-secret` | `DEVICE_SERVICE_CLIENT_SECRET` | — (required for Swagger SSO and device registration) |
| `app.auth-service.base-url` | `AUTH_SERVICE_BASE_URL` | `http://auth-service.apps.svc.cluster.local:8080` |
| `app.websocket.allowed-origins` | `APP_WEBSOCKET_ALLOWED_ORIGINS` | `https://furchert.ch` |
| `app.websocket.require-auth` | `APP_WEBSOCKET_REQUIRE_AUTH` | `true` |
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

One of the homelab services: auth-service (OIDC IdP, JWKS), device-service (this repo), furchert-ch (web frontend + admin dashboard) are deployed; data-service is planned only.

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

Required Secrets must exist in namespace `apps` before the first deploy (see `DEPLOYMENT.md`):
- `device-service-secrets`
- `sentry-dsn` — must contain the Sentry DSN under key `dsn`

**Deployments are automated via Flux CD.** Push to `main` — CI builds a new `main-YYYYMMDDTHHmmss` image, Flux detects it within 5 min, commits the updated tag to this repo, and the cluster rolls out the new pod automatically. No manual `kubectl` steps needed.

---

## CI/CD

GitHub Actions workflow at `.github/workflows/build.yml`:

- **test** job: runs `./mvnw verify` on every push and PR to `main`
- **build-and-push** job: builds a multi-arch image (`linux/amd64` + `linux/arm64`) and pushes to `ghcr.io/doemefu/homelab-device-service` — runs only on push to `main` after tests pass

Two image tags are pushed per build:
- `<git-sha>` — content-addressable, retained for debugging
- `main-YYYYMMDDTHHmmss` — timestamp tag used by Flux CD for automatic deployment

The `latest` tag is not pushed. Flux CD selects the newest `main-*` tag via `ImagePolicy` and updates `k8s/deployment.yaml` automatically.

---

## Related Repositories

| Repo | Description |
|------|-------------|
| [homelab](https://github.com/doemefu/homelab) | Infrastructure-as-Code -- Ansible, K3s cluster, platform services (PostgreSQL, InfluxDB, Mosquitto) |
| [homelab-auth-service](https://github.com/doemefu/homelab-auth-service) | JWT authentication -- user CRUD, token issuance, JWKS endpoint |
| homelab-data-service | Historical data queries (InfluxDB) for consuming services (not yet created) |

Full architecture docs (migration plan, current/target architecture, cross-service contracts): [homelab/docs/](https://github.com/doemefu/homelab/tree/main/docs)
