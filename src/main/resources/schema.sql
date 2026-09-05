-- Step 1: staging tables (raw text, one row per source CSV row) + trip_fact (typed, one row per trip).
-- Dropped and recreated on every ingest so re-running ingest is trivially idempotent (decision 8).

-- Views depend on trip_fact/sla_target, so drop them first (see sql/create_views.sql,
-- which recreates them every ingest too - dropped here in case a prior run left them
-- pointing at a now-incompatible trip_fact shape).
DROP VIEW IF EXISTS benchmark_view;
DROP VIEW IF EXISTS daily_metric;
DROP MATERIALIZED VIEW IF EXISTS daily_aggregate;

DROP TABLE IF EXISTS trip_fact;
DROP TABLE IF EXISTS sla_target;
DROP TABLE IF EXISTS stg_ride_trip;
DROP TABLE IF EXISTS stg_bill;
DROP TABLE IF EXISTS stg_alerts;
DROP TABLE IF EXISTS stg_feedback;

CREATE TABLE stg_ride_trip (
    id BIGSERIAL PRIMARY KEY,
    business_unit TEXT,
    office TEXT,
    product_type TEXT,
    trip_date TEXT,
    shift_type TEXT,
    trip_id TEXT,
    trip_direction TEXT,
    actual_escort TEXT,
    vendor_id TEXT,
    planned_cab_registration TEXT,
    actual_cab_registration TEXT,
    actual_cab_capacity TEXT,
    planned_km TEXT,
    traveled_km TEXT,
    planned_start_epoch TEXT,
    planned_end_epoch TEXT,
    actual_start_epoch TEXT,
    actual_end_epoch TEXT,
    delay_reason TEXT,
    delay_minutes TEXT,
    route_source TEXT,
    actual_cab_fuel_type TEXT,
    is_driver_nc TEXT,
    is_cab_nc TEXT,
    trip_nodal TEXT,
    plannedemployee_cnt TEXT,
    actualemployee_cnt TEXT,
    noshow_cnt TEXT,
    source_file TEXT
);

CREATE TABLE stg_bill (
    id BIGSERIAL PRIMARY KEY,
    business_unit TEXT,
    office TEXT,
    vendor TEXT,
    cycle_start TEXT,
    cycle_end TEXT,
    trip_id TEXT,
    contract TEXT,
    slab_name TEXT,
    total_trip_km TEXT,
    trip_cost TEXT,
    source_file TEXT
);

CREATE TABLE stg_alerts (
    id BIGSERIAL PRIMARY KEY,
    business_unit TEXT,
    trip_id TEXT,
    stwid TEXT,
    event_id TEXT,
    event_type TEXT,
    start_time TEXT,
    acknowledge_time TEXT,
    state_text TEXT,
    severity TEXT,
    source TEXT,
    source_file TEXT
);

CREATE TABLE stg_feedback (
    id BIGSERIAL PRIMARY KEY,
    business_unit TEXT,
    trip_id TEXT,
    trip_type TEXT,
    trip_date TEXT,
    stwid TEXT,
    route_rating TEXT,
    driver_rating TEXT,
    cab_rating TEXT,
    safety_rating TEXT,
    marshal_rating TEXT,
    creation_time TEXT,
    source_file TEXT
);

CREATE INDEX idx_stg_bill_trip_id ON stg_bill (trip_id);
CREATE INDEX idx_stg_alerts_trip_id ON stg_alerts (trip_id);
CREATE INDEX idx_stg_feedback_trip_id ON stg_feedback (trip_id);

-- trip_fact: one row per trip, grain matches the ride_data_trip hub (see CLAUDE.md).
-- trip_id alone is NOT globally unique - it collides across tenants/months (confirmed
-- during Step 1: same trip_id used by different business_units in different months).
-- (tenant_id, trip_id) is the real key.
CREATE TABLE trip_fact (
    trip_id BIGINT NOT NULL,
    tenant_id TEXT NOT NULL,
    office TEXT,
    product_type TEXT,
    mode TEXT,
    trip_date DATE,
    shift_type TEXT,
    trip_direction TEXT,
    vendor_id TEXT,
    actual_cab_registration TEXT,
    actual_cab_capacity INT,
    actual_cab_fuel_type TEXT,
    planned_km NUMERIC,
    traveled_km NUMERIC,
    detour_km NUMERIC,
    planned_start_ts TIMESTAMP,
    planned_end_ts TIMESTAMP,
    actual_start_ts TIMESTAMP,
    actual_end_ts TIMESTAMP,
    delay_minutes NUMERIC,
    delay_reason TEXT,
    route_source TEXT,
    is_driver_nc BOOLEAN,
    is_cab_nc BOOLEAN,
    actual_escort BOOLEAN,
    planned_employee_cnt INT,
    actual_employee_cnt INT,
    noshow_cnt INT,
    fill_rate NUMERIC,
    billed_cost NUMERIC,
    billed_km NUMERIC,
    contract TEXT,
    slab_name TEXT,
    co2_kg NUMERIC,
    alert_count INT,
    sev1_count INT,
    sev2_count INT,
    sev3_count INT,
    severity_unknown_count INT,
    has_sos BOOLEAN,
    avg_route_rating NUMERIC,
    avg_driver_rating NUMERIC,
    avg_cab_rating NUMERIC,
    avg_safety_rating NUMERIC,
    avg_marshal_rating NUMERIC,
    feedback_count INT,
    data_quality_flags TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, trip_id)
);

CREATE INDEX idx_trip_fact_date ON trip_fact (trip_date);
CREATE INDEX idx_trip_fact_trip_id ON trip_fact (trip_id);
CREATE INDEX idx_trip_fact_vendor ON trip_fact (vendor_id);

-- Step 2: SLA config (decision 9). Illustrative targets, not dataset-derived - label as
-- such anywhere shown. Rate metrics are fractions (0-1), matching daily_aggregate below.
-- NULL tenant_id/mode = applies to all.
CREATE TABLE sla_target (
    metric_name TEXT NOT NULL,
    tenant_id TEXT,
    mode TEXT,
    target_value NUMERIC NOT NULL,
    direction TEXT NOT NULL CHECK (direction IN ('higher_better', 'lower_better'))
);

-- escort_presence_rate deliberately has NO target row: verified during Step 3 that
-- escort_presence_rate = 0 on 72.3% of grain-groups (median 0, p90 1.0 - bimodal, not a
-- metric with a meaningful universal target). Without a real escort_required flag
-- (see glossary), any fixed target here would make ~72% of vendors "fail" identically,
-- flooding signals with homogeneous noise. Baseline/peer comparison (already in
-- benchmark_view for every metric) is the right tool for this one, not an SLA.
INSERT INTO sla_target (metric_name, tenant_id, mode, target_value, direction) VALUES
    ('ota_pct', NULL, NULL, 0.90, 'higher_better'),
    ('avg_delay_minutes', NULL, NULL, 15, 'lower_better'),
    ('no_show_rate', NULL, NULL, 0.05, 'lower_better'),
    ('noncompliance_rate', NULL, NULL, 0.02, 'lower_better'),
    ('avg_safety_rating', NULL, NULL, 4.5, 'higher_better');

-- Step 3: signal table. Deliberately NOT dropped above with everything else - ingest (C1)
-- and scan (C3) are decoupled lifecycles (decision 10), so re-running ingest must not
-- wipe scan history/review status. Scan re-runs are idempotent on their own terms
-- (ScanService deletes signal rows for the target date before reinserting).
CREATE TABLE IF NOT EXISTS signal (
    signal_id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    date DATE NOT NULL,
    persona TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    office TEXT,
    mode TEXT,
    shift_type TEXT,
    metric_name TEXT NOT NULL,
    observed_value NUMERIC,
    reference_type TEXT,
    reference_value NUMERIC,
    deviation_magnitude NUMERIC,
    materiality_score NUMERIC,
    severity_score NUMERIC,
    persistence_days INT,
    final_score NUMERIC,
    safety_flag BOOLEAN NOT NULL DEFAULT false,
    data_quality_confidence NUMERIC,
    status TEXT NOT NULL DEFAULT 'new',
    narrative TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Migration for the local DB from before Step 4: CREATE TABLE IF NOT EXISTS above only
-- applies on a truly fresh DB. Existing installs need these added explicitly.
ALTER TABLE signal ADD COLUMN IF NOT EXISTS office TEXT;
ALTER TABLE signal ADD COLUMN IF NOT EXISTS mode TEXT;
ALTER TABLE signal ADD COLUMN IF NOT EXISTS shift_type TEXT;
ALTER TABLE signal ADD COLUMN IF NOT EXISTS narrative TEXT;

CREATE INDEX IF NOT EXISTS idx_signal_date ON signal (date);
CREATE INDEX IF NOT EXISTS idx_signal_tenant ON signal (tenant_id);

-- Step 4: escalation drafts (decision 7 - draft-and-approve, never auto-sent). Same
-- decoupled-lifecycle treatment as signal: not dropped on ingest re-run.
CREATE TABLE IF NOT EXISTS draft (
    draft_id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    signal_id BIGINT NOT NULL REFERENCES signal (signal_id),
    recipient_vendor_id TEXT NOT NULL,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    evidence_ref TEXT,
    status TEXT NOT NULL DEFAULT 'draft',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    approved_by TEXT,
    approved_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_draft_tenant ON draft (tenant_id);
CREATE INDEX IF NOT EXISTS idx_draft_signal ON draft (signal_id);

-- Step 4: leadership briefs, one per (tenant, week ending). Not dropped on ingest re-run.
CREATE TABLE IF NOT EXISTS leadership_brief (
    brief_id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    week_ending DATE NOT NULL,
    narrative TEXT NOT NULL,
    evidence_ref TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, week_ending)
);
