ALTER TABLE inventory ADD COLUMN IF NOT EXISTS damaged BIGINT NOT NULL DEFAULT 0 CHECK (damaged >= 0);
ALTER TABLE inventory ADD COLUMN IF NOT EXISTS written_off BIGINT NOT NULL DEFAULT 0 CHECK (written_off >= 0);

CREATE TABLE IF NOT EXISTS stock_thresholds (
    sku VARCHAR(24) NOT NULL REFERENCES products(sku),
    warehouse_code VARCHAR(16) NOT NULL REFERENCES warehouses(code),
    minimum_available BIGINT NOT NULL CHECK (minimum_available >= 0),
    PRIMARY KEY (sku, warehouse_code)
);

INSERT INTO stock_thresholds (sku, warehouse_code, minimum_available)
SELECT i.sku, i.warehouse_code, 5 FROM inventory i
WHERE NOT EXISTS (SELECT 1 FROM stock_thresholds t
    WHERE t.sku = i.sku AND t.warehouse_code = i.warehouse_code);
