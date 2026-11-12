ALTER TABLE outbox_events ADD COLUMN request_correlation_id UUID;

CREATE INDEX outbox_events_recent_failures_idx
    ON outbox_events(status, created_at DESC, event_id);
CREATE INDEX outbox_attempts_completed_idx ON outbox_event_attempts(completed_at DESC);

CREATE INDEX order_status_time_idx ON customer_orders(status, created_at DESC, id);
