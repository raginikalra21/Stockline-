-- Preserve legacy operator accounts without silently granting them a manager's permissions.
-- Their role is converted to WAREHOUSE_MANAGER but the account is disabled for explicit admin review.
UPDATE app_users
SET role = 'WAREHOUSE_MANAGER', enabled = FALSE
WHERE role = 'INVENTORY_OPERATOR';

-- A second named constraint narrows the old check without depending on the database-generated name.
ALTER TABLE app_users ADD CONSTRAINT ck_app_users_two_roles
    CHECK (role IN ('ADMIN', 'WAREHOUSE_MANAGER'));
