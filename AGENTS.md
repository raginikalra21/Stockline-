# Repository Guidelines

## Project Structure

Java 17 / Spring Boot 3 code is grouped under `src/main/java/in/stockline`: `api`, `domain`, `service`, `persistence/repository`, `events`, `security`, and `simulation/concurrency`. Flyway SQL is in `src/main/resources/db/migration`, the browser client in `src/main/resources/static`, and tests in `src/test/java/in/stockline`.

## Build, Test, and Run

- `mvn clean verify` runs tests and packages the executable JAR.
- `mvn spring-boot:run` starts the local H2 development profile; set `STOCKLINE_ADMIN_USERNAME` and `STOCKLINE_ADMIN_PASSWORD` first to provision the initial administrator.
- `docker compose up --build` starts PostgreSQL and the application; configure `.env` from `.env.example` first.
- `mvn -Dtest=InventoryServiceTest test` runs the inventory/concurrency service tests. PostgreSQL Testcontainers tests run when Docker is available and skip otherwise.
- `node --check src/main/resources/static/app.js` checks browser JavaScript syntax.

## Style and Design

Use four spaces for Java indentation, types in `UpperCamelCase`, and members in `lowerCamelCase`. Put validated request records in `api/dto` and SQL in repositories. Keep stock/order transactions atomic with idempotency, ordered updates, version checks, and ledger writes. Keep `service/allocation` side-effect free; placement revalidates and reserves in one transaction. Write outbox events with business changes and make handlers idempotent. Add forward-only migrations.

## Tests

Name JUnit tests for verified behavior. Use H2 transactions for service invariants, MockMvc for API/security, and Testcontainers for PostgreSQL behavior. Assert persisted balances, allocations, ledger entries, and rollback outcomes.

## Observability and Benchmarks

Keep actuator details and metrics admin-only; liveness must not depend on PostgreSQL, while readiness must. Preserve request correlation when writing outbox events. Run `scripts/run-benchmark.sh` only against its isolated in-memory profile; keep JSON under ignored `benchmark-results/`.

## Commits and Pull Requests

Recent commits use short sentence-case summaries (for example, `Add PostgreSQL deployment and integration tests`). Keep each commit focused. Pull requests should explain the behavior change, list migrations/configuration requirements, include relevant test results, link related issues, and attach screenshots for visible UI changes.

## Configuration and Security

Never commit `.env`, credentials, database files, benchmark results, or generated `target/` output. Production and PostgreSQL profiles require a strong Base64 `JWT_SECRET`; use environment variables for admin bootstrap and database settings. Preserve server-side warehouse checks even when the UI hides restricted views.
