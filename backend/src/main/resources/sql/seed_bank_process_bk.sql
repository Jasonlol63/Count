-- Seed one Bank Process list row for tenant BK (testcount).
-- Safe to re-run: skips when country MYR already exists for BK.

SET @bk_tenant_id := (SELECT id FROM tenant WHERE UPPER(TRIM(code)) = 'BK' LIMIT 1);

-- Enable BANK module on BK (idempotent)
INSERT INTO tenant_feature_module (tenant_id, module_id)
SELECT @bk_tenant_id, fm.id
FROM feature_module fm
WHERE fm.code = 'BANK'
  AND @bk_tenant_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM tenant_feature_module tfm
      WHERE tfm.tenant_id = @bk_tenant_id AND tfm.module_id = fm.id
  );

-- Demo accounts (login passwords reuse Member01 hash; list only needs display)
INSERT INTO account (account_id, name, password, role, status, created_source, created_at)
SELECT 'BKSUP', 'BK SUPPLIER', '$2a$10$tmrhwJ0o0CTwGQKecEoCmuW5EZJhAERPG7jJdgfruZsCMIPsGl/cG', 'BANK', 'ACTIVE', 'seed_bank_process_bk', NOW()
WHERE @bk_tenant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM account WHERE account_id = 'BKSUP');

INSERT INTO account (account_id, name, password, role, status, created_source, created_at)
SELECT 'BKCUS', 'BK CUSTOMER', '$2a$10$tmrhwJ0o0CTwGQKecEoCmuW5EZJhAERPG7jJdgfruZsCMIPsGl/cG', 'AGENT', 'ACTIVE', 'seed_bank_process_bk', NOW()
WHERE @bk_tenant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM account WHERE account_id = 'BKCUS');

INSERT INTO account (account_id, name, password, role, status, created_source, created_at)
SELECT 'BKCOM', 'BK COMPANY ACC', '$2a$10$tmrhwJ0o0CTwGQKecEoCmuW5EZJhAERPG7jJdgfruZsCMIPsGl/cG', 'PROFIT', 'ACTIVE', 'seed_bank_process_bk', NOW()
WHERE @bk_tenant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM account WHERE account_id = 'BKCOM');

INSERT INTO account (account_id, name, password, role, status, created_source, created_at)
SELECT 'BKSHR', 'BK SHARE ACC', '$2a$10$tmrhwJ0o0CTwGQKecEoCmuW5EZJhAERPG7jJdgfruZsCMIPsGl/cG', 'MEMBER', 'ACTIVE', 'seed_bank_process_bk', NOW()
WHERE @bk_tenant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM account WHERE account_id = 'BKSHR');

SET @sup_id := (SELECT id FROM account WHERE account_id = 'BKSUP' LIMIT 1);
SET @cus_id := (SELECT id FROM account WHERE account_id = 'BKCUS' LIMIT 1);
SET @com_id := (SELECT id FROM account WHERE account_id = 'BKCOM' LIMIT 1);
SET @shr_id := (SELECT id FROM account WHERE account_id = 'BKSHR' LIMIT 1);

INSERT INTO account_tenant_access (account_id, tenant_id)
SELECT a.id, @bk_tenant_id
FROM account a
WHERE a.account_id IN ('BKSUP', 'BKCUS', 'BKCOM', 'BKSHR')
  AND @bk_tenant_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM account_tenant_access ata
      WHERE ata.account_id = a.id AND ata.tenant_id = @bk_tenant_id
  );

-- Country + bank option
INSERT INTO bank_country (tenant_id, code, created_at)
SELECT @bk_tenant_id, 'MYR', NOW()
WHERE @bk_tenant_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM bank_country WHERE tenant_id = @bk_tenant_id AND code = 'MYR'
  );

SET @country_id := (
    SELECT id FROM bank_country WHERE tenant_id = @bk_tenant_id AND code = 'MYR' LIMIT 1
);

INSERT INTO bank_option (tenant_id, country_id, name, created_at)
SELECT @bk_tenant_id, @country_id, 'RHB', NOW()
WHERE @bk_tenant_id IS NOT NULL
  AND @country_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM bank_option
      WHERE tenant_id = @bk_tenant_id AND country_id = @country_id AND name = 'RHB'
  );

SET @bank_option_id := (
    SELECT id FROM bank_option
    WHERE tenant_id = @bk_tenant_id AND country_id = @country_id AND name = 'RHB'
    LIMIT 1
);

-- One listable bank process row
INSERT INTO bank_process (
    tenant_id, country_id, bank_option_id,
    card_owner, card_owner_type,
    day_start, day_end, frequency,
    supplier_account_id, supplier_price,
    customer_account_id, customer_price,
    company_account_id, company_price,
    contract, insurance_price,
    sop, remark, status, created_by, created_at
)
SELECT
    @bk_tenant_id, @country_id, @bank_option_id,
    'TRAVELMINI SDN BHD', 'BUSINESS',
    CURDATE(), DATE_ADD(CURDATE(), INTERVAL 6 MONTH), 'FIRST_OF_EVERY_MONTH',
    @sup_id, 2500.00,
    @cus_id, 2700.00,
    @com_id, 200.00,
    '6', 50000.00,
    'Demo SOP for BK list', 'Seed remark', 'ACTIVE', 'seed', NOW()
WHERE @bk_tenant_id IS NOT NULL
  AND @country_id IS NOT NULL
  AND @bank_option_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM bank_process
      WHERE tenant_id = @bk_tenant_id AND card_owner = 'TRAVELMINI SDN BHD'
  );

SET @bp_id := (
    SELECT id FROM bank_process
    WHERE tenant_id = @bk_tenant_id AND card_owner = 'TRAVELMINI SDN BHD'
    LIMIT 1
);

INSERT INTO bank_process_share (bank_process_id, account_id, amount, sort_order)
SELECT @bp_id, @shr_id, 50.00, 0
WHERE @bp_id IS NOT NULL
  AND @shr_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM bank_process_share WHERE bank_process_id = @bp_id AND account_id = @shr_id
  );

SELECT t.id AS tenant_id, t.code AS tenant_code,
       bp.id AS bank_process_id, bp.card_owner, bp.status,
       bc.code AS country, bo.name AS bank,
       sa.account_id AS supplier, ca.account_id AS customer
FROM bank_process bp
JOIN tenant t ON t.id = bp.tenant_id
LEFT JOIN bank_country bc ON bc.id = bp.country_id
LEFT JOIN bank_option bo ON bo.id = bp.bank_option_id
LEFT JOIN account sa ON sa.id = bp.supplier_account_id
LEFT JOIN account ca ON ca.id = bp.customer_account_id
WHERE t.code = 'BK';
