---
paths:
  - "src/**/*.java"
  - "src/main/resources/db/migration/**"
  - "pom.xml"
---

### Java
- Standard Spring Boot conventions
- Lombok for boilerplate reduction
- Flyway for all database migrations (no `ddl-auto=update` in production)

### Secrets
- Plaintext secrets in git: **forbidden**
- Plaintext secrets in git are forbidden. Secrets are SOPS + age encrypted by the user, never by Claude; Claude never reads or edits `*.sops.*` files — name the file and key and hand it to the user.
- age key lives **outside** the repo
