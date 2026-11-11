CREATE TABLE IF NOT EXISTS products (
    sku VARCHAR(24) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    category VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS warehouses (
    code VARCHAR(16) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    city VARCHAR(60) NOT NULL,
    priority INTEGER NOT NULL CHECK (priority BETWEEN 1 AND 999)
);

CREATE TABLE IF NOT EXISTS inventory (
    sku VARCHAR(24) NOT NULL REFERENCES products(sku),
    warehouse_code VARCHAR(16) NOT NULL REFERENCES warehouses(code),
    available BIGINT NOT NULL CHECK (available >= 0),
    reserved BIGINT NOT NULL CHECK (reserved >= 0),
    shipped BIGINT NOT NULL CHECK (shipped >= 0),
    version BIGINT NOT NULL CHECK (version >= 0),
    PRIMARY KEY (sku, warehouse_code)
);

CREATE TABLE IF NOT EXISTS customer_orders (
    id UUID PRIMARY KEY,
    request_key UUID NOT NULL UNIQUE,
    fingerprint CHAR(64) NOT NULL,
    customer VARCHAR(80) NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('RESERVED', 'FULFILLED', 'CANCELLED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS allocations (
    order_id UUID NOT NULL REFERENCES customer_orders(id),
    sku VARCHAR(24) NOT NULL,
    warehouse_code VARCHAR(16) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, sku, warehouse_code),
    FOREIGN KEY (sku, warehouse_code) REFERENCES inventory(sku, warehouse_code)
);

CREATE TABLE IF NOT EXISTS outbox (
    order_id UUID PRIMARY KEY REFERENCES customer_orders(id),
    event_type VARCHAR(20) NOT NULL CHECK (event_type = 'ORDER_RESERVED'),
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'PROCESSED', 'CANCELLED')),
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS catalog_guard (id INTEGER PRIMARY KEY);
INSERT INTO catalog_guard(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM catalog_guard WHERE id = 1);

INSERT INTO warehouses (code, name, city, priority)
SELECT 'PUN', 'Punjab Central', 'Patiala', 10 WHERE NOT EXISTS (SELECT 1 FROM warehouses WHERE code = 'PUN');
INSERT INTO warehouses (code, name, city, priority)
SELECT 'DEL', 'North Hub', 'New Delhi', 20 WHERE NOT EXISTS (SELECT 1 FROM warehouses WHERE code = 'DEL');
INSERT INTO warehouses (code, name, city, priority)
SELECT 'BLR', 'South Hub', 'Bengaluru', 30 WHERE NOT EXISTS (SELECT 1 FROM warehouses WHERE code = 'BLR');

INSERT INTO products (sku, name, category)
SELECT 'KB-104', 'Mechanical Keyboard', 'Peripherals' WHERE NOT EXISTS (SELECT 1 FROM products WHERE sku = 'KB-104');
INSERT INTO products (sku, name, category)
SELECT 'MS-210', 'Wireless Mouse', 'Peripherals' WHERE NOT EXISTS (SELECT 1 FROM products WHERE sku = 'MS-210');
INSERT INTO products (sku, name, category)
SELECT 'CB-305', 'USB-C Cable', 'Accessories' WHERE NOT EXISTS (SELECT 1 FROM products WHERE sku = 'CB-305');
INSERT INTO products (sku, name, category)
SELECT 'HD-410', 'Studio Headphones', 'Audio' WHERE NOT EXISTS (SELECT 1 FROM products WHERE sku = 'HD-410');
INSERT INTO products (sku, name, category)
SELECT 'DK-502', 'USB-C Dock', 'Accessories' WHERE NOT EXISTS (SELECT 1 FROM products WHERE sku = 'DK-502');

INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'KB-104', 'PUN', 8, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'KB-104' AND warehouse_code = 'PUN');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'KB-104', 'DEL', 14, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'KB-104' AND warehouse_code = 'DEL');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'KB-104', 'BLR', 2, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'KB-104' AND warehouse_code = 'BLR');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'MS-210', 'PUN', 24, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'MS-210' AND warehouse_code = 'PUN');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'MS-210', 'DEL', 20, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'MS-210' AND warehouse_code = 'DEL');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'MS-210', 'BLR', 12, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'MS-210' AND warehouse_code = 'BLR');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'CB-305', 'PUN', 38, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'CB-305' AND warehouse_code = 'PUN');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'CB-305', 'DEL', 27, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'CB-305' AND warehouse_code = 'DEL');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'CB-305', 'BLR', 19, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'CB-305' AND warehouse_code = 'BLR');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'HD-410', 'PUN', 3, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'HD-410' AND warehouse_code = 'PUN');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'HD-410', 'DEL', 8, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'HD-410' AND warehouse_code = 'DEL');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'HD-410', 'BLR', 10, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'HD-410' AND warehouse_code = 'BLR');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'DK-502', 'PUN', 0, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'DK-502' AND warehouse_code = 'PUN');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'DK-502', 'DEL', 4, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'DK-502' AND warehouse_code = 'DEL');
INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
SELECT 'DK-502', 'BLR', 16, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku = 'DK-502' AND warehouse_code = 'BLR');
