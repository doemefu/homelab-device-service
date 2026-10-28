# OPERATIONS.md — homelab-device-service

Runbooks and operational reference for device-service on the K3s cluster.

---

## Service Runbooks

### Restart device-service

```bash
kubectl -n apps delete pod -l app=device-service
kubectl -n apps get pods -l app=device-service        # new pod, age in seconds, Ready 1/1
kubectl -n apps logs deployment/device-service --tail=50
```

> **Note:** Do not use `kubectl rollout restart`. Flux reconciles this Deployment and strips the `restartedAt` annotation at its next apply (every 10 min), so the restart can be cancelled before the new pod is Ready while `rollout status` still reports success, or the pod restarts twice. With `replicas: 1` it would also run two MQTT clients side by side for a moment. See infrastructure `DEPLOYMENT.md`, note under "Enable order (NM-4)". Check the pod age and the startup log, not `rollout status`.
>
> Restarting this service temporarily disconnects the MQTT client (about 30 to 60 s). The client reconnects automatically via Paho's `setAutomaticReconnect(true)`. Scheduled tasks are re-registered from the database on startup.

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

Restart the service by deleting its pod (see "Restart device-service") — schedules are loaded from the database on startup:

```bash
kubectl -n apps delete pod -l app=device-service
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

All secrets are sourced from the `device-service-secrets` Kubernetes Secret (see `k8s/deployment.yml`).

| Env Var | K8s Source | Description |
|---------|-----------|-------------|
| `DB_USERNAME` | Secret `device-service-secrets` / key `db-username` | PostgreSQL username |
| `DB_PASSWORD` | Secret `device-service-secrets` / key `db-password` | PostgreSQL password |
| `MQTT_PASSWORD` | Secret `device-service-secrets` / key `mqtt-password` | Mosquitto backend user password |
| `INFLUX_TOKEN` | Secret `device-service-secrets` / key `influx-token` | InfluxDB admin token |
| `MQTT_BROKER_URL` | Env / ConfigMap | Mosquitto broker URL (default: `tcp://mosquitto.apps.svc.cluster.local:1883`) |
| `MQTT_USERNAME` | Env / ConfigMap | Mosquitto username (default: `backend`) |
| `INFLUX_URL` | Env / ConfigMap | InfluxDB HTTP endpoint (default: `http://influxdb2.apps.svc.cluster.local:80`) |
| `INFLUX_ORG` | Env / ConfigMap | InfluxDB organisation (default: `homelab`) |
| `INFLUX_BUCKET` | Env / ConfigMap | InfluxDB bucket for sensor data (default: `iot-bucket`) |
| `JWKS_URI` | Env / ConfigMap | Auth-service JWKS endpoint (default: `http://auth-service.apps.svc.cluster.local:8080/oauth2/jwks`) |
| `DEVICE_SERVICE_CLIENT_SECRET` | Secret `device-service-secrets` / key `device-service-client-secret` | OIDC client secret for Swagger SSO login |

---

## Troubleshooting

### Temporary trust-store workaround (emSign roots)

The runtime image deletes the emSign ECC Root CA – G3, emSign Root CA – C1 and emSign ECC Root CA – C3 entries from the Temurin JRE cacerts (eMudhra compromise, https://blog.cloudflare.com/emudhra-certificate-compromise; tracking doemefu/homelab#212). The build fails if any of their SHA-256 fingerprints remain.

Verify in the running pod by SHA-256 fingerprint, so a root kept under a renamed alias is caught too (expected output `0`; kubectl then reports exit code 1 because `grep -c` exits 1 on zero matches; `changeit` is the JDK's public default store password, not a secret):

```bash
kubectl -n apps exec deploy/device-service -- sh -c 'keytool -list -v -cacerts -storepass changeit | grep -ciE "86:A1:EC:BA:08:9C:4A:8D:3B:BE:27:34:C6:12:BA:34:1D:81:3E:04:3C:F9:E8:A8:62:CD:5C:57:A3:6B:BE:6B|12:56:09:AA:30:1D:A0:A2:49:B9:7A:82:39:CB:6A:34:21:6F:44:DC:AC:9F:39:54:B1:42:92:F2:E8:C8:60:8F|BC:4D:80:9B:15:18:9D:78:DB:3E:1D:8C:F4:F9:72:6A:79:5D:A1:64:3C:A5:F1:35:8E:1D:DB:0E:DC:0D:7E:B3"'
```

Removal: once Temurin ships cacerts from NSS >= 3.131.1 (adoptium/temurin-build#4587), take the Dependabot docker digest bump, delete the `RUN` step including its comment from the `Dockerfile`, and tick the Temurin item in doemefu/homelab#212.

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

### Scheduled commands not executing

1. Check logs for SchedulerService errors:
   ```bash
   kubectl logs -n apps deployment/device-service | grep -i scheduler
   ```
2. Verify the `schedules` table exists (this service creates it via Flyway V2):
   ```bash
   kubectl logs -n apps deployment/device-service | grep -i flyway
   ```
3. Restart to force a schedule reload (delete the pod, see "Restart device-service"):
   ```bash
   kubectl -n apps delete pod -l app=device-service
   ```

### REST API returns 401 Unauthorized

1. Verify auth-service is running and its JWKS endpoint is reachable:
   ```bash
   kubectl get pods -n apps -l app=auth-service
   kubectl logs -n apps deployment/device-service | grep -iE "jwt|jwks|oauth"
   ```
2. Confirm `JWKS_URI` is set correctly in the device-service deployment:
   ```bash
   kubectl describe deployment device-service -n apps | grep JWKS_URI
   ```

### Swagger login redirect loop / OAuth2 login fails

1. Confirm the client secret env var is present:
   ```bash
   kubectl describe deployment device-service -n apps | grep DEVICE_SERVICE_CLIENT_SECRET
   ```
2. Verify `device-service-secrets` contains key `device-service-client-secret`.
3. Verify auth-service has a matching `device-service` OIDC client entry and the stored secret includes a Spring Security prefix (`{noop}` or `{bcrypt}`).

---

## InfluxDB Measurements

The service writes to the `iot-bucket` bucket (configurable via `INFLUX_BUCKET`) on every MQTT sensor message.

| Property | Value |
|----------|-------|
| Measurement | `terrarium` |
| Tag: `device` | Device name, e.g. `terra1`, `terra2` |
| Field: `temperature` | Air temperature in °C (Double) |
| Field: `humidity` | Relative humidity in % (Double) |
| Precision | Milliseconds |
| Trigger | Every `terra{n}/SHT35/data` MQTT message |

Only `SENSOR_DATA` messages write to InfluxDB. State-change messages (light, rain, etc.) update PostgreSQL only.
