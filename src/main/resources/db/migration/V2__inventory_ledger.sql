CREATE TABLE IF NOT EXISTS inventory_operations (
    operation_id UUID PRIMARY KEY,
    request_key UUID UNIQUE,
    operation_type VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS inventory_movements (
    movement_id UUID PRIMARY KEY,
    product_id VARCHAR(24) NOT NULL REFERENCES products(sku),
    warehouse_id VARCHAR(16) NOT NULL REFERENCES warehouses(code),
    movement_type VARCHAR(24) NOT NULL CHECK (movement_type IN
        ('INITIAL_STOCK','RESTOCK','RESERVATION','RESERVATION_RELEASE','FULFILLMENT','TRANSFER_OUT',
         'TRANSFER_IN','STOCK_ADJUSTMENT','DAMAGED_STOCK','RETURN','STOCK_WRITE_OFF')),
    stock_bucket VARCHAR(16) NOT NULL CHECK (stock_bucket IN ('AVAILABLE','RESERVED','DAMAGED','SHIPPED','WRITTEN_OFF')),
    quantity_change BIGINT NOT NULL CHECK (quantity_change <> 0),
    previous_stock BIGINT NOT NULL CHECK (previous_stock >= 0),
    new_stock BIGINT NOT NULL CHECK (new_stock >= 0),
    reference_order_id UUID REFERENCES customer_orders(id),
    source_warehouse VARCHAR(16) REFERENCES warehouses(code),
    destination_warehouse VARCHAR(16) REFERENCES warehouses(code),
    reason VARCHAR(240) NOT NULL,
    actor VARCHAR(80) NOT NULL DEFAULT 'SYSTEM',
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    operation_id UUID NOT NULL REFERENCES inventory_operations(operation_id),
    CONSTRAINT movement_balance_ck CHECK (new_stock = previous_stock + quantity_change),
    CONSTRAINT movement_operation_bucket_uq UNIQUE
        (operation_id, product_id, warehouse_id, movement_type, stock_bucket)
);
