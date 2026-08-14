-- Invoice repurposing (Quotations/Invoices plan, section 17.3): the existing invoicing module
-- becomes the genuine downstream tax invoice - Ship-To, CGST/SGST split, discount, bank/payment
-- details, due date, place of supply, reverse charge, and an optional back-reference to the
-- Quotation it was converted from. Direct invoice creation (no quotation) remains fully
-- supported - quotation_id is simply null in that case.

ALTER TABLE organizations
    ADD COLUMN bank_name varchar(255) NULL,
    ADD COLUMN bank_account_number varchar(50) NULL,
    ADD COLUMN bank_ifsc varchar(20) NULL,
    ADD COLUMN bank_branch varchar(255) NULL,
    ADD COLUMN upi_id varchar(100) NULL;

ALTER TABLE invoices
    ADD COLUMN ship_to_name varchar(255) NULL,
    ADD COLUMN ship_to_address varchar(1000) NULL,
    ADD COLUMN ship_to_gstin varchar(20) NULL,
    ADD COLUMN due_date date NULL,
    ADD COLUMN place_of_supply varchar(255) NULL,
    ADD COLUMN reverse_charge boolean NOT NULL DEFAULT false,
    ADD COLUMN quotation_id uuid NULL REFERENCES quotations(id);

-- Retrofits the discount/HSN-SAC fields quotation_line_items already has (V20) onto
-- invoice_line_items, plus the derived CGST/SGST split columns - both computed and stored at
-- write time (tax_rate_percent / 2 each), same "store at write time" discipline as quotations.
ALTER TABLE invoice_line_items
    ADD COLUMN hsn_sac varchar(20) NULL,
    ADD COLUMN discount_percent numeric(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN line_discount_amount numeric(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN line_cgst_amount numeric(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN line_sgst_amount numeric(12,2) NOT NULL DEFAULT 0;
