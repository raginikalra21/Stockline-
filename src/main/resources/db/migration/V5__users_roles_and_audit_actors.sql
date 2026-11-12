CREATE TABLE IF NOT EXISTS app_users (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(24) NOT NULL CHECK (role IN ('ADMIN', 'WAREHOUSE_MANAGER', 'INVENTORY_OPERATOR')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS user_warehouse_access (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    warehouse_code VARCHAR(16) NOT NULL REFERENCES warehouses(code),
    PRIMARY KEY (user_id, warehouse_code)
);

ALTER TABLE inventory_movements ADD COLUMN IF NOT EXISTS actor_user_id UUID;
ALTER TABLE inventory_movements ADD COLUMN IF NOT EXISTS actor_role VARCHAR(24);
ALTER TABLE inventory_movements ADD CONSTRAINT movement_actor_user_fk
    FOREIGN KEY (actor_user_id) REFERENCES app_users(id);

CREATE INDEX IF NOT EXISTS user_warehouse_access_warehouse_idx ON user_warehouse_access(warehouse_code, user_id);
CREATE INDEX IF NOT EXISTS inventory_movements_actor_idx ON inventory_movements(actor_user_id, occurred_at);
