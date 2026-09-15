ALTER TABLE devices ADD COLUMN owner_uid TEXT;
ALTER TABLE devices ADD COLUMN owner_email TEXT;
ALTER TABLE devices ADD COLUMN price_target REAL;
ALTER TABLE devices ADD COLUMN price_rule_id TEXT;
ALTER TABLE devices ADD COLUMN price_fired INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN previous_price REAL;
ALTER TABLE devices ADD COLUMN price_checked_at INTEGER;
ALTER TABLE alerts ADD COLUMN price_rule_id TEXT;
-- Legacy code-based enrollments must explicitly sign in before receiving premium alerts.
UPDATE devices SET enabled=0;
UPDATE alerts SET status='cancelled' WHERE status='pending';
CREATE INDEX devices_owner ON devices(owner_uid);
