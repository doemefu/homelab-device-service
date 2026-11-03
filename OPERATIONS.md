# OPERATIONS.md — homelab-device-service

Runbooks and operational reference for device-service on the K3s cluster.

---

## Service Runbooks

### Restart device-service

```bash
kubectl -n apps delete pod -l app=device-service
kubectl -n apps get pods -l app=device-service        # new pod: age in seconds, Ready 1/1 (ignore the old one while it is Terminating)
kubectl -n apps logs deployment/device-service --tail=50
```

> **Note:** Do not use `kubectl rollout restart`. Flux reconciles this Deployment and strips the `restartedAt` annotation at its next reconcile (at most 10 min), so the restart can be cancelled before the new pod is Ready while `rollout status` still reports success, or the pod restarts twice. See infrastructure `DEPLOYMENT.md`, note under "Enable order (NM-4)". Check the pod age and the startup log, not `rollout status`.
>
> The service is unavailable for its startup time, typically about 45 to 95 s (single replica: REST, WebSocket and MQTT). The same applies to every Flux rollout, because a rollout does not start the new pod next to the running old one (`maxSurge: 0`, see "MQTT reconnection behavior"). The new pod connects to the broker at startup and re-registers scheduled tasks from the database; `setAutomaticReconnect(true)` retries only after a successful first connect (broker or network drops); if the first connect at startup fails, the pod logs `Failed to connect to MQTT broker` and does not retry until it is restarted.

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
- One `Connected to MQTT broker` line, no `MQTT connection lost`
- Subscriptions to `terra1/#`, `terra2/#`, `terraGeneral/#`
- LWT published to `javaBackend/mqtt/status`

### MQTT reconnection behavior

- Eclipse Paho auto-reconnect is enabled (`setAutomaticReconnect(true)`)
- On reconnect, subscriptions are re-established via `MqttCallbackExtended.connectComplete()`
- LWT (`javaBackend/mqtt/status` with `{"MqttState": 0}`) is published by the broker when the client disconnects ungracefully
- **`MQTT connection lost` / `Reconnected` every ~2.6 s, repeating** is the signature of a second client connected with the same client id `device-service` (a single broker restart or network drop produces one pair, not a cadence): the broker drops the older session on each connect, and both clients reconnect after 1 s. Until #67 this happened on every rollout (the default RollingUpdate ran the new pod next to the old one, which also ran a second scheduler); the Deployment now uses `maxSurge: 0`. If the pattern shows up again, look for the second client: a pod still running on a `NotReady` node, or a local `./mvnw spring-boot:run` against the port-forwarded broker without its own `MQTT_CLIENT_ID`.

### Mosquitto broker down

If Mosquitto is restarted or unavailable:
1. device-service logs will show connection errors
2. Paho retries automatically with exponential backoff
3. No manual intervention needed — wait for Mosquitto to come back
4. Verify recovery: `kubectl logs -n apps deployment/device-service | tail -20`

---

## Scheduler

Schedules are managed through the REST API (`/schedules`, README); API writes take effect immediately. The scheduler also re-reads the `schedules` table every 60 seconds (`app.scheduler.poll-interval`) as a fallback for out-of-band database edits. Cron expressions run in UTC (container default).

If an API write logs `Schedule change saved, but the immediate reload failed`, the change is stored and the next periodic reload applies it.

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
| `JWT_ISSUER` | Env (not set; default used) | Expected `iss` of access tokens (default: `https://auth.furchert.ch`) |
| `AUTH_SERVICE_BASE_URL` | Env / ConfigMap | Auth-service base URL for the admin client API used by device registration (default: `http://auth-service.apps.svc.cluster.local:8080`) |
| `DEVICE_SERVICE_CLIENT_SECRET` | Secret `device-service-secrets` / key `device-service-client-secret` | OAuth2 client secret — reused for Swagger SSO login **and** the `device-service-admin` `client_credentials` registration that calls the auth-service admin API |

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
   # Connect a STOMP client to ws://localhost:8081/ws with the CONNECT header
   # "Authorization: Bearer <access token>"
   ```
3. A STOMP `ERROR` frame with message `Unauthorized` on CONNECT means the token is missing, not a `Bearer` token, or rejected by the JWT decoder (expired, wrong signer, wrong or missing `iss`, `typ: at+jwt`, or an ID token). Rejected CONNECTs are logged only at DEBUG.
4. A handshake rejected with HTTP 403 comes from the origin check: add the browser origin to `APP_WEBSOCKET_ALLOWED_ORIGINS` (default `https://furchert.ch`; local dev e.g. `http://localhost:3000`).
5. Emergency kill switch, if a legitimate client cannot send a token yet: open a PR that adds `APP_WEBSOCKET_REQUIRE_AUTH=false` to the container `env` in `k8s/deployment.yaml` (or revert the change). Do not use `kubectl set env`: the Deployment is Flux-managed and Flux reverts it. `SUBSCRIBE` outside `/topic/terrarium/` and client `SEND` outside `/app/**` stay rejected either way.

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
3. The token's `iss` must equal `JWT_ISSUER` (default `https://auth.furchert.ch`, the issuer in auth-service's discovery document). ID tokens (claim `azp`) and tokens with header `typ: at+jwt` are rejected as well; the response header `WWW-Authenticate` carries `error="invalid_token"`.

### REST API returns 403 Forbidden

Every method other than GET under `/devices/**` (device control, registration, deletion) needs the `role` claim `ADMIN` (#81). Tokens of signed-in users carry their role; client_credentials tokens carry none and get 403 with `WWW-Authenticate: ... error="insufficient_scope"`. Use the access token of an ADMIN user.

### Device registration (`POST /devices`) fails

1. `403 Forbidden` — the caller's JWT lacks `role: ADMIN` (mapped to
   `ROLE_ADMIN`). Confirm the user is an admin in auth-service.
2. `409 Conflict` — the device name already exists locally **or** auth-service
   already has that `clientId`. Pick another name or `DELETE /devices/{name}`
   first. **Orphaned-client dead-end:** if you get `409` but `GET /devices`
   shows no such device, an earlier compensation failed and left an
   auth-service client with no local row. `DELETE /devices/{name}` returns
   `404` (no local row to delete) and does **not** clear it — reconcile
   directly via the auth-service admin API, then re-register:
   ```bash
   # cluster-internal; needs an ADMIN token or clients:admin scope
   curl -X DELETE -H "Authorization: Bearer <admin-access-token>" \
     http://auth-service.apps.svc.cluster.local:8080/api/v1/clients/<name>
   ```
3. `5xx` with a compensation log line:
   ```bash
   kubectl logs -n apps deployment/device-service | grep -iE "compensat|orphaned auth-service client"
   ```
   `COMPENSATION FAILED — orphaned auth-service client` means an auth-service
   client exists with no local row. Reconcile manually via the auth-service
   admin API (`DELETE /api/v1/clients/{clientId}`). `Outcome of auth-service
   client creation ... is unknown` (timeout, reset) is followed by the same
   compensation; retry the registration once auth-service is healthy.
4. Token/connectivity errors calling auth-service: verify `AUTH_SERVICE_BASE_URL`
   and that the `device-service` client has the `client_credentials` grant +
   `clients:admin` scope in auth-service (auth-service `INTERFACES.md` §8).
   auth-service seeds clients only once (existing rows are skipped), so check the
   live row, not `application.yaml` (reads no secret column):
   ```bash
   kubectl -n apps exec postgresql-0 -c postgresql -- psql -U postgres -d homelabdb -tAc "SELECT client_id, authorization_grant_types, scopes FROM oauth2_registered_client WHERE client_id = 'device-service'"
   ```
5. Deleting a device that was auto-created from its first MQTT message
   (`provisioned = false`) revokes nothing in auth-service (it has no client),
   and the row comes back with the device's next MQTT message.
6. `DELETE /devices/{name}` returns `5xx` and the device is still listed: the
   auth-service revocation failed, so the row was kept on purpose. Fix
   auth-service connectivity and retry (revocation is idempotent).
7. A device deleted locally but still able to obtain tokens: deletion revokes
   the client immediately, but already-issued device JWTs stay valid until
   their `exp` (≤1 h) — this is expected (auth-service `INTERFACES.md` §8).

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
