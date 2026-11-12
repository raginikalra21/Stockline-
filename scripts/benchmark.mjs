#!/usr/bin/env node
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const config = {
  baseUrl: process.env.BENCHMARK_BASE_URL || 'http://127.0.0.1:18080',
  username: process.env.STOCKLINE_ADMIN_USERNAME, password: process.env.STOCKLINE_ADMIN_PASSWORD,
  users: Number(process.env.BENCHMARK_USERS || 8), requests: Number(process.env.BENCHMARK_REQUESTS || 100),
  durationSeconds: Number(process.env.BENCHMARK_DURATION_SECONDS || 0),
  warmupSeconds: Number(process.env.BENCHMARK_WARMUP_SECONDS || 5),
  products: Number(process.env.BENCHMARK_PRODUCTS || 4), warehouses: Number(process.env.BENCHMARK_WAREHOUSES || 3),
  initialStock: Number(process.env.BENCHMARK_INITIAL_STOCK || 100), quantity: Number(process.env.BENCHMARK_QUANTITY || 1),
  scenario: process.env.BENCHMARK_SCENARIO || 'inventory-read',
  output: process.env.BENCHMARK_OUTPUT || `benchmark-results/run-${new Date().toISOString().replaceAll(':', '-')}.json`
};

for (let index = 2; index < process.argv.length; index++) {
  const arg = process.argv[index];
  if (arg === '--help') {
    console.log(`Stockline isolated API benchmark

Options: --base-url URL --users N --requests N --duration-seconds N --warmup-seconds N
         --products N --warehouses N --initial-stock N --quantity N --scenario NAME --output FILE
Scenarios: inventory-read, network-summary, order-create, split-allocation,
           order-cancel, event-fulfillment, stock-contention
Set STOCKLINE_ADMIN_USERNAME and STOCKLINE_ADMIN_PASSWORD for the isolated benchmark admin.`);
    process.exit(0);
  }
  if (!arg.startsWith('--') || index + 1 >= process.argv.length) throw new Error(`Invalid option: ${arg}`);
  config[arg.slice(2).replaceAll(/-([a-z])/g, (_, char) => char.toUpperCase())] = process.argv[++index];
}
for (const key of ['users', 'requests', 'durationSeconds', 'warmupSeconds', 'products', 'warehouses', 'initialStock', 'quantity']) {
  config[key] = Number(config[key]);
}
const scenarios = new Set(['inventory-read', 'network-summary', 'order-create', 'split-allocation',
  'order-cancel', 'event-fulfillment', 'stock-contention']);
if (!config.username || !config.password) throw new Error('Set STOCKLINE_ADMIN_USERNAME and STOCKLINE_ADMIN_PASSWORD.');
if (!scenarios.has(config.scenario)) throw new Error(`Unsupported scenario: ${config.scenario}`);
if (config.users < 1 || config.users > 256 || config.products < 1 || config.products > 100
    || config.warehouses < 1 || config.warehouses > 20 || config.initialStock < 1 || config.quantity < 1
    || (config.durationSeconds <= 0 && config.requests < 1) || config.warmupSeconds < 0) {
  throw new Error('Benchmark settings are outside supported bounds.');
}
if (config.scenario === 'split-allocation'
    && (config.quantity <= config.initialStock || config.quantity > config.initialStock * config.warehouses)) {
  throw new Error('split-allocation requires quantity > initial-stock and quantity <= initial-stock × warehouses.');
}

const baseUrl = config.baseUrl.replace(/\/$/, '');
const cookieJar = new Map();
let csrfToken = '';
let measured = false;
const samples = [];
let scenarioCompleted = 0;
let scenarioFailed = 0;
let expectedStockRejections = 0;
let splitOrdersObserved = 0;
let measuredWorkloadStart = 0;
let measuredWorkloadEnd = 0;

function cookieHeaders() { return [...cookieJar].map(([name, value]) => `${name}=${value}`).join('; '); }
function updateCookies(response) {
  const headers = response.headers.getSetCookie?.() || [response.headers.get('set-cookie')].filter(Boolean);
  for (const header of headers) {
    const first = header.split(';', 1)[0];
    const separator = first.indexOf('=');
    if (separator > 0) cookieJar.set(first.slice(0, separator), first.slice(separator + 1));
  }
}
async function raw(path, { method = 'GET', body, headers = {} } = {}) {
  const requestHeaders = { ...headers };
  const cookies = cookieHeaders();
  if (cookies) requestHeaders.Cookie = cookies;
  if (body !== undefined) {
    requestHeaders['Content-Type'] = 'application/json';
    requestHeaders['X-XSRF-TOKEN'] = csrfToken;
  }
  requestHeaders['X-Correlation-ID'] ||= randomUUID();
  const response = await fetch(`${baseUrl}${path}`, { method, headers: requestHeaders,
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30_000) });
  updateCookies(response);
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* keep non-JSON responses opaque */ }
  return { response, data };
}
async function api(path, options = {}, record = measured) {
  const started = performance.now();
  try {
    const { response, data } = await raw(path, options);
    if (record) samples.push({ endpoint: path.split('?')[0], status: response.status,
      durationMs: performance.now() - started, errorCode: data?.code || null });
    return { ok: response.ok, status: response.status, data };
  } catch (error) {
    if (record) samples.push({ endpoint: path.split('?')[0], status: 0,
      durationMs: performance.now() - started, errorCode: error.name || 'NETWORK_ERROR' });
    return { ok: false, status: 0, data: { code: error.name || 'NETWORK_ERROR' } };
  }
}
async function required(path, options = {}) {
  const result = await api(path, options, false);
  if (!result.ok) throw new Error(`${options.method || 'GET'} ${path} failed with HTTP ${result.status}.`);
  return result.data;
}

async function authenticate() {
  let response = await fetch(`${baseUrl}/api/auth/csrf`);
  updateCookies(response);
  csrfToken = (await response.json()).token;
  const login = await raw('/api/auth/login', { method: 'POST',
    body: { username: config.username, password: config.password } });
  if (!login.response.ok) throw new Error(`Benchmark admin login failed with HTTP ${login.response.status}.`);
  response = await fetch(`${baseUrl}/api/auth/csrf`, { headers: { Cookie: cookieHeaders() } });
  updateCookies(response);
  csrfToken = (await response.json()).token;
  const info = await required('/actuator/info');
  if (info.stockline?.benchmarkIsolated !== true) {
    throw new Error('Refusing to benchmark: the server does not identify itself as the isolated benchmark profile.');
  }
  return info.stockline;
}

const runTag = `${Date.now().toString(36).slice(-6)}${Math.floor(Math.random() * 900 + 100)}`.toUpperCase();
async function prepareDataset() {
  const catalog = await required('/api/catalog');
  const warehouses = [...catalog.warehouses].slice(0, config.warehouses);
  while (warehouses.length < config.warehouses) {
    const index = warehouses.length + 1;
    const created = await required('/api/warehouses', { method: 'POST', body: {
      code: `BM${runTag}W${index}`, name: `Benchmark warehouse ${index}`, city: `Bench ${index}`, priority: 900 + index
    } });
    warehouses.push(created);
  }
  const skus = [];
  for (let index = 1; index <= config.products; index++) {
    const sku = `BM-${runTag}-P${index}`;
    await required('/api/products', { method: 'POST', body: { sku, name: `Benchmark product ${index}`, category: 'Benchmark' } });
    skus.push(sku);
    for (const warehouse of warehouses) await required('/api/inventory/restock', { method: 'POST', body: {
      sku, warehouseCode: warehouse.code, quantity: config.initialStock, requestKey: randomUUID()
    } });
  }
  const warmupSku = `BM-${runTag}-WARM`;
  await required('/api/products', { method: 'POST', body: { sku: warmupSku, name: 'Benchmark warm-up product', category: 'Benchmark' } });
  const warmupUnits = Math.max(config.initialStock, config.quantity * (config.warmupSeconds * config.users + 10));
  for (const warehouse of warehouses) await required('/api/inventory/restock', { method: 'POST', body: {
    sku: warmupSku, warehouseCode: warehouse.code, quantity: warmupUnits, requestKey: randomUUID()
  } });
  return { skus, warmupSku, warehouses: warehouses.map(warehouse => warehouse.code) };
}

async function makeOrder(sku, index, warmup = false) {
  const order = await api('/api/orders', { method: 'POST', body: {
    customer: `Benchmark ${warmup ? 'warm-up' : 'request'} ${index}`, requestKey: randomUUID(),
    allocationStrategy: 'NEAREST_WAREHOUSE', lines: [{ sku, quantity: config.quantity }]
  } });
  if (!order.ok) {
    if (order.status === 409 && order.data?.code === 'INSUFFICIENT_STOCK') expectedStockRejections++;
    return null;
  }
  if (!warmup && order.data.allocations.length > 1) splitOrdersObserved++;
  return order.data;
}

async function performScenario(index, dataset, warmup = false) {
  const sku = warmup ? dataset.warmupSku : dataset.skus[index % dataset.skus.length];
  let success = false;
  switch (config.scenario) {
    case 'inventory-read': success = (await api('/api/inventory/products')).ok; break;
    case 'network-summary': success = (await api('/api/inventory/network-summary')).ok; break;
    case 'order-create':
    case 'split-allocation': {
      const order = await makeOrder(sku, index, warmup);
      success = order !== null && (config.scenario !== 'split-allocation' || order.allocations.length > 1);
      break;
    }
    case 'order-cancel': {
      const order = await makeOrder(sku, index, warmup);
      if (order) success = (await api(`/api/orders/${order.id}/cancel`, { method: 'POST', body: {} })).ok;
      break;
    }
    case 'event-fulfillment': {
      const order = await makeOrder(sku, index, warmup);
      if (order) {
        const deadline = Date.now() + 60_000;
        while (Date.now() < deadline) {
          const result = await api(`/api/orders/${order.id}`);
          if (!result.ok) break;
          if (result.data.status === 'FULFILLED') { success = true; break; }
          if (result.data.status === 'CANCELLED') break;
          await new Promise(resolve => setTimeout(resolve, 200));
        }
      }
      break;
    }
    case 'stock-contention': success = (await makeOrder(sku, index, warmup)) !== null; break;
  }
  if (success) scenarioCompleted++;
  else scenarioFailed++;
}

async function runPhase(dataset, { seconds, requests, warmup = false }) {
  let next = 0;
  const deadline = Date.now() + seconds * 1000;
  const worker = async () => {
    while (true) {
      const index = next++;
      if (requests > 0 ? index >= requests : Date.now() >= deadline) break;
      await performScenario(index, dataset, warmup);
    }
  };
  await Promise.all(Array.from({ length: config.users }, worker));
}

async function readMetric(name, query = '') {
  const result = await api(`/actuator/metrics/${name}${query}`, {}, false);
  return result.ok ? Object.fromEntries((result.data.measurements || []).map(item => [item.statistic, item.value])) : null;
}
async function takeResourceSample() {
  const [active, pending, maximum, cpu, heap] = await Promise.all([
    readMetric('hikaricp.connections.active'), readMetric('hikaricp.connections.pending'),
    readMetric('hikaricp.connections.max'), readMetric('process.cpu.usage'),
    readMetric('jvm.memory.used', '?tag=area:heap')
  ]);
  return { at: new Date().toISOString(), poolActive: active?.VALUE ?? 0,
    poolPending: pending?.VALUE ?? 0, poolMaximum: maximum?.VALUE ?? 0,
    processCpuUsage: cpu?.VALUE ?? null, heapUsedBytes: heap?.VALUE ?? null };
}
function percentile(values, quantile) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(quantile * sorted.length) - 1)];
}
function summarizeSamples() {
  const durations = samples.map(sample => sample.durationMs);
  const successfulRequests = samples.filter(sample => sample.status >= 200 && sample.status < 300).length;
  const expected = samples.filter(sample => sample.status === 409 && sample.errorCode === 'INSUFFICIENT_STOCK').length;
  return { totalRequests: samples.length, successfulRequests, failedRequests: samples.length - successfulRequests,
    expectedStockRejections: expected, errorRate: samples.length ? (samples.length - successfulRequests) / samples.length : 0,
    averageLatencyMs: durations.length ? durations.reduce((sum, item) => sum + item, 0) / durations.length : 0,
    p50LatencyMs: percentile(durations, .5), p95LatencyMs: percentile(durations, .95),
    p99LatencyMs: percentile(durations, .99), requestsPerSecond: measuredWorkloadEnd > measuredWorkloadStart
      ? samples.length / ((measuredWorkloadEnd - measuredWorkloadStart) / 1000) : 0,
    byEndpoint: Object.fromEntries([...new Set(samples.map(sample => sample.endpoint))].map(endpoint => {
      const subset = samples.filter(sample => sample.endpoint === endpoint);
      return [endpoint, { requests: subset.length,
        averageLatencyMs: subset.reduce((sum, item) => sum + item.durationMs, 0) / subset.length,
        p50LatencyMs: percentile(subset.map(item => item.durationMs), .5),
        p95LatencyMs: percentile(subset.map(item => item.durationMs), .95),
        p99LatencyMs: percentile(subset.map(item => item.durationMs), .99),
        errors: subset.filter(item => item.status < 200 || item.status >= 300).length }];
    })) };
}

function gitCommit() {
  try { return execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(); }
  catch { return null; }
}
async function main() {
  const app = await authenticate();
  const dataset = await prepareDataset();
  const startSamples = await takeResourceSample();
  if (config.warmupSeconds > 0) await runPhase(dataset, { seconds: config.warmupSeconds, requests: 0, warmup: true });
  scenarioCompleted = 0; scenarioFailed = 0; expectedStockRejections = 0; splitOrdersObserved = 0;
  measuredWorkloadStart = Date.now(); measured = true;
  let sampling = true;
  const resourceSamples = [];
  const sampler = (async () => {
    while (sampling) {
      resourceSamples.push(await takeResourceSample());
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
  })();
  await runPhase(dataset, { seconds: config.durationSeconds,
    requests: config.durationSeconds > 0 ? 0 : config.requests });
  measuredWorkloadEnd = Date.now(); measured = false; sampling = false; await sampler;
  const after = await required('/api/inventory/products');
  const reconciliation = await required('/api/inventory/reconciliation');
  const invalidRows = reconciliation.filter(row => row.overallIntegrityStatus !== 'VALID'
    || row.bucketBalanceMismatch || row.invalidMovementCount > 0 || row.discontinuityCount > 0
    || row.unbalancedTransferCount > 0 || row.unbalancedOperationCount > 0);
  const testProducts = after.filter(product => dataset.skus.includes(product.sku));
  const inventoryNonnegative = testProducts.every(product => product.available >= 0 && product.reserved >= 0 && product.damaged >= 0);
  const productStockWithinSeededLimit = testProducts.every(product => product.available + product.reserved + product.damaged
    + product.shipped + product.writtenOff <= config.warehouses * config.initialStock);
  const correctness = { inventoryNonnegative, productStockWithinSeededLimit,
    reconciliationValid: invalidRows.length === 0, invalidReconciliationRows: invalidRows.length };
  const httpMetric = await readMetric('http.server.requests');
  const endSamples = await takeResourceSample();
  const report = { schemaVersion: 1, runId: runTag, scenario: config.scenario,
    startedAt: new Date(measuredWorkloadStart).toISOString(), endedAt: new Date(measuredWorkloadEnd).toISOString(),
    configuration: { baseUrl, users: config.users, requests: config.durationSeconds > 0 ? null : config.requests,
      durationSeconds: config.durationSeconds || null, warmupSeconds: config.warmupSeconds, products: config.products,
      warehouses: config.warehouses, initialStockPerProductWarehouse: config.initialStock, requestQuantity: config.quantity },
    application: { version: app.applicationVersion, gitCommit: gitCommit(), database: app.database, isolated: true },
    results: { ...summarizeSamples(), scenarioCompleted, scenarioFailed, splitOrdersObserved,
      expectedStockRejections, observedServerHttpRequestCount: httpMetric?.COUNT ?? null },
    resources: { before: startSamples, samples: resourceSamples, after: endSamples },
    correctness, products: dataset.skus, warehouseCodes: dataset.warehouses };
  const output = resolve(config.output);
  await mkdir(dirname(output), { recursive: true });
  await writeFile(output, `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify({ output, scenario: report.scenario, ...report.results, correctness }, null, 2));
  if (Object.values(correctness).some(value => typeof value === 'boolean' && !value)
      || invalidRows.length > 0) process.exitCode = 2;
}

main().catch(error => { console.error(`Benchmark failed: ${error.message}`); process.exitCode = 1; });
