-- Add a database-level guard for one user buying the same voucher only once.
-- If existing data contains duplicates, clean the duplicate rows before running this statement.
ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
