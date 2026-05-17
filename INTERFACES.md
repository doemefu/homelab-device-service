# homelab-device-service — Interfaces

This document describes how external services, applications, and devices
interact with `homelab-device-service`.

---

## Interface Types

device-service exposes and consumes **four types of interfaces**:

1. **REST API** — device state queries and manual control (JWT-protected)
2. **WebSocket / STOMP** — live device-state broadcast to the frontend
3. **MQTT topic contract** — what it subscribes to and publishes (Mosquitto)
4. **Inbound JWT validation** — bearer tokens verified against auth-service JWKS

---

## 1. REST API Interface

### Base URL

- **Cluster-internal:** `http://device-service.apps.svc.cluster.local:8081`
- **Production (ingress):** `https://device.furchert.ch`

### Authentication

The REST API is an **OAuth2 resource server**. Callers present a JWT issued by
auth-service:

- **Header:** `Authorization: Bearer <access_token>`
- **Validation:** signature checked against the JWKS at `JWKS_URI`
  (default `http://localhost:8080/oauth2/jwks`; cluster:
  `http://auth-service.apps.svc.cluster.local:8080/oauth2/jwks`)
- No tokens are issued here — device-service only validates.

### Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/devices` | JWT | List all devices with current state |
| GET | `/devices/{id}` | JWT | Single device state, or `404` |
| POST | `/devices` | JWT, `ROLE_ADMIN` | Register a device + provision its OAuth2 client (see below) |
| DELETE | `/devices/{name}` | JWT, `ROLE_ADMIN` | Delete a device + revoke its OAuth2 client → `204` |
| POST | `/devices/{id}/control` | JWT | Send a control command (publishes to MQTT) |
| GET | `/actuator/health` | None | Liveness/readiness — `{"status":"UP"}` |
| GET | `/actuator/info` | None | Service metadata |
| GET | `/api-docs` | OIDC login | OpenAPI JSON spec |
| GET | `/swagger-ui.html` | OIDC login | Swagger UI |

Unauthenticated **browser** requests to the Swagger endpoints are redirected
to auth-service OIDC login and returned to Swagger after successful sign-in
(this is the OAuth2 *client* flow, distinct from the resource-server flow used
by the `/devices` API).

### Device Registration (`POST /devices`)

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

`clientSecret` is returned **exactly once** (never persisted by
device-service, never logged). Flash it onto the device. `DELETE
/devices/{name}` deletes the row and revokes the auth-service client
(idempotent; `404` if the device is unknown).

The `role` claim from auth-service JWTs is mapped to `ROLE_<value>` for these
admin checks.

### Control Request

`POST /devices/{id}/control` triggers a manual MQTT publish (see §3, the
`terra{n}/{field}/man` topic). The command takes effect when the physical
device acts on it and reports back its new state on its status topic.

### OpenAPI Specification

```bash
curl -s https://device.furchert.ch/api-docs > openapi.json
# or cluster-internal
curl -s http://device-service.apps.svc.cluster.local:8081/api-docs > openapi.json
```

---

## 2. WebSocket / STOMP Interface

STOMP endpoint at **`/ws`** — no authentication (read-only broadcast).

Subscribe to per-device state updates:

```
/topic/terrarium/{deviceName}
```

Each message contains the **full current state** of the device after a change
(not a delta). Clients should treat each message as the authoritative current
state and replace any cached value.

---

## 3. MQTT Topic Contract

device-service connects to Mosquitto as a long-lived client (Eclipse Paho,
auto-reconnect, QoS 1) and registers a Last Will and Testament.

### Subscribe (device → broker, consumed by device-service)

| Topic | Payload | Frequency |
|-------|---------|-----------|
| `terra{n}/SHT35/data` | `{"Temperature": 22.5, "Humidity": 65.0}` | Every 30s |
| `terra{n}/mqtt/status` | `{"MqttState": 1}` | On connect (retained) |
| `terra{n}/light` | `{"LightState": 1}` | On state change |
| `terra{n}/nightLight` | `{"NightLightState": 1}` | On state change |
| `terra{n}/rain` | `{"RainState": 1}` | On state change |

Every inbound message updates the `devices` table and is written to InfluxDB.
State-changing topics also trigger a STOMP broadcast (§2).

### Publish (device-service → broker)

| Topic | Payload | Trigger |
|-------|---------|---------|
| `terra{n}/{field}/man` | `{"{Field}State": 0\|1}` | Manual control via REST (`POST /devices/{id}/control`) |
| `terraGeneral/{field}/schedule` | `{"{Field}State": 0\|1}` | Scheduled task (cron, in-process scheduler) |
| `javaBackend/mqtt/status` | `{"MqttState": 0\|1}` | Service connect/disconnect (LWT) |

`{field}` is one of `light`, `nightLight`, `rain`; `{Field}State` is the
PascalCase form (`LightState`, `NightLightState`, `RainState`).

---

## 4. Inbound Token Validation

device-service validates JWTs locally using auth-service's published keys:

1. Fetch the JWKS from `JWKS_URI` and cache the public keys by `kid`.
2. Verify the bearer token signature on each request to a protected endpoint.
3. Reject expired or untrusted-issuer tokens.

Configuration (`application.yaml` / env override):

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${JWKS_URI:http://localhost:8080/oauth2/jwks}
```

In-cluster, point `JWKS_URI` at the internal auth-service address
(`http://auth-service.apps.svc.cluster.local:8080/oauth2/jwks`) to avoid an
external network hop.

---

## Important Notes

1. **Stateful service:** device-service holds a persistent MQTT connection and
   an in-process scheduler. A restart briefly drops the MQTT link; Paho
   auto-reconnects and the LWT marks the service down/up on
   `javaBackend/mqtt/status`.

2. **STOMP is unauthenticated:** the `/ws` broadcast is read-only and carries
   no secrets — only public device state. Do not put sensitive data on it.

3. **Control is fire-and-forget:** `POST /devices/{id}/control` publishes an
   MQTT command; the authoritative confirmation is the device's own status
   message arriving back on its subscribe topic.

4. **Key rotation:** when auth-service rotates RSA keys, device-service picks
   up the new keys on the next JWKS fetch; in-flight tokens remain valid until
   their `exp`.
