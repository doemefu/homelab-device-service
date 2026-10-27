# CLAUDE.md — homelab-device-service

In-progress worklogs are listed at session start by the `homelab-workflow` plugin; start work with `/homelab-workflow:start-task`. Durable facts go to auto memory.

## Service Overview

Real-time IoT device management service for the homelab IoT ecosystem. Subscribes to MQTT, persists device state, writes sensor data to InfluxDB, runs scheduled commands, and broadcasts live state via WebSocket.

**Port:** 8081
**Package:** `ch.furchert.homelab.device`
**Database:** PostgreSQL — `devices` table + `schedules` table (owns both)
**InfluxDB:** Write-only (sensor measurements)
**MQTT:** Eclipse Paho — subscribe + publish
**WebSocket:** STOMP at `/ws`

## Architecture Context

Part of the homelab IoT ecosystem alongside auth-service (OIDC IdP, issues the JWTs this service validates via JWKS), furchert-ch (Next.js site whose `/dashboard` consumes this service's REST API server-side), and data-service (analytical data plane, deployed; no direct interaction with this service). The most complex service — long-running, stateful (persistent MQTT connections + in-process scheduler). Owns the schedules table and runs cron-based MQTT commands.

**Full architecture spec:** `../docs/052-architecture-target.md`
**Schedules ownership:** `../docs/adr/0001-schedules-ownership-and-data-plane.md`
**MQTT device auth (draft):** `../infrastructure/docs/053-mqtt-device-authentication.md`
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
- Commit, push and open PRs on feature branches without asking. Merging PRs is always the user's. Rolling out changes the user has already merged needs no extra go; force-pushes, playbook runs and other cluster mutations need an explicit go. SOPS files are never read or edited by Claude — the user edits them.
- Before any merge, wait for the Copilot review (CodeRabbit via `@coderabbitai review` if Copilot is out of quota) and fix or answer every comment (`/homelab-workflow:review-gate`).

## Tech Stack (pinned)

| Component | Version |
|-----------|---------|
| Java | 25 |
| Spring Boot | 4.1.1 |
| Eclipse Paho MQTT | 1.2.5 |
| influxdb-client-java | 8.0.0 |
| springdoc-openapi | 3.1.1 |
| Testcontainers | 1.21.4 (core `testcontainers` artifact: 2.0.5) |
| Base image | `eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61` (build: `25-jdk-alpine@sha256:3fd2d245c4e0eba615fe366a71b8bd25f5db7104f53e4026b24bf508b880bd2a`); runtime strips the emSign roots from cacerts (temporary, homelab#212) |

## Spring Boot 4.x Notes

- Flyway via `spring-boot-starter-flyway` + `flyway-database-postgresql` (required since Flyway 10)
- Jackson 3 (`tools.jackson` group ID) — affects MQTT JSON parsing
- `@SpringBootTest` needs explicit `@AutoConfigureMockMvc` for MockMvc
- Spring Security 7.0, OAuth2 Resource Server

## Service-Specific Conventions

- Flyway for `devices` + `schedules` tables (`spring.flyway.table=flyway_schema_history_device`)
- `spring.jpa.hibernate.ddl-auto=validate`
- Package structure: `config/`, `controller/`, `dto/`, `entity/`, `repository/`, `service/`, `exception/`
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

## Commands

```bash
./mvnw clean package              # Build
./mvnw clean package -DskipTests  # Build, skip tests
./mvnw spring-boot:run            # Run locally (cluster via port-forward)
./mvnw test                       # All tests
./mvnw test -Dtest=ClassName      # Single test class
./mvnw verify                     # Full build + tests
docker build -t device-service .  # Docker image
```

Cluster access for local dev:
```bash
kubectl port-forward -n apps svc/postgresql 5432:5432  # service is `postgresql`, not `postgres`
kubectl port-forward -n apps svc/influxdb2 8086:80     # service port 80 -> container 8086
kubectl port-forward -n apps svc/mosquitto 1883:1883
```
auth-service must be reachable for JWT validation (`JWKS_URI`, default `http://localhost:8080/oauth2/jwks`). This service owns and migrates `devices` and `schedules` (`flyway_schema_history_device`); data-service owns only `netmon`.

## Process & Conventions

Workflow, worklogs, plan structure, GitHub Project board, review gate and cross-repo tasks come from the `homelab-workflow` plugin (`/homelab-workflow:workflow`, `:start-task`, `:project-board`, `:review-gate`, `:cross-repo`).

Agents are the plugin's `homelab-workflow:<role>` (architect, implementer, reviewer, documenter, devops, plan-reviewer, doc-auditor). Repo specifics for each role are in `.claude/agent-context.md`. There are no repo-only agents.

Repo rules in `.claude/rules/` (auto-loaded by Claude Code, scoped by `paths:`):

| Rule file | Covers |
|-----------|--------|
| `code-style-conventions.md` | Java/Spring Boot, Lombok, Flyway, secrets |
| `documentation-files.md` | README, OPERATIONS, CONTRIBUTING, DEPLOYMENT, CHANGELOG, PLAN |
