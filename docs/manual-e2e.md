# Manual End-to-End Checks

Use a fresh local H2 database for the inventory walkthrough so orders and balances do not affect a developer's existing data. Start Stockline with bootstrap administrator credentials as described in `README.md`. Use unique product and warehouse codes if you repeat a journey against a persistent database.

## Admin journey

1. Sign in as the bootstrap administrator. Confirm the dashboard loads real inventory, warehouse, order, movement, and event data.
2. Open **Catalog & warehouses**. Add a product and confirm its SKU appears. New products must show zero stock at every warehouse. Edit a warehouse configuration and verify its status, priority, cost, and capacity values persist after navigating away and back.
3. Open **Inventory**. Search by product name and SKU; filter by warehouse, category, and status. Select **View all warehouses** and compare the per-location rows with the network totals.
4. Restock the iPhone 17 to Punjab Central (50), North Hub (30), and South Hub (20). Preview and reserve 50 units with Punjab preferred, leaving Punjab out of stock while network availability remains 50 and the product remains available. Reserve the remaining 50 with Punjab preferred; verify the allocation splits to North and South, every location is out of stock, and the product is globally out of stock.
5. Open **Orders** and inspect both allocations and lifecycle status. Open **Event log**, filter and search for the order, then inspect event details and attempts. Open **Operations health** and confirm database readiness, worker state, and event counts.
6. Sign out and confirm the login screen returns. A protected API request without a session must return 401.

## Warehouse manager and inventory journey

1. As admin, create a `WAREHOUSE_MANAGER` account through `POST /api/admin/users` and assign only `PUN`. Sign in as that manager and confirm network inventory, low-stock alerts, and order data contain only assigned-warehouse information.
2. Restock or adjust stock at PUN using an authorized action and an audit reason. Attempt the same operation at `DEL`; the UI must not offer DEL, and a direct API request for DEL must be rejected by backend authorization.
3. Transfer available units between two assigned warehouses if the account has both assignments. Confirm paired transfer movements, then reserve an order and cancel it while `RESERVED`. Confirm the original warehouse balances are restored.
4. Open **Inventory history** as admin; verify restock, transfer, reservation, and cancellation movements. Run reconciliation and require valid ledger chains and zero bucket differences.

## Reliability and responsive review

1. Use an existing retryable dead-letter event in an isolated test database. In **Event log**, confirm its retry action is enabled, retry it once, and verify the status/attempt count changes. Completed or permanent failures must not offer retry.
2. Review **Concurrency lab** with a safe strategy and confirm accepted reservations never exceed the configured stock. Keep the separate saved API report labeled as an isolated H2 benchmark.
3. Repeat the dashboard, inventory, order dialog, event log, and operations checks at desktop, tablet, and mobile widths. Verify navigation wraps, controls remain reachable, tables scroll within their panels, and keyboard focus stays visible.
