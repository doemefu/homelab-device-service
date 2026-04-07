# CLAUDE.md — homelab-device-service

> **Session start:** Read `.claude/memory/MEMORY.md` completely. The topmost entry shows the current state. If there is an entry with `status: in_progress`, read the linked worklog and ask the user: *"I see we were interrupted at [SLUG]. Continue?"* — before doing anything else.

> **After each completed change:** Insert a new block **at the top** of `.claude/memory/MEMORY.md`. The file grows top-down — newest entries always visible first.

## Service Overview

Real-time IoT device management service for the homelab IoT ecosystem. Subscribes to MQTT, persists device state, writes sensor data to InfluxDB, runs scheduled commands, and broadcasts live state via WebSocket.

**Port:** 8081
**Package:** `ch.furchert.homelab.device`
**Database:** PostgreSQL — `devices` table (owns it), reads `schedules` table (data-service owns it)
**InfluxDB:** Write-only (sensor measurements)
**MQTT:** Eclipse Paho — subscribe + publish
**WebSocket:** STOMP at `/ws`

## Architecture Context

This is 1 of 3 microservices. The most complex service — long-running, stateful (persistent MQTT connections + in-process scheduler). Validates JWTs via auth-service JWKS endpoint. Reads schedules from data-service's table.

**Full architecture spec:** `../../docs/052-architecture-target.md`
**Implementation plan:** `PLAN.md`

## Non-Negotiables

- Do **not** touch secrets, MQTT passwords, or credentials
- Do **not** use `latest` for any dependency version — all versions pinned
- Do **not** use `ddl-auto=update` or `ddl-auto=create` — Flyway only
- Do **not** log tokens, MQTT passwords, or secrets
- Do **not** create Flyway migrations for the `schedules` table — data-service owns it
- Do **not** introduce new dependencies without explicit user approval
- All comments and documentation in **English**
- Minimize diff size: no drive-by refactors

## Tech Stack (pinned)

| Component | Version |
|-----------|---------|
| Java | 25 |
| Spring Boot | 4.0.5 |
| Eclipse Paho MQTT | 1.2.5 |
| influxdb-client-java | 7.2.0 |
| springdoc-openapi | 2.7.0 |
| Testcontainers BOM | 1.20.4 |

## Spring Boot 4.0 Notes

- Flyway via `spring-boot-starter-flyway` (no separate dialect dep)
- Jackson 3 (`tools.jackson` group ID) — affects MQTT JSON parsing
- `@SpringBootTest` needs explicit `@AutoConfigureMockMvc` for MockMvc
- Spring Security 7.0, OAuth2 Resource Server
- WebSocket+Jackson bug fixed in 4.0.5 (`#49749`)

## Service-Specific Conventions

- Flyway for `devices` table only (`spring.flyway.table=flyway_schema_history_device`)
- `spring.jpa.hibernate.ddl-auto=validate`
- Package structure: `config/`, `controller/`, `dto/`, `entity/`, `repository/`, `service/`, `security/`, `exception/`
- OAuth2 Resource Server for JWT validation
- MQTT: Eclipse Paho with `MqttCallbackExtended`, auto-reconnect, LWT
- Scheduler: `ThreadPoolTaskScheduler` + `ConcurrentHashMap` for task tracking

## MQTT Topics

**Subscribe:** `terra1/#`, `terra2/#`, `terraGeneral/#`
**Publish:** `terra{n}/{field}/man` (control), `terraGeneral/{field}/schedule` (scheduled), `javaBackend/mqtt/status` (heartbeat)

## Testing

- Unit tests: Mockito, MockMvc (`@AutoConfigureMockMvc`) for controllers
- Integration tests: Testcontainers with `PostgreSQLContainer`, `InfluxDBContainer`, `GenericContainer("eclipse-mosquitto:2")`
- Mosquitto test config: `src/test/resources/mosquitto-test.conf` (anonymous auth)
- Tests are required for every feature

---

## Process & Conventions

Detailed process rules are in `.claude/rules/` (auto-loaded by Claude Code):

| Rule file | Covers |
|-----------|--------|
| `workflow.md` | 6-phase milestone workflow |
| `worklog-conventions.md` | Worklog location, naming, header, structure |
| `plan-structure.md` | 8-section plan template |
| `commands.md` | Build, test, cluster access commands |
| `code-style-conventions.md` | Java/Spring Boot, Lombok, Flyway, secrets |
| `review-guidelines.md` | Security, diffs, version pinning, tests |
| `documentation-files.md` | README, OPERATIONS, CONTRIBUTING, DEPLOYMENT |
