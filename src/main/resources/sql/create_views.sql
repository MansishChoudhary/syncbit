-- Step 2: aggregate + benchmark views (decision 9). Pure SQL, no Java business logic.
-- Recreated fresh every ingest (schema.sql already dropped these) so they always match
-- the current trip_fact shape.

-- daily_aggregate: wide, human-readable rollup, grain = date x office x vendor x mode x
-- shift. This is what the evidence drill-down (decision 2) and the wide-table cut-list
-- fallback (decision: "charts -> plain tables") read directly.
--
-- MATERIALIZED, not a plain view: benchmark_view below re-references this (via
-- daily_metric) multiple times per row through several LATERAL joins. As a plain view
-- over trip_fact's 615K rows, that meant re-running this GROUP BY from scratch on every
-- reference - measured at 205s for one scan. Materializing it (120K rows, built once per
-- ingest) turns every downstream reference into a cheap scan of a small precomputed
-- table instead. Not "unlikely at sample-dataset scale" after all - this is exactly the
-- fallback CLAUDE.md's C2 notes already anticipated.
CREATE MATERIALIZED VIEW daily_aggregate AS
SELECT
    tenant_id,
    trip_date,
    office,
    vendor_id,
    mode,
    shift_type,
    COUNT(*) AS trip_count,
    AVG(delay_minutes) AS avg_delay_minutes,
    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY delay_minutes) AS median_delay_minutes,
    AVG(CASE WHEN delay_minutes <= 15 THEN 1.0 ELSE 0 END) AS ota_pct,
    (SUM(noshow_cnt)::numeric / NULLIF(SUM(planned_employee_cnt), 0)) AS no_show_rate,
    AVG(fill_rate) AS avg_fill_rate,
    AVG(detour_km) AS avg_detour_km,
    SUM(billed_cost) AS cost_total,
    (SUM(billed_cost) / NULLIF(COUNT(*) FILTER (WHERE billed_cost IS NOT NULL), 0)) AS cost_per_trip,
    AVG(CASE WHEN actual_escort THEN 1.0 ELSE 0 END) AS escort_presence_rate,
    AVG(CASE WHEN is_driver_nc OR is_cab_nc THEN 1.0 ELSE 0 END) AS noncompliance_rate,
    (SUM(alert_count)::numeric / NULLIF(COUNT(*), 0)) AS alert_rate,
    (SUM(sev1_count)::numeric / NULLIF(COUNT(*), 0)) AS sev1_rate,
    AVG(CASE WHEN has_sos THEN 1.0 ELSE 0 END) AS sos_rate,
    AVG(co2_kg) AS avg_co2_kg,
    AVG(avg_route_rating) AS avg_route_rating,
    AVG(avg_driver_rating) AS avg_driver_rating,
    AVG(avg_cab_rating) AS avg_cab_rating,
    AVG(avg_safety_rating) AS avg_safety_rating,
    AVG(avg_marshal_rating) AS avg_marshal_rating,
    (COUNT(*) FILTER (WHERE data_quality_flags IS NOT NULL)::numeric / NULLIF(COUNT(*), 0)) AS dq_flag_rate
FROM trip_fact
GROUP BY tenant_id, trip_date, office, vendor_id, mode, shift_type;

CREATE INDEX ON daily_aggregate (tenant_id, office, vendor_id, mode, shift_type, trip_date);
CREATE INDEX ON daily_aggregate (trip_date);

-- daily_metric: internal plumbing, not a separate "deliverable" view - unpivots
-- daily_aggregate so benchmark_view can do ONE generic self-join instead of repeating
-- prior/baseline/sla/peer columns per metric. Add a metric here and it's benchmarked
-- for free (decision 5: every metric shown to a human needs a reference point).
CREATE VIEW daily_metric AS
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count AS sample_size, 'ota_pct' AS metric_name, ota_pct AS metric_value FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'avg_delay_minutes', avg_delay_minutes FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'no_show_rate', no_show_rate FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'avg_fill_rate', avg_fill_rate FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'escort_presence_rate', escort_presence_rate FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'noncompliance_rate', noncompliance_rate FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'alert_rate', alert_rate FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'sev1_rate', sev1_rate FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'cost_per_trip', cost_per_trip FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'avg_co2_kg', avg_co2_kg FROM daily_aggregate
UNION ALL
SELECT tenant_id, trip_date, office, vendor_id, mode, shift_type, trip_count, 'avg_safety_rating', avg_safety_rating FROM daily_aggregate;

-- benchmark_view: the one view every signal/screen reads for context (decision 9).
-- Reference points: prior day, trailing 4-wk same-weekday baseline, SLA target, peer
-- median (other vendors, same tenant+office+mode+shift+date). delta_vs_sla is sign-
-- normalized so "negative = breaching" regardless of direction.
CREATE VIEW benchmark_view AS
SELECT
    dm.tenant_id,
    dm.trip_date,
    dm.office,
    dm.vendor_id,
    dm.mode,
    dm.shift_type,
    dm.metric_name,
    dm.sample_size,
    dm.metric_value,
    prior.metric_value AS prior_value,
    (dm.metric_value - prior.metric_value) AS delta_vs_prior,
    CASE WHEN prior.metric_value IS NOT NULL AND prior.metric_value <> 0
        THEN (dm.metric_value - prior.metric_value) / ABS(prior.metric_value) END AS pct_change_vs_prior,
    baseline.baseline_avg,
    baseline.baseline_stddev,
    CASE WHEN baseline.baseline_stddev IS NOT NULL AND baseline.baseline_stddev <> 0
        THEN (dm.metric_value - baseline.baseline_avg) / baseline.baseline_stddev END AS z_score_vs_baseline,
    sla.target_value AS sla_target_value,
    sla.direction AS sla_direction,
    CASE
        WHEN sla.target_value IS NULL THEN NULL
        WHEN sla.direction = 'higher_better' THEN dm.metric_value - sla.target_value
        ELSE sla.target_value - dm.metric_value
    END AS delta_vs_sla,
    peer.peer_median,
    CASE WHEN peer.peer_median IS NOT NULL AND peer.peer_median <> 0
        THEN (dm.metric_value - peer.peer_median) / ABS(peer.peer_median) END AS delta_vs_peer_pct
FROM daily_metric dm
LEFT JOIN daily_metric prior
    ON prior.tenant_id = dm.tenant_id AND prior.office = dm.office AND prior.vendor_id = dm.vendor_id
   AND prior.mode = dm.mode AND prior.shift_type = dm.shift_type AND prior.metric_name = dm.metric_name
   AND prior.trip_date = dm.trip_date - 1
LEFT JOIN LATERAL (
    SELECT AVG(dm2.metric_value) AS baseline_avg, STDDEV(dm2.metric_value) AS baseline_stddev
    FROM daily_metric dm2
    WHERE dm2.tenant_id = dm.tenant_id AND dm2.office = dm.office AND dm2.vendor_id = dm.vendor_id
      AND dm2.mode = dm.mode AND dm2.shift_type = dm.shift_type AND dm2.metric_name = dm.metric_name
      AND dm2.trip_date IN (dm.trip_date - 7, dm.trip_date - 14, dm.trip_date - 21, dm.trip_date - 28)
) baseline ON true
LEFT JOIN sla_target sla
    ON sla.metric_name = dm.metric_name
   AND (sla.tenant_id IS NULL OR sla.tenant_id = dm.tenant_id)
   AND (sla.mode IS NULL OR sla.mode = dm.mode)
LEFT JOIN LATERAL (
    SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY dm3.metric_value) AS peer_median
    FROM daily_metric dm3
    WHERE dm3.tenant_id = dm.tenant_id AND dm3.office = dm.office AND dm3.mode = dm.mode
      AND dm3.shift_type = dm.shift_type AND dm3.metric_name = dm.metric_name AND dm3.trip_date = dm.trip_date
) peer ON true;
