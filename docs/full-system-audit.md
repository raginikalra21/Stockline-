# Stockline System Audit

## Reference and UI design

The CampusPool reference was opened on 12 November 2026. Its unauthenticated route redirected to sign-in, so its authenticated dashboard and mobile interactions could not be inspected. The available page showed a deep charcoal-green background, restrained green action, serif heading, muted copy, narrow centered form, rounded surfaces, and generous spacing. `docs/design-spec.md` records this limitation and Stockline's adapted warm-neutral, forest-green, serif-title, sans-serif-control visual system.

Stockline now focuses on Dashboard, Inventory, Warehouses, and Orders. Dashboard uses five live scoped figures and recent orders, low stock, warehouse balances, and quick actions. Inventory distinguishes network totals from each warehouse and exposes product details and per-location stock. Warehouse details open from the list. Order previews prefer one warehouse that can fulfill the whole order and split deterministically when needed. User management, history, reconciliation, event processing, health, and the concurrency lab remain in secondary/admin tools. `AGENTS.md` documents project structure, commands, conventions, tests, and safety requirements.

## Access model

The application has two roles: `ADMIN` and `WAREHOUSE_MANAGER`. Admins can view and operate across the network, manage users, and access technical tools. Managers are restricted to assigned warehouses. Controller authorization and repository filters enforce the scope; integration tests compare manager-visible aggregates with persisted warehouse totals and verify forbidden cross-warehouse/admin access.

## Inventory and allocation

Each product/warehouse pair has independent persisted available, reserved, damaged, shipped, and written-off balances. Reservations update only the allocated warehouse rows; network totals and product status are aggregated from database rows. The iPhone 17 integration test exercises the 50/30/20 scenario and verifies local stockout with network availability, then global stockout after the remaining units are reserved. The CB-305 integration test seeds PUN 18, DEL 27, BLR 19 through stock operations, reserves 20, checks 64 physical / 44 available / 20 reserved, then verifies persisted cancellation, fulfillment, ledger rows, and reconciliation.

Allocation preview is read-only. Order placement replans from fresh balances and reserves all allocations, order state, movements, and outbox events transactionally. Inactive/maintenance locations and non-available buckets are excluded. Tests cover complete-site preference, split fallback, deterministic distribution, concurrent reservations, cancellation, and rollback.

## Concurrency implementations and observed tests

`NAIVE` performs unguarded absolute read-modify-write in an isolated simulation database with a coordination barrier; it is never used by the order API. `OPTIMISTIC` calls the production inventory service with version-conditional updates and bounded retries. `PESSIMISTIC` locks ordered rows with database `SELECT FOR UPDATE`; it does not use a JVM monitor. Every strategy/repetition gets a fresh Flyway database and the same `READ_COMMITTED` setup. Only simulation databases may demonstrate incorrect naive results.

The verified 100-buyer / 10-unit regression run observed 100 accepted and 90 oversold in NAIVE, while OPTIMISTIC and PESSIMISTIC each accepted 10, oversold zero, and ended at zero available. A 1,000-buyer / 100-unit test verified exactly 100 safe reservations and no oversell. These are database-backed test outcomes, not performance claims. The lab reports measured latency, throughput, conflicts/retries, lock waits, and ledger correctness from each run.

## Verification and remaining limits

`mvn clean verify` passed with 82 tests, zero failures/errors/skips, including six PostgreSQL Testcontainers tests for PostgreSQL reservation behavior and row-lock waiting. The CB-305 and iPhone 17 persisted scenarios passed. `node --check src/main/resources/static/app.js`, `git diff --check`, and `docker compose config --quiet` with temporary non-secret validation values passed. `docker build --tag stockline:local-audit .` succeeded; Compose stack startup was not repeated in this run.

Safari initially could not connect because the stale server on port 8080 accepted TCP without returning HTTP. It was stopped gracefully and the freshly verified JAR was relaunched against the same H2 file without running demo-account initialization. Safari sign-in then succeeded at `http://127.0.0.1:8080/`; Safari could not connect to `localhost` while the app was bound to IPv4 loopback. Read-only browser checks covered Dashboard, Inventory, Warehouses, and a successful allocation preview; no order was submitted. The Mac locked before the browser concurrency simulation and manager-role UI could be checked. Automated manager authorization and concurrency tests passed. Earlier Chromium responsive captures and measurements remain in `docs/responsive-audit/`. The CampusPool authenticated views, production TLS, backups, and cloud deployment remain unverified.
