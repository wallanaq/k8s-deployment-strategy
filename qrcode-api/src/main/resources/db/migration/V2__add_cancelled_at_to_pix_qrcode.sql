-- Logical deletion for QR codes: this is a payment system, so a cancelled
-- record must survive for audit/reconciliation rather than being physically
-- deleted. NULL means active; a non-null value means cancelled and records
-- when -- carries strictly more information than a boolean at the same
-- storage cost. Nullable with no default so this applies instantly against
-- existing rows without a table rewrite or backfill.
ALTER TABLE pix_qrcode ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE;
