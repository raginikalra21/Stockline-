# Phase 9 Audit Checklist

## Architecture and inventory behavior

- [x] Product balances remain separate by warehouse and stock bucket in the database.
- [x] Network totals and product availability are computed from persisted warehouse rows; a location is out of stock only when its own available balance is zero.
- [x] Reservation uses version-checked stock updates and retries in a fresh transaction. A multi-warehouse order commits all its allocations, movements, and outbox events together.
- [x] Preferred warehouse fallback and split allocations are covered, including concurrent orders across warehouses.
- [x] The iPhone 17 integration scenario restocks 50/30/20, reserves the first 50 from Punjab Central, then reserves the remaining 50 from North and South. It asserts per-warehouse status, network available/reserved totals, global status, and ledger reconciliation from database reads.
- [x] `/api/inventory/products` exposes each product's network totals and all visible warehouse balances; the Inventory screen presents the same data and per-location statuses.

## Release checks

- [x] `mvn clean verify`: 82 tests, zero failures, errors, or skips; all six PostgreSQL Testcontainers tests, including the held-row-lock check, passed against PostgreSQL.
- [x] `node --check src/main/resources/static/app.js` and `git diff --check`.
- [x] `docker compose config --quiet` with required environment variables supplied for validation.
- [x] Freshly rebuilt packaged JAR started with the isolated benchmark profile; `/actuator/health/readiness` returned `UP`.
- [x] Authenticated API walkthrough against the packaged JAR's isolated H2 database created an iPhone 17 SKU, restocked 50/30/20, reserved Punjab's 50, then reserved the remaining 50 through North and South. API reads returned 50 network units available and global `AVAILABLE` after the first order, then zero available and global `OUT_OF_STOCK`; warehouse statuses matched each expected state.
- [x] The saved isolated H2 API contention report records 18 real order requests, 8 reservations, 10 expected stock rejections, 2 split orders, nonnegative inventory, and valid reconciliation.
- [x] Browser smoke covered admin sign-in, dashboard, catalog creation, network inventory, restocking, and allocation preview. It revealed that optional delivery coordinates were incorrectly required; the shared form helper now supports optional fields.
- [x] Multi-architecture Docker image build and isolated Compose PostgreSQL startup: both database and app containers became healthy, and `/actuator/health/readiness` returned `UP`.
- [x] Authenticated Compose/PostgreSQL walkthrough created an iPhone 17 SKU, restocked 50/30/20, reserved the first 50 from Punjab, then allocated the remaining 50 across South (20) and North (30). Persisted API reads and the browser showed warehouse statuses and the expected global availability transitions.
- [x] Final Compose browser review covered admin sign-in, network inventory and per-warehouse balances, split order details, completed outbox events, and Operations Health (`UP`, database `UP`, reconciliation `VALID`). The compact browser viewport wrapped navigation and kept inventory and order information reachable.
- [x] A temporary PUN-only warehouse manager signed in through the browser. Its inventory, warehouse selector, alerts, and orders were limited to PUN; direct DEL summary access returned `403`. The manager restocked six units through the UI, and the account was removed after verification.
- [x] Reservation and cancellation were completed through the admin UI; inventory history showed transfer, restock, reservation, and release movements, and reconciliation reported that all buckets matched the ledger.
- [x] The retry action was exercised in the event log. A retryable `DEAD` event seeded only in the disposable audit database was queued through the UI and later persisted as `COMPLETED` with two recorded attempts. This exposed and fixed the frontend retry-action condition to use the persisted `failureKind` field.

## Mobile responsiveness audit

- [x] Playwright Chromium audited login, dashboard, network inventory, catalog and warehouses, orders, history, events, Operations Health, and Concurrency Lab at 320×700, 375×812, 390×844, 430×932, 768×1024, and 1366×768.
- [x] Every page and viewport had document/body scroll width equal to the viewport; navigation links and non-table controls stayed within bounds. Tables remained horizontally scrollable inside their own panels, including their last column.
- [x] New-order/allocation preview, product, and warehouse dialogs fit all six viewports. The long allocation result stayed in an internal table scroller; its last column and the dialog footer remained reachable.
- [x] A 10-buyer, 10-unit, 2-warehouse, 4-worker isolated H2 comparison rendered the Concurrency Lab charts at every viewport. Chart rows fit their containers and the metrics table scrolled inside its panel.
- [x] Fixed the confirmed workspace overflow, topbar wrapping, and allocation-preview table overflow issues. Corrected the chart result row data shape after the audit found the lab discarded successful results with `rows.join is not a function`.
- [x] Captured login, dashboard, inventory, order dialogs, allocation previews, Operations Health, Concurrency Lab, and simulation results. Route-by-route measurements are in [responsive audit results](responsive-audit/results.json), with captures indexed in the [responsive screenshot gallery](responsive-audit/README.md).

## PostgreSQL verification and startup

The earlier isolated audit stack used PostgreSQL 16.15 and applied Flyway migrations through V8. The current migrations include V9, which narrows application roles. The current `mvn clean verify` run executed all six PostgreSQL Testcontainers tests, including strategy isolation and a real held-row-lock wait. Browser concurrency charts use isolated H2 databases; PostgreSQL-backed application data is managed by the separate Compose stack.

For local PostgreSQL startup, run `cp .env.example .env`, set unique `DB_PASSWORD`, `STOCKLINE_ADMIN_PASSWORD`, and a Base64 `JWT_SECRET` generated with `openssl rand -base64 32`, then run `docker compose up --build` and open `http://localhost:8080`. The Compose deployment is single-host; production still requires operator-managed TLS, persistent-volume backups, and production secrets. Cloud deployment was not performed.

## Release sign-off

No responsive issue remains in the previously tested Chromium viewports. Wide tables intentionally scroll within their panels. User management is available in the secondary Settings & tools area. The current session confirmed the Safari sign-in page at `http://127.0.0.1:8080`; other Safari workflows were not re-run. These checks do not certify production TLS, backup recovery, or every browser engine.
