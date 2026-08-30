# CLAUDE.md — homelab-device-service

> **Session start:** Read `.claude/memory/MEMORY.md` completely. The topmost entry shows the current state. If there is an entry with `status: in_progress`, read the linked worklog and ask the user: *"I see we were interrupted at [SLUG]. Continue?"* — before doing anything else.

`.claude/` is fully gitignored in this repo — memory, worklogs, agents and rules exist only on this machine; cross-check `git log`/GitHub when they look stale.

> **After each completed change:** Insert a new block **at the top** of `.claude/memory/MEMORY.md`. The file grows top-down — newest entries always visible first.

## Service Overview

Real-time IoT device management service for the homelab IoT ecosystem. Subscribes to MQTT, persists device state, writes sensor data to InfluxDB, runs scheduled commands, and broadcasts live state via WebSocket.

**Port:** 8081
**Package:** `ch.furchert.homelab.device`
**Database:** PostgreSQL — `devices` table + `schedules` table (owns both)
**InfluxDB:** Write-only (sensor measurements)
**MQTT:** Eclipse Paho — subscribe + publish
**WebSocket:** STOMP at `/ws`

## Architecture Context

Part of the homelab IoT ecosystem alongside auth-service (OIDC IdP, issues the JWTs this service validates via JWKS), furchert-ch (Next.js site whose `/dashboard` consumes this service's REST API server-side), and data-service (planned only — not yet deployed). The most complex service — long-running, stateful (persistent MQTT connections + in-process scheduler). Owns the schedules table and runs cron-based MQTT commands.

**Full architecture spec:** `../docs/052-architecture-target.md`
**Implementation plan:** `PLAN.md`

## Non-Negotiables

- Do **not** touch secrets, MQTT passwords, or credentials
- Do **not** use `latest` for any dependency version — all versions pinned
- Do **not** use `ddl-auto=update` or `ddl-auto=create` — Flyway only
- Do **not** log tokens, MQTT passwords, or secrets
- Do **not** create Flyway migrations for tables owned by other services
- Do **not** introduce new dependencies without explicit user approval
- All comments and documentation in **English**
- Minimize diff size: no drive-by refactors
- Commit, push and open PRs on feature branches without asking (standing permission, 2026-08-28). Merging, force-pushes, playbook runs, cluster mutations and anything touching SOPS/secrets need an explicit go for that task.
- Before any merge, wait for the Copilot review and fix or answer every comment.

## Tech Stack (pinned)

| Component | Version |
|-----------|---------|
| Java | 25 |
| Spring Boot | 4.1.1 |
| Eclipse Paho MQTT | 1.2.5 |
| influxdb-client-java | 8.0.0 |
| springdoc-openapi | 3.1.0 |
| Testcontainers | 1.21.4 (core `testcontainers` artifact: 2.0.5) |
| Base image | `eclipse-temurin:25-jre-alpine` |

## Spring Boot 4.x Notes

- Flyway via `spring-boot-starter-flyway` + `flyway-database-postgresql` (required since Flyway 10)
- Jackson 3 (`tools.jackson` group ID) — affects MQTT JSON parsing
- `@SpringBootTest` needs explicit `@AutoConfigureMockMvc` for MockMvc
- Spring Security 7.0, OAuth2 Resource Server
- WebSocket+Jackson bug fixed in 4.0.5 (`#49749`)

## Service-Specific Conventions

- Flyway for `devices` + `schedules` tables (`spring.flyway.table=flyway_schema_history_device`)
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

Detailed process rules are in `.claude/rules/` (auto-loaded by Claude Code; local-only in this repo, see the note above):

| Rule file | Covers |
|-----------|--------|
| `workflow.md` | 6-phase milestone workflow |
| `worklog-conventions.md` | Worklog location, naming, header, structure |
| `plan-structure.md` | 8-section plan template |
| `commands.md` | Build, test, cluster access commands |
| `code-style-conventions.md` | Java/Spring Boot, Lombok, Flyway, secrets |
| `review-guidelines.md` | Security, diffs, version pinning, tests |
| `documentation-files.md` | README, OPERATIONS, CONTRIBUTING, DEPLOYMENT |
| `github-project.md` | GitHub Project #5 status transitions |
