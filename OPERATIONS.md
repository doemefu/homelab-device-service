# OPERATIONS.md — homelab-device-service

Runbooks and operational reference for device-service on the K3s cluster.

---

## Service Runbooks

### Restart device-service

```bash
kubectl rollout restart deployment/device-service -n apps
kubectl rollout status deployment/device-service -n apps
```

> **Note:** Restarting this service temporarily disconnects the MQTT client. The client reconnects automatically via Paho's `setAutomaticReconnect(true)`. Scheduled tasks are re-registered from the database on startup.

### View logs

```bash
kubectl logs -n apps deployment/device-service --tail=100 -f
```

---

## MQTT Connection

### Verify MQTT connectivity

```bash
kubectl logs -n apps deployment/device-service | grep -i "mqtt\|connected\|subscri"
```

Expected on healthy startup:
- "MQTT connected" or "Connection complete"
- Subscriptions to `terra1/#`, `terra2/#`, `terraGeneral/#`
- LWT published to `javaBackend/mqtt/status`

### MQTT reconnection behavior

- Eclipse Paho auto-reconnect is enabled (`setAutomaticReconnect(true)`)
- On reconnect, subscriptions are re-established via `MqttCallbackExtended.connectComplete()`
- LWT (`javaBackend/mqtt/status` with `{"MqttState": 0}`) is published by the broker when the client disconnects ungracefully

### Mosquitto broker down

If Mosquitto is restarted or unavailable:
1. device-service logs will show connection errors
2. Paho retries automatically with exponential backoff
3. No manual intervention needed — wait for Mosquitto to come back
4. Verify recovery: `kubectl logs -n apps deployment/device-service | tail -20`

---

## Scheduler

The scheduler polls the `schedules` table every 60 seconds (configurable via `app.scheduler.poll-interval`).

### Verify scheduler is running

```bash
kubectl logs -n apps deployment/device-service | grep -i "schedul"
```

### Force schedule reload

Restart the service — schedules are loaded from the database on startup:

```bash
kubectl rollout restart deployment/device-service -n apps
```

---

## InfluxDB Writer

### Verify data is being written

```bash
kubectl logs -n apps deployment/device-service | grep -i "influx\|write"
```

### InfluxDB unreachable

If InfluxDB is down, sensor data MQTT messages are still processed (device state is updated in PostgreSQL) but InfluxDB writes will fail. Check logs for write errors. Data during the outage is lost (not queued).

---

## Database Migrations

Flyway runs automatically on service startup. Migration history is tracked in `flyway_schema_history_device`.

```bash
kubectl logs -n apps deployment/device-service | grep -i flyway
```

Current migrations:
- **V1** — Initial schema: `devices` table + seed data (terra1, terra2)

**Never edit or delete existing `V*.sql` migration files.** Flyway checksums will fail.

---

## Configuration Reference

| Env Var | K8s Source | Description |
|---------|-----------|-------------|
| `DB_USERNAME` | Secret `homelab-db-credentials` / key `username` | PostgreSQL username |
| `DB_PASSWORD` | Secret `homelab-db-credentials` / key `password` | PostgreSQL password |
| `MQTT_PASSWORD` | Secret (TBD) | Mosquitto backend user password |
| `INFLUX_TOKEN` | Secret (TBD) | InfluxDB admin token |

---

## Troubleshooting

### Service fails to start — Flyway migration error

```bash
kubectl logs -n apps deployment/device-service | grep -i "flyway\|migration\|error"
```

Common causes: database unreachable, migration checksum mismatch.

### No device state updates

1. Check MQTT connection in logs
2. Verify Mosquitto is running: `kubectl get pods -n apps -l app=mosquitto`
3. Verify ESP32 devices are publishing (check Mosquitto logs or use `mosquitto_sub`)

### WebSocket not broadcasting

1. Check service logs for WebSocket errors
2. Verify the STOMP endpoint is reachable:
   ```bash
   kubectl port-forward -n apps svc/device-service 8081:8081
   # Connect a STOMP client to ws://localhost:8081/ws
   ```
