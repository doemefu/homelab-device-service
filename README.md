# homelab-device-service

**Real-time IoT device management service** for the doemefu homelab ecosystem — MQTT subscriber, device state persistence, InfluxDB writer, scheduled commands, and WebSocket broadcast.

**Port:** 8081 | **Package:** `ch.furchert.homelab.device` | **Database:** PostgreSQL | **External deps:** MQTT (Mosquitto), InfluxDB, auth-service (JWKS)

---

## Documentation

This repository uses a structured documentation approach. Start here:

| Document | Purpose | When to Read |
|----------|---------|---------------|
| **[OVERVIEW.md](./OVERVIEW.md)** | What this service is, responsibilities, features, API/MQTT summary | First — understand the service |
| **[INTERFACES.md](./INTERFACES.md)** | REST API, WebSocket/STOMP, MQTT contract, JWT validation | When integrating or calling the service |
| **[OPERATIONS.md](./OPERATIONS.md)** | Runbooks, troubleshooting, MQTT/InfluxDB monitoring | When operating in production |
| **[DEPLOYMENT.md](./DEPLOYMENT.md)** | K8s deployment, secrets, ingress | When deploying |
| **[CONTRIBUTING.md](./CONTRIBUTING.md)** | Local dev setup, testing, PR process | When developing locally |
| **[docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md)** | Extended development guide | For detailed development reference |
| **[docs/INDEX.md](./docs/INDEX.md)** | Full documentation index | To find anything else |

---

## Quick Start

### Local Development

```bash
# 1. Port-forward cluster services
kubectl port-forward -n apps svc/postgres 5432:5432
kubectl port-forward -n apps svc/mosquitto 1883:1883
kubectl port-forward -n apps svc/influxdb 8086:8086

# 2. Set environment variables
export DB_USERNAME=homelab
export DB_PASSWORD=homelab
export MQTT_PASSWORD=<mqtt-password>
export INFLUX_TOKEN=<influx-token>
export DEVICE_SERVICE_CLIENT_SECRET=<oidc-client-secret>

# 3. Run (auth-service must be reachable at localhost:8080 for JWKS)
./mvnw spring-boot:run
```

Full setup, configuration reference, and testing: see
[docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md).

### Deploy to Production

Push to `main` — **Flux CD handles everything automatically** (see
[DEPLOYMENT.md](./DEPLOYMENT.md)).

---

## Quick Reference

| Interface | Address |
|-----------|---------|
| REST API | `https://device.furchert.ch/devices` (JWT) |
| WebSocket (STOMP) | `wss://device.furchert.ch/ws` → `/topic/terrarium/{deviceName}` |
| Swagger UI | `https://device.furchert.ch/swagger-ui.html` (OIDC login) |
| Health | `https://device.furchert.ch/actuator/health` |
| MQTT (subscribe) | `terra{n}/SHT35/data`, `terra{n}/mqtt/status`, `terra{n}/{light\|nightLight\|rain}` |
| MQTT (publish) | `terra{n}/{field}/man`, `terraGeneral/{field}/schedule`, `javaBackend/mqtt/status` |

Full contracts: [INTERFACES.md](./INTERFACES.md).

---

## Architecture

```
auth-service (JWKS) ──> device-service (this repo, port 8081) <──> Mosquitto (MQTT)
                                  │                              └──> InfluxDB
                                  └──> PostgreSQL (devices, schedules)
                                  └──> STOMP /ws broadcast ──> frontend
```

1 of 3 microservices. Long-running and stateful (persistent MQTT connection +
in-process scheduler). See [OVERVIEW.md](./OVERVIEW.md) for the full picture.

---

## Related Repositories

| Repo | Description |
|------|-------------|
| [homelab](https://github.com/doemefu/homelab) | Infrastructure-as-Code — Ansible, K3s cluster, platform services |
| [homelab-auth-service](https://github.com/doemefu/homelab-auth-service) | JWT authentication — user CRUD, token issuance, JWKS endpoint |
| homelab-data-service | Historical data queries (InfluxDB) — not yet created |

---

## Support

- **Issues:** GitHub Issues
- **Status:** See [CHANGELOG.md](./CHANGELOG.md) for recent changes
