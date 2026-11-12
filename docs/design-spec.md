# Stockline Design Specification

## Reference inspection

Inspected `https://campuspool-s2bm.onrender.com` in the in-app browser on 12 November 2026. The public route redirected to a sign-in screen, so authenticated dashboard, navigation, and interaction states were not available to inspect without creating an account. Its visible mobile-sized sign-in page uses a deep charcoal-green background, a restrained green primary action, muted supporting copy, a large serif page heading, a centered narrow content column, soft rounded surfaces, and generous vertical spacing. Direct web-page access also failed, so this record is based on the live browser rendering.

## Stockline direction

Keep Stockline’s warehouse identity and inventory language. Use a warm off-white workspace against a dark forest shell, restrained green for primary actions and positive status, near-black serif page titles, and a neutral sans-serif for controls, tables, and dense operational data. Use consistent spacing, quiet borders, modest corner radii, visible focus states, and compact status chips. Avoid heavy shadows, saturated panels, decorative charts, and duplicate metrics.

## Information architecture

Keep Dashboard, Inventory, Warehouses, and Orders as the four primary destinations. Put account identity and sign-out in the header. Keep history, reconciliation, event processing, health, and concurrency tooling available under a secondary admin operations area; these support maintenance and the technical demonstration, not the everyday stock workflow. Warehouse managers see the same primary destinations but only assigned warehouse records and aggregates.

## Dashboard hierarchy

Show five persisted, role-scoped figures: products, available units, reserved units, active orders, and low-stock locations. Follow with recent orders, warehouse stock, low-stock items, and direct order/restock actions. Omit decorative charts and repeated movement/event tables from the main dashboard.
