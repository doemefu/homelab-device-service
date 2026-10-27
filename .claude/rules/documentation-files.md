---
paths:
  - "**/*.md"
---

# Documentation Files — Purpose & Owner

When making changes, update the relevant file(s) — the `doc-auditor` subagent (Phase 6) identifies what needs updating.

| File              | Audience     | Covers |
|-------------------|--------------|--------|
| `README.md`       | Everyone     | Landing page — responsibilities, REST/WebSocket/MQTT contract, quick start, quick reference |
| `OPERATIONS.md`   | Operators    | Runbooks, troubleshooting, MQTT/InfluxDB monitoring |
| `DEPLOYMENT.md`   | App developers | K8s deployment, secrets, ingress |
| `CONTRIBUTING.md` | Contributors | Local dev setup (port-forward), testing, PR process |
| `CHANGELOG.md`    | App users    | Describes changes to prior versions the user is affected by |
| `PLAN.md`         | Contributors | Implementation plan |

**Rule:** Docs are written in parallel with the code change, not after.
