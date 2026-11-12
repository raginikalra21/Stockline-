CREATE TABLE IF NOT EXISTS outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'ORDER_RESERVED', 'ORDER_FULFILLED', 'ORDER_CANCELLED', 'LOW_STOCK',
        'INVENTORY_ADJUSTED', 'STOCK_TRANSFERRED', 'RESERVATION_EXPIRED')),
    aggregate_type VARCHAR(24) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'DEAD')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(1000),
    failure_kind VARCHAR(16) CHECK (failure_kind IN ('RETRYABLE', 'PERMANENT')),
    processing_at TIMESTAMP WITH TIME ZONE,
    claimed_by VARCHAR(100),
    lease_until TIMESTAMP WITH TIME ZONE,
    processed_at TIMESTAMP WITH TIME ZONE,
    correlation_id UUID,
    dedupe_key VARCHAR(180) NOT NULL UNIQUE
);

CREATE INDEX IF NOT EXISTS outbox_events_due_idx ON outbox_events(status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS outbox_events_lease_idx ON outbox_events(status, lease_until);
CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx ON outbox_events(aggregate_type, aggregate_id, created_at);
CREATE INDEX IF NOT EXISTS outbox_events_correlation_idx ON outbox_events(correlation_id, created_at);

CREATE TABLE IF NOT EXISTS outbox_event_attempts (
    attempt_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES outbox_events(event_id),
    attempt_number INTEGER NOT NULL,
    worker_id VARCHAR(100) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('STARTED', 'COMPLETED', 'RETRY', 'DEAD', 'ABANDONED')),
    error_message VARCHAR(1000)
);
CREATE INDEX IF NOT EXISTS outbox_attempt_event_idx ON outbox_event_attempts(event_id, attempt_number DESC);

CREATE TABLE IF NOT EXISTS replenishment_suggestions (
    suggestion_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE REFERENCES outbox_events(event_id),
    sku VARCHAR(24) NOT NULL REFERENCES products(sku),
    warehouse_code VARCHAR(16) NOT NULL REFERENCES warehouses(code),
    available BIGINT NOT NULL CHECK (available >= 0),
    minimum_available BIGINT NOT NULL CHECK (minimum_available >= 0),
    recommended_quantity BIGINT NOT NULL CHECK (recommended_quantity >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'CLEARED', 'DISMISSED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS replenishment_status_idx ON replenishment_suggestions(status, warehouse_code, created_at DESC);

CREATE TABLE IF NOT EXISTS low_stock_event_state (
    sku VARCHAR(24) NOT NULL REFERENCES products(sku),
    warehouse_code VARCHAR(16) NOT NULL REFERENCES warehouses(code),
    is_low BOOLEAN NOT NULL DEFAULT FALSE,
    active_event_id UUID,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (sku, warehouse_code)
);

INSERT INTO low_stock_event_state(sku, warehouse_code, is_low, active_event_id, updated_at)
SELECT i.sku, i.warehouse_code, FALSE, NULL, CURRENT_TIMESTAMP
FROM inventory i
WHERE NOT EXISTS (SELECT 1 FROM low_stock_event_state s
                  WHERE s.sku = i.sku AND s.warehouse_code = i.warehouse_code);

INSERT INTO outbox_events(event_id, event_type, aggregate_type, aggregate_id, payload,
    created_at, status, attempt_count, next_attempt_at, processed_at, correlation_id, dedupe_key)
SELECT o.order_id, 'ORDER_RESERVED', 'ORDER', CAST(o.order_id AS VARCHAR),
       '{"schemaVersion":1,"orderId":"' || CAST(o.order_id AS VARCHAR) || '"}',
       COALESCE(o.processed_at, o.due_at),
       CASE WHEN o.status = 'PENDING' THEN 'PENDING' ELSE 'COMPLETED' END,
       0, o.due_at, o.processed_at, o.order_id, 'ORDER_RESERVED:' || CAST(o.order_id AS VARCHAR)
FROM outbox o
WHERE NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.dedupe_key = 'ORDER_RESERVED:' || CAST(o.order_id AS VARCHAR));
