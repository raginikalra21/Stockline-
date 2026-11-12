# Stockline Render Deployment Readiness

**Audit date:** 2026-11-13  
**Repository:** `raginikalra21/Stockline-`  
**Status:** Prepared for review; not deployed.

## Scope and result

Reviewed the application profiles, authentication and warehouse authorization, Flyway migrations, inventory transactions, worker shutdown behavior, static assets, Docker/Compose setup, and PostgreSQL integration tests. The production profile now uses Render's `PORT`, binds to all interfaces, enables graceful shutdown and secure forwarded cookies, and requires PostgreSQL credentials plus a strong Base64 `JWT_SECRET`. A Render Blueprint defines one Docker web service and a persistent PostgreSQL 16 database in Singapore. The production entrypoint converts Render's `postgresql://` connection string to the JDBC URL expected by Spring.

The production JAR excludes H2 and test-only settings. Local demo credentials are environment supplied; the browser demo-accounts asset contains only an empty array in the production image. Production rejects unsafe combinations with `dev`, `test`, `benchmark`, and `local-demo`. The concurrency lab uses disposable PostgreSQL schemas in production and drops them after a run; its H2 implementation remains available outside production.

## Verification

- `mvn clean verify`: **85 tests passed**, including **7 PostgreSQL Testcontainers tests**; no failures, errors, or skips.
- `node --check src/main/resources/static/app.js`: passed.
- `git diff --check`: passed.
- `render.yaml`: parsed as valid YAML and contains the expected web service and database resources.
- Docker production image: built successfully for `linux/amd64`; Docker reports 327 MB (down from the 474 MB Ubuntu-based build); runs as non-root `stockline`; H2 and `application-test.properties` are absent.
- Disposable production-container smoke check against PostgreSQL 16: readiness passed on `PORT=10000`; the login page and both admin and warehouse-manager dashboards rendered in the browser. The admin saw all three warehouses; a temporary PUN-only manager saw PUN and received 403 for DEL and admin access. Anonymous API access returned 401, an out-of-scope order returned 403, login/logout worked, and the auth cookie was `Secure`, `HttpOnly`, and `SameSite=Strict`.
- The same isolated database was used to create a temporary product with 2 units each in PUN and DEL. Its network total was 4 and status `AVAILABLE`. A 3-unit order split as PUN:2 and DEL:1; PUN became `OUT_OF_STOCK`, DEL stayed `AVAILABLE`, and global availability stayed `AVAILABLE` with 1 unit. The network summary matched after reservation and after fulfillment; fulfillment changed 3 reserved units to shipped. The temporary manager, product, stock, containers, network, and database were removed with the disposable stack.
- Application logs from the isolated smoke were checked for its temporary admin password, database password, and manager username; none were present.
- `docker compose config --quiet`: passed when the required environment variables were supplied with non-secret placeholder values. Compose was also checked without those variables and correctly rejected the missing configuration.

No live Render deployment was created, and no GitHub push was made. No local H2 database or credentials file was read, copied, reset, or removed. The local stock demo and the throwaway PostgreSQL smoke database are separate. Browser console inspection and mobile/desktop layout checks remain part of the live verification after a Render URL is provided.

## Review and deployment steps

Review the code and `render.yaml`, then push the accepted changes to the repository's `main` branch. In Render, create a Blueprint from `https://github.com/raginikalra21/Stockline-`, inspect the displayed web and database plans and current charges, supply a unique admin username and password of at least 12 characters, and create the resources. Render generates the JWT secret and wires the private PostgreSQL connection. Wait for readiness, sign in, and share the public service URL for the final live checks.

The database plan in the Blueprint is paid and persistent. Render documents `0.5c-1g` as a PostgreSQL compute plan and `0.5c-512mb` as a web compute plan; see [Blueprint YAML reference](https://render.com/docs/blueprint-spec), [compute plans](https://render.com/docs/compute-plans), and [Postgres connection strings](https://render.com/tutorials/postgres-on-render/connection-strings). Confirm the plan and current price in Render before creating the Blueprint. Do not import local demo data into production.
