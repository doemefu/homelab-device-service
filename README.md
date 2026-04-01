Soon to come :)

### device-service

**Domain:** Real-time IoT device management.

**Responsibilities:**
- MQTT subscriber (all `terra#/#` topics)
- Device state persistence in PostgreSQL (`devices` table)
- InfluxDB writer (sensor data on every MQTT message)
- Scheduled MQTT commands (light, nightlight, rain automation)
- WebSocket broadcast (STOMP, live device state to frontend)
- Device control REST endpoint (manual toggle -> MQTT publish)

**Does NOT:**
- Handle user authentication or user CRUD
- Query historical InfluxDB data for charts

**Database:** PostgreSQL — `devices` table (owns it), reads `schedules` table
**InfluxDB:** Write-only (sensor measurements)
**MQTT:** Full client (subscribe + publish)

**Key design decision:** This is a long-running, stateful service. It maintains persistent MQTT connections and in-process scheduled tasks. Restarting this service briefly disconnects from MQTT but reconnects automatically. The scheduling engine reads from the `schedules` DB table and refreshes periodically or on notification.

