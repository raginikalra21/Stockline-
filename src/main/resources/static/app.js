"use strict";

const $ = selector => document.querySelector(selector);
const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"></use></svg>`;
const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g, character =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
const format = value => new Intl.NumberFormat().format(value);
const formatDate = value => value ? new Date(value).toLocaleString() : "—";
const shortId = id => id.slice(0, 8).toUpperCase();
const statusLabel = status => status[0] + status.slice(1).toLowerCase();
const pill = (status, label = statusLabel(status)) =>
    `<span class="pill ${escapeHtml(status.toLowerCase())}">${escapeHtml(label)}</span>`;
const views = {
    overview: "Overview", inventory: "Inventory", orders: "Orders",
    history: "Inventory History", warehouses: "Catalog", race: "Concurrency Lab", events: "Event Log"
};
const pageSize = 15;
const state = {
    view: "overview", page: 0, catalog: null, stock: [], productInventory: [], overview: null, alerts: [],
    selectedNetworkSku: null, selectedOrder: null, pendingDialog: false, refreshPromise: null, orderFetch: 0
};
let toastTimer;
const rendered = new WeakMap();

function updateHtml(container, html) {
    if (rendered.get(container) !== html) {
        container.innerHTML = html;
        rendered.set(container, html);
    }
}

async function api(path, body) {
    const options = body === undefined ? { cache: "no-store" } : {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
    };
    const response = await fetch(`/api${path}`, options);
    const data = await response.json().catch(() => null);
    if (!response.ok || data === null) {
        throw new Error(data?.message || `Request failed (HTTP ${response.status}).`);
    }
    return data;
}

function notify(message, isError = false) {
    const toast = $("#toast");
    clearTimeout(toastTimer);
    toast.textContent = message;
    toast.classList.toggle("error", isError);
    toast.hidden = false;
    toastTimer = setTimeout(() => { toast.hidden = true; }, 4500);
}

function connected(ok, message = "") {
    const connection = $("#connection");
    connection.classList.toggle("online", ok);
    connection.classList.toggle("offline", !ok);
    connection.lastElementChild.textContent = ok ? "Connected" : "Unavailable";
    const notice = $("#notice");
    notice.textContent = message;
    notice.classList.toggle("error", !ok);
    notice.hidden = ok;
}

function empty(title, description, type = "inventory") {
    return `<div class="empty"><span class="empty-icon">${icon(type)}</span>
        <strong>${escapeHtml(title)}</strong><p>${escapeHtml(description)}</p></div>`;
}

function table(label, headings, rows) {
    return `<table aria-label="${escapeHtml(label)}"><thead><tr>${headings
        .map(heading => `<th scope="col">${escapeHtml(heading)}</th>`).join("")}</tr></thead>
        <tbody>${rows.join("")}</tbody></table>`;
}

function warehouseLabel(code) {
    return state.catalog?.warehouses.find(warehouse => warehouse.code === code)?.name ?? code;
}

function renderOverview() {
    const totals = state.overview;
    const cards = [
        ["inventory", "Available units", totals.available, `Across ${format(totals.stockRows)} stock records`],
        ["clock", "Reserved units", totals.reserved, `${format(totals.pendingOrders)} orders in fulfillment`],
        ["close", "Damaged units", totals.damaged, "Unavailable pending disposition"],
        ["truck", "Shipped units", totals.shipped, `${format(totals.fulfilledOrders)} orders fulfilled`],
        ["warehouse", "Low-stock products", totals.lowStockProducts, `${format(totals.lowStockRows)} product/warehouse alerts`],
        ["warehouse", "Physical units", totals.physical, "Available + reserved + damaged"]
    ];
    $("#stats").innerHTML = cards.map(([name, title, value, caption]) => `<div class="stat">
        <div class="stat-label"><span class="stat-icon">${icon(name)}</span>${title}</div>
        <p class="stat-value number">${format(value)}</p><p>${caption}</p></div>`).join("");

    const grouped = new Map(state.catalog.warehouses.map(location => [location.code, { ...location, available: 0, reserved: 0 }]));
    for (const stock of state.stock) {
        const location = grouped.get(stock.warehouseCode);
        if (location) {
            location.available += stock.available;
            location.reserved += stock.reserved;
        }
    }
    const scale = [...grouped.values()].reduce((max, item) => Math.max(max, item.available + item.reserved), 1);
    $("#network").innerHTML = [...grouped.values()].map(location => `<div class="network-row">
        <div class="network-title"><span>${escapeHtml(location.name)} <small>${escapeHtml(location.city)}</small></span>
        <span class="mono">${escapeHtml(location.code)}</span></div>
        <div class="track" aria-hidden="true"><span style="width:${100 * location.available / scale}%"></span>
        <span style="width:${100 * location.reserved / scale}%"></span></div>
        <p class="network-note">${format(location.available)} available · ${format(location.reserved)} reserved</p></div>`).join("");
}

function renderInventory() {
    if (!state.catalog) return;
    const select = $("#stock-warehouse");
    const selectedWarehouse = select.value;
    const options = `<option value="">All warehouses</option>${warehouseOptions()}`;
    if (select.dataset.options !== options) {
        select.innerHTML = options;
        select.dataset.options = options;
        select.value = selectedWarehouse;
    }
    const search = $("#stock-search").value.trim().toLowerCase();
    const low = $("#stock-low").checked;
    const products = new Map(state.catalog.products.map(product => [product.sku, product.name]));
    const rows = state.stock.filter(stock => {
        const product = products.get(stock.sku) ?? "";
        return (!selectedWarehouse || stock.warehouseCode === selectedWarehouse)
            && (!low || stock.available <= stock.minimumAvailable)
            && `${stock.sku} ${product}`.toLowerCase().includes(search);
    }).map(stock => {
        const stockLevel = stock.available === 0 ? pill("OUT", "Out of stock")
            : stock.available <= stock.minimumAvailable ? pill("LOW", "Low stock") : pill("HEALTHY", "In stock");
        return `<tr><td><strong>${escapeHtml(products.get(stock.sku) ?? stock.sku)}</strong>
            <span class="subcell mono">${escapeHtml(stock.sku)}</span></td>
            <td>${escapeHtml(warehouseLabel(stock.warehouseCode))}<span class="subcell mono">${escapeHtml(stock.warehouseCode)}</span></td>
            <td class="number"><strong>${format(stock.available)}</strong></td><td class="number">${format(stock.reserved)}</td>
            <td class="number">${format(stock.damaged)}</td><td class="number">${format(stock.shipped)}</td>
            <td class="number">${format(stock.writtenOff)}</td><td><form class="threshold-form" data-sku="${escapeHtml(stock.sku)}" data-warehouse="${escapeHtml(stock.warehouseCode)}">
                <input aria-label="Minimum available for ${escapeHtml(stock.sku)} at ${escapeHtml(stock.warehouseCode)}" type="number" min="0" max="1000000" value="${stock.minimumAvailable}" required>
                <button type="submit" class="link-button">Save</button></form></td><td>${stockLevel}</td></tr>`;
    });
    $("#inventory-table").innerHTML = rows.length
        ? table("Stock records", ["Product", "Warehouse", "Available", "Reserved", "Damaged", "Shipped", "Written off", "Minimum available", "Status"], rows)
        : empty("No matching stock", "Try another warehouse or clear the search filters.");
    renderProductInventory();
}

function renderProductInventory() {
    const products = state.productInventory;
    const summary = $("#product-inventory-table");
    const detail = $("#product-warehouse-detail");
    if (!products.length) {
        updateHtml(summary, empty("No products yet", "Add a product to see network-wide inventory."));
        updateHtml(detail, "");
        return;
    }
    if (!products.some(product => product.sku === state.selectedNetworkSku)) {
        state.selectedNetworkSku = products[0].sku;
    }
    updateHtml(summary, table("Product network inventory",
        ["Product", "Network stock", "Available", "Reserved", "Damaged", "Global status", "Warehouse balances"],
        products.map(product => `<tr><td><strong>${escapeHtml(product.product)}</strong>
                <span class="subcell mono">${escapeHtml(product.sku)}</span></td>
            <td class="number">${format(product.totalStock)}</td><td class="number">${format(product.available)}</td>
            <td class="number">${format(product.reserved)}</td><td class="number">${format(product.damaged)}</td>
            <td>${pill(product.status === "AVAILABLE" ? "HEALTHY" : "OUT",
                product.status === "AVAILABLE" ? "Available" : "Out of stock")}</td>
            <td><button type="button" class="link-button" data-network-product="${escapeHtml(product.sku)}"
                aria-pressed="${product.sku === state.selectedNetworkSku}">View all warehouses</button></td></tr>`)));

    const selected = products.find(product => product.sku === state.selectedNetworkSku);
    updateHtml(detail, `<h3>${escapeHtml(selected.product)} <span class="subcell mono">${escapeHtml(selected.sku)}</span> · warehouse balances</h3>
        ${table(`Warehouse balances for ${selected.sku}`,
            ["Warehouse", "Network stock", "Available", "Reserved", "Damaged", "Status"],
            selected.warehouses.map(warehouse => `<tr><td><strong>${escapeHtml(warehouse.warehouse)}</strong>
                    <span class="subcell mono">${escapeHtml(warehouse.warehouseCode)}</span></td>
                <td class="number">${format(warehouse.totalStock)}</td><td class="number">${format(warehouse.available)}</td>
                <td class="number">${format(warehouse.reserved)}</td><td class="number">${format(warehouse.damaged)}</td>
                <td>${pill(warehouse.status === "AVAILABLE" ? "HEALTHY" : "OUT",
                    warehouse.status === "AVAILABLE" ? "Available" : "Out of stock")}</td></tr>`))}`);
}

function renderAlerts(container, alerts) {
    updateHtml(container, alerts.length
        ? table("Low stock alerts", ["Product", "Warehouse", "Available", "Threshold", "Replenish", "Status"],
            alerts.map(alert => `<tr><td><strong>${escapeHtml(alert.product)}</strong><span class="subcell mono">${escapeHtml(alert.sku)}</span></td>
                <td>${escapeHtml(alert.warehouse)} <span class="subcell mono">${escapeHtml(alert.warehouseCode)}</span></td>
                <td class="number">${format(alert.available)}</td><td class="number">${format(alert.minimumAvailable)}</td>
                <td class="number">${format(alert.replenishmentUnits)}</td><td>${pill(alert.outOfStock ? "OUT" : "LOW", alert.outOfStock ? "Out of stock" : "Below threshold")}</td></tr>`))
        : empty("No replenishment alerts", "All warehouse balances are above their configured minimums."));
}

function renderInventorySummary() {
    const totals = state.overview;
    const cards = [["Available", totals.available], ["Reserved", totals.reserved], ["Damaged", totals.damaged],
        ["Shipped", totals.shipped], ["Low-stock products", totals.lowStockProducts]];
    $("#inventory-summary").innerHTML = cards.map(([label, value]) => `<div class="stat"><div class="stat-label">${label}</div>
        <p class="stat-value number">${format(value)}</p></div>`).join("");
}

function movementQuery() {
    const params = new URLSearchParams({ limit: "100", offset: "0" });
    const search = $("#movement-search").value.trim();
    const warehouse = $("#movement-warehouse").value;
    const type = $("#movement-type").value;
    const from = $("#movement-from").value;
    const to = $("#movement-to").value;
    const orderId = $("#movement-order").value.trim();
    if (search) params.set("search", search);
    if (warehouse) params.set("warehouseCode", warehouse);
    if (type) params.set("type", type);
    if (from) params.set("from", new Date(from).toISOString());
    if (to) params.set("to", new Date(to).toISOString());
    if (orderId) params.set("orderId", orderId);
    return params;
}

async function loadHistory() {
    const select = $("#movement-warehouse");
    const current = select.value;
    const options = `<option value="">All warehouses</option>${warehouseOptions()}`;
    if (select.dataset.options !== options) {
        select.innerHTML = options;
        select.dataset.options = options;
        select.value = current;
    }
    const typeSelect = $("#movement-type");
    if (typeSelect.options.length === 1) {
        typeSelect.insertAdjacentHTML("beforeend", ["INITIAL_STOCK", "RESTOCK", "RESERVATION", "RESERVATION_RELEASE",
            "FULFILLMENT", "TRANSFER_OUT", "TRANSFER_IN", "STOCK_ADJUSTMENT", "DAMAGED_STOCK", "RETURN", "STOCK_WRITE_OFF"]
            .map(type => `<option value="${type}">${type.replaceAll("_", " ")}</option>`).join(""));
    }
    const movements = await api(`/inventory/movements?${movementQuery()}`);
    $("#movement-count").textContent = `${format(movements.length)} movement(s) · latest 100 matching records`;
    $("#movement-table").innerHTML = movements.length ? table("Inventory movement history",
        ["Movement", "Type", "Product", "Warehouse", "Bucket", "Change", "Before → after", "Order / transfer", "Reason and actor", "Timestamp"],
        movements.map(item => `<tr><td class="mono">${escapeHtml(item.movementId.slice(0, 8))}<span class="subcell">${escapeHtml(item.operationId.slice(0, 8))} op</span></td>
            <td>${pill(item.movementType, item.movementType.replaceAll("_", " "))}</td>
            <td><strong>${escapeHtml(item.productId)}</strong></td><td>${escapeHtml(item.warehouseId)}</td>
            <td>${escapeHtml(item.stockBucket)}</td><td class="number">${item.quantityChange > 0 ? "+" : ""}${format(item.quantityChange)}</td>
            <td class="number">${format(item.previousStock)} → ${format(item.newStock)}</td>
            <td>${item.referenceOrderId ? `<button type="button" class="link-button mono" data-order-id="${escapeHtml(item.referenceOrderId)}">#${escapeHtml(shortId(item.referenceOrderId))}</button>` : "—"}
                ${item.sourceWarehouse ? `<span class="subcell">${escapeHtml(item.sourceWarehouse)} → ${escapeHtml(item.destinationWarehouse)}</span>` : ""}</td>
            <td>${escapeHtml(item.reason)}<span class="subcell">Actor: ${escapeHtml(item.actor)}</span></td><td>${escapeHtml(formatDate(item.timestamp))}</td></tr>`))
        : empty("No movements match", "Change or clear the movement filters.", "events");
}

async function runReconciliation() {
    const rows = await api("/inventory/reconciliation");
    const issues = rows.filter(row => row.ledgerChainStatus !== "VALID" || row.invalidMovementCount > 0
        || row.discontinuityCount > 0 || row.missingOperationCount > 0 || row.unbalancedTransferCount > 0
        || row.unbalancedOperationCount > 0 || row.bucketBalanceMismatch);
    $("#reconciliation-table").innerHTML = issues.length
        ? table("Inventory ledger integrity issues", ["Product", "Warehouse", "Bucket", "Expected", "Actual", "Difference",
            "Chain", "Invalid", "Gaps", "Missing ops", "Unbalanced transfers", "Unbalanced operations", "Balance mismatch", "Overall"],
            issues.map(row => `<tr><td>${escapeHtml(row.product)} <span class="subcell mono">${escapeHtml(row.sku)}</span></td>
                <td>${escapeHtml(row.warehouse)} <span class="subcell mono">${escapeHtml(row.warehouseCode)}</span></td>
                <td>${escapeHtml(row.stockBucket)}</td><td class="number">${format(row.expectedQuantity)}</td>
                <td class="number">${format(row.actualQuantity)}</td><td class="number">${format(row.difference)}</td>
                <td>${pill(row.ledgerChainStatus === "VALID" ? "HEALTHY" : "OUT", row.ledgerChainStatus)}</td>
                <td class="number">${format(row.invalidMovementCount)}</td><td class="number">${format(row.discontinuityCount)}</td>
                <td class="number">${format(row.missingOperationCount)}</td><td class="number">${format(row.unbalancedTransferCount)}</td>
                <td class="number">${format(row.unbalancedOperationCount)}</td><td>${row.bucketBalanceMismatch ? pill("OUT", "Yes") : "No"}</td>
                <td>${pill(row.overallIntegrityStatus === "VALID" ? "HEALTHY" : "OUT", row.overallIntegrityStatus)}</td></tr>`))
        : empty("Ledger integrity verified", "Movement chains, operations, and all inventory buckets reconcile. No records were changed.", "check");
}

function renderCatalog() {
    $("#warehouse-table").innerHTML = table("Warehouses", ["Warehouse", "City", "Priority"],
        state.catalog.warehouses.map(item => `<tr><td><strong>${escapeHtml(item.name)}</strong>
        <span class="subcell mono">${escapeHtml(item.code)}</span></td><td>${escapeHtml(item.city)}</td>
        <td class="number">${format(item.priority)}</td></tr>`));
    $("#product-table").innerHTML = table("Products", ["Product", "Category"],
        state.catalog.products.map(item => `<tr><td><strong>${escapeHtml(item.name)}</strong>
        <span class="subcell mono">${escapeHtml(item.sku)}</span></td><td>${escapeHtml(item.category)}</td></tr>`));
}

function renderOrders(container, orders) {
    if (!orders.length) {
        const filtered = container.id === "orders-table" && $("#order-filter").value;
        updateHtml(container, filtered
            ? empty("No matching orders", "Change the status filter to see other orders.", "orders")
            : empty("No orders yet", "Create a new order to reserve stock and start fulfillment.", "orders"));
        return;
    }

    updateHtml(container, table("Orders", ["Order", "Customer", "Units", "Status", "Created", "Details"],
        orders.map(order => `<tr><td class="mono">#${escapeHtml(shortId(order.id))}</td><td>${escapeHtml(order.customer)}</td>
        <td class="number">${format(order.allocations.reduce((total, allocation) => total + allocation.quantity, 0))}</td>
        <td>${pill(order.status)}</td><td>${escapeHtml(formatDate(order.createdAt))}</td>
        <td><button type="button" class="link-button" data-order-id="${escapeHtml(order.id)}"
        aria-label="View order ${escapeHtml(shortId(order.id))}">View</button></td></tr>`)));
}

async function loadOrders() {
    const request = ++state.orderFetch;
    const status = $("#order-filter").value;
    const params = new URLSearchParams({ limit: pageSize + 1, offset: state.page * pageSize });
    if (status) params.set("status", status);
    const orders = await api(`/orders?${params}`);
    if (request !== state.orderFetch || state.view !== "orders") return;
    if (!orders.length && state.page > 0) {
        state.page--;
        await loadOrders();
        return;
    }
    const page = orders.slice(0, pageSize);
    renderOrders($("#orders-table"), page);
    $("#order-count").textContent = `${page.length} on this page`;
    $("#order-page").textContent = `Page ${state.page + 1}`;
    $("#page-back").disabled = state.page === 0;
    $("#page-next").disabled = orders.length <= pageSize;
}

function renderEvents(events) {
    updateHtml($("#events-table"), events.length
        ? table("Fulfillment outbox", ["Event", "Order", "Status", "Eligible at", "Closed at"], events.map(event =>
            `<tr><td class="mono">${escapeHtml(event.eventType)}</td><td><button class="link-button mono" type="button"
            data-order-id="${escapeHtml(event.orderId)}">#${escapeHtml(shortId(event.orderId))}</button></td>
            <td>${pill(event.status)}</td><td>${escapeHtml(formatDate(event.dueAt))}</td>
            <td>${escapeHtml(formatDate(event.processedAt))}</td></tr>`))
        : empty("No events yet", "Reserving an order writes its first fulfillment event here.", "events"));
}

async function refresh() {
    if (state.refreshPromise) return state.refreshPromise;
    state.refreshPromise = (async () => {
        try {
            const [catalog, stock, productInventory, overview] = await Promise.all([
                api("/catalog"), api("/inventory"), api("/inventory/products"), api("/overview")
            ]);
            Object.assign(state, { catalog, stock, productInventory, overview });
            if (state.view === "overview") {
                renderOverview();
                renderOrders($("#recent-orders"), await api("/orders?limit=5"));
                renderAlerts($("#overview-alerts"), await api("/inventory/alerts"));
            }
            if (state.view === "inventory") renderInventory();
            if (state.view === "history") {
                renderInventorySummary();
                await loadHistory();
            }
            if (state.view === "warehouses") renderCatalog();
            if (state.view === "orders") await loadOrders();
            if (state.view === "events") renderEvents(await api("/events"));
            const open = state.selectedOrder;
            if (open?.status === "RESERVED" && !state.pendingDialog) {
                const latest = await api(`/orders/${open.id}`);
                if (state.selectedOrder === open && latest.status !== open.status && !state.pendingDialog) {
                    renderOrderDetails(latest);
                }
            }
            connected(true);
        } catch (error) {
            connected(false, `Could not refresh the workspace. ${error.message}`);
        } finally {
            state.refreshPromise = null;
        }
    })();
    return state.refreshPromise;
}

async function refreshAfterCurrent() {
    if (state.refreshPromise) await state.refreshPromise;
    return refresh();
}

function navigate() {
    const target = location.hash.slice(1);
    if (target === "main") return;
    state.view = Object.hasOwn(views, target) ? target : "overview";
    $("#crumb").textContent = views[state.view];
    document.title = `${views[state.view]} · Stockline`;
    document.querySelectorAll("[data-view]").forEach(section => { section.hidden = section.dataset.view !== state.view; });
    document.querySelectorAll(".sidebar nav a").forEach(link => {
        if (link.hash === `#${state.view}`) link.setAttribute("aria-current", "page");
        else link.removeAttribute("aria-current");
    });
    void refreshAfterCurrent();
}

function warehouseOptions() {
    return state.catalog.warehouses.map(warehouse =>
        `<option value="${escapeHtml(warehouse.code)}">${escapeHtml(warehouse.name)} (${escapeHtml(warehouse.code)})</option>`).join("");
}

function productOptions() {
    return state.catalog.products.map(product =>
        `<option value="${escapeHtml(product.sku)}">${escapeHtml(product.name)} (${escapeHtml(product.sku)})</option>`).join("");
}

const field = (label, name, attributes = "") =>
    `<label>${label}<input name="${name}" ${attributes} required></label>`;
const selectField = (label, name, choices) =>
    `<label>${label}<select name="${name}" aria-label="${label}" required>${choices}</select></label>`;
const quantityField = () => field("Quantity", "quantity", 'type="number" min="1" max="10000" value="1"');
const number = (form, key) => Number(form.get(key));
const upper = (form, key) => form.get(key).trim().toUpperCase();

function dialogContent(title, subtitle, html) {
    $("#dialog-title").textContent = title;
    $("#dialog-subtitle").textContent = subtitle;
    $("#dialog-body").innerHTML = html;
    if (!$("#dialog").open) $("#dialog").showModal();
}

function newLine() {
    return `<div class="line-row">${selectField("Product", "lineSku", productOptions())}
        ${field("Quantity", "lineQuantity", 'type="number" min="1" max="10000" value="1"')}
        <button class="icon-button remove-line" type="button" aria-label="Remove line">${icon("close")}</button></div>`;
}

function updateLineButtons() {
    const lines = [...document.querySelectorAll(".line-row")];
    lines.forEach(line => { line.querySelector(".remove-line").disabled = lines.length === 1; });
    $("#add-line").disabled = lines.length >= 20;
}

const actions = {
    order: {
        title: "New order", subtitle: "Choose a preferred warehouse or let the network allocate available stock.",
        fields: () => `${field("Customer name", "customer", 'maxlength="80" autocomplete="off"')}
            <label>Preferred warehouse<select name="preferredWarehouseCode"><option value="">Automatic allocation</option>${warehouseOptions()}</select></label>
            <div class="order-lines" id="order-lines">${newLine()}</div>
            <button class="button secondary" id="add-line" type="button">${icon("plus")} Add line</button>`,
        label: "Reserve stock", path: "/orders",
        body: (form, key) => ({
            customer: form.get("customer").trim(), requestKey: key,
            preferredWarehouseCode: form.get("preferredWarehouseCode") || null,
            lines: form.getAll("lineSku").map((sku, index) => ({ sku, quantity: Number(form.getAll("lineQuantity")[index]) }))
        })
    },
    restock: {
        title: "Restock inventory", subtitle: "Add available stock to a warehouse.", label: "Add stock", path: "/inventory/restock",
        fields: () => selectField("Product", "sku", productOptions())
            + selectField("Warehouse", "warehouseCode", warehouseOptions()) + quantityField(),
        body: (form, key) => ({ sku: form.get("sku"), warehouseCode: form.get("warehouseCode"), quantity: number(form, "quantity"), requestKey: key }),
        done: "Stock added."
    },
    transfer: {
        title: "Transfer stock", subtitle: "Move available units between two warehouses.", label: "Transfer", path: "/inventory/transfer",
        fields: () => selectField("Product", "sku", productOptions()) + selectField("From warehouse", "fromWarehouse", warehouseOptions())
            + selectField("To warehouse", "toWarehouse", warehouseOptions()) + quantityField(),
        body: (form, key) => ({
            sku: form.get("sku"), fromWarehouse: form.get("fromWarehouse"),
            toWarehouse: form.get("toWarehouse"), quantity: number(form, "quantity"), requestKey: key
        }),
        done: "Stock transferred."
    },
    adjust: {
        title: "Adjust inventory", subtitle: "Apply a signed correction with a required audit reason.",
        label: "Save adjustment", path: "/inventory/adjustments",
        fields: () => selectField("Product", "sku", productOptions()) + selectField("Warehouse", "warehouseCode", warehouseOptions())
            + selectField("Stock bucket", "stockBucket", ["AVAILABLE", "DAMAGED", "SHIPPED", "WRITTEN_OFF"]
                .map(bucket => `<option value="${bucket}">${bucket.replaceAll("_", " ")}</option>`).join(""))
            + field("Signed quantity change", "quantityChange", 'type="number" min="-10000" max="10000" step="1" value="1"')
            + field("Reason", "reason", 'maxlength="240"'),
        body: (form, key) => ({ sku: form.get("sku"), warehouseCode: form.get("warehouseCode"),
            stockBucket: form.get("stockBucket"), quantityChange: number(form, "quantityChange"), reason: form.get("reason").trim(), requestKey: key }),
        done: "Inventory adjustment recorded."
    },
    damage: {
        title: "Record damaged stock", subtitle: "Move available units into the damaged bucket.",
        label: "Record damage", path: "/inventory/damaged",
        fields: () => selectField("Product", "sku", productOptions()) + selectField("Warehouse", "warehouseCode", warehouseOptions())
            + quantityField() + field("Reason", "reason", 'maxlength="240"'),
        body: (form, key) => ({ sku: form.get("sku"), warehouseCode: form.get("warehouseCode"),
            quantity: number(form, "quantity"), reason: form.get("reason").trim(), requestKey: key }),
        done: "Damaged stock recorded."
    },
    writeoff: {
        title: "Write off damaged stock", subtitle: "Remove damaged units from physical inventory.",
        label: "Write off stock", path: "/inventory/write-offs",
        fields: () => selectField("Product", "sku", productOptions()) + selectField("Warehouse", "warehouseCode", warehouseOptions())
            + quantityField() + field("Reason", "reason", 'maxlength="240"'),
        body: (form, key) => ({ sku: form.get("sku"), warehouseCode: form.get("warehouseCode"),
            quantity: number(form, "quantity"), reason: form.get("reason").trim(), requestKey: key }),
        done: "Damaged stock written off."
    },
    return: {
        title: "Record returned inventory", subtitle: "Return fulfilled units to their original warehouse allocation.",
        label: "Record return", path: "/inventory/returns",
        fields: () => field("Order ID", "orderId", 'type="text" placeholder="Full order UUID" pattern="[0-9a-fA-F-]{36}"')
            + selectField("Product", "sku", productOptions()) + selectField("Warehouse", "warehouseCode", warehouseOptions())
            + quantityField() + field("Reason", "reason", 'maxlength="240"'),
        body: (form, key) => ({ orderId: form.get("orderId").trim(), sku: form.get("sku"),
            warehouseCode: form.get("warehouseCode"), quantity: number(form, "quantity"),
            reason: form.get("reason").trim(), requestKey: key }),
        done: "Return recorded."
    },
    product: {
        title: "Add product", subtitle: "The new SKU starts with zero stock at each warehouse.", label: "Create product", path: "/products",
        fields: () => field("SKU", "sku", 'maxlength="24" pattern="[A-Za-z0-9][A-Za-z0-9\\-]*" placeholder="e.g. MN-610"')
            + field("Product name", "name", 'maxlength="80"') + field("Category", "category", 'maxlength="40"'),
        body: form => ({ sku: upper(form, "sku"), name: form.get("name").trim(), category: form.get("category").trim() }),
        done: "Product created."
    },
    warehouse: {
        title: "Add warehouse", subtitle: "Lower priority numbers are allocated first.", label: "Create warehouse", path: "/warehouses",
        fields: () => field("Warehouse code", "code", 'maxlength="16" pattern="[A-Za-z0-9][A-Za-z0-9\\-]*" placeholder="e.g. MUM"')
            + field("Warehouse name", "name", 'maxlength="80"') + field("City", "city", 'maxlength="60"')
            + field("Priority", "priority", 'type="number" min="1" max="999" value="40"'),
        body: form => ({
            code: upper(form, "code"), name: form.get("name").trim(), city: form.get("city").trim(), priority: number(form, "priority")
        }),
        done: "Warehouse created."
    }
};

function openAction(name) {
    if (!state.catalog) {
        notify("Waiting for the warehouse catalog to load.", true);
        return;
    }
    state.selectedOrder = null;
    const action = actions[name];
    dialogContent(action.title, action.subtitle, `<form class="form-body" id="action-form">
        ${action.fields()}<p class="form-error" role="alert" hidden></p>
        <div class="form-footer"><button type="button" class="button secondary" data-close>Close</button>
            <button class="button primary" type="submit">${action.label}</button></div></form>`);
    if (name === "transfer" && $("#action-form").elements.toWarehouse.options.length > 1) {
        $("#action-form").elements.toWarehouse.selectedIndex = 1;
    }
    if (name === "order") updateLineButtons();
    $("#action-form").querySelector("input, select")?.focus();
    const requestKey = crypto.randomUUID();
    $("#action-form").addEventListener("submit", async event => {
        event.preventDefault();
        if (state.pendingDialog) return;
        const form = event.currentTarget;
        const error = form.querySelector(".form-error");
        error.hidden = true;
        const body = action.body(new FormData(form), requestKey);
        const submit = form.querySelector("[type=submit]");
        setDialogBusy(true);
        const controls = [...form.elements].filter(control => !control.disabled);
        controls.forEach(control => { control.disabled = true; });
        submit.textContent = "Saving…";
        try {
            const result = await api(action.path, body);
            if (name === "order") {
                renderOrderDetails(result);
            } else {
                $("#dialog").close();
                notify(action.done);
            }
            void refreshAfterCurrent();
        } catch (failure) {
            error.textContent = failure.message;
            error.hidden = false;
        } finally {
            controls.forEach(control => { control.disabled = false; });
            submit.textContent = action.label;
            setDialogBusy(false);
        }
    });
}

function setDialogBusy(busy) {
    state.pendingDialog = busy;
    $("#dialog-close").disabled = busy;
}

function renderOrderDetails(order) {
    state.selectedOrder = order;
    const rows = order.allocations.map(item => `<tr><td class="mono">${escapeHtml(item.sku)}</td>
        <td>${escapeHtml(warehouseLabel(item.warehouseCode))} <span class="mono">(${escapeHtml(item.warehouseCode)})</span></td>
        <td class="number">${format(item.quantity)}</td></tr>`);
    const description = {
        RESERVED: "Units are reserved. The background worker will fulfill this order shortly.",
        FULFILLED: "Warehouse allocations have been shipped.",
        CANCELLED: "Reserved units have been released back to inventory."
    };
    dialogContent(`Order #${shortId(order.id)}`, `${order.customer} · ${formatDate(order.createdAt)}`,
        `<div class="details-body">${pill(order.status)}<p class="muted">${description[order.status]}</p>
        <p class="muted">Order ID <span class="mono">${escapeHtml(order.id)}</span></p>
        <div class="table-wrap">${table("Warehouse allocations", ["SKU", "Warehouse", "Units"], rows)}</div>
        <p class="form-error" role="alert" hidden></p><div class="form-footer">
        ${order.status === "RESERVED" ? '<button id="cancel-order" type="button" class="button danger">Cancel order</button>' : ""}
        <button type="button" class="button secondary" data-close>Close</button></div></div>`);
}

async function openOrder(id, button) {
    button.disabled = true;
    try {
        const order = await api(`/orders/${id}`);
        if (!$("#dialog").open) renderOrderDetails(order);
    } catch (error) {
        notify(error.message, true);
    } finally {
        button.disabled = false;
    }
}

async function cancelOrder(button) {
    const order = state.selectedOrder;
    if (!order || state.pendingDialog) return;
    if (button.dataset.confirm !== "true") {
        button.dataset.confirm = "true";
        button.textContent = "Confirm cancellation";
        return;
    }
    setDialogBusy(true);
    button.disabled = true;
    try {
        renderOrderDetails(await api(`/orders/${order.id}/cancel`, {}));
        void refreshAfterCurrent();
    } catch (error) {
        const field = $("#dialog-body .form-error");
        field.textContent = error.message;
        field.hidden = false;
        void refreshAfterCurrent();
    } finally {
        button.disabled = false;
        setDialogBusy(false);
    }
}

async function runRace(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    const payload = {
        buyers: number(fields, "buyers"), stock: number(fields, "stock"),
        quantity: number(fields, "quantity"), warehouses: number(fields, "warehouses"),
        workers: number(fields, "workers"), durationSeconds: number(fields, "durationSeconds"),
        mode: fields.get("mode"), repetitions: number(fields, "repetitions")
    };
    const controls = [...form.elements];
    controls.forEach(control => { control.disabled = true; });
    $("#race-error").hidden = true;
    const resultPanel = $("#race-result");
    resultPanel.setAttribute("aria-busy", "true");
    const strategyCount = payload.mode === "COMPARE" ? 3 : 1;
    resultPanel.innerHTML = `<div class="simulation-progress"><div class="progress-heading"><strong>Starting isolated database simulation…</strong><span>Queued</span></div><progress max="${payload.buyers * strategyCount * payload.repetitions}" value="0"></progress><p class="muted">No live inventory is used.</p></div>`;
    try {
        const job = await api("/demo/race/jobs", payload);
        let progress;
        do {
            await new Promise(resolve => setTimeout(resolve, 300));
            progress = await api(`/demo/race/jobs/${encodeURIComponent(job.id)}`);
            const done = progress.completed;
            const total = progress.total || payload.buyers;
            resultPanel.innerHTML = `<div class="simulation-progress"><div class="progress-heading"><strong>${escapeHtml(progress.status.replaceAll("_", " "))}</strong><span>${format(done)} / ${format(total)} attempts</span></div><progress max="${total}" value="${Math.min(done, total)}"></progress><div class="live-stats"><span>Accepted <b>${format(progress.accepted)}</b></span><span>Rejected <b>${format(progress.rejected)}</b></span><span>Available <b>${format(progress.currentAvailable)}</b></span><span>Optimistic conflicts <b>${format(progress.conflicts)}</b></span><span>Retries <b>${format(progress.retries)}</b></span></div></div>`;
            if (progress.status === "FAILED") throw new Error("The simulation failed or exceeded its configured duration.");
        } while (progress.status !== "COMPLETED");
        resultPanel.innerHTML = renderRaceResult(progress.result, payload);
    } catch (error) {
        $("#race-error").textContent = error.message;
        $("#race-error").hidden = false;
        resultPanel.innerHTML = empty("Run a new race", "The previous race did not complete.", "race");
    } finally {
        resultPanel.removeAttribute("aria-busy");
        controls.forEach(control => { control.disabled = false; });
    }
}

function renderRaceResult(result, workload) {
    const rounds = result.rounds?.length ? result.rounds : [{ repetition: 1,
        strategies: [result.unsafe, result.optimistic, result.pessimistic].filter(Boolean) }];
    const labels = ["Purchase attempts", "Inventory correct", "Oversold units", "Lost updates", "Accepted", "Rejected", "Optimistic conflicts", "Retries", "Deadlocks", "Failed transactions", "Final available", "Total execution time", "Throughput", "Average latency", "P50 latency", "P95 latency", "P99 latency", "Lock wait total", "Queue wait total", "Cumulative DB transaction time", "Isolation level"];
    const values = mode => [format(mode.attempts), mode.correct ? "Yes" : "No", format(mode.oversold), format(mode.lostUpdates), format(mode.accepted), format(mode.rejected), format(mode.optimisticConflicts), format(mode.retryAttempts), format(mode.deadlocks), format(mode.failedTransactions), format(mode.finalAvailable), `${format(mode.durationMs)} ms`, `${mode.requestsPerSecond.toFixed(1)} /s`, `${mode.averageLatencyMs.toFixed(2)} ms`, `${mode.p50LatencyMs.toFixed(2)} ms`, `${mode.p95LatencyMs.toFixed(2)} ms`, `${mode.p99LatencyMs.toFixed(2)} ms`, `${mode.lockWaitMs.toFixed(2)} ms`, `${mode.queueWaitMs.toFixed(2)} ms`, `${mode.databaseTimeMs.toFixed(2)} ms`, mode.isolationLevel];
    const roundsHtml = rounds.map(round => {
        const strategies = round.strategies;
        const headings = ["Measured result", ...strategies.map(mode => strategyLabel(mode.mode))];
        const rows = labels.map((label, index) => `<tr><th scope="row">${label}</th>${strategies.map(mode => `<td class="number">${values(mode)[index]}</td>`).join("")}</tr>`).join("");
        const maxP95 = Math.max(1, ...strategies.map(mode => mode.p95LatencyMs));
        const charts = strategies.map(mode => `<div class="latency-row" data-strategy="${mode.mode}"><span>${strategyLabel(mode.mode)}</span><div class="latency-bar"><span style="width:${mode.p95LatencyMs === 0 ? 0 : Math.max(2, mode.p95LatencyMs / maxP95 * 100)}%"></span></div><strong>${mode.p95LatencyMs.toFixed(2)} ms</strong></div>`).join("");
        const maxActivity = Math.max(1, ...strategies.map(mode => Math.max(mode.optimisticConflicts, mode.retryAttempts)));
        const activity = strategies.map(mode => `<div class="latency-row" data-strategy="${mode.mode}"><span>${strategyLabel(mode.mode)}</span><div class="latency-bar"><span style="width:${mode.retryAttempts === 0 ? 0 : Math.max(2, mode.retryAttempts / maxActivity * 100)}%"></span></div><strong>${format(mode.retryAttempts)} retries</strong></div>`).join("");
        const conflicts = strategies.map(mode => `<div class="latency-row" data-strategy="${mode.mode}"><span>${strategyLabel(mode.mode)}</span><div class="latency-bar"><span style="width:${mode.optimisticConflicts === 0 ? 0 : Math.max(2, mode.optimisticConflicts / maxActivity * 100)}%"></span></div><strong>${format(mode.optimisticConflicts)} conflicts</strong></div>`).join("");
        return `<section class="race-round"><h3>Benchmark ${format(round.repetition)}</h3><p class="muted">P95 request latency</p><div class="latency-chart" aria-label="P95 request latency comparison">${charts}</div><p class="muted">Retry activity</p><div class="latency-chart" aria-label="Retry count comparison">${activity}</div><p class="muted">Optimistic conflicts</p><div class="latency-chart" aria-label="Optimistic conflict comparison">${conflicts}</div><div class="table-wrap metrics-table">${table("Measured database strategy outcomes", headings, rows)}</div></section>`;
    }).join("");
    const allModes = rounds.flatMap(round => round.strategies);
    const safeCorrect = allModes.filter(mode => mode.mode !== "NAIVE").every(mode => mode.correct);
    const naive = allModes.filter(mode => mode.mode === "NAIVE");
    const summary = naive.some(mode => !mode.correct) && safeCorrect
        ? "The naive database transactions exposed lost inventory updates; safe strategies preserved the measured invariants."
        : naive.some(mode => !mode.correct)
            ? "The naive strategy exposed lost inventory updates, and a safe strategy also failed a correctness check."
        : naive.length && !allModes.some(mode => mode.mode !== "NAIVE")
            ? "No lost update or oversell appeared in this naive run; the transaction remains unprotected."
            : safeCorrect ? "The safe strategies preserved inventory and ledger invariants in every run."
            : "At least one strategy failed an inventory or ledger correctness check.";
    const correct = safeCorrect && !naive.some(mode => !mode.correct);
    return `<div class="result-banner ${correct ? "" : "failed"}">${icon(correct ? "check" : "close")}${summary}</div>
        <p class="race-foot comparison-note">Each repetition used ${format(workload.buyers)} requests, ${format(workload.workers)} workers, ${format(workload.warehouses)} warehouses, ${format(workload.stock)} starting units, and ${format(workload.quantity)} unit(s) per buyer. Every strategy received a fresh H2 database with identical seed stock. Latency covers submit-to-completion. Queue wait and cumulative transaction execution time are summed across requests; the naive and optimistic timing subtracts the simulation-only coordination barrier. Cumulative time can exceed wall-clock duration. All runs use READ_COMMITTED. H2 measurements are for local comparison, not production capacity claims.</p>${roundsHtml}`;
}

function strategyLabel(mode) {
    return ({ NAIVE: "Naive database", OPTIMISTIC: "Optimistic locking", PESSIMISTIC: "Pessimistic row lock" })[mode] || mode;
}

document.addEventListener("click", event => {
    const button = event.target.closest("button");
    if (!button) return;
    if (button.dataset.dialog) openAction(button.dataset.dialog);
    if (button.dataset.orderId) void openOrder(button.dataset.orderId, button);
    if (button.id === "cancel-order") void cancelOrder(button);
    if ((button.id === "dialog-close" || button.hasAttribute("data-close")) && !state.pendingDialog) $("#dialog").close();
    if (button.id === "add-line") {
        $("#order-lines").insertAdjacentHTML("beforeend", newLine());
        updateLineButtons();
        $("#order-lines").lastElementChild.querySelector("select").focus();
    }
    if (button.id === "reconcile-button") {
        button.disabled = true;
        void runReconciliation().catch(error => notify(error.message, true)).finally(() => { button.disabled = false; });
    }
    if (button.classList.contains("remove-line")) {
        const row = button.closest(".line-row");
        const next = row.nextElementSibling ?? row.previousElementSibling;
        row.remove();
        updateLineButtons();
        next?.querySelector("select").focus();
    }
});
document.addEventListener("submit", event => {
    const form = event.target;
    if (!form.matches(".threshold-form")) return;
    event.preventDefault();
    const input = form.querySelector("input");
    const button = form.querySelector("button");
    button.disabled = true;
    void api("/inventory/threshold", { sku: form.dataset.sku, warehouseCode: form.dataset.warehouse,
        minimumAvailable: Number(input.value) })
        .then(() => refreshAfterCurrent())
        .then(() => notify("Warehouse stock threshold saved."))
        .catch(error => notify(error.message, true))
        .finally(() => { button.disabled = false; });
});
$("#dialog").addEventListener("cancel", event => {
    if (state.pendingDialog) event.preventDefault();
});
$("#dialog").addEventListener("close", () => {
    if (!$("#dialog").open) state.selectedOrder = null;
});
$("#stock-search").addEventListener("input", renderInventory);
$("#stock-warehouse").addEventListener("change", renderInventory);
$("#stock-low").addEventListener("change", renderInventory);
$("#product-inventory-table").addEventListener("click", event => {
    const button = event.target.closest("[data-network-product]");
    if (!button) return;
    state.selectedNetworkSku = button.dataset.networkProduct;
    renderProductInventory();
});
for (const selector of ["#movement-search", "#movement-warehouse", "#movement-type", "#movement-from", "#movement-to", "#movement-order"]) {
    $(selector).addEventListener(selector === "#movement-search" || selector === "#movement-order" ? "input" : "change", () => {
        if (state.view === "history") void loadHistory().catch(error => notify(error.message, true));
    });
}
$("#order-filter").addEventListener("change", () => {
    state.page = 0;
    void loadOrders().catch(error => notify(error.message, true));
});
$("#page-back").addEventListener("click", () => {
    state.page = Math.max(0, state.page - 1);
    void loadOrders().catch(error => notify(error.message, true));
});
$("#page-next").addEventListener("click", () => {
    state.page++;
    void loadOrders().catch(error => notify(error.message, true));
});
$("#race-form").addEventListener("submit", runRace);
$("#race-form").addEventListener("click", event => {
    const card = event.target.closest("[data-mode]");
    if (!card) return;
    $("#race-form [name=mode]").value = card.dataset.mode;
    updateStrategyCards();
});
$("#race-form [name=mode]").addEventListener("change", updateStrategyCards);
function updateStrategyCards() {
    const mode = $("#race-form [name=mode]").value;
    document.querySelectorAll("#race-form [data-mode]").forEach(card => {
        card.setAttribute("aria-pressed", String(card.dataset.mode === mode));
    });
}
window.addEventListener("hashchange", navigate);
window.addEventListener("focus", () => { void refresh(); });
navigate();
setInterval(() => { if (!document.hidden) void refresh(); }, 2300);
