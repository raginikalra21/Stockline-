# Repository Guidelines

## Project Structure

Stockline is a Java 17 and Spring Boot inventory application. Backend code lives in `src/main/java/in/stockline/`: `ApiController` exposes HTTP endpoints, `InventoryService` owns transactional stock and order rules, `Store` contains JDBC queries, and `InventoryAuditService` reconciles the movement ledger. Versioned database migrations are in `src/main/resources/db/migration/`. The vanilla HTML, CSS, and JavaScript dashboard is under `src/main/resources/static/`. JUnit tests are in `src/test/java/in/stockline/`.

## Build, Test, and Run

- `mvn clean verify` compiles, runs the test suite, and packages the application.
- `mvn test` runs the JUnit 5 tests.
- `mvn spring-boot:run` starts the local app at `http://localhost:8080`.
- `node --check src/main/resources/static/app.js` checks frontend JavaScript syntax.

The default development profile uses the file-backed H2 database in `data/`; do not commit that database or Maven output under `target/`.

## Coding Style and Invariants

Use four spaces for Java and frontend indentation. Follow Java naming conventions (`UpperCamelCase` types, `lowerCamelCase` members, uppercase enum constants) and the existing plain JavaScript style. No formatter or linter is configured. Keep SQL access in `Store` and stock mutations in `InventoryService` transactions. Inventory is recorded per `(sku, warehouse_code)`; network totals are calculated from those persisted rows. Preserve version-checked updates, idempotency keys, and atomic movement-ledger writes. Every movement must balance from its previous quantity to its new quantity. Concurrency demonstrations must use isolated simulation data.

## Testing Guidelines

Use JUnit 5 and descriptive test names, for example `concurrentTransfersConserveStockAndRejectExcessRequests`. Cover rollback, split warehouse allocations, network availability, concurrent reservations, and ledger reconciliation when changing inventory behavior. No coverage threshold is configured.

## Commits and Pull Requests

This checkout has no Git metadata, so commit conventions cannot be verified. Prefer a short imperative subject such as `Prefer selected warehouse for reservations`. Pull requests should describe behavior and API or schema changes, list verification commands and results, link relevant issues, and include screenshots for dashboard changes.

## Configuration

Keep credentials and machine-specific settings outside source control. PostgreSQL connection settings come from environment variables; use the documented development profile for local H2 work.
