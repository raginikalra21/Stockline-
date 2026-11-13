# Stockline Render Deployment Readiness

**Audit date:** 2026-11-13  
**Repository:** `raginikalra21/Stockline-`  
**Status:** Free-tier configuration prepared for review; not deployed.

## Scope and result

Reviewed the application profiles, authentication and warehouse authorization, Flyway migrations, inventory transactions, worker shutdown behavior, static assets, Docker/Compose setup, and PostgreSQL integration tests. The production profile uses Render's `PORT`, binds to all interfaces, enables graceful shutdown and secure forwarded cookies, and requires PostgreSQL credentials plus a strong Base64 `JWT_SECRET`. The Blueprint defines one Docker web service on the Free plan in Oregon and references the existing `stockline-db` PostgreSQL resource without declaring a database. The production entrypoint converts Render's `postgresql://` connection string to the JDBC URL expected by Spring.

The production JAR excludes H2 and test-only settings. Local demo credentials are environment supplied; the browser demo-accounts asset contains only an empty array in the production image. Production rejects unsafe combinations with `dev`, `test`, `benchmark`, and `local-demo`. The concurrency lab uses disposable PostgreSQL schemas in production and drops them after a run; its H2 implementation remains available outside production.

## Verification

- `mvn clean verify`: **85 tests run**, no failures or errors; **7 Docker-dependent tests skipped** because the Docker socket was inaccessible in this session.
- `node --check src/main/resources/static/app.js`: passed.
- `git diff --check`: passed.
- `render.yaml`: parsed as valid YAML; it contains only the web service and points to the existing database by name. Render's Blueprint preview is still needed to confirm the account-level sync plan.
- `docker compose config --quiet`: passed with non-secret placeholder values.
- `docker build --platform linux/amd64`: passed for the reviewed production image after allowing access to Docker Desktop. The image runs as non-root `stockline`; the live Render startup remains unverified.
- Disposable production-container smoke check against PostgreSQL 16: readiness passed on `PORT=10000`; the login page and both admin and warehouse-manager dashboards rendered in the browser. The admin saw all three warehouses; a temporary PUN-only manager saw PUN and received 403 for DEL and admin access. Anonymous API access returned 401, an out-of-scope order returned 403, login/logout worked, and the auth cookie was `Secure`, `HttpOnly`, and `SameSite=Strict`.
- The same isolated database was used to create a temporary product with 2 units each in PUN and DEL. Its network total was 4 and status `AVAILABLE`. A 3-unit order split as PUN:2 and DEL:1; PUN became `OUT_OF_STOCK`, DEL stayed `AVAILABLE`, and global availability stayed `AVAILABLE` with 1 unit. The network summary matched after reservation and after fulfillment; fulfillment changed 3 reserved units to shipped. The temporary manager, product, stock, containers, network, and database were removed with the disposable stack.
- Application logs from the isolated smoke were checked for its temporary admin password, database password, and manager username; none were present.

No live Render deployment has been created. The current Render workspace has an available Free PostgreSQL 18 database named `stockline-db` in Oregon, scheduled to expire December 13, 2026; the project has no Stockline web service. No database contents or secrets were inspected, and no production inventory was changed. No local H2 database or credentials file was read, copied, reset, or removed. The local stock demo and the throwaway PostgreSQL smoke database are separate. Browser console inspection and mobile/desktop layout checks remain part of the live verification after a Render URL is provided.

## Review and deployment steps

Review the deployment-only changes and push them to the existing `render-deployment` branch of `https://github.com/raginikalra21/Stockline-`; do not include unrelated frontend review edits. In Render, create a Blueprint from that repository and branch, then inspect the preview. It must show only the Free web service and references to the existing database, with no database creation. Supply a unique admin username and password of at least 12 characters and let Render generate the JWT secret. Stop before the final Create/Deploy action for explicit approval. After approval, wait for readiness, sign in, and use only separately approved test data for live order and inventory checks.

This is a temporary Free-tier evaluation, not durable production hosting. Render Free Postgres has a 1 GB limit, expires 30 days after creation, and has no backups. The existing database's scheduled expiry is December 13, 2026. Free web services sleep after inactivity and can take about a minute to wake. See [Render Free instance limits](https://render.com/docs/free), [Blueprint YAML reference](https://render.com/docs/blueprint-spec), and [Postgres connection strings](https://render.com/tutorials/postgres-on-render/connection-strings). Do not import local demo data into the existing database. First application startup runs Flyway; on an already baselined schema, migrations may create missing objects, add guarded demo catalog rows, disable legacy `INVENTORY_OPERATOR` accounts, and bootstrap opening ledger records. Review those effects before approving the first deploy.
