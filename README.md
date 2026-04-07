# homelab-device-service

Real-time IoT device management microservice. Subscribes to MQTT, persists device state to PostgreSQL, writes sensor data to InfluxDB, and broadcasts live state via WebSocket.

**Port:** 8081 | **Package:** `ch.furchert.homelab.device`

---

## REST API

All endpoints require a valid JWT (`Authorization: Bearer <token>`).

| Method | Path | Description |
|--------|------|-------------|
| GET | `/devices` | List all devices and their current state |
| GET | `/devices/{id}` | Get a single device by ID |
| POST | `/devices/{id}/control` | Send a manual control command (publishes to MQTT) |

Control command body:
```json
{ "field": "light", "state": 1 }
```
`field` must be one of `light`, `nightLight`, `rain`. `state` must be `0` (off) or `1` (on).

---

## MQTT Topics

### Device → Broker (subscribe)

| Topic | Payload | Description |
|-------|---------|-------------|
| `terra{n}/SHT35/data` | `{"Temperature": 22.5, "Humidity": 65.0}` | Sensor readings every 30 s |
| `terra{n}/mqtt/status` | `{"MqttState": 1}` | Device connect/disconnect |
| `terra{n}/light` | `{"LightState": 1}` | Light state change |
| `terra{n}/nightLight` | `{"NightLightState": 1}` | Night-light state change |
| `terra{n}/rain` | `{"RainState": 1}` | Rain/mist state change |

### Service → Broker (publish)

| Topic | Payload | Trigger |
|-------|---------|---------|
| `terra{n}/{field}/man` | `{"<Field>State": 0\|1}` | Manual control via REST API |
| `terraGeneral/{field}/schedule` | `{"<Field>State": 0\|1}` | Scheduled automation |
| `javaBackend/mqtt/status` | `{"MqttState": 0\|1}` | Connect / LWT |

---

## WebSocket

Connect to `ws://host:8081/ws` (STOMP). Subscribe to `/topic/terrarium/{deviceName}` for live device state updates.

Payload is a `DeviceStateDto` JSON object with fields: `id`, `name`, `mqttOnline`, `temperature`, `humidity`, `light`, `nightLight`, `rain`, `lastSeen`.

Authentication is optional for WebSocket (read-only broadcast).

---

## Required Environment Variables

| Variable | Description |
|----------|-------------|
| `DB_USERNAME` | PostgreSQL username (default: `homelab`) |
| `DB_PASSWORD` | PostgreSQL password — **required, no default** |
| `MQTT_PASSWORD` | Mosquitto backend user password — **required, no default** |
| `INFLUX_TOKEN` | InfluxDB admin token — **required, no default** |

Optional overrides (have defaults in `application.yaml`):

| Variable | Default | Description |
|----------|---------|-------------|
| `MQTT_BROKER_URL` | `tcp://localhost:1883` | Mosquitto broker URL |
| `MQTT_CLIENT_ID` | `device-service` | MQTT client identifier |
| `MQTT_USERNAME` | `backend` | Mosquitto username |
| `INFLUX_URL` | `http://localhost:8086` | InfluxDB HTTP endpoint |
| `INFLUX_ORG` | `homelab` | InfluxDB organisation |
| `INFLUX_BUCKET` | `iot-bucket` | InfluxDB bucket |
| `JWKS_URI` | `http://localhost:8080/auth/jwks` | Auth-service JWKS endpoint |

---

## Dependencies

| Service | Role |
|---------|------|
| PostgreSQL 17 | Owns `devices` table. Reads (read-only) `schedules` table — owned by data-service |
| InfluxDB 2 | Write-only: sensor measurements |
| Mosquitto 2 | MQTT broker: subscribe + publish |
| auth-service | JWT validation via JWKS endpoint |

---

## Quick Start

See [CONTRIBUTING.md](CONTRIBUTING.md) for local development setup (port-forwarding, test commands).

---

## Scheduler

The service reads the `schedules` table (owned by data-service) every 60 seconds and registers or cancels `CronTrigger` tasks accordingly. Each task publishes an MQTT message to `terraGeneral/{field}/schedule` at the configured time. Schedule changes (including cron expression or payload edits) are picked up automatically on the next poll.
