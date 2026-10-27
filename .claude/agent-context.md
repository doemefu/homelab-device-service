# Agent context — device-service

Repo-specific facts for the `homelab-workflow` agents. Each agent reads only its own `## <role>` section; generic rules live in the plugin.

## architect

- Stack: Java 25 / Spring Boot 4.1 / Spring Security 7 (OAuth2 Resource Server).
- Contract document: `README.md` ("API Reference", "MQTT Topic Reference": REST, WebSocket/STOMP, MQTT topics and payloads). There is no `INTERFACES.md` yet; create one only if a change needs a dedicated contract file. Cross-repo changes: spec goes into `../docs/` per `/homelab-workflow:cross-repo`; this repo only summarises and links.
- Read before writing: `CLAUDE.md`, `README.md`, `PLAN.md`, the touched code under `src/main/java/ch/furchert/homelab/device/` (`config/`, `controller/`, `service/`, `dto/`), and `../auth-service/INTERFACES.md` when JWT claims or JWKS consumption are touched.
- Contract scope for this service:
  - WebSocket/STOMP destination `/topic/terrarium/{name}` and its payload.
  - MQTT topics: `terra1/#`, `terra2/#`, `terraGeneral/#`, `terra{n}/{field}/man`, `terraGeneral/{field}/schedule`, `javaBackend/mqtt/status`; exact JSON payload per topic.
  - DB: Flyway migration filename, columns, constraints for `devices` / `schedules`. This service owns both tables; never specify a migration for a table another service owns (data-service owns only the `netmon` schema).
  - Env vars / Kubernetes secret keys (name and purpose only), JWT scope/role per endpoint.
- Consumers: furchert-ch and data-service. Backwards-incompatible change: this service ships first, consumers adapt after.
- Hand-back line: "Contract updated — implementer can start."

## implementer

- Read first: the contract in `README.md` (if contract-affecting), the worklog plan, `CLAUDE.md` (including `## Commands`), `.claude/rules/code-style-conventions.md`.
- Stack: Java 25, Spring Boot 4.1.1, Spring Security 7 (OAuth2 Resource Server validating JWTs against auth-service JWKS at `/oauth2/jwks`), Eclipse Paho 1.2.5 (MQTT), influxdb-client-java 8.0.0, Flyway, springdoc 3.1.1, Lombok, Jackson 3. Tests: Mockito, MockMvc, Testcontainers (Postgres, InfluxDB, `eclipse-mosquitto:2`).
- Packages under `ch.furchert.homelab.device`: `config/` (incl. `SecurityConfig`), `controller/`, `dto/`, `entity/`, `repository/`, `service/`, `exception/`.
- Schema: only via a new Flyway migration `V{n}__{description}.sql`, only for `devices` / `schedules`. `spring.jpa.hibernate.ddl-auto=validate` (never `update`/`create`); history table `flyway_schema_history_device`. Never a migration for a table another service owns.
- Credentials (MQTT, client secret `DEVICE_SERVICE_CLIENT_SECRET`) come only from env vars / Kubernetes `secretKeyRef`.
- Every `@WebMvcTest` needs `@Import(SecurityConfig.class)`, otherwise MVC security is not wired.
- Unit tests for everything; an integration test (Testcontainers) for anything touching persistence, MQTT or InfluxDB.
- New dependencies: check `pom.xml` first, need explicit user approval.
- Hand-offs: ambiguous contract -> ask the architect; new secret, env var or migration -> tell devops after review approval.

## reviewer

Verify the implementation matches every documented endpoint, WebSocket destination, MQTT topic/payload and claim in `README.md` exactly. Also check `pom.xml` for required dependencies with exact pinned versions.

Security:
- [ ] Protected endpoints are enforced in `SecurityConfig`'s filter chain, not merely configured; no stray `permitAll()`.
- [ ] `securityMatcher` lists both `/api-docs` and `/api-docs/**` if springdoc paths changed.
- [ ] JWTs are validated against auth-service JWKS, never a hardcoded key.
- [ ] Unauthenticated POST returns 403, not 401 (CSRF runs before the bearer entry point); tests must assert the correct code.

Correctness:
- [ ] Flyway only (`V{n}__{description}.sql`), no `ddl-auto=create`/`update`; table `flyway_schema_history_device`, `ddl-auto=validate`, only `devices`/`schedules`.
- [ ] MQTT topic strings match the README contract exactly (`terra1/#`, `terra2/#`, `terraGeneral/#`, `terra{n}/{field}/man`, `terraGeneral/{field}/schedule`, `javaBackend/mqtt/status`).
- [ ] Jackson 3 parsing does not call `asInt()` on a possibly non-numeric node without a guard (it throws instead of returning a default).
- [ ] STOMP destination `/topic/terrarium/{name}` and payload shape match the README contract.
- [ ] REST response shapes match the README contract exactly (every field, correct types).
- [ ] Any change to a documented endpoint, topic or claim is reflected in `README.md` in the same change.

Architecture and tests:
- [ ] No business logic in controllers (belongs in the service layer).
- [ ] Inter-service/cluster URLs use env vars, not hardcoded values.
- [ ] Each Spring `ClientRegistration` declares exactly one grant type.
- [ ] `@WebMvcTest` classes import `SecurityConfig`.
- [ ] Tests present: MockMvc for controllers, Testcontainers (Postgres/InfluxDB/`eclipse-mosquitto:2`) for integration. Tests are required for every feature (unit + integration).
- [ ] No secrets, tokens or credentials logged in any form.

## documenter

- Maintains: `PLAN.md` (mark phases complete, update status table, record deviations; never delete future phases), `CLAUDE.md` (build commands and architecture section, kept concise), `README.md`, plus the per-file ownership in `.claude/rules/documentation-files.md`.
- `README.md` must contain: (1) what the service does in 2–3 sentences; (2) key endpoints (method, path, auth required, description); (3) env vars read (name + description); (4) how to build and run locally; (5) how to run locally against a port-forwarded cluster DB/broker (`kubectl port-forward -n apps svc/postgresql 5432:5432`, `svc/mosquitto 1883:1883`).
- `README.md` is the contract with consumers; `PLAN.md` is the roadmap.

## devops

- Image: CI builds multi-arch images to GHCR `ghcr.io/doemefu/homelab-device-service`. Flux CD image automation detects the new `main-YYYYMMDDTHHmmss` tag and updates `k8s/deployment.yaml`; no manual `kubectl apply` in normal operation. Flux/CI own the image tag: do not edit `k8s/deployment.yaml` for it.
- Secret `device-service-secrets` (including `DEVICE_SERVICE_CLIENT_SECRET`) is provisioned by the `infrastructure` repo's `59_app_services.yml` playbook via SOPS. New env var or secret key: check `DEPLOYMENT.md` documents it and tell the user the playbook needs the matching entry; do not edit the `infrastructure` repo.
- Flyway migrations run automatically on pod startup; no manual migration step.
- CI check: `gh run list -R doemefu/homelab-device-service --limit 5`.
- Post-rollout verification (read-only):
  `kubectl -n apps rollout status deployment/device-service`
  `kubectl -n apps get pods -l app=device-service`
  `kubectl -n apps logs deployment/device-service --tail=50`
- Never `kubectl delete pod` on this deployment: `replicas: 1` with a persistent MQTT connection. Use `kubectl rollout restart deployment/device-service`.
- Do not lower the startup probe budget (`failureThreshold: 60` x `periodSeconds: 5` = 300 s) or `limits.cpu: 1000m` without measuring actual startup time.
- Do not touch MQTT/DB credentials or anything in the `infrastructure` repo.

## plan-reviewer

Applies to this Java/Spring Boot service.

**Secrets**
- Plaintext secrets, credentials or tokens in code, properties or test fixtures? Secrets only via env vars; integration tests use test-specific credentials only.

**Version pinning**
- `latest` tag for Docker base images? Version ranges (`^`, `~`, `+`) in `pom.xml`? Spring Boot pinned to an exact patch (currently 4.1.1, not `4.1.x`)?

**Database migrations**
- Flyway used for ALL schema changes; no `ddl-auto=create`/`update` in production config?
- Migration named `V{n}__{description}.sql`? Rollback considered? Idempotent against an already-migrated DB (`IF NOT EXISTS` etc.)?
- Does it respect that device-service owns `devices`/`schedules` (ADR 0001) and never migrates another service's table?

**JWT / security**
- RSA public key loaded from auth-service JWKS endpoint at startup, not hardcoded?
- All auth-required endpoints secured in `SecurityFilterChain`; nothing that should be protected is `permitAll()`?
- Role checks (`hasRole('ADMIN')`) applied at the right layer (controller vs. service)?

**MQTT**
- Client reconnects automatically on disconnect? Will message (LWT) set so the broker announces disconnection? QoS appropriate per topic (sensor data vs. control commands)? Client is a singleton, not created per request?
- Race conditions in the MQTT message handler when messages arrive concurrently?

**InfluxDB writes**
- Writes non-blocking (async, not holding up the MQTT message handler)? Tags vs. fields correct (device name = tag, measurements = fields)?

**Tests**
- Testcontainers for all integration tests (no mocked DB/MQTT/InfluxDB)?
- Every new public service method has a unit test?
- Every controller endpoint has a MockMvc test covering success, validation error, unauthorized, not found?
- Integration tests clean up state between runs (no test order dependency)?

**K8s manifest**
- CPU and memory `requests` and `limits` set? Liveness probe present (not only readiness)? Image tag specific, not `latest`? Secrets via `secretKeyRef`, not hardcoded env? Namespace `apps`?

**Diff size**
- Files unrelated to the stated goal, drive-by refactors, style changes or renames?

**Multi-arch**
- Dockerfile uses a multi-arch base image (`eclipse-temurin:25-jre-alpine`)? GitHub Actions workflow builds both `linux/arm64` and `linux/amd64`?

**Architectural questions (tailor to the plan)**
- Simpler alternative (reuse existing service vs. new endpoint)? New dependency explicitly approved by the user?
- Fits existing patterns (error handling, response shapes)? Business logic in the service layer, not controller or repository?
- Fewer classes/abstractions possible? Failure mode if PostgreSQL or InfluxDB is temporarily unavailable?
- Correct after a pod restart (no in-memory state that must survive)?
- Does a schedule/MQTT change respect that device-service owns the `schedules` table?

## doc-auditor

- Context: real-time IoT device management service (Java/Spring Boot, MQTT + InfluxDB + WebSocket) on K3s (namespace `apps`, `replicas: 1`) via Cloudflare Tunnel. Secrets are provisioned by the `infrastructure` repo's `infra/playbooks/59_app_services.yml`. Consumers: furchert-ch and data-service.
- Docs to read in full: `README.md`, `OPERATIONS.md`, `DEPLOYMENT.md`, `CONTRIBUTING.md`, `CHANGELOG.md` (`PLAN.md` is the roadmap, maintained by the documenter).
- Per doc:
  - `README.md`: quick start / quick reference accurate; REST endpoint, WebSocket destination, MQTT topic/payload or JWT claim requirement added or changed.
  - `OPERATIONS.md`: new runbook entry (`kubectl rollout restart deployment/device-service`), failure mode (MQTT disconnect, InfluxDB write failure) or monitoring note.
  - `DEPLOYMENT.md`: new env var or Secret key in `device-service-secrets` (provisioned via `infra/playbooks/59_app_services.yml`); changed probe timing or resource limits.
  - `CONTRIBUTING.md`: new local-dev prerequisite, test command or Flyway migration convention.
  - `CHANGELOG.md`: user-visible change since the last entry.
