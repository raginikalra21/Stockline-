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
    overview: "Dashboard", inventory: "Inventory", orders: "Orders", users: "User management",
    history: "Inventory History", warehouses: "Warehouses", race: "Concurrency Lab", events: "Event Log",
    operations: "Operations Health"
};
const pageSize = 15;
const eventPageSize = 25;
const state = {
    view: "overview", page: 0, eventPage: 0, catalog: null, stock: [], productInventory: [], networkSummary: null,
    allocationConfig: null, lowStockProducts: [], overview: null, alerts: [],
    selectedNetworkSku: null, selectedOrder: null, selectedAllocationDecisions: [], pendingDialog: false, refreshPromise: null, orderFetch: 0, eventFetch: 0,
    auth: null, csrfToken: null, users: []
};
let toastTimer;
let eventSearchTimer;
const rendered = new WeakMap();

function updateHtml(container, html) {
    if (rendered.get(container) !== html) {
        container.innerHTML = html;
        rendered.set(container, html);
    }
}

async function api(path, body, method = "POST") {
    if (body !== undefined && !state.csrfToken) await loadCsrf();
    const options = body === undefined ? { cache: "no-store" } : {
        method,
        headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": state.csrfToken },
        body: JSON.stringify(body)
    };
    const response = await fetch(`/api${path}`, options);
    if (response.status === 401 && !["/auth/me", "/auth/login"].includes(path))
        showLogin("Your session expired. Sign in again to continue.");
    const data = await response.json().catch(() => null);
    if (!response.ok || data === null) {
        const failure = new Error(data?.message || `Request failed (HTTP ${response.status}).`);
        failure.status = response.status;
        throw failure;
    }
    return data;
}

async function actuator(path) {
    const response = await fetch(`/actuator${path}`, { cache: "no-store" });
    const data = await response.json().catch(() => null);
    if (response.status === 401) showLogin("Your session expired. Sign in again to continue.");
    if (!response.ok || data === null) throw new Error(data?.message || `Actuator request failed (HTTP ${response.status}).`);
    return data;
}

async function loadCsrf() {
    const response = await fetch("/api/auth/csrf", { cache: "no-store" });
    const data = await response.json();
    state.csrfToken = data.token;
}

function showLogin(message = "") {
    state.auth = null;
    state.csrfToken = null;
    $("#app-shell").hidden = true;
    $("#login-screen").hidden = false;
    $("#login-form [name=password]").value = "";
    $("#login-error").textContent = message;
    $("#login-error").hidden = !message;
}

function showWorkspace(profile) {
    state.auth = profile;
    $("#user-profile").textContent = `${profile.username} · ${profile.role.replaceAll("_", " ")}`;
    $("#login-screen").hidden = true;
    $("#app-shell").hidden = false;
    document.querySelectorAll("[data-admin-only]").forEach(element => { element.hidden = profile.role !== "ADMIN"; });
    document.querySelectorAll("[data-manager-only]").forEach(element => {
        element.hidden = !["ADMIN", "WAREHOUSE_MANAGER"].includes(profile.role);
    });
    if (profile.role !== "ADMIN" && ["users", "race", "events", "operations"].includes(state.view)) {
        location.hash = "#inventory";
    } else void refresh();
}

async function startApp() {
    try {
        await loadCsrf();
    } catch {
        showLogin("Stockline could not be reached. Refresh the page when the service is available.");
        return;
    }
    try { showWorkspace(await api("/auth/me")); }
    catch (error) {
        showLogin(error.status === 401 ? "" : "Stockline could not verify your session. Refresh the page when the service is available.");
    }
}

$("#login-form").addEventListener("submit", async event => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const button = event.currentTarget.querySelector("button");
    button.disabled = true;
    try {
        await api("/auth/login", { username: form.get("username"), password: form.get("password") });
        // A full reload clears data cached under the previous account before rendering the new role's view.
        window.location.reload();
    } catch (error) {
        $("#login-error").textContent = error.message;
        $("#login-error").hidden = false;
    } finally { button.disabled = false; }
});

function configureDemoLogin() {
    const accounts = window.STOCKLINE_DEMO_ACCOUNTS;
    if (!Array.isArray(accounts) || accounts.length !== 2) return;
    const section = $("#demo-accounts");
    for (const account of accounts) {
        if (!["admin", "manager"].includes(account.id)) return;
        const card = section.querySelector(`[data-demo-card="${account.id}"]`);
        if (!card || typeof account.username !== "string" || typeof account.password !== "string") return;
        card.querySelector("[data-demo-username]").textContent = account.username;
        card.querySelector("[data-demo-password]").textContent = account.password;
        card.querySelector("[data-use-demo]").addEventListener("click", () => {
            $("#login-form [name=username]").value = account.username;
            $("#login-form [name=password]").value = account.password;
            $("#login-form [name=password]").focus();
        });
    }
    section.hidden = false;
}
configureDemoLogin();

$("#logout").addEventListener("click", async event => {
    const button = event.currentTarget;
    button.disabled = true;
    try {
        await api("/auth/logout", {});
        showLogin("You have signed out.");
    } catch (error) {
        notify(`Sign out could not be confirmed. ${error.message}`, true);
    } finally { button.disabled = false; }
});

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
        ["inventory", "Products", totals.products, "With stock in your access scope"],
        ["inventory", "Available stock", totals.available, "Ready to reserve"],
        ["clock", "Reserved stock", totals.reserved, "Held for active orders"],
        ["truck", "Active orders", totals.pendingOrders, "Reserved and awaiting fulfillment"],
        ["warehouse", "Low-stock locations", totals.lowStockRows, "At or below minimum stock"]
    ];
    updateHtml($("#stats"), cards.map(([name, title, value, caption]) => `<div class="stat">
        <div class="stat-label"><span class="stat-icon">${icon(name)}</span>${title}</div>
        <p class="stat-value number">${format(value)}</p><p>${caption}</p></div>`).join(""));

    const grouped = new Map(state.catalog.warehouses.map(location => [location.code, { ...location, available: 0, reserved: 0 }]));
    for (const stock of state.stock) {
        const location = grouped.get(stock.warehouseCode);
        if (location) {
            location.available += stock.available;
            location.reserved += stock.reserved;
        }
    }
    const scale = [...grouped.values()].reduce((max, item) => Math.max(max, item.available + item.reserved), 1);
    $("#network").innerHTML = grouped.size ? [...grouped.values()].map(location => `<div class="network-row">
        <div class="network-title"><span>${escapeHtml(location.name)} <small>${escapeHtml(location.city)}</small></span>
        <span class="mono">${escapeHtml(location.code)}</span></div>
        <div class="track" aria-hidden="true"><span style="width:${100 * location.available / scale}%"></span>
        <span style="width:${100 * location.reserved / scale}%"></span></div>
        <p class="network-note">${format(location.available)} available · ${format(location.reserved)} reserved</p></div>`).join("")
        : empty("No warehouses yet", "Add a warehouse to see stock across the network.", "warehouse");
}

function renderOverviewMovements(movements) {
    updateHtml($("#overview-movements"), movements.length
        ? table("Recent inventory movements", ["Product", "Warehouse", "Movement", "Change", "When"],
            movements.map(item => `<tr><td><strong>${escapeHtml(item.productId)}</strong></td>
                <td>${escapeHtml(warehouseLabel(item.warehouseId))}</td>
                <td>${escapeHtml(item.movementType.replaceAll("_", " "))}</td>
                <td class="number">${item.quantityChange > 0 ? "+" : ""}${format(item.quantityChange)}</td>
                <td>${escapeHtml(formatDate(item.timestamp))}</td></tr>`))
        : empty("No inventory activity yet", "Restocks, reservations, transfers, and adjustments will appear here.", "events"));
}

function renderOverviewEvents(events) {
    updateHtml($("#overview-events"), events.length
        ? table("Recent operational events", ["Event", "Status", "Attempts", "Created"],
            events.map(item => `<tr><td><strong>${escapeHtml(item.eventType.replaceAll("_", " "))}</strong>
                <span class="subcell mono">${escapeHtml(item.eventId.slice(0, 8))}</span></td>
                <td>${pill(item.status, item.status.replaceAll("_", " "))}</td>
                <td class="number">${format(item.attemptCount)}</td><td>${escapeHtml(formatDate(item.createdAt))}</td></tr>`))
        : empty("No operational events yet", "Order and inventory changes create persisted outbox events.", "events"));
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
    const categorySelect = $("#stock-category");
    const selectedCategory = categorySelect.value;
    const categories = [...new Set(state.catalog.products.map(product => product.category))].sort();
    const categoryOptions = `<option value="">All categories</option>${categories.map(category =>
        `<option value="${escapeHtml(category)}">${escapeHtml(category)}</option>`).join("")}`;
    if (categorySelect.dataset.options !== categoryOptions) {
        categorySelect.innerHTML = categoryOptions;
        categorySelect.dataset.options = categoryOptions;
        categorySelect.value = selectedCategory;
    }
    const search = $("#stock-search").value.trim().toLowerCase();
    const category = categorySelect.value;
    const status = $("#stock-status").value;
    const products = new Map(state.catalog.products.map(product => [product.sku, product.name]));
    const productCategories = new Map(state.catalog.products.map(product => [product.sku, product.category]));
    const rows = state.stock.filter(stock => {
        const product = products.get(stock.sku) ?? "";
        const belowThreshold = stock.available <= stock.minimumAvailable;
        const stockStatus = stock.available === 0 ? "OUT" : belowThreshold ? "LOW" : "AVAILABLE";
        return (!selectedWarehouse || stock.warehouseCode === selectedWarehouse)
            && (!category || productCategories.get(stock.sku) === category)
            && (!status || stockStatus === status)
            && `${stock.sku} ${product} ${productCategories.get(stock.sku) ?? ""}`.toLowerCase().includes(search);
    }).map(stock => {
        const stockLevel = stock.available === 0 ? pill("OUT", "Out of stock")
            : stock.available <= stock.minimumAvailable ? pill("LOW", "Low stock") : pill("HEALTHY", "In stock");
        const thresholdControl = ["ADMIN", "WAREHOUSE_MANAGER"].includes(state.auth.role)
            ? `<form class="threshold-form" data-sku="${escapeHtml(stock.sku)}" data-warehouse="${escapeHtml(stock.warehouseCode)}">
                <input aria-label="Minimum available for ${escapeHtml(stock.sku)} at ${escapeHtml(stock.warehouseCode)}" type="number" min="0" max="1000000" value="${stock.minimumAvailable}" required>
                <button type="submit" class="link-button">Save</button></form>`
            : format(stock.minimumAvailable);
        return `<tr><td><strong>${escapeHtml(products.get(stock.sku) ?? stock.sku)}</strong>
            <span class="subcell mono">${escapeHtml(stock.sku)}</span></td>
            <td>${escapeHtml(warehouseLabel(stock.warehouseCode))}<span class="subcell mono">${escapeHtml(stock.warehouseCode)}</span></td>
            <td class="number">${format(stock.available + stock.reserved + stock.damaged)}</td>
            <td class="number"><strong>${format(stock.available)}</strong></td><td class="number">${format(stock.reserved)}</td>
            <td class="number">${format(stock.damaged)}</td><td class="number">${format(stock.shipped)}</td>
            <td class="number">${format(stock.writtenOff)}</td><td>${thresholdControl}</td><td>${stockLevel}</td></tr>`;
    });
    $("#inventory-table").innerHTML = rows.length
        ? table("Stock records", ["Product", "Warehouse", "Physical", "Available", "Reserved", "Damaged", "Shipped", "Written off", "Minimum available", "Status"], rows)
        : empty("No matching stock", "Try another warehouse or clear the search filters.");
    renderProductInventory();
}

function renderProductInventory() {
    const products = state.productInventory;
    const summary = $("#product-inventory-table");
    const detail = $("#product-warehouse-detail");
    renderNetworkInventorySummary();
    if (!products.length) {
        updateHtml(summary, empty("No products yet", "Add a product to see network-wide inventory."));
        updateHtml(detail, "");
        return;
    }
    if (!products.some(product => product.sku === state.selectedNetworkSku)) {
        state.selectedNetworkSku = products[0].sku;
    }
    const networkView = state.auth.role === "ADMIN";
    updateHtml(summary, table(networkView ? "Product network inventory" : "Product inventory for assigned warehouses",
        ["Product", networkView ? "Network stock" : "Assigned stock", networkView ? "Network available" : "Assigned available",
            "Reserved", "Damaged", "Shipped", "Stocking locations", "Below threshold",
            networkView ? "Network status" : "Assigned availability", "Warehouse balances"],
        products.map(product => `<tr><td><strong>${escapeHtml(product.product)}</strong>
                <span class="subcell mono">${escapeHtml(product.sku)}</span></td>
            <td class="number">${format(product.totalStock)}</td><td class="number">${format(product.available)}</td>
            <td class="number">${format(product.reserved)}</td><td class="number">${format(product.damaged)}</td><td class="number">${format(product.shipped)}</td>
            <td class="number">${format(product.stockingWarehouses)}</td><td>${escapeHtml((product.belowThresholdWarehouses || []).join(", ") || "—")}</td>
            <td>${pill(product.status === "AVAILABLE" ? "HEALTHY" : "OUT",
                product.status === "AVAILABLE" ? "Available" : "Out of stock")}</td>
            <td><button type="button" class="link-button" data-network-product="${escapeHtml(product.sku)}"
                aria-pressed="${product.sku === state.selectedNetworkSku}">View all warehouses</button></td></tr>`)));

    const selected = products.find(product => product.sku === state.selectedNetworkSku);
    updateHtml(detail, `<h3>${escapeHtml(selected.product)} <span class="subcell mono">${escapeHtml(selected.sku)}</span> · warehouse balances</h3>
        ${table(`Warehouse balances for ${selected.sku}`,
            ["Warehouse", "Physical", "Available here", "Reserved here", "Damaged here", "Shipped", "Threshold",
                "Location availability", "Warehouse state"],
            selected.warehouses.map(warehouse => `<tr><td><strong>${escapeHtml(warehouse.warehouse)}</strong>
                    <span class="subcell mono">${escapeHtml(warehouse.warehouseCode)}</span></td>
                <td class="number">${format(warehouse.totalStock)}</td><td class="number">${format(warehouse.available)}</td>
                <td class="number">${format(warehouse.reserved)}</td><td class="number">${format(warehouse.damaged)}</td><td class="number">${format(warehouse.shipped)}</td>
                <td>${format(warehouse.minimumAvailable)}${warehouse.belowThreshold ? ` ${pill("LOW", "Below")}` : ""}</td>
                <td>${pill(warehouse.status === "AVAILABLE" ? "HEALTHY" : "OUT",
                    warehouse.status === "AVAILABLE" ? "Available" : "Out of stock")}</td>
                <td>${pill(warehouse.warehouseStatus, warehouse.warehouseStatus)}</td></tr>`))}`);
}

function renderNetworkInventorySummary() {
    const totals = state.networkSummary;
    if (!totals) return;
    const cards = [["Physical stock", totals.totalPhysical], ["Available", totals.available],
        ["Reserved", totals.reserved], ["Damaged", totals.damaged], ["Shipped", totals.shipped], ["Written off", totals.writtenOff],
        ["Stocked warehouses", totals.stockedWarehouses], ["Below threshold rows", totals.belowThresholdRows]];
    updateHtml($("#network-inventory-summary"), cards.map(([label, value]) => `<div class="stat">
        <div class="stat-label">${label}</div><p class="stat-value number">${format(value)}</p></div>`).join(""));
}

function renderNetworkLowStock(rows) {
    updateHtml($("#network-low-stock-table"), rows.length ? table("Low-stock product locations",
        ["Product", "Warehouse", "Available", "Threshold", "Recommended"],
        rows.map(row => `<tr><td><strong>${escapeHtml(row.product)}</strong><span class="subcell mono">${escapeHtml(row.sku)}</span></td>
            <td>${escapeHtml(row.warehouse)} <span class="mono">(${escapeHtml(row.warehouseCode)})</span></td>
            <td class="number">${format(row.available)}</td><td class="number">${format(row.minimumAvailable)}</td>
            <td class="number">${format(row.replenishmentUnits)}</td></tr>`))
        : empty("No below-threshold locations", "No assigned warehouse is currently below its configured minimum."));
}

function renderReplenishmentSuggestions(rows) {
    const container = $("#replenishment-table");
    updateHtml(container, rows.length ? table("Replenishment suggestions",
        ["Product", "Warehouse", "Available", "Threshold", "Recommended", "Updated", "Action"],
        rows.map(row => `<tr><td><strong>${escapeHtml(row.product)}</strong><span class="subcell mono">${escapeHtml(row.sku)}</span></td>
            <td>${escapeHtml(row.warehouse)}<span class="subcell mono">${escapeHtml(row.warehouseCode)}</span></td>
            <td class="number">${format(row.available)}</td><td class="number">${format(row.minimumAvailable)}</td>
            <td class="number">${format(row.recommendedQuantity)}</td><td>${escapeHtml(formatDate(row.updatedAt))}</td>
            <td><button type="button" class="link-button" data-dismiss-suggestion="${escapeHtml(row.suggestionId)}">Dismiss</button></td></tr>`))
        : empty("No active suggestions", "No assigned warehouse is currently below its configured stock threshold."));
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
    const totals = state.overview ?? state.networkSummary;
    if (!totals) return;
    const cards = [["Available", totals.available], ["Reserved", totals.reserved], ["Damaged", totals.damaged],
        ["Shipped", totals.shipped], ["Low-stock products", state.auth.role === "ADMIN"
            ? totals.lowStockProducts : state.lowStockProducts.length]];
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
    const totalsByWarehouse = new Map(state.catalog.warehouses.map(warehouse => [warehouse.code,
        state.stock.filter(stock => stock.warehouseCode === warehouse.code).reduce((total, stock) => ({
            physical: total.physical + stock.available + stock.reserved + stock.damaged,
            available: total.available + stock.available, reserved: total.reserved + stock.reserved
        }), { physical: 0, available: 0, reserved: 0 })]));
    $("#warehouse-table").innerHTML = state.catalog.warehouses.length ? table("Warehouses", ["Warehouse", "City", "Status", "Products", "Available", "Low stock", "Actions"],
        state.catalog.warehouses.map(item => {
            const stocks = state.stock.filter(stock => stock.warehouseCode === item.code);
            const lowCount = state.lowStockProducts.filter(row => row.warehouseCode === item.code).length;
            return `<tr><td><strong>${escapeHtml(item.name)}</strong>
                <span class="subcell mono">${escapeHtml(item.code)}</span></td><td>${escapeHtml(item.city)}</td>
                <td>${pill(item.status, item.status)}</td><td class="number">${format(stocks.length)}</td>
                <td class="number">${format(totalsByWarehouse.get(item.code).available)}</td>
                <td class="number">${format(lowCount)}</td><td><button type="button" class="link-button" data-view-warehouse="${escapeHtml(item.code)}">View warehouse</button>
                ${state.auth.role === "ADMIN" ? `<button type="button" class="link-button" data-config-warehouse="${escapeHtml(item.code)}">Edit</button>` : ""}</td></tr>`;
        }))
        : empty("No warehouses yet", "Add a warehouse to start building the network.", "warehouse");
    $("#product-table").innerHTML = state.catalog.products.length ? table("Products", ["Product", "Category"],
        state.catalog.products.map(item => `<tr><td><strong>${escapeHtml(item.name)}</strong>
        <span class="subcell mono">${escapeHtml(item.sku)}</span></td><td>${escapeHtml(item.category)}</td></tr>`))
        : empty("No products yet", "Add a product before recording inventory or creating an order.");
}

function renderUsers() {
    const select = $("#user-warehouses");
    const selected = new Set([...select.selectedOptions].map(option => option.value));
    const options = state.catalog.warehouses.map(warehouse => `<option value="${escapeHtml(warehouse.code)}">${escapeHtml(warehouse.name)} · ${escapeHtml(warehouse.code)}</option>`).join("");
    if (select.dataset.options !== options) {
        select.innerHTML = options;
        select.dataset.options = options;
        [...select.options].forEach(option => { option.selected = selected.has(option.value); });
    }
    const rows = state.users.map(user => `<tr><td><strong>${escapeHtml(user.username)}</strong></td>
        <td>${escapeHtml(user.role.replaceAll("_", " "))}</td><td>${user.warehouseCodes.length ? escapeHtml(user.warehouseCodes.join(", ")) : "All warehouses"}</td>
        <td>${pill(user.enabled ? "HEALTHY" : "OUT", user.enabled ? "Enabled" : "Disabled")}</td>
        <td><button class="link-button" type="button" data-toggle-user="${escapeHtml(user.id)}" ${user.username === state.auth.username ? "disabled title=\"You cannot disable your own account here.\"" : ""}>${user.enabled ? "Disable" : "Enable"}</button></td></tr>`);
    updateHtml($("#user-table"), rows.length
        ? table("User accounts", ["Username", "Role", "Warehouse access", "Status", "Action"], rows)
        : empty("No user accounts", "Create an administrator or warehouse manager account.", "users"));
}

async function openWarehouseDetails(code) {
    const warehouse = state.catalog?.warehouses.find(item => item.code === code);
    if (!warehouse) return notify("That warehouse is not in your accessible list.", true);
    try {
        const [summary, movements, orders] = await Promise.all([
            api(`/inventory/warehouses/${encodeURIComponent(code)}/summary`),
            api(`/inventory/movements?warehouseCode=${encodeURIComponent(code)}&limit=5&offset=0`),
            api("/orders?limit=100&offset=0")
        ]);
        const stockRows = state.stock.filter(item => item.warehouseCode === code);
        const productNames = new Map(state.catalog.products.map(item => [item.sku, item.name]));
        const inventory = stockRows.length ? table("Warehouse inventory", ["Product", "Physical", "Available", "Reserved", "Damaged", "Status"],
            stockRows.map(item => `<tr><td><strong>${escapeHtml(productNames.get(item.sku) ?? item.sku)}</strong><span class="subcell mono">${escapeHtml(item.sku)}</span></td>
                <td class="number">${format(item.available + item.reserved + item.damaged)}</td><td class="number">${format(item.available)}</td>
                <td class="number">${format(item.reserved)}</td><td class="number">${format(item.damaged)}</td>
                <td>${pill(item.available === 0 ? "OUT" : item.available <= item.minimumAvailable ? "LOW" : "HEALTHY",
                    item.available === 0 ? "Out of stock" : item.available <= item.minimumAvailable ? "Low stock" : "Available")}</td></tr>`))
            : empty("No stock in this warehouse", "Restock a product to make it available here.");
        const relevantOrders = orders.filter(order => order.allocations.some(allocation => allocation.warehouseCode === code)).slice(0, 5);
        const orderTable = relevantOrders.length ? table("Recent warehouse orders", ["Order", "Customer", "Units here", "Status", "Details"],
            relevantOrders.map(order => {
                const units = order.allocations.filter(item => item.warehouseCode === code).reduce((sum, item) => sum + item.quantity, 0);
                return `<tr><td class="mono">#${escapeHtml(shortId(order.id))}</td><td>${escapeHtml(order.customer)}</td><td class="number">${format(units)}</td><td>${pill(order.status)}</td><td><button type="button" class="link-button" data-order-id="${escapeHtml(order.id)}">View</button></td></tr>`;
            })) : empty("No recent orders", "Orders allocated from this warehouse will appear here.", "orders");
        const locationAlerts = state.lowStockProducts.filter(row => row.warehouseCode === code);
        const alertTable = locationAlerts.length ? table("Low stock in warehouse", ["Product", "Available", "Minimum", "Replenish"],
            locationAlerts.map(row => `<tr><td>${escapeHtml(row.product)} <span class="subcell mono">${escapeHtml(row.sku)}</span></td><td class="number">${format(row.available)}</td><td class="number">${format(row.minimumAvailable)}</td><td class="number">${format(row.replenishmentUnits)}</td></tr>`))
            : empty("No low-stock items", "All balances are above their configured minimum.");
        const recentMovements = movements.length ? table("Recent warehouse movements", ["Product", "Type", "Change", "When"],
            movements.map(item => `<tr><td>${escapeHtml(item.productId)}</td><td>${escapeHtml(item.movementType.replaceAll("_", " "))}</td><td class="number">${item.quantityChange > 0 ? "+" : ""}${format(item.quantityChange)}</td><td>${escapeHtml(formatDate(item.timestamp))}</td></tr>`))
            : empty("No recent movements", "Restocks and order activity will appear here.", "events");
        dialogContent(warehouse.name, `${warehouse.code} · ${warehouse.city} · ${warehouse.status}`,
            `<div class="details-body"><div class="stats warehouse-detail-stats">
                ${[["Physical stock", summary.totalPhysical], ["Available", summary.available], ["Reserved", summary.reserved], ["Damaged", summary.damaged], ["Products", summary.products], ["Low stock", summary.belowThresholdProducts]].map(([label, value]) => `<div class="stat"><div class="stat-label">${label}</div><p class="stat-value number">${format(value)}</p></div>`).join("")}
                </div><h3>Inventory</h3><div class="table-wrap">${inventory}</div><h3>Recent orders</h3><div class="table-wrap">${orderTable}</div>
                <h3>Low-stock items</h3><div class="table-wrap">${alertTable}</div><h3>Recent movements</h3><div class="table-wrap">${recentMovements}</div></div>`);
    } catch (error) { notify(error.message, true); }
}

async function openWarehouseConfiguration(code) {
    try {
        const warehouse = await api(`/warehouses/${encodeURIComponent(code)}/configuration`);
        dialogContent(`Configure ${warehouse.name}`, `${warehouse.code} · ${warehouse.city}`,
            `<form class="form-body warehouse-config-form" data-warehouse="${escapeHtml(code)}">
                <label>Status<select name="status">${["ACTIVE", "INACTIVE", "MAINTENANCE"].map(status =>
                    `<option value="${status}" ${warehouse.status === status ? "selected" : ""}>${status}</option>`).join("")}</select></label>
                <label>Latitude<input name="latitude" type="number" min="-90" max="90" step="0.000001" value="${warehouse.latitude ?? ""}"></label>
                <label>Longitude<input name="longitude" type="number" min="-180" max="180" step="0.000001" value="${warehouse.longitude ?? ""}"></label>
                <label>Handling cost per order<input name="handlingCost" type="number" min="0" step="0.01" value="${warehouse.handlingCost}"></label>
                <label>Shipping cost per unit<input name="shippingCostPerUnit" type="number" min="0" step="0.01" value="${warehouse.shippingCostPerUnit}"></label>
                <label>Maximum units per order (optional)<input name="maxFulfillmentCapacity" type="number" min="1" step="1" value="${warehouse.maxFulfillmentCapacity ?? ""}"></label>
                <p class="form-error" role="alert" hidden></p><div class="form-footer">
                    <button type="button" class="button secondary" data-close>Cancel</button>
                    <button class="button primary" type="submit">Save configuration</button></div></form>`);
    } catch (error) { notify(error.message, true); }
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
    const page = events.slice(0, eventPageSize);
    updateHtml($("#events-table"), events.length
        ? table("Fulfillment outbox", ["Event ID", "Type / aggregate", "Status", "Attempts", "Created", "Processing", "Next attempt", "Last error", "Inspect / retry"], page.map(event =>
            `<tr><td class="mono">${escapeHtml(event.eventId.slice(0, 8))}<span class="subcell">${escapeHtml(event.correlationId?.slice(0, 8) ?? "no correlation")}</span></td>
            <td>${pill(event.eventType, event.eventType.replaceAll("_", " "))}<span class="subcell">${escapeHtml(event.aggregateType)} · ${escapeHtml(event.aggregateId)}</span></td>
            <td>${pill(event.status, event.status.replaceAll("_", " "))}</td><td class="number">${format(event.attemptCount)}</td>
            <td>${escapeHtml(formatDate(event.createdAt))}</td><td>${escapeHtml(formatDate(event.processingAt))}</td>
            <td>${escapeHtml(formatDate(event.nextAttemptAt))}</td><td>${escapeHtml(event.lastError || "—")}</td>
            <td><button type="button" class="link-button" data-event-id="${escapeHtml(event.eventId)}">Details</button>
                ${event.status === "DEAD" && event.failureKind === "RETRYABLE" ? `<button type="button" class="link-button" data-retry-event="${escapeHtml(event.eventId)}">Retry</button>` : ""}</td></tr>`))
        : empty("No events match", "Change the search or filters, or create an inventory or order event.", "events"));
}

function eventFilterQuery() {
    const params = new URLSearchParams({ limit: String(eventPageSize + 1), offset: String(state.eventPage * eventPageSize) });
    const search = $("#event-search").value.trim();
    if (search) params.set("search", search);
    if ($("#event-type-filter").value) params.set("eventType", $("#event-type-filter").value);
    if ($("#event-status-filter").value) params.set("status", $("#event-status-filter").value);
    return params;
}

async function loadEvents() {
    const request = ++state.eventFetch;
    const eventTypes = ["ORDER_RESERVED", "ORDER_FULFILLED", "ORDER_CANCELLED", "LOW_STOCK",
        "INVENTORY_ADJUSTED", "STOCK_TRANSFERRED", "RESERVATION_EXPIRED"];
    const statuses = ["PENDING", "PROCESSING", "RETRY_WAIT", "COMPLETED", "DEAD"];
    const typeSelect = $("#event-type-filter");
    if (typeSelect.options.length === 1) typeSelect.insertAdjacentHTML("beforeend", eventTypes
        .map(type => `<option value="${type}">${type.replaceAll("_", " ")}</option>`).join(""));
    const statusSelect = $("#event-status-filter");
    if (statusSelect.options.length === 1) statusSelect.insertAdjacentHTML("beforeend", statuses
        .map(status => `<option value="${status}">${status.replaceAll("_", " ")}</option>`).join(""));
    const [events, metrics, health] = await Promise.all([
        api(`/events?${eventFilterQuery()}`), api("/events/metrics"), api("/events/health")
    ]);
    if (request !== state.eventFetch || state.view !== "events") return;
    if (!events.length && state.eventPage > 0) {
        state.eventPage--;
        await loadEvents();
        return;
    }
    renderEvents(events);
    $("#event-page").textContent = `Page ${state.eventPage + 1}`;
    $("#event-page-back").disabled = state.eventPage === 0;
    $("#event-page-next").disabled = events.length <= eventPageSize;
    $("#event-metrics").textContent = `${format(metrics.pending)} pending · ${format(metrics.processing)} processing · ${format(metrics.retryWait)} waiting to retry · ${format(metrics.completed)} completed · ${format(metrics.dead)} dead · ${format(metrics.totalAttempts)} attempts · average ${metrics.averageProcessingMillis.toFixed(1)} ms · oldest pending ${format(metrics.oldestPendingAgeMillis)} ms`;
    $("#event-worker-health").textContent = `${format(health.activeWorkers)} / ${format(health.configuredWorkers)} workers active · ${format(health.queuedTasks)} queued · last poll ${formatDate(health.lastPollAt)}`;
}

async function openEvent(eventId) {
    const [event, attempts] = await Promise.all([api(`/events/${eventId}`), api(`/events/${eventId}/attempts`)]);
    const attemptsTable = attempts.length ? table("Event processing attempts", ["Attempt", "Worker", "Started", "Finished", "Outcome", "Error"],
        attempts.map(item => `<tr><td class="number">${format(item.attemptNumber)}</td><td class="mono">${escapeHtml(item.workerId)}</td>
            <td>${escapeHtml(formatDate(item.startedAt))}</td><td>${escapeHtml(formatDate(item.completedAt))}</td>
            <td>${pill(item.outcome, item.outcome.replaceAll("_", " "))}</td><td>${escapeHtml(item.errorMessage || "—")}</td></tr>`))
        : empty("No attempts yet", "This event has not been claimed by a worker.", "events");
    const retry = event.status === "DEAD" && event.failureKind === "RETRYABLE"
        ? `<button type="button" class="button secondary" data-retry-event="${escapeHtml(event.eventId)}">Retry event</button>` : "";
    dialogContent(`${event.eventType.replaceAll("_", " ")} · ${event.eventId.slice(0, 8)}`,
        `${event.aggregateType} ${event.aggregateId} · ${event.status} · ${event.attemptCount} attempt(s)`,
        `<div class="details-body"><h3>Versioned payload</h3><pre class="event-payload">${escapeHtml(JSON.stringify(JSON.parse(event.payload), null, 2))}</pre>
            <h3>Retry history</h3><div class="table-wrap">${attemptsTable}</div>${retry}</div>`);
}

async function retryEvent(eventId, button) {
    button.disabled = true;
    try {
        await api(`/events/${eventId}/retry`, {});
        notify("Event returned to the retry queue.");
        if ($("#dialog").open) $("#dialog").close();
        await loadEvents();
    } catch (error) { notify(error.message, true); }
    finally { button.disabled = false; }
}

async function dismissSuggestion(suggestionId, button) {
    button.disabled = true;
    try {
        await api(`/inventory/replenishment-suggestions/${suggestionId}/dismiss`, {});
        notify("Replenishment suggestion dismissed.");
        await refreshAfterCurrent();
    } catch (error) { notify(error.message, true); }
    finally { button.disabled = false; }
}

async function refresh() {
    if (!state.auth) return;
    if (state.refreshPromise) return state.refreshPromise;
    const firstLoad = !state.catalog;
    if (firstLoad) {
        $("#notice").textContent = "Loading your workspace…";
        $("#notice").classList.remove("error");
        $("#notice").hidden = false;
    }
    state.refreshPromise = (async () => {
        try {
            const admin = state.auth.role === "ADMIN";
            const manager = ["ADMIN", "WAREHOUSE_MANAGER"].includes(state.auth.role);
            const needsStock = ["overview", "inventory", "history", "warehouses"].includes(state.view);
            const needsNetworkInventory = state.view === "inventory";
            const needsScopedHistorySummary = state.view === "history" && !admin;
            const needsOverview = ["overview", "history"].includes(state.view);
            const needsLowStock = ["overview", "inventory", "warehouses"].includes(state.view) || needsScopedHistorySummary;
            const needsSuggestions = state.view === "inventory" && manager;
            const [catalog, stock, productInventory, networkSummary, allocationConfig, lowStockProducts, overview, suggestions, users] = await Promise.all([
                api("/catalog"), needsStock ? api("/inventory") : Promise.resolve(state.stock),
                needsNetworkInventory ? api("/inventory/products") : Promise.resolve(state.productInventory),
                needsNetworkInventory || needsScopedHistorySummary ? api("/inventory/network-summary") : Promise.resolve(state.networkSummary),
                api("/allocation/strategies"), needsLowStock ? api("/inventory/low-stock") : Promise.resolve(state.lowStockProducts),
                needsOverview ? api("/overview") : Promise.resolve(state.overview),
                needsSuggestions ? api("/inventory/replenishment-suggestions") : Promise.resolve(state.suggestions || []),
                state.view === "users" && admin ? api("/admin/users") : Promise.resolve(state.users)
            ]);
            Object.assign(state, { catalog, stock, productInventory, networkSummary, allocationConfig,
                lowStockProducts, overview, suggestions, users });
            if (state.view === "overview") {
                renderOverview();
                const [orders, alerts] = await Promise.all([
                    api("/orders?limit=5"), api("/inventory/low-stock")
                ]);
                renderOrders($("#recent-orders"), orders);
                renderAlerts($("#overview-alerts"), alerts);
            }
            if (state.view === "inventory") {
                renderInventory();
                renderNetworkLowStock(lowStockProducts);
                if (["ADMIN", "WAREHOUSE_MANAGER"].includes(state.auth.role)) renderReplenishmentSuggestions(suggestions);
            }
            if (state.view === "history") {
                renderInventorySummary();
                await loadHistory();
            }
            if (state.view === "warehouses") renderCatalog();
            if (state.view === "users" && admin) renderUsers();
            if (state.view === "orders") await loadOrders();
            if (state.view === "events") await loadEvents();
            const open = state.selectedOrder;
            if (open?.status === "RESERVED" && !state.pendingDialog) {
                const latest = await api(`/orders/${open.id}`);
                if (state.selectedOrder === open && latest.status !== open.status && !state.pendingDialog) {
                    renderOrderDetails(latest);
                }
            }
            connected(true);
        } catch (error) {
            if (state.auth) connected(false, `Could not refresh the workspace. ${error.message}`);
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
    if (state.auth && state.auth.role !== "ADMIN" && !["overview", "inventory", "warehouses", "orders", "history"].includes(state.view)) {
        state.view = "inventory";
        if (location.hash !== "#inventory") location.hash = "#inventory";
    }
    $("#crumb").textContent = views[state.view];
    document.title = `${views[state.view]} · Stockline`;
    document.querySelectorAll("[data-view]").forEach(section => { section.hidden = section.dataset.view !== state.view; });
    document.querySelectorAll(".sidebar nav a").forEach(link => {
        if (link.hash === `#${state.view}`) link.setAttribute("aria-current", "page");
        else link.removeAttribute("aria-current");
    });
    if (state.auth) {
        void refreshAfterCurrent();
        if (state.view === "operations") void refreshOperations();
    }
}

function metricValue(metric, statistic = "VALUE") {
    return metric?.measurements?.find(item => item.statistic === statistic)?.value ?? null;
}

async function metricPercentile(metric, percentile) {
    const value = String(percentile);
    const tag = metric?.availableTags?.find(item => item.values?.some(candidate => Number(candidate) === percentile));
    if (!tag) return null;
    const response = await actuator(`/metrics/http.server.requests?${encodeURIComponent(tag.tag)}=${encodeURIComponent(value)}`);
    return metricValue(response);
}

function operationCard(label, value, detail, status = "") {
    return `<article class="stat"><div class="stat-label">${escapeHtml(label)}</div>
        <div class="stat-value">${status ? pill(status, value) : escapeHtml(value)}</div>
        <p>${escapeHtml(detail)}</p></article>`;
}

async function refreshOperations() {
    const button = $("#operations-refresh");
    const error = $("#operations-error");
    if (!button || !state.auth || state.auth.role !== "ADMIN") return;
    button.disabled = true;
    error.hidden = true;
    try {
        const [health, liveness, readiness, eventMetrics, worker, http, dbActive, dbPending, dbMax, cpu, heap, dead, retrying, reconciliation] = await Promise.all([
            actuator("/health"), actuator("/health/liveness"), actuator("/health/readiness"),
            api("/events/metrics"), api("/events/health"), actuator("/metrics/http.server.requests"),
            actuator("/metrics/hikaricp.connections.active"), actuator("/metrics/hikaricp.connections.pending"),
            actuator("/metrics/hikaricp.connections.max"), actuator("/metrics/process.cpu.usage"),
            actuator("/metrics/jvm.memory.used?tag=area:heap"), api("/events?status=DEAD&limit=5"),
            api("/events?status=RETRY_WAIT&limit=5"),
            api("/inventory/reconciliation")
        ]);
        const failures = [...dead, ...retrying].sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt)).slice(0, 10);
        const p95 = await metricPercentile(http, 0.95);
        const p99 = await metricPercentile(http, 0.99);
        const reconciliationFailures = reconciliation.filter(row => row.overallIntegrityStatus !== "VALID").length;
        const dbStatus = health.components?.db?.status || readiness.status;
        const outbox = health.components?.eventOutbox?.details || {};
        const average = metricValue(http, "TOTAL_TIME") && metricValue(http, "COUNT")
            ? metricValue(http, "TOTAL_TIME") * 1000 / metricValue(http, "COUNT") : 0;
        const cards = [
            operationCard("Application", liveness.status, "Liveness probe", liveness.status === "UP" ? "HEALTHY" : "OUT"),
            operationCard("Readiness / database", `${readiness.status} / ${dbStatus}`, "Readiness includes database connectivity",
                readiness.status === "UP" && dbStatus === "UP" ? "HEALTHY" : "OUT"),
            operationCard("Event workers", `${format(worker.activeWorkers)} / ${format(worker.configuredWorkers)}`,
                `${format(worker.queuedTasks)} queued · last poll ${formatDate(worker.lastPollAt)}`),
            operationCard("Outbox", `${format(eventMetrics.pending)} pending · ${format(eventMetrics.retryWait)} retry · ${format(eventMetrics.dead)} dead`,
                `Oldest pending ${format(eventMetrics.oldestPendingAgeMillis)} ms`),
            operationCard("Inventory reconciliation", reconciliationFailures ? `${format(reconciliationFailures)} issue(s)` : "VALID",
                `${format(reconciliation.length)} bucket rows checked`, reconciliationFailures ? "OUT" : "HEALTHY"),
            operationCard("API latency", `${average.toFixed(1)} ms average`,
                `P95 ${p95 == null ? "n/a" : `${(p95 * 1000).toFixed(1)} ms`} · P99 ${p99 == null ? "n/a" : `${(p99 * 1000).toFixed(1)} ms`}`),
            operationCard("Database pool", `${format(metricValue({ measurements: dbActive.measurements }))} / ${format(metricValue({ measurements: dbMax.measurements }))}`,
                `${format(metricValue({ measurements: dbPending.measurements }))} waiting`),
            operationCard("Process resources", `${cpu.measurements?.find(item => item.statistic === "VALUE")?.value == null
                ? "n/a" : `${(cpu.measurements.find(item => item.statistic === "VALUE").value * 100).toFixed(1)}% CPU`}`,
                `Heap ${metricValue(heap) == null ? "n/a" : `${(metricValue(heap) / 1024 / 1024).toFixed(1)} MiB`}`)
        ].join("");
        updateHtml($("#operations-summary"), cards);
        const rows = failures.map(item => `<tr><td class="mono">${escapeHtml(item.eventId.slice(0, 8))}</td>
            <td>${escapeHtml(item.eventType)}</td><td>${pill(item.status)}</td><td class="number">${format(item.attemptCount)}</td>
            <td>${escapeHtml(item.lastError || "No failure detail")}</td><td>${escapeHtml(formatDate(item.createdAt))}</td></tr>`);
        updateHtml($("#operations-failures"), rows.length
            ? table("Recent retry and dead-letter events", ["Event", "Type", "State", "Attempts", "Last error", "Created"], rows)
            : empty("No recent event failures", "The outbox has no retry-wait or dead-letter records.", "check"));
        $("#operations-updated").textContent = `Snapshot updated ${formatDate(new Date().toISOString())} · health ${health.status} · ${format(eventMetrics.totalAttempts)} persisted attempts`;
        connected(true);
    } catch (failure) {
        error.textContent = failure.message;
        error.hidden = false;
    } finally { button.disabled = false; }
}

function warehouseOptions() {
    return state.catalog.warehouses.filter(warehouse => state.auth?.role === "ADMIN"
        || state.auth?.warehouseCodes?.includes(warehouse.code)).map(warehouse =>
        `<option value="${escapeHtml(warehouse.code)}">${escapeHtml(warehouse.name)} (${escapeHtml(warehouse.code)})</option>`).join("");
}

function productOptions() {
    return state.catalog.products.map(product =>
        `<option value="${escapeHtml(product.sku)}">${escapeHtml(product.name)} (${escapeHtml(product.sku)})</option>`).join("");
}

const field = (label, name, attributes = "", required = true) =>
    `<label>${label}<input name="${name}" ${attributes} ${required ? "required" : ""}></label>`;
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

function allocationStrategyOptions() {
    const config = state.allocationConfig;
    const strategies = config?.supportedStrategies || ["SINGLE_WAREHOUSE", "NEAREST_WAREHOUSE", "FEWEST_SPLITS", "MAXIMUM_AVAILABILITY", "COST_OPTIMIZED"];
    return strategies.map(strategy => `<option value="${escapeHtml(strategy)}" ${strategy === config?.defaultStrategy ? "selected" : ""}>${escapeHtml(strategy.replaceAll("_", " "))}${strategy === config?.defaultStrategy ? " · network default" : ""}</option>`).join("");
}

function updateLineButtons() {
    const lines = [...document.querySelectorAll(".line-row")];
    lines.forEach(line => { line.querySelector(".remove-line").disabled = lines.length === 1; });
    $("#add-line").disabled = lines.length >= 20;
}

const actions = {
    order: {
        title: "New order", subtitle: "Preview a warehouse strategy, then reserve against the latest inventory in one transaction.",
        fields: () => `${field("Customer name", "customer", 'maxlength="80" autocomplete="off"')}
            <label>Preferred warehouse<select name="preferredWarehouseCode"><option value="">Automatic allocation</option>${warehouseOptions()}</select></label>
            <label>Allocation strategy<select name="allocationStrategy">${allocationStrategyOptions()}</select></label>
            ${field("Delivery latitude (optional)", "deliveryLatitude", 'type="number" min="-90" max="90" step="0.000001"', false)}
            ${field("Delivery longitude (optional)", "deliveryLongitude", 'type="number" min="-180" max="180" step="0.000001"', false)}
            <div class="order-lines" id="order-lines">${newLine()}</div>
            <button class="button secondary" id="add-line" type="button">${icon("plus")} Add line</button>
            <div class="allocation-preview-actions"><button class="button secondary" id="preview-allocation" type="button">Preview allocation</button>
            <div id="allocation-preview" class="allocation-preview" aria-live="polite">Preview reads current stock and does not reserve units.</div></div>`,
        label: "Reserve stock", path: "/orders",
        body: (form, key) => ({
            customer: form.get("customer").trim(), requestKey: key,
            preferredWarehouseCode: form.get("preferredWarehouseCode") || null,
            allocationStrategy: form.get("allocationStrategy"),
            deliveryLatitude: form.get("deliveryLatitude") === "" ? null : Number(form.get("deliveryLatitude")),
            deliveryLongitude: form.get("deliveryLongitude") === "" ? null : Number(form.get("deliveryLongitude")),
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
                const decisions = await api(`/orders/${result.id}/allocations`);
                renderOrderDetails(result, decisions);
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

async function previewOrderAllocation(button) {
    const form = $("#action-form");
    if (!form || state.pendingDialog) return;
    const data = new FormData(form);
    const body = {
        preferredWarehouseCode: data.get("preferredWarehouseCode") || null,
        allocationStrategy: data.get("allocationStrategy"),
        deliveryLatitude: data.get("deliveryLatitude") === "" ? null : Number(data.get("deliveryLatitude")),
        deliveryLongitude: data.get("deliveryLongitude") === "" ? null : Number(data.get("deliveryLongitude")),
        lines: data.getAll("lineSku").map((sku, index) => ({ sku, quantity: Number(data.getAll("lineQuantity")[index]) }))
    };
    button.disabled = true;
    button.textContent = "Calculating…";
    try { renderAllocationPreview(await api("/orders/allocation-preview", body)); }
    catch (error) { $("#allocation-preview").textContent = error.message; }
    finally { button.disabled = false; button.textContent = "Preview allocation"; }
}

function renderAllocationPreview(preview) {
    const rows = preview.allocations.map(item => `<tr><td>${escapeHtml(item.sku)}</td>
        <td>${escapeHtml(item.warehouse)} <span class="mono">(${escapeHtml(item.warehouseCode)})</span></td>
        <td class="number">${format(item.proposedQuantity)} / ${format(item.requestedQuantity)}</td>
        <td class="number">${format(item.availableBefore)}</td>
        <td class="number">${item.estimatedCost == null ? "—" : format(item.estimatedCost)}</td>
        <td>${item.estimatedDistanceKm == null ? "—" : `${format(item.estimatedDistanceKm)} km`}</td>
        <td>${escapeHtml(item.reason)}</td></tr>`);
    const excluded = preview.excludedWarehouses.length ? `<ul>${preview.excludedWarehouses.map(item =>
        `<li>${escapeHtml(item.sku)} · ${escapeHtml(item.warehouse)}: ${escapeHtml(item.reason)}</li>`).join("")}</ul>` : "";
    updateHtml($("#allocation-preview"), `<p>${pill(preview.fullyFulfillable ? "HEALTHY" : "OUT",
        preview.fullyFulfillable ? "Complete plan" : "Partial plan")} · ${escapeHtml(preview.strategy.replaceAll("_", " "))} · `
        + `${format(preview.warehousesInvolved)} warehouse(s) · estimated cost ${format(preview.estimatedTotalCost)}</p>
        <p class="muted">${escapeHtml(preview.explanation)}</p>
        ${rows.length ? `<div class="table-wrap">${table("Proposed allocations", ["SKU", "Warehouse", "Proposed / requested", "Available before", "Estimated cost", "Distance estimate", "Reason"], rows)}</div>` : ""}
        ${excluded}`);
}

function renderOrderDetails(order, decisions = state.selectedAllocationDecisions) {
    state.selectedOrder = order;
    state.selectedAllocationDecisions = decisions || [];
    const rows = order.allocations.map(item => `<tr><td class="mono">${escapeHtml(item.sku)}</td>
        <td>${escapeHtml(warehouseLabel(item.warehouseCode))} <span class="mono">(${escapeHtml(item.warehouseCode)})</span></td>
        <td class="number">${format(item.quantity)}</td></tr>`);
    const decisionRows = state.selectedAllocationDecisions.map(item => `<tr><td>${escapeHtml(item.sku)}</td>
        <td>${escapeHtml(item.warehouseCode)}</td><td>${escapeHtml(item.strategy.replaceAll("_", " "))}</td>
        <td class="number">${item.estimatedCost == null ? "—" : format(item.estimatedCost)}</td>
        <td>${escapeHtml(item.reason)}</td></tr>`);
    const split = new Set(order.allocations.map(item => item.warehouseCode)).size > 1;
    const description = {
        RESERVED: "Units are reserved. The background worker will fulfill this order shortly.",
        FULFILLED: "Warehouse allocations have been shipped.",
        CANCELLED: "Reserved units have been released back to inventory."
    };
    dialogContent(`Order #${shortId(order.id)}`, `${order.customer} · ${formatDate(order.createdAt)}`,
        `<div class="details-body">${pill(order.status)}<p class="muted">${description[order.status]}</p>
        ${split ? pill("LOW", "Split across warehouses") : pill("HEALTHY", "Single warehouse")}
        <p class="muted">Status updated ${escapeHtml(formatDate(order.updatedAt))}</p>
        <p class="muted">Order ID <span class="mono">${escapeHtml(order.id)}</span></p>
        <div class="table-wrap">${table("Warehouse allocations", ["SKU", "Warehouse", "Units"], rows)}</div>
        ${decisionRows.length ? `<div class="table-wrap">${table("Allocation decisions", ["SKU", "Warehouse", "Strategy", "Estimated cost", "Reason"], decisionRows)}</div>` : ""}
        <p class="form-error" role="alert" hidden></p><div class="form-footer">
        ${order.status === "RESERVED" ? '<button id="cancel-order" type="button" class="button danger">Cancel order</button>' : ""}
        <button type="button" class="button secondary" data-close>Close</button></div></div>`);
}

async function openOrder(id, button) {
    button.disabled = true;
    try {
        const [order, decisions] = await Promise.all([api(`/orders/${id}`), api(`/orders/${id}/allocations`)]);
        if (!$("#dialog").open) renderOrderDetails(order, decisions);
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
        const rows = labels.map((label, index) => `<tr><th scope="row">${label}</th>${strategies.map(mode => `<td class="number">${values(mode)[index]}</td>`).join("")}</tr>`);
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
    const hasSafeStrategy = allModes.some(mode => mode.mode !== "NAIVE");
    const correct = safeCorrect && (hasSafeStrategy || naive.every(mode => mode.correct));
    const summaryMode = result.optimistic ? "Optimistic" : result.pessimistic ? "Pessimistic" : "Naive";
    const finalState = `<div class="result-stats"><div><strong>${format(result.remaining)}</strong><span>Available after processing</span></div>
        <div><strong>${format(result.reserved)}</strong><span>Reserved after processing</span></div>
        <div><strong>${format(result.shipped)}</strong><span>Shipped after processing</span></div>
        <div><strong>${result.verified ? "Pass" : "Fail"}</strong><span>${summaryMode} ledger and stock check</span></div></div>`;
    return `<div class="result-banner ${correct ? "" : "failed"}">${icon(correct ? "check" : "close")}${summary}</div>${finalState}
        <p class="race-foot comparison-note">Each repetition used ${format(workload.buyers)} requests, ${format(workload.workers)} workers, ${format(workload.warehouses)} warehouses, ${format(workload.stock)} starting units, and ${format(workload.quantity)} unit(s) per buyer. Every strategy received a fresh H2 database with identical seed stock. Latency covers submit-to-completion. Queue wait and cumulative transaction execution time are summed across requests; the naive and optimistic timing subtracts the simulation-only coordination barrier. Cumulative time can exceed wall-clock duration. All runs use READ_COMMITTED. H2 measurements are for local comparison, not production capacity claims.</p>${roundsHtml}`;
}

function validateBenchmarkReport(report) {
    const scenarios = new Set(["inventory-read", "network-summary", "order-create",
        "split-allocation", "order-cancel", "event-fulfillment", "stock-contention"]);
    const results = report?.results;
    const correctness = report?.correctness;
    const numeric = value => typeof value === "number" && Number.isFinite(value) && value >= 0;
    const integer = value => numeric(value) && Number.isInteger(value);
    return report?.schemaVersion === 1 && typeof report.runId === "string" && report.runId.length > 0
        && scenarios.has(report.scenario) && Number.isFinite(Date.parse(report.startedAt))
        && Number.isFinite(Date.parse(report.endedAt)) && report.application?.isolated === true
        && typeof report.application.database === "string" && report.application.database.length > 0
        && integer(results?.totalRequests) && results.totalRequests > 0
        && integer(results.successfulRequests) && integer(results.failedRequests)
        && integer(results.expectedStockRejections)
        && results.successfulRequests + results.failedRequests === results.totalRequests
        && results.failedRequests === results.expectedStockRejections
        && [results.averageLatencyMs, results.p50LatencyMs, results.p95LatencyMs, results.p99LatencyMs,
            results.requestsPerSecond].every(numeric)
        && results.p50LatencyMs <= results.p95LatencyMs && results.p95LatencyMs <= results.p99LatencyMs
        && integer(results.scenarioCompleted) && integer(results.scenarioFailed)
        && integer(results.splitOrdersObserved)
        && correctness?.inventoryNonnegative === true && correctness?.productStockWithinSeededLimit === true
        && correctness?.reconciliationValid === true && correctness?.invalidReconciliationRows === 0
        && Array.isArray(report.products) && report.products.length > 0
        && Array.isArray(report.warehouseCodes) && report.warehouseCodes.length > 0;
}

function renderBenchmarkReport(report) {
    const results = report.results;
    const cards = [
        ["Scenario", report.scenario.replaceAll("-", " ")],
        ["Database", report.application.database],
        ["HTTP requests", `${format(results.successfulRequests)} passed / ${format(results.totalRequests)}`],
        ["Expected stock rejections", format(results.expectedStockRejections)],
        ["Throughput", `${results.requestsPerSecond.toFixed(1)} requests/s`],
        ["Latency", `Avg ${results.averageLatencyMs.toFixed(1)} ms · P50 ${results.p50LatencyMs.toFixed(1)} · P95 ${results.p95LatencyMs.toFixed(1)} · P99 ${results.p99LatencyMs.toFixed(1)}`],
        ["Split orders", format(results.splitOrdersObserved)],
        ["Inventory checks", "Nonnegative stock · valid ledger reconciliation"]
    ];
    updateHtml($("#benchmark-report-result"), `<p class="benchmark-report-meta">Run ${escapeHtml(report.runId)} · ${escapeHtml(formatDate(report.startedAt))}`
        + `${report.application.gitCommit ? ` · commit ${escapeHtml(report.application.gitCommit.slice(0, 12))}` : ""}</p>`
        + `<div class="stats benchmark-report-stats">${cards.map(([label, value]) => `<article class="stat"><div class="stat-label">${escapeHtml(label)}</div>`
            + `<p class="stat-value">${escapeHtml(value)}</p></article>`).join("")}</div>`);
}

$("#benchmark-report-file").addEventListener("change", async event => {
    const file = event.currentTarget.files?.[0];
    const error = $("#benchmark-report-error");
    error.hidden = true;
    updateHtml($("#benchmark-report-result"), "");
    if (!file) return;
    try {
        if (file.size > 1_000_000) throw new Error("Choose a JSON report smaller than 1 MB.");
        const report = JSON.parse(await file.text());
        if (!validateBenchmarkReport(report)) throw new Error("This report is incomplete, not from the isolated benchmark profile, or has failed inventory checks.");
        renderBenchmarkReport(report);
    } catch (failure) {
        error.textContent = failure instanceof SyntaxError ? "The selected file is not valid JSON." : failure.message;
        error.hidden = false;
    }
});

function strategyLabel(mode) {
    return ({ NAIVE: "Naive database", OPTIMISTIC: "Optimistic locking", PESSIMISTIC: "Pessimistic row lock" })[mode] || mode;
}

document.addEventListener("click", event => {
    const button = event.target.closest("button");
    if (!button) return;
    if (button.dataset.dialog) openAction(button.dataset.dialog);
    if (button.dataset.orderId) void openOrder(button.dataset.orderId, button);
    if (button.dataset.eventId) void openEvent(button.dataset.eventId).catch(error => notify(error.message, true));
    if (button.dataset.retryEvent) void retryEvent(button.dataset.retryEvent, button);
    if (button.dataset.dismissSuggestion) void dismissSuggestion(button.dataset.dismissSuggestion, button);
    if (button.dataset.configWarehouse) void openWarehouseConfiguration(button.dataset.configWarehouse);
    if (button.dataset.viewWarehouse) void openWarehouseDetails(button.dataset.viewWarehouse);
    if (button.dataset.toggleUser) {
        const account = state.users.find(user => user.id === button.dataset.toggleUser);
        if (account) {
            button.disabled = true;
            void api(`/admin/users/${account.id}`, { role: account.role, enabled: !account.enabled, warehouseCodes: account.warehouseCodes }, "PUT")
                .then(() => refreshAfterCurrent()).then(() => notify("User access updated."))
                .catch(error => notify(error.message, true)).finally(() => { button.disabled = false; });
        }
    }
    if (button.id === "preview-allocation") void previewOrderAllocation(button);
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
    if (form.matches("#user-create-form")) {
        event.preventDefault();
        const data = new FormData(form);
        const button = form.querySelector("button[type=submit]");
        const error = form.querySelector(".form-error");
        const warehouseCodes = [...form.elements.warehouseCodes.selectedOptions].map(option => option.value);
        button.disabled = true;
        error.hidden = true;
        void api("/admin/users", { username: data.get("username"), password: data.get("password"),
            role: data.get("role"), warehouseCodes }).then(() => {
            form.reset();
            notify("User account created.");
            return refreshAfterCurrent();
        }).catch(failure => {
            error.textContent = failure.message;
            error.hidden = false;
        }).finally(() => { button.disabled = false; });
        return;
    }
    if (form.matches(".warehouse-config-form")) {
        event.preventDefault();
        const data = new FormData(form);
        const value = name => data.get(name) === "" ? null : Number(data.get(name));
        const body = { status: data.get("status"), latitude: value("latitude"), longitude: value("longitude"),
            handlingCost: Number(data.get("handlingCost")), shippingCostPerUnit: Number(data.get("shippingCostPerUnit")),
            maxFulfillmentCapacity: value("maxFulfillmentCapacity") };
        const button = form.querySelector("[type=submit]");
        const error = form.querySelector(".form-error");
        button.disabled = true;
        void api(`/warehouses/${encodeURIComponent(form.dataset.warehouse)}/configuration`, body, "PUT")
            .then(() => { $("#dialog").close(); notify("Warehouse configuration saved."); return refreshAfterCurrent(); })
            .catch(failure => { error.textContent = failure.message; error.hidden = false; })
            .finally(() => { button.disabled = false; });
        return;
    }
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
$("#stock-category").addEventListener("change", renderInventory);
$("#stock-status").addEventListener("change", renderInventory);
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
function resetEventsPage() {
    state.eventPage = 0;
    if (state.view === "events") void loadEvents().catch(error => notify(error.message, true));
}
$("#event-search").addEventListener("input", () => {
    clearTimeout(eventSearchTimer);
    eventSearchTimer = setTimeout(resetEventsPage, 250);
});
$("#event-type-filter").addEventListener("change", resetEventsPage);
$("#event-status-filter").addEventListener("change", resetEventsPage);
$("#event-refresh").addEventListener("click", () => { void loadEvents().catch(error => notify(error.message, true)); });
$("#event-page-back").addEventListener("click", () => {
    state.eventPage = Math.max(0, state.eventPage - 1);
    void loadEvents().catch(error => notify(error.message, true));
});
$("#event-page-next").addEventListener("click", () => {
    state.eventPage++;
    void loadEvents().catch(error => notify(error.message, true));
});
$("#operations-refresh").addEventListener("click", () => { void refreshOperations(); });
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
window.addEventListener("focus", () => { if (state.auth) void refresh(); });
navigate();
void startApp();
setInterval(() => { if (!document.hidden && state.auth) void refresh(); }, 15000);
